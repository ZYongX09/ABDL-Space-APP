package org.joinmastodon.android.api;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.util.Pair;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.BuildConfig;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.BaseModel;
import org.joinmastodon.android.model.Token;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import androidx.annotation.CallSuper;
import androidx.annotation.StringRes;
import me.grishka.appkit.api.APIRequest;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import okhttp3.Call;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.internal.http.HttpMethod;

public abstract class MastodonAPIRequest<T> extends APIRequest<T>{
	protected static final String TAG="MastodonAPIRequest";

	private String domain;
	private AccountSession account;
	private String path;
	private String method;
	private Object requestBody;
	private List<Pair<String, String>> queryParams;
	Class<T> respClass;
	TypeToken<T> respTypeToken;
	Call okhttpCall;
	Token token;
	volatile boolean canceled;
	Map<String, String> headers;
	long timeout;
	boolean cacheable;
	private ProgressDialog progressDialog;
	protected boolean removeUnsupportedItems;
	protected boolean skipValidation;
	private org.joinmastodon.android.api.session.NBWForumOptionsRepository remoteRepository;
	private final org.joinmastodon.android.api.session.NBWForumOptionsRepository.Cancelable remoteCancellation=this::cancel;
	private long remoteGeneration=-1, rejectedGeneration=-1;
	private boolean nbwRemoteRequest;
	private boolean callbackDelivered;
	private org.joinmastodon.android.api.session.ContentAccessCoordinator.Owner contentOwner;
	private boolean protectedContent;
	private Runnable nbwCancellationCleanup;

	/** Called before transport dispatch; no response may borrow the current account's credentials. */
	boolean prepareContentAccess(AccountSession session){
		if(!org.joinmastodon.android.api.session.ContentAccessCoordinator.applies(session)) return true;
		protectedContent=org.joinmastodon.android.api.session.ContentAccessCoordinator.isProtectedRoute(getURL(),getMethod());
		if(protectedContent || this instanceof org.joinmastodon.android.api.requests.GetContentAccess)
			contentOwner=session.getContentAccessCoordinator().capture();
		return !protectedContent || contentOwner==null || contentOwner.canDispatch();
	}

	void captureContentAccessHeader(Response response){
		// Never trust a cached/redirected foreign response, /c/token JSON, or an auth failure.
		if(!protectedContent || contentOwner==null || response.code()==401 || response.cacheResponse()!=null && response.networkResponse()==null
				|| !org.joinmastodon.android.api.session.ContentAccessCoordinator.isFirstParty(Uri.parse(response.request().url().toString()))) return;
		contentOwner.receiveHeader(response.header(org.joinmastodon.android.api.session.ContentAccessCoordinator.HEADER));
	}

	org.joinmastodon.android.api.session.ContentAccessCoordinator.Owner contentOwner(){ return contentOwner; }
	boolean contentDispatchAllowed(){ return !protectedContent || contentOwner==null || contentOwner.canDispatch(); }
	private boolean acceptsContentDelivery(){ return !protectedContent || contentOwner==null || contentOwner.canDispatch(); }
	private boolean acceptsContentError(ErrorResponse error){
		if(!protectedContent) return true;
		// Preserve the real current-account auth error; it is not a verification policy response.
		if(error instanceof MastodonErrorResponse value && value.httpStatus==401) return contentOwner!=null && contentOwner.sameIdentity();
		return contentOwner!=null && contentOwner.isCurrent();
	}

	/** Cancellation is an unknown remote outcome, not a fabricated success or ordinary error. */
	public MastodonAPIRequest<T> setNBWCancellationCleanup(Runnable cleanup){
		nbwCancellationCleanup=cleanup;
		return this;
	}

	private void finishRemote(){
		if(remoteRepository!=null) remoteRepository.finishRemote(remoteCancellation);
	}

	private void completeCancellationOnUiThread(){
		if(callbackDelivered) return;
		callbackDelivered=true;
		finishRemote();
		onRequestDone();
		if(nbwCancellationCleanup!=null) nbwCancellationCleanup.run();
	}

	private boolean guardNBW(AccountSession session){
		String urlPath=getURL().getPath();
		// These legacy auth/recommendation flows must never be used by the new native client.
		if(urlPath!=null && (urlPath.startsWith("/api/auth/nbw/") || urlPath.startsWith("/api/v1/auth/nbw/"))){
			onError(new MastodonErrorResponse.SilentNBWError("NBW V2 connection required", 503, null));
			return false;
		}
		nbwRemoteRequest=urlPath!=null && (urlPath.contains("/nbw_") || urlPath.endsWith("/timelines/nbw")
				|| urlPath.endsWith("/nbw/authorization/start"));
		if(!nbwRemoteRequest) return true;
		if(session==null){ onError(new MastodonErrorResponse.SilentNBWError("NBW unavailable", 503, null)); return false; }
		remoteRepository=session.getNBWForumOptionsRepository();
		rejectedGeneration=remoteRepository.getSnapshot().generation;
		remoteGeneration=remoteRepository.registerRemote(remoteCancellation, !"GET".equals(method));
		if(remoteGeneration<0){ onError(new MastodonErrorResponse.SilentNBWError("NBW unavailable", 503, null)); return false; }
		return true;
	}
	private boolean hasOriginalSession(){ return account!=null && AccountSessionManager.getInstance().tryGetAccount(account.getID())==account; }
	boolean acceptsRemoteResponse(){ return !nbwRemoteRequest || remoteRepository!=null && hasOriginalSession() && remoteRepository.isCurrentRemote(remoteGeneration); }

	public MastodonAPIRequest(HttpMethod method, String path, Class<T> respClass){
		this.path=path;
		this.method=method.toString();
		this.respClass=respClass;
	}

	public MastodonAPIRequest(HttpMethod method, String path, TypeToken<T> respTypeToken){
		this.path=path;
		this.method=method.toString();
		this.respTypeToken=respTypeToken;
	}

	@Override
	public synchronized void cancel(){
		if(BuildConfig.DEBUG)
			Log.d(TAG, "canceling request "+this);
		canceled=true;
		if(okhttpCall!=null){
			okhttpCall.cancel();
		}
		if(nbwRemoteRequest || nbwCancellationCleanup!=null)
			uiThreadHandler.post(this::completeCancellationOnUiThread);
	}

	@Override
	public APIRequest<T> exec(){
		throw new UnsupportedOperationException("Use exec(accountID) or execNoAuth(domain)");
	}

	public MastodonAPIRequest<T> exec(String accountID){
		try{
			account=AccountSessionManager.getInstance().getAccount(accountID);
			domain=account.domain;
			if(guardNBW(account)) account.getApiController().submitRequest(this);
		}catch(Exception x){
			Log.e(TAG, "exec: this shouldn't happen, but it still did", x);
			invokeErrorCallback(new MastodonErrorResponse(x.getLocalizedMessage(), -1, x));
		}
		return this;
	}

	public MastodonAPIRequest<T> execNoAuth(String domain){
		this.domain=domain;
		if(guardNBW(null)) AccountSessionManager.getInstance().getUnauthenticatedApiController().submitRequest(this);
		return this;
	}

	public MastodonAPIRequest<T> exec(String domain, Token token){
		this.domain=domain;
		this.token=token;
		if(guardNBW(null)) AccountSessionManager.getInstance().getUnauthenticatedApiController().submitRequest(this);
		return this;
	}

	// MOSHIDON:
	public MastodonAPIRequest<T> wrapProgress(Context context, @StringRes int message, boolean cancelable){
		return wrapProgress(context, message, cancelable, null);
	}

	// MOSHIDON: custom dialogs
	public MastodonAPIRequest<T> wrapProgress(Context context, @StringRes int message, boolean cancelable, Consumer<ProgressDialog> transform){
		progressDialog=new ProgressDialog(context);
		progressDialog.setMessage(context.getString(message));
		progressDialog.setCancelable(cancelable);
		if (transform != null) transform.accept(progressDialog);
		if(cancelable){
			progressDialog.setOnCancelListener(dialog->cancel());
		}
		progressDialog.show();
		return this;
	}

	protected void setRequestBody(Object body){
		requestBody=body;
	}

	protected void setSkipValidation(){
		skipValidation=true;
	}

	protected void addQueryParameter(String key, String value){
		if(queryParams==null)
			queryParams=new ArrayList<>();
		queryParams.add(new Pair<>(key, value));
	}

	protected void addHeader(String key, String value){
		if(headers==null)
			headers=new HashMap<>();
		headers.put(key, value);
	}

	protected void setTimeout(long timeout){
		this.timeout=timeout;
	}

	protected void setCacheable(){
		cacheable=true;
	}

	/** Sensitive extensions may preserve structured server errors and forbid redirects. */
	public ErrorResponse deserializeError(com.google.gson.JsonObject body, int status){ return null; }
	public boolean isSensitiveRequest(){ return false; }
	/** First-party high-sensitivity flows can additionally exclude user-installed certificate authorities. */
	public boolean requiresSystemTrust(){ return false; }
	/** Opt-in only: reject non-JSON or oversized metadata on the actual byte stream. */
	public int responseByteLimit(){ return 0; }

	protected String getPathPrefix(){
		return "/api/v1";
	}

	public Uri getURL(){
		Uri.Builder builder=new Uri.Builder()
				.scheme("https")
				.authority(domain)
				.path(getPathPrefix()+path);
		if(queryParams!=null){
			for(Pair<String, String> param:queryParams){
				builder.appendQueryParameter(param.first, param.second);
			}
		}
		return builder.build();
	}

	public String getMethod(){
		return method;
	}

	public RequestBody getRequestBody() throws IOException{
		if(requestBody instanceof RequestBody rb)
			return rb;
		return requestBody==null ? null : new JsonObjectRequestBody(requestBody);
	}

	@Override
	public MastodonAPIRequest<T> setCallback(Callback<T> callback){
		super.setCallback(callback);
		return this;
	}

	@CallSuper
	public void validateAndPostprocessResponse(T respObj, Response httpResponse) throws IOException{
		if(skipValidation)
			return;
		if(respObj==null && (respTypeToken!=null || respClass!=null)){
			throw new ObjectValidationException("Server response is empty");
		}else if(respObj instanceof BaseModel){
			((BaseModel) respObj).postprocess();
		}else if(respObj instanceof List<?> respList){
			if(removeUnsupportedItems){
				Iterator<?> itr=respList.iterator();
				while(itr.hasNext()){
					Object item=itr.next();
					if(item instanceof BaseModel bm){
						try{
							bm.postprocess();
						}catch(ObjectValidationException x){
							Log.w(TAG, "Removing invalid object from list", x);
							itr.remove();
						}
					}else if(item==null){
						Log.w(TAG, "Removing null object from list");
						itr.remove();
					}
				}
			}else{
				for(Object item:respList){
					if(item instanceof BaseModel bm)
						bm.postprocess();
					else if(item==null)
						throw new ObjectValidationException("null item in list");
				}
			}
		}
	}

	void onError(ErrorResponse err){
		if(!nbwRemoteRequest){
			// Recheck at actual UI delivery, including cancellation while an error body is read.
			uiThreadHandler.post(()->{
				if(callbackDelivered) return;
				if(canceled || !acceptsContentError(err)){ callbackDelivered=true; onRequestDone(); return; }
				callbackDelivered=true;
				onRequestDone();
				if(callback!=null) callback.onError(err);
			});
			return;
		}
		uiThreadHandler.post(()->{
			if(callbackDelivered) return;
			if(canceled || remoteGeneration>=0 && !acceptsRemoteResponse()
					|| remoteGeneration<0 && remoteRepository!=null && (!hasOriginalSession() || remoteRepository.getSnapshot().generation!=rejectedGeneration)){
				completeCancellationOnUiThread();
				return;
			}
			callbackDelivered=true;
			finishRemote();
			onRequestDone();
			if(callback!=null) callback.onError(new MastodonErrorResponse.SilentNBWError("NBW operation unavailable", 0, null));
		});
	}

	protected void onError(String msg, int httpStatus, Throwable exception){
		onError(nbwRemoteRequest ? new MastodonErrorResponse.SilentNBWError(msg, httpStatus, exception) : new MastodonErrorResponse(msg, httpStatus, exception));
	}

	void onSuccess(T resp){
		if(!nbwRemoteRequest){
			// Validate at the actual UI callback, after any captured policy header has closed the gate.
			uiThreadHandler.post(()->{
				if(callbackDelivered) return;
				if(canceled || !acceptsContentDelivery()){ callbackDelivered=true; onRequestDone(); return; }
				callbackDelivered=true;
				onRequestDone();
				if(callback!=null) callback.onSuccess(resp);
			});
			return;
		}
		// AppKit posts again without checking cancellation. Validate at the actual delivery point
		// and keep the request registered until then so a mode/account change can still cancel it.
		uiThreadHandler.post(()->{
			if(callbackDelivered) return;
			if(canceled || !acceptsRemoteResponse() || !acceptsContentDelivery()){
				completeCancellationOnUiThread();
				return;
			}
			callbackDelivered=true;
			finishRemote();
			onRequestDone();
			if(callback!=null) callback.onSuccess(resp);
		});
	}

	protected final void dispatchSuccess(T resp){
		onSuccess(resp);
	}

	protected final void dispatchError(ErrorResponse error){
		onError(error);
	}

	protected final boolean isCanceled(){
		return canceled;
	}

	@Override
	protected void onRequestDone(){
		if(progressDialog!=null){
			progressDialog.dismiss();
		}
	}

	public enum HttpMethod{
		GET,
		POST,
		PUT,
		DELETE,
		PATCH
	}
}
