package org.joinmastodon.android.api;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonIOException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import org.joinmastodon.android.BuildConfig;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.gson.IsoInstantTypeAdapter;
import org.joinmastodon.android.api.gson.IsoLocalDateTypeAdapter;
import org.joinmastodon.android.api.requests.async_refreshes.GetAsyncRefresh;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.model.AsyncRefresh;
import org.joinmastodon.android.model.Status;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.utils.WorkerThread;
import okhttp3.Cache;
import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class MastodonAPIController{
	private static final String TAG="MastodonAPIController";

	// MOSHIDON:
	public static final Gson gsonWithoutDeserializer = new GsonBuilder()
			.disableHtmlEscaping()
			.setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
			.registerTypeAdapter(Instant.class, new IsoInstantTypeAdapter())
			.registerTypeAdapter(LocalDate.class, new IsoLocalDateTypeAdapter())
			.registerTypeAdapter(org.joinmastodon.android.model.Account.PublicFriendCard.class, new org.joinmastodon.android.model.Account.PublicFriendCardDeserializer())
			.create();
	public static final Gson gson = gsonWithoutDeserializer.newBuilder()
			.registerTypeAdapter(Status.class, new Status.StatusDeserializer())
			.create();

	private static WorkerThread thread=new WorkerThread("MastodonAPIController");
	private static OkHttpClient httpClient=new OkHttpClient.Builder()
			.connectTimeout(60, TimeUnit.SECONDS)
			.writeTimeout(60, TimeUnit.SECONDS)
			.readTimeout(60, TimeUnit.SECONDS)
			.cache(new Cache(new File(MastodonApp.context.getCacheDir(), "http"), 10*1024*1024))
			.build();
	private static final OkHttpClient sensitiveHttpClient=httpClient.newBuilder().cache(null).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
	private static final OkHttpClient systemTrustSensitiveHttpClient=createSystemTrustClient(sensitiveHttpClient);
	private static Handler uiThreadHandler=new Handler(Looper.getMainLooper());

	private static final CacheControl NO_CACHE_WHATSOEVER=new CacheControl.Builder().noCache().noStore().build();

	private AccountSession session;
	private HashMap<String, AsyncRefreshPollRecord> asyncRefreshPolls=new HashMap<>();

	static{
		thread.start();
	}

	private static OkHttpClient createSystemTrustClient(OkHttpClient base){
		try{
			KeyStore androidStore=KeyStore.getInstance("AndroidCAStore");
			androidStore.load(null);
			KeyStore systemOnly=KeyStore.getInstance(KeyStore.getDefaultType());
			systemOnly.load(null);
			Enumeration<String> aliases=androidStore.aliases();
			while(aliases.hasMoreElements()){
				String alias=aliases.nextElement();
				if(!alias.startsWith("system:")) continue;
				Certificate certificate=androidStore.getCertificate(alias);
				if(certificate!=null) systemOnly.setCertificateEntry(alias, certificate);
			}
			TrustManagerFactory factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			factory.init(systemOnly);
			X509TrustManager trustManager=null;
			for(javax.net.ssl.TrustManager manager:factory.getTrustManagers()) if(manager instanceof X509TrustManager x509){ trustManager=x509; break; }
			if(trustManager==null) throw new IllegalStateException("No X509 trust manager");
			SSLContext context=SSLContext.getInstance("TLS");
			context.init(null, new javax.net.ssl.TrustManager[]{trustManager}, new SecureRandom());
			return base.newBuilder().sslSocketFactory(context.getSocketFactory(), trustManager).build();
		}catch(Exception error){
			// Fail closed for verification requests if a device cannot construct the system-only store.
			return base.newBuilder().addInterceptor(chain->{ throw new IOException("无法建立认证专用安全连接"); }).build();
		}
	}

	public MastodonAPIController(@Nullable AccountSession session){
		this.session=session;
	}

	public <T> void submitRequest(final MastodonAPIRequest<T> req){
		thread.postRunnable(()->{
			try{
				if(req.canceled || !req.acceptsRemoteResponse())
					return;
				Request.Builder builder=new Request.Builder()
						.url(req.getURL().toString())
						.method(req.getMethod(), req.getRequestBody());

				String token=null;
				if(session!=null)
					token=session.token.accessToken;
				else if(req.token!=null)
					token=req.token.accessToken;

				if(token!=null)
					builder.header("Authorization", "Bearer "+token);

				if(!req.cacheable)
					builder.cacheControl(NO_CACHE_WHATSOEVER);

				if(req.headers!=null){
					for(Map.Entry<String, String> header:req.headers.entrySet()){
						builder.header(header.getKey(), header.getValue());
					}
				}

				// Native build identity is mandatory, even when a request supplies its own headers.
				// Keep the MastodonAndroid/ prefix compatible with backend native-client detection.
				builder.header("User-Agent", "MastodonAndroid/"+BuildConfig.VERSION_NAME)
						.header("X-App-Version-Code", String.valueOf(BuildConfig.VERSION_CODE))
						.header("X-ABDL-NBW-Protocol", "2")
						.header("X-ABDL-App-AI", "1");

				Request hreq=builder.build();
					OkHttpClient selectedClient=req.requiresSystemTrust() ? systemTrustSensitiveHttpClient : req.isSensitiveRequest() ? sensitiveHttpClient : httpClient;
					Call call=selectedClient.newCall(hreq);

				synchronized(req){
					req.okhttpCall=call;
				}
				if(req.timeout>0){
					call.timeout().timeout(req.timeout, TimeUnit.MILLISECONDS);
				}

				if(BuildConfig.DEBUG && !req.isSensitiveRequest())
					Log.d(TAG, logTag(session)+"Sending request: "+hreq);

				call.enqueue(new Callback(){
					@Override
					public void onFailure(@NonNull Call call, @NonNull IOException e){
						if(req.canceled)
							return;
						if(BuildConfig.DEBUG && !req.isSensitiveRequest())
							Log.w(TAG, logTag(session)+""+hreq+" failed", e);
						synchronized(req){
							req.okhttpCall=null;
						}
						req.onError(e.getLocalizedMessage(), 0, e);
					}

					@Override
					public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException{
						if(req.canceled || !req.acceptsRemoteResponse()){
							response.close();
							return;
						}
						if(BuildConfig.DEBUG && !req.isSensitiveRequest())
							Log.d(TAG, logTag(session)+hreq+" received response: "+response);
						// Keep the call cancellable until its (bounded) body has finished reading.
						if(BuildConfig.DEBUG){
							String deprecationHeader=response.header("Deprecation");
							if(deprecationHeader!=null && deprecationHeader.startsWith("@")){
								try{
									Instant date=Instant.ofEpochSecond(Long.parseLong(deprecationHeader.substring(1)));
									String msg=hreq.url().encodedPath();
									if(date.isAfter(Instant.now()))
										msg+=" will be deprecated on ";
									else
										msg+=" is deprecated as of ";
									msg+=date.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM));
									Log.w(TAG, logTag(session)+msg);
									final String finalMsg=msg;
									uiThreadHandler.post(()->Toast.makeText(MastodonApp.context, finalMsg, Toast.LENGTH_SHORT).show());
								}catch(NumberFormatException ignored){}
							}
						}
						try(ResponseBody body=response.body()){
							// Resultless requests succeed on HTTP status, including 204/empty/partial bodies.
							if(response.isSuccessful() && req.respClass==null && req.respTypeToken==null){
								req.validateAndPostprocessResponse(null, response);
								req.onSuccess(null);
								return;
							}
							if(body==null){
								req.onError("服务器返回空响应", response.code(), null);
								return;
							}
							if(response.isSuccessful()){
								Reader reader=boundedResponseReader(req, body);
								T respObj;
								try{
									if(BuildConfig.DEBUG && !req.isSensitiveRequest()){
										JsonElement respJson=JsonParser.parseReader(reader);
										Log.d(TAG, logTag(session)+"response body: "+respJson);
										if(req.respTypeToken!=null)
											respObj=gson.fromJson(respJson, req.respTypeToken.getType());
										else if(req.respClass!=null)
											respObj=gson.fromJson(respJson, req.respClass);
										else
											respObj=null;
									}else{
										if(req.respTypeToken!=null)
											respObj=gson.fromJson(reader, req.respTypeToken.getType());
										else if(req.respClass!=null)
											respObj=gson.fromJson(reader, req.respClass);
										else
											respObj=null;
									}
								}catch(JsonIOException|JsonSyntaxException x){
									if(BuildConfig.DEBUG && !req.isSensitiveRequest())
										Log.w(TAG, logTag(session)+response+" error parsing or reading body", x);
									req.onError(req.isSensitiveRequest() ? "服务器响应格式无效，请重试" : x.getLocalizedMessage(), response.code(), req.isSensitiveRequest() ? null : x);
									return;
								}

								try{
									req.validateAndPostprocessResponse(respObj, response);
								}catch(IOException x){
									if(BuildConfig.DEBUG && !req.isSensitiveRequest())
										Log.w(TAG, logTag(session)+response+" error post-processing or validating response", x);
									req.onError(x.getLocalizedMessage(), response.code(), x);
									return;
								}

								if(BuildConfig.DEBUG && !req.isSensitiveRequest())
									Log.d(TAG, logTag(session)+response+" parsed successfully: "+respObj);

								req.onSuccess(respObj);
							}else{
								ErrorResponse error;
								try{
									JsonObject json=ServiceResourcePause.parseErrorObject(readErrorBody(req, body));
									error=decodeError(req, response, json);
								}catch(IOException invalid){ error=null; }
								if(!req.canceled && req.acceptsRemoteResponse()){
									if(error!=null) req.onError(error);
									else req.onError("HTTP "+response.code(), response.code(), null);
								}
							}
						}catch(Exception x){
							if(req.responseByteLimit()>0){ req.onError("Invalid NBW metadata response",response.code(),null); return; }
							Log.w(TAG, "onResponse: error processing response", x);
							onFailure(call, (IOException) new IOException(x).fillInStackTrace());
						}finally{
							synchronized(req){ if(req.okhttpCall==call) req.okhttpCall=null; }
						}

					}
				});
			}catch(Exception x){
				if(BuildConfig.DEBUG)
					Log.w(TAG, logTag(session)+"error creating and sending http request", x);
				req.onError(x.getLocalizedMessage(), 0, x);
			}
		}, 0);
	}

	/** Read at most limit+1 actual bytes, once. The owning response closes/cancels the remainder. */
	static String readErrorBody(MastodonAPIRequest<?> request, ResponseBody body) throws IOException{
		int requested=request.responseByteLimit();
		int limit=requested>0 ? Math.min(requested, ServiceResourcePause.MAX_ERROR_BYTES) : ServiceResourcePause.MAX_ERROR_BYTES;
		if(body.contentLength()>limit) throw new IOException("Error response too large");
		if(requested>0 && (body.contentType()==null || !"application".equals(body.contentType().type()) || !"json".equals(body.contentType().subtype()))) throw new IOException("Invalid error content type");
		java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
		java.io.InputStream input=body.byteStream();
		byte[] chunk=new byte[2048];
		int count;
		while((count=input.read(chunk, 0, Math.min(chunk.length, limit+1-output.size())))!=-1){
			output.write(chunk, 0, count);
			if(output.size()>limit) throw new IOException("Error response too large");
		}
		byte[] bytes=output.toByteArray();
		try{
			return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
					.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
					.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
		}catch(java.nio.charset.CharacterCodingException invalid){ throw new IOException("Invalid error encoding"); }
	}

	/** Preserve the original typed/custom error; presentation is additional metadata only. */
	static ErrorResponse decodeError(MastodonAPIRequest<?> request, Response response, JsonObject body){
		if(body==null) return null;
		ServiceResourcePause pause=ServiceResourcePause.fromResponse(response, body);
		ErrorResponse result;
		try{
			result=request.deserializeError(body, response.code());
			if(result==null && body.has("details")){
				MastodonDetailedErrorResponse detailed=new MastodonDetailedErrorResponse(errorText(body), response.code(), null);
				HashMap<String, List<MastodonDetailedErrorResponse.FieldError>> details=new HashMap<>();
				JsonObject errorDetails=body.getAsJsonObject("details");
				for(String key:errorDetails.keySet()){
					ArrayList<MastodonDetailedErrorResponse.FieldError> fields=new ArrayList<>();
					for(JsonElement element:errorDetails.getAsJsonArray(key)){
						JsonObject object=element.getAsJsonObject();
						MastodonDetailedErrorResponse.FieldError field=new MastodonDetailedErrorResponse.FieldError();
						field.description=object.get("description").getAsString(); field.error=object.get("error").getAsString(); fields.add(field);
					}
					details.put(key, fields);
				}
				detailed.detailedErrors=details; result=detailed;
			}
		}catch(RuntimeException malformed){ result=null; }
		if(result==null) result=new MastodonErrorResponse(errorText(body), response.code(), null);
		ServiceResourcePause.attach(result, pause);
		return result;
	}

	private static String errorText(JsonObject body){
		JsonElement value=body.get("error");
		if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return "API request failed";
		String text=value.getAsString();
		return text.indexOf('<')>=0 || text.indexOf('>')>=0 ? "API request failed" : text;
	}

	private static Reader boundedResponseReader(MastodonAPIRequest<?> request, ResponseBody body) throws IOException{
		int limit=request.responseByteLimit();
		if(limit<=0) return body.charStream();
		if(body.contentType()==null || !"application".equals(body.contentType().type()) || !"json".equals(body.contentType().subtype())
				|| body.contentLength()>limit) throw new IOException("Invalid or oversized NBW metadata response");
		java.io.InputStream input=new java.io.FilterInputStream(body.byteStream()){
			private int remaining=limit;
			@Override public int read() throws IOException{
				int value=super.read(); if(value>=0 && --remaining<0) throw new IOException("NBW metadata response too large"); return value;
			}
			@Override public int read(byte[] bytes,int offset,int length) throws IOException{
				int read=in.read(bytes,offset,Math.min(length,remaining+1));
				if(read>0 && (remaining-=read)<0) throw new IOException("NBW metadata response too large"); return read;
			}
		};
		return new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8);
	}

	public static void runInBackground(Runnable action){
		thread.postRunnable(action, 0);
	}

	public static OkHttpClient getHttpClient(){
		return httpClient;
	}

	public static OkHttpClient getSystemTrustSensitiveHttpClient(){
		return systemTrustSensitiveHttpClient;
	}

	private static String logTag(AccountSession session){
		return "["+(session==null ? "no-auth" : session.getID())+"] ";
	}

	public void startPollingAsyncRefresh(AsyncRefreshHeader header, Consumer<AsyncRefresh> callback){
		AsyncRefreshPollRecord existingRecord=asyncRefreshPolls.get(header.id);
		if(existingRecord!=null){
			if(existingRecord.callbacks.contains(callback))
				throw new IllegalStateException("This callback is already polling this async refresh");
			existingRecord.callbacks.add(callback);
			return;
		}
		AsyncRefreshPollRecord r=new AsyncRefreshPollRecord();
		r.callbacks.add(callback);
		r.retryInterval=header.retryInterval;
		r.upcomingApiCall=()->doAsyncRefreshPoll(header.id, r);
		asyncRefreshPolls.put(header.id, r);
		thread.postRunnable(r.upcomingApiCall, r.retryInterval*1000);
		if(BuildConfig.DEBUG)
			Log.d(TAG, "Starting async refresh poll for id "+header.id);
	}

	private void doAsyncRefreshPoll(String id, AsyncRefreshPollRecord r){
		r.currentApiCall=new GetAsyncRefresh(id)
				.setCallback(new me.grishka.appkit.api.Callback<>(){
					@Override
					public void onSuccess(GetAsyncRefresh.Response result){
						AsyncRefresh ar=result.asyncRefresh;
						if(ar.status==AsyncRefresh.RefreshStatus.FINISHED){
							for(Consumer<AsyncRefresh> callback:r.callbacks){
								callback.accept(ar);
							}
							asyncRefreshPolls.remove(id);
							r.currentApiCall=null;
						}else if(ar.status==AsyncRefresh.RefreshStatus.RUNNING){
							r.resultCount=ar.resultCount;
							r.currentApiCall=null;
							thread.postRunnable(r.upcomingApiCall, r.retryInterval*1000);
						}
					}

					@Override
					public void onError(ErrorResponse error){
						if(BuildConfig.DEBUG)
							Log.w(TAG, "Async refresh "+id+" polling failed");
						asyncRefreshPolls.remove(id);
						r.currentApiCall=null;
					}
				})
				.exec(session.getID());
	}

	public void cancelPollingAsyncRefresh(String id, Consumer<AsyncRefresh> callback){
		AsyncRefreshPollRecord r=asyncRefreshPolls.get(id);
		if(r==null)
			return;
		r.callbacks.remove(callback);
		if(r.callbacks.isEmpty()){
			if(BuildConfig.DEBUG)
				Log.d(TAG, "Canceling async refresh poll "+id+" because there are no callbacks left");
			asyncRefreshPolls.remove(id);
			thread.handler.removeCallbacks(r.upcomingApiCall);
			if(r.currentApiCall!=null)
				r.currentApiCall.cancel();
		}
	}

	public int getAsyncRefreshResultCount(String id){
		AsyncRefreshPollRecord r=asyncRefreshPolls.get(id);
		return r==null ? 0 : r.resultCount;
	}

	private static class AsyncRefreshPollRecord{
		public ArrayList<Consumer<AsyncRefresh>> callbacks=new ArrayList<>();
		public int retryInterval;
		public Runnable upcomingApiCall;
		public MastodonAPIRequest<?> currentApiCall;
		public int resultCount;
	}
}
