package org.joinmastodon.android.auth;

import android.net.Uri;
import android.os.SystemClock;
import android.util.Base64;

import com.squareup.otto.Subscribe;

import org.joinmastodon.android.E;
import org.joinmastodon.android.events.AccountLoggedOutEvent;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.api.requests.nbw.NBWLoginRequest;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Objects;

import me.grishka.appkit.api.ErrorResponse;

/** Process-only proof and single-flight ownership. No proof is serialized, logged or persisted. */
public final class NBWLoginFlow{
	public static final long TTL_MS=600_000L;
	public static final long COMPLETION_TTL_MS=120_000L;
	private static final NBWLoginFlow INSTANCE=new NBWLoginFlow();
	private static final SecureRandom RANDOM=new SecureRandom();
	private long generation, owner;
	private Pending pending;

	public static NBWLoginFlow getInstance(){ return INSTANCE; }
	private NBWLoginFlow(){ E.register(this); }
	@Subscribe public synchronized void onAccountLoggedOut(AccountLoggedOutEvent event){ clear(); }

	/** Only these non-secret counters may cross a lifecycle boundary. */
	public static final class Handle{
		public final long generation, owner;
		private Handle(long generation, long owner){ this.generation=generation; this.owner=owner; }
	}
	public enum Phase{ NEW, STARTING, WAITING, EXCHANGING, INSTALLING }
	public static final class Snapshot{
		public final Phase phase;
		public final int message;
		public final long remainingMillis;
		private final boolean hasCompletionCode;
		private Snapshot(Pending p){
			phase=p.phase; message=p.message; hasCompletionCode=p.completionCode!=null;
			long now=SystemClock.elapsedRealtime();
			remainingMillis=Math.max(0, Math.min(TTL_MS-(now-p.created), hasCompletionCode || phase==Phase.INSTALLING ? COMPLETION_TTL_MS-(now-p.completionReceived) : TTL_MS));
		}
		public boolean canRetry(){ return phase==Phase.NEW || phase==Phase.WAITING && hasCompletionCode; }
	}
	private static final class Identity{
		final String accountID, accessToken;
		final AccountSession session;
		final Token token;
		final long sessionGeneration;
		Identity(){
			AccountSessionManager manager=AccountSessionManager.getInstance();
			accountID=manager.getLastActiveAccountID(); session=manager.tryGetAccount(accountID);
			token=session==null ? null : session.token; accessToken=token==null ? null : token.accessToken;
			// Account switching/logout deactivates this repository, including switch-away-and-back.
			sessionGeneration=session==null ? -1 : session.getNBWForumOptionsRepository().getSnapshot().generation;
		}
		boolean current(){
			AccountSessionManager manager=AccountSessionManager.getInstance();
			return Objects.equals(accountID, manager.getLastActiveAccountID()) && session==manager.tryGetAccount(accountID)
					&& (session==null || session.token==token && Objects.equals(accessToken, token==null ? null : token.accessToken)
					&& sessionGeneration==session.getNBWForumOptionsRepository().getSnapshot().generation);
		}
	}
	private static final class Pending{
		final long generation, created;
		final Identity identity=new Identity();
		long owner;
		String verifier, flow, completionCode;
		long completionReceived;
		boolean hintHandled;
		Phase phase=Phase.NEW;
		int message=R.string.nbw_login_starting;
		MastodonAPIRequest<?> request;
		Pending(long generation, long owner){
			this.generation=generation; this.owner=owner; created=SystemClock.elapsedRealtime(); verifier=newVerifier();
		}
	}

	public synchronized Handle begin(){
		clear();
		pending=new Pending(++generation, ++owner);
		return new Handle(pending.generation, pending.owner);
	}
	public synchronized Handle attach(long expectedGeneration){
		if(!validPending() || pending.generation!=expectedGeneration) return null;
		// Transferring ownership invalidates callbacks held by an old Activity.
		if(pending.owner!=0) releaseRequest(true);
		if(pending==null) return null;
		pending.owner=++owner;
		return new Handle(pending.generation, pending.owner);
	}
	public synchronized boolean isCurrent(Handle handle){ return owns(handle) && validPending(); }
	public synchronized Snapshot snapshot(Handle handle){ return isCurrent(handle) ? new Snapshot(pending) : null; }
	public synchronized long acceptCompletion(Uri uri){
		if(!isCompletionCallback(uri) || !validPending() || pending.flow==null) return 0;
		String code=uri.getQueryParameter("code");
		if(pending.completionCode!=null && !pending.completionCode.equals(code)) return 0;
		if(pending.completionCode==null){
			pending.completionCode=code; pending.completionReceived=SystemClock.elapsedRealtime();
		}
		return pending.generation;
	}
	public synchronized NBWLoginRequest<NBWLoginRequest.Start> start(Handle handle){
		if(!isCurrent(handle) || pending.phase!=Phase.NEW) return null;
		NBWLoginRequest<NBWLoginRequest.Start> request=NBWLoginRequest.start(challenge(pending.verifier));
		pending.phase=Phase.STARTING; pending.message=R.string.nbw_login_starting; pending.request=request;
		return request;
	}
	public synchronized Uri acceptStart(Handle handle, MastodonAPIRequest<?> request, NBWLoginRequest.Start result){
		if(!isCurrent(handle) || pending.request!=request || pending.phase!=Phase.STARTING) return null;
		try{ result.postprocess(); }catch(Exception ignored){ clear(); return null; }
		pending.request=null; pending.flow=result.flow; pending.phase=Phase.WAITING; pending.message=R.string.nbw_login_authorizing;
		// The URL is returned once, never retained or saved for a second browser launch.
		return Uri.parse(result.authorizationUrl);
	}
	public synchronized NBWLoginRequest<NBWLoginRequest.Exchange> exchange(Handle handle, boolean manual){
		if(!isCurrent(handle) || pending.phase!=Phase.WAITING || pending.completionCode==null || !manual && pending.hintHandled) return null;
		pending.hintHandled=true;
		NBWLoginRequest<NBWLoginRequest.Exchange> request=NBWLoginRequest.exchange(pending.flow, pending.verifier, pending.completionCode);
		pending.phase=Phase.EXCHANGING; pending.message=R.string.nbw_login_finishing; pending.request=request;
		return request;
	}
	public synchronized boolean acceptExchange(Handle handle, MastodonAPIRequest<?> request, NBWLoginRequest.Exchange result){
		if(!isCurrent(handle) || pending.request!=request || pending.phase!=Phase.EXCHANGING) return false;
		try{ result.postprocess(); }catch(Exception ignored){ clear(); return false; }
		pending.request=null; pending.verifier=pending.flow=pending.completionCode=null; pending.phase=Phase.INSTALLING; pending.message=R.string.nbw_login_finishing;
		return true;
	}
	public synchronized void installation(Handle handle, MastodonAPIRequest<?> request){
		if(isCurrent(handle) && pending.phase==Phase.INSTALLING) pending.request=request;
		else if(request!=null) request.cancel();
	}
	/** Unknown HTTP errors are terminal. Only explicit pending/service failures or transport failure retry. */
	public synchronized int failure(Handle handle, MastodonAPIRequest<?> request, ErrorResponse error){
		if(!isCurrent(handle) || pending.request!=request) return R.string.nbw_login_expired;
		int message=R.string.nbw_login_failed;
		boolean retry=false;
		if(error instanceof NBWLoginRequest.LoginError known){ message=known.message; retry=known.retryable; }
		else if(error instanceof MastodonErrorResponse transport && transport.httpStatus<=0){ message=R.string.nbw_login_network_error; retry=true; }
		pending.request=null;
		if(retry && (pending.phase==Phase.STARTING || pending.phase==Phase.EXCHANGING)){
			pending.phase=pending.phase==Phase.STARTING ? Phase.NEW : Phase.WAITING; pending.message=message;
		}else clear();
		return message;
	}
	public synchronized void complete(Handle handle){ if(owns(handle)) clear(); }
	public synchronized void cancel(Handle handle){ if(owns(handle)) clear(); }
	public synchronized void detach(Handle handle, boolean recreating){
		if(!owns(handle)) return;
		releaseRequest(recreating);
		if(pending!=null) pending.owner=0;
	}
	private void releaseRequest(boolean recreating){
		MastodonAPIRequest<?> request=pending.request; pending.request=null;
		if(recreating && (pending.phase==Phase.WAITING || pending.phase==Phase.EXCHANGING)){
			if(pending.phase==Phase.EXCHANGING){ pending.phase=Phase.WAITING; pending.message=R.string.nbw_login_network_error; }
		}else clear();
		if(request!=null) request.cancel();
	}
	private boolean owns(Handle handle){ return handle!=null && pending!=null && pending.generation==handle.generation && pending.owner==handle.owner; }
	private boolean validPending(){
		if(pending==null) return false;
		long elapsed=SystemClock.elapsedRealtime()-pending.created;
		long completionElapsed=SystemClock.elapsedRealtime()-pending.completionReceived;
		if(elapsed<0 || elapsed>=TTL_MS || (pending.completionCode!=null || pending.phase==Phase.INSTALLING) && (completionElapsed<0 || completionElapsed>=COMPLETION_TTL_MS)
				|| !pending.identity.current()){ clear(); return false; }
		return true;
	}
	private void clear(){
		if(pending==null) return;
		MastodonAPIRequest<?> request=pending.request;
		pending.verifier=pending.flow=pending.completionCode=null; pending.request=null; pending=null;
		if(request!=null) request.cancel();
	}
	public static String newVerifier(){
		byte[] bytes=new byte[32]; RANDOM.nextBytes(bytes);
		return Base64.encodeToString(bytes, Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
	}
	public static String challenge(String verifier){
		try{
			byte[] digest=MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.encodeToString(digest, Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
		}catch(NoSuchAlgorithmException impossible){ throw new IllegalStateException("SHA-256 unavailable"); }
	}
	public static boolean isCompletionCallback(Uri uri){
		if(uri==null || !uri.isHierarchical()) return false;
		if(!"abdl-space".equals(uri.getScheme()) || !"callback".equals(uri.getEncodedAuthority())
				|| uri.getUserInfo()!=null || uri.getPort()!=-1 || uri.getEncodedFragment()!=null
				|| uri.getEncodedPath()!=null && !uri.getEncodedPath().isEmpty()) return false;
		String query=uri.getEncodedQuery();
		return query!=null && query.matches("nbw_v2_login=complete&code=[A-Za-z0-9_-]{43}")
				&& NBWLoginRequest.canonical43(uri.getQueryParameter("code"));
	}
	/** Marker-bearing malformed callbacks are consumed before OAuth/binding, never interpreted as codes. */
	public static boolean hasCompletionMarker(Uri uri){
		if(uri==null || !uri.isHierarchical()) return false;
		try{ return uri.getQueryParameterNames().contains("nbw_v2_login"); }
		catch(RuntimeException ignored){ return true; }
	}
}
