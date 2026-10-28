package org.joinmastodon.android.api.requests.auth;

import android.content.Context;
import android.net.Uri;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import org.joinmastodon.android.BuildConfig;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.Token;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import me.grishka.appkit.api.ErrorResponse;
import okhttp3.Authenticator;
import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.CookieJar;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Independent, anonymous, fixed-origin protocol. No old endpoint or authenticated execution path. */
public final class HumanCheckRequest extends MastodonAPIRequest<HumanCheckRequest.Reply>{
	public static final String DOMAIN="api.abdl-space.top";
	public static final String ORIGIN="https://"+DOMAIN;
	public static final String PREFIX="/api/app/human-check";
	public static final int MAX_BYTES=8192;
	public enum Endpoint{
		SEND_CODE("send-code"), CHALLENGE("challenge"), COMPLETE("complete"), STATUS("status"), CANCEL("cancel"), CHALLENGE_CANCEL("challenge/cancel");
		final String path;
		Endpoint(String path){ this.path=path; }
	}
	public final Endpoint endpoint;
	private final String operationId;
	private volatile Call call;
	private boolean executed;

	private HumanCheckRequest(Endpoint endpoint, String operationId, JsonObject body){
		super(HttpMethod.POST, "/"+endpoint.path, Reply.class);
		this.endpoint=endpoint; this.operationId=operationId;
		setRequestBody(body); setTimeout(15000); addHeader("X-App-Human-Check", "1");
	}
	@Override protected String getPathPrefix(){ return PREFIX; }
	@Override public Uri getURL(){ return Uri.parse(ORIGIN+PREFIX+"/"+endpoint.path); }
	@Override public boolean isSensitiveRequest(){ return true; }
	@Override public boolean requiresSystemTrust(){ return true; }
	@Override public MastodonAPIRequest<Reply> exec(String accountID){ throw new UnsupportedOperationException("Anonymous human check only"); }
	@Override public MastodonAPIRequest<Reply> exec(String domain, Token token){ throw new UnsupportedOperationException("Anonymous human check only"); }

	/**
	 * The shared controller's generic JSON/error reader is intentionally not used: it loses duplicate
	 * members and has no body bound. Reuse its system-only trust client, not its authenticated transport.
	 */
	@Override public synchronized HumanCheckRequest execNoAuth(String domain){
		if(!DOMAIN.equals(domain)) throw new IllegalArgumentException("Fixed human-check origin required");
		if(executed) throw new IllegalStateException("Request already dispatched");
		executed=true;
		if(isCanceled()) return this;
		try{
			Request request=new Request.Builder().url(getURL().toString()).post(getRequestBody())
					.header("User-Agent", "MastodonAndroid/"+BuildConfig.VERSION_NAME)
					.header("X-App-Version-Code", String.valueOf(BuildConfig.VERSION_CODE))
					.header("X-App-Human-Check", "1").header("Accept", "application/json")
					.header("Referrer-Policy", "no-referrer")
					.cacheControl(new CacheControl.Builder().noCache().noStore().build()).build();
			call=MastodonAPIController.getSystemTrustSensitiveHttpClient().newBuilder()
					.cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
					.cache(null).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build().newCall(request);
			call.timeout().timeout(15, java.util.concurrent.TimeUnit.SECONDS);
			call.enqueue(new okhttp3.Callback(){
				@Override public void onFailure(Call ignored, IOException error){ finishError(new Failure(0, "NETWORK")); }
				@Override public void onResponse(Call ignored, Response response){
					try(response){
						if(isCanceled()) return;
						Reply reply=readResponse(response, endpoint, operationId);
						if(reply.code!=null) finishError(new Failure(response.code(), reply.code, reply.operationId, reply.expiresIn));
						else{ call=null; if(!isCanceled()) dispatchSuccess(reply); }
					}catch(Exception invalid){ finishError(new Failure(0, "PROTOCOL")); }
				}
			});
		}catch(Exception unavailable){ finishError(new Failure(0, "NETWORK")); }
		return this;
	}
	private void finishError(Failure error){ call=null; if(!isCanceled()) dispatchError(error); }
	@Override public synchronized void cancel(){ if(call!=null) call.cancel(); call=null; super.cancel(); }

	public static HumanCheckRequest sendCode(String email, String operation, String challenge){
		if(email==null || email.length()>320 || email.isEmpty() || !email.equals(email.trim()) || email.indexOf('\r')>=0 || email.indexOf('\n')>=0 || !canonical43(challenge))
			throw new IllegalArgumentException("Invalid email operation");
		JsonObject body=operation(operation); body.addProperty("email", email); body.addProperty("type", "register"); body.addProperty("code_challenge", challenge);
		return new HumanCheckRequest(Endpoint.SEND_CODE, operation, body);
	}
	public static HumanCheckRequest challenge(String operation, String verifier){ return proof(Endpoint.CHALLENGE, operation, verifier, null); }
	public static HumanCheckRequest complete(String operation, String verifier, String challenge){ return proof(Endpoint.COMPLETE, operation, verifier, requiredChallenge(challenge)); }
	public static HumanCheckRequest status(String operation, String verifier){ return proof(Endpoint.STATUS, operation, verifier, null); }
	public static HumanCheckRequest cancel(String operation, String verifier){ return proof(Endpoint.CANCEL, operation, verifier, null); }
	public static HumanCheckRequest cancelChallenge(String operation, String verifier, String challenge){ return proof(Endpoint.CHALLENGE_CANCEL, operation, verifier, requiredChallenge(challenge)); }
	private static String requiredChallenge(String challenge){ if(!canonical43(challenge)) throw new IllegalArgumentException("Invalid attempt"); return challenge; }
	private static HumanCheckRequest proof(Endpoint endpoint, String operation, String verifier, String challenge){
		if(verifier==null || !verifier.matches("[A-Za-z0-9._~-]{43,128}")) throw new IllegalArgumentException("Invalid proof");
		JsonObject body=operation(operation); body.addProperty("code_verifier", verifier); if(challenge!=null) body.addProperty("challenge_id", challenge);
		return new HumanCheckRequest(endpoint, operation, body);
	}
	private static JsonObject operation(String operation){
		if(!uuid4(operation)) throw new IllegalArgumentException("Invalid operation");
		JsonObject body=new JsonObject(); body.addProperty("operation_id", operation); return body;
	}
	public static boolean uuid4(String value){ return value!=null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"); }
	public static boolean canonical43(String value){ return value!=null && value.matches("[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]"); }
	public static String pageUrl(String challenge){ return canonical43(challenge) ? ORIGIN+PREFIX+"/page?challenge_id="+challenge : null; }
	public static boolean validPageUrl(String value, String challenge){ return value!=null && value.equals(pageUrl(challenge)); }

	@JsonAdapter(ReplyAdapter.class)
	public static final class Reply{
		public final String state, operationId, challengeId, challengeUrl, code;
		public final int expiresIn;
		public final boolean cancelled;
		private final Set<String> keys;
		private Reply(Map<String, String> values){
			keys=Set.copyOf(values.keySet()); state=values.get("state"); operationId=values.get("operation_id");
			challengeId=values.get("challenge_id"); challengeUrl=values.get("challenge_url"); code=values.get("code");
			expiresIn=values.containsKey("expires_in") ? Integer.parseInt(values.get("expires_in")) : 0;
			cancelled="true".equals(values.get("cancelled"));
		}
	}
	/** Reject duplicates, coercion, unknown fields, nested shapes and token-bearing payloads. */
	public static final class ReplyAdapter extends TypeAdapter<Reply>{
		@Override public Reply read(JsonReader reader) throws IOException{
			Map<String, String> values=readFlatObject(reader, Set.of("state", "operation_id", "message", "challenge_id", "challenge_url", "expires_in", "cancelled", "error", "code"));
			Reply reply=new Reply(values);
			if(reply.operationId!=null && !uuid4(reply.operationId) || reply.challengeId!=null && !canonical43(reply.challengeId)
					|| reply.challengeUrl!=null && !validPageUrl(reply.challengeUrl, reply.challengeId)) throw invalid();
			if(reply.code!=null){
				Set<String> expected="HUMAN_CHECK_REQUIRED".equals(reply.code) && reply.operationId!=null
						? Set.of("error", "code", "operation_id", "expires_in") : Set.of("error", "code");
				if(!reply.keys.equals(expected) || !knownCode(reply.code)) throw invalid();
			}else if(reply.challengeId!=null){
				if(!reply.keys.equals(Set.of("challenge_id", "challenge_url", "expires_in"))) throw invalid();
			}else if(values.containsKey("cancelled")){
				if(!reply.keys.equals(Set.of("cancelled")) || !reply.cancelled) throw invalid();
			}else{
				if(!Set.of("sent", "processing", "challenge_required", "cancelled", "failed", "unknown").contains(reply.state==null ? "" : reply.state)
						|| !reply.keys.equals(values.containsKey("message") ? Set.of("state", "operation_id", "message") : Set.of("state", "operation_id"))) throw invalid();
				if(values.containsKey("message") && (!"sent".equals(reply.state) || !"验证码已发送".equals(values.get("message")))) throw invalid();
			}
			return reply;
		}
		@Override public void write(JsonWriter out, Reply reply) throws IOException{ throw invalid(); }
	}
	private static Map<String, String> readFlatObject(JsonReader reader, Set<String> allowed) throws IOException{
		if(reader.peek()!=JsonToken.BEGIN_OBJECT) throw invalid();
		reader.beginObject(); Map<String, String> values=new HashMap<>(); int count=0;
		while(reader.hasNext()){
			String key=reader.nextName();
			if(!allowed.contains(key) || values.containsKey(key) || ++count>10) throw invalid();
			JsonToken token=reader.peek(); String value;
			if("expires_in".equals(key)){
				if(token!=JsonToken.NUMBER || !(value=reader.nextString()).matches("[1-9][0-9]{0,2}") || Integer.parseInt(value)>300) throw invalid();
			}else if("cancelled".equals(key)){
				if(token!=JsonToken.BOOLEAN) throw invalid(); value=String.valueOf(reader.nextBoolean());
			}else{
				if(token!=JsonToken.STRING) throw invalid(); value=reader.nextString();
				if(value.isEmpty() || value.length()>2048) throw invalid();
			}
			values.put(key, value);
		}
		reader.endObject(); return values;
	}
	public static Reply parse(String json) throws IOException{
		if(json==null || json.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES) throw invalid();
		try(JsonReader reader=new JsonReader(new StringReader(json))){
			reader.setLenient(false); Reply reply=new ReplyAdapter().read(reader);
			if(reader.peek()!=JsonToken.END_DOCUMENT) throw invalid(); return reply;
		}catch(RuntimeException badShape){ throw invalid(); }
	}
	public static String parsePageEvent(String json, String challenge) throws IOException{
		if(json==null || json.length()>512 || !canonical43(challenge)) throw invalid();
		try(JsonReader reader=new JsonReader(new StringReader(json))){
			reader.setLenient(false); Map<String, String> values=readFlatObject(reader, Set.of("event", "challenge_id"));
			String event=values.get("event");
			if(reader.peek()!=JsonToken.END_DOCUMENT || values.size()!=2 || !challenge.equals(values.get("challenge_id"))
					|| !Set.of("ready", "verifying", "verified", "failed", "expired").contains(event==null ? "" : event)) throw invalid();
			return event;
		}catch(RuntimeException badShape){ throw invalid(); }
	}
	public static Reply readResponse(Response response, Endpoint endpoint, String operation) throws IOException{
		ResponseBody body=response.body();
		if(body==null || body.contentLength()>MAX_BYTES || body.contentType()==null
				|| !"application".equals(body.contentType().type()) || !"json".equals(body.contentType().subtype())) throw invalid();
		okio.Buffer buffer=new okio.Buffer();
		while(buffer.size()<=MAX_BYTES){
			long read=body.source().read(buffer, MAX_BYTES+1L-buffer.size());
			if(read==-1) break;
		}
		if(buffer.size()>MAX_BYTES) throw invalid();
		byte[] bytes=buffer.readByteArray();
		String json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
		Reply reply=parse(json); validateReply(reply, response.code(), endpoint, operation); return reply;
	}
	public static void validateReply(Reply reply, int status, Endpoint endpoint, String operation) throws IOException{
		if(reply==null || reply.operationId!=null && !operation.equals(reply.operationId)) throw invalid();
		if(reply.code!=null){
			if(!validErrorStatus(status, reply.code)) throw invalid();
			if("HUMAN_CHECK_REQUIRED".equals(reply.code) && endpoint==Endpoint.SEND_CODE && (reply.operationId==null || reply.expiresIn!=300)) throw invalid();
			if(reply.operationId!=null && endpoint!=Endpoint.SEND_CODE) throw invalid(); return;
		}
		boolean valid=switch(endpoint){
			case SEND_CODE, COMPLETE -> status==200 && "sent".equals(reply.state) && reply.keys.equals(Set.of("state", "operation_id", "message"))
					|| status==202 && "processing".equals(reply.state) && reply.keys.equals(Set.of("state", "operation_id"));
			case CHALLENGE -> status==200 && reply.challengeId!=null && reply.expiresIn>=1 && reply.expiresIn<=300;
			case STATUS -> status==200 && reply.state!=null && reply.keys.equals(Set.of("state", "operation_id"));
			case CANCEL, CHALLENGE_CANCEL -> status==200 && reply.cancelled;
		};
		if(!valid) throw invalid();
	}
	private static boolean knownCode(String code){ return Set.of("HUMAN_CHECK_INVALID_REQUEST", "HUMAN_CHECK_INVALID_PROOF", "HUMAN_CHECK_REQUIRED", "HUMAN_CHECK_CLIENT_REQUIRED", "HUMAN_CHECK_EXPIRED", "HUMAN_CHECK_CANCELLED", "HUMAN_CHECK_POLICY_CHANGED", "HUMAN_CHECK_DELIVERY_UNKNOWN", "AUTH_RATE_LIMITED", "HUMAN_CHECK_RATE_LIMITED", "HUMAN_CHECK_UNAVAILABLE", "EMAIL_DELIVERY_UNAVAILABLE").contains(code); }
	private static boolean validErrorStatus(int status, String code){
		return switch(code){
			case "HUMAN_CHECK_INVALID_REQUEST" -> status==400 || status==413;
			case "HUMAN_CHECK_INVALID_PROOF" -> status==400;
			case "HUMAN_CHECK_REQUIRED", "HUMAN_CHECK_CLIENT_REQUIRED" -> status==403;
			case "HUMAN_CHECK_EXPIRED", "HUMAN_CHECK_CANCELLED", "HUMAN_CHECK_POLICY_CHANGED", "HUMAN_CHECK_DELIVERY_UNKNOWN" -> status==409;
			case "AUTH_RATE_LIMITED", "HUMAN_CHECK_RATE_LIMITED" -> status==429;
			case "HUMAN_CHECK_UNAVAILABLE", "EMAIL_DELIVERY_UNAVAILABLE" -> status==503;
			default -> false;
		};
	}
	private static IOException invalid(){ return new IOException("Invalid human-check response"); }
	public static final class Failure extends ErrorResponse{
		public final int status, message, expiresIn;
		public final String code, operationId;
		public Failure(int status, String code){ this(status, code, null, 0); }
		private Failure(int status, String code, String operation, int expires){
			this.status=status; this.code=code; operationId=operation; expiresIn=expires;
			message=switch(code){
				case "NETWORK" -> R.string.human_check_network;
				case "HUMAN_CHECK_POLICY_CHANGED" -> R.string.human_check_policy_changed;
				case "HUMAN_CHECK_EXPIRED", "HUMAN_CHECK_INVALID_PROOF" -> R.string.human_check_expired;
				case "HUMAN_CHECK_CANCELLED" -> R.string.human_check_cancelled;
				case "HUMAN_CHECK_DELIVERY_UNKNOWN" -> R.string.human_check_unknown;
				case "AUTH_RATE_LIMITED", "HUMAN_CHECK_RATE_LIMITED" -> R.string.human_check_rate_limited;
				case "HUMAN_CHECK_UNAVAILABLE" -> R.string.human_check_unavailable;
				case "EMAIL_DELIVERY_UNAVAILABLE" -> R.string.human_check_delivery_failed;
				default -> R.string.human_check_failed;
			};
		}
		@Override public void bindErrorView(View view){ if(view instanceof TextView text) text.setText(message); }
		@Override public void showToast(Context context){ Toast.makeText(context, message, Toast.LENGTH_LONG).show(); }
	}
}
