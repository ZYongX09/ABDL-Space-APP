package org.joinmastodon.android.auth;

import android.os.SystemClock;
import android.util.Base64;

import com.squareup.otto.Subscribe;

import org.joinmastodon.android.E;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.auth.HumanCheckRequest;
import org.joinmastodon.android.api.requests.auth.HumanCheckRequest.Failure;
import org.joinmastodon.android.api.requests.auth.HumanCheckRequest.Reply;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.events.AccountLoggedOutEvent;
import org.joinmastodon.android.model.Token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.UUID;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;

/** Single process, single flight. Only non-secret generation/owner counters leave this object. */
public final class HumanCheckCoordinator{
	public static final long OPERATION_TTL=600_000L, ATTEMPT_TTL=300_000L;
	private static final HumanCheckCoordinator INSTANCE=new HumanCheckCoordinator();
	private static final SecureRandom RANDOM=new SecureRandom();
	private long generation, owner;
	private Pending pending;
	public static HumanCheckCoordinator getInstance(){ return INSTANCE; }
	private HumanCheckCoordinator(){ E.register(this); }
	public enum Action{ SEND, RESEND }
	public enum Phase{ SENDING, PROMPT, CREATING, LOADING, READY, VERIFYING, COMPLETING, PROCESSING, UNKNOWN, SENT, FAILED, CANCELLING, CANCELLED, CANCEL_UNKNOWN }
	public interface Listener{ void onChanged(); }
	public static final class Handle{
		public final long generation, owner;
		private final boolean ui;
		private Handle(long generation, long owner, boolean ui){ this.generation=generation; this.owner=owner; this.ui=ui; }
	}
	public static final class Snapshot{
		public final Phase phase;
		public final int message;
		public final long remainingMillis, attempt;
		// Opaque challenge identity only; never a mailbox, verifier, widget token or authorization value.
		public final String challengeId, pageUrl;
		public final boolean canStart, canQuery, busy;
		private Snapshot(Pending p){
			phase=p.phase; message=p.message; attempt=p.attempt; challengeId=p.challengeId;
			pageUrl=p.challengeId==null ? null : HumanCheckRequest.pageUrl(p.challengeId);
			remainingMillis=Math.max(0, p.deadline-SystemClock.elapsedRealtime());
			canStart=p.phase==Phase.PROMPT && p.request==null && p.verifier!=null;
			canQuery=(p.phase==Phase.PROCESSING || p.phase==Phase.UNKNOWN || p.phase==Phase.CANCEL_UNKNOWN) && p.request==null && p.verifier!=null;
			busy=p.request!=null || p.phase==Phase.SENDING || p.phase==Phase.CREATING || p.phase==Phase.LOADING || p.phase==Phase.VERIFYING || p.phase==Phase.COMPLETING;
		}
	}
	private static final class Identity{
		final String id, accessToken;
		final AccountSession session;
		final Token token;
		final long sessionEpoch;
		Identity(){
			AccountSessionManager manager=AccountSessionManager.getInstance(); id=manager.getLastActiveAccountID(); session=manager.tryGetAccount(id);
			token=session==null ? null : session.token; accessToken=token==null ? null : token.accessToken;
			// Switching away and back deactivates/reactivates this existing session epoch.
			sessionEpoch=session==null ? -1 : session.getNBWForumOptionsRepository().getSnapshot().generation;
		}
		boolean current(){
			AccountSessionManager manager=AccountSessionManager.getInstance();
			return Objects.equals(id, manager.getLastActiveAccountID()) && session==manager.tryGetAccount(id)
					&& (session==null || token==session.token && Objects.equals(accessToken, token==null ? null : token.accessToken)
					&& sessionEpoch==session.getNBWForumOptionsRepository().getSnapshot().generation);
		}
	}
	private static final class Pending{
		final long generation, created;
		long entryOwner;
		final Identity identity=new Identity();
		final Action action;
		final String fingerprint, operation;
		long uiOwner, serial, attempt, deadline, attemptCreated;
		String email, verifier, challengeId;
		Listener entryListener, uiListener;
		HumanCheckRequest request;
		Phase phase=Phase.SENDING;
		int message=R.string.human_check_sending;
		boolean ready, suppressed, delivered, admitted, resultIssued, expired;
		Pending(long generation, long owner, String email, Action action, Listener listener){
			this.generation=generation; entryOwner=owner; this.email=email; this.action=action; entryListener=listener;
			created=SystemClock.elapsedRealtime(); deadline=created+OPERATION_TTL;
			operation=UUID.randomUUID().toString(); verifier=newVerifier(); fingerprint=fingerprint(email, action);
		}
	}

	/** Null means an earlier dispatch is unresolved. A new operation would be a duplicate send. */
	public synchronized Handle begin(String email, Action action, Listener listener){
		if(email==null || email.isEmpty() || email.length()>320) return null;
		if(pending!=null){
			checkLifetime(pending);
			if(pending!=null && unresolved(pending)){
				if(!pending.fingerprint.equals(fingerprint(email, action)) || pending.uiOwner!=0) return null;
				pending.entryOwner=++owner; pending.entryListener=listener;
				return new Handle(pending.generation, pending.entryOwner, false); // Status/cancel only; never repeat send.
			}
			if(pending!=null) retire(pending);
		}
		Pending p=new Pending(++generation, ++owner, email, action, listener); pending=p;
		p.admitted=true; // A dispatched /send-code may already have sent mail even if its reply is lost.
		Handle handle=new Handle(p.generation, p.entryOwner, false);
		send(p, HumanCheckRequest.sendCode(email, p.operation, s256(p.verifier)), 0);
		return handle;
	}
	public synchronized Snapshot snapshot(Handle handle){ return current(handle) ? new Snapshot(pending) : null; }
	public synchronized boolean isEntry(Handle handle, String email, Action action){ return current(handle) && !handle.ui && pending.fingerprint.equals(fingerprint(email, action)); }
	public synchronized Handle attach(long expectedGeneration, long expectedOwner, Listener listener){
		if(pending==null || pending.generation!=expectedGeneration || pending.entryOwner!=expectedOwner || !checkLifetime(pending)
				|| pending.expired || pending.phase!=Phase.PROMPT || pending.uiOwner!=0 || pending.suppressed) return null;
		pending.uiOwner=++owner; pending.uiListener=listener;
		return new Handle(pending.generation, pending.uiOwner, true);
	}
	public synchronized boolean start(Handle handle){
		if(!current(handle) || !handle.ui || pending.phase!=Phase.PROMPT || pending.request!=null) return false;
		Pending p=pending; p.attempt++; p.attemptCreated=SystemClock.elapsedRealtime(); p.ready=false; p.challengeId=null;
		p.phase=Phase.CREATING; p.message=R.string.human_check_loading;
		// Notify first: the centered modal and loading indicator exist before challenge I/O begins.
		notifyChanged(p);
		send(p, HumanCheckRequest.challenge(p.operation, p.verifier), p.attempt); return true;
	}
	public synchronized void webMessage(Handle handle, long attempt, String origin, boolean mainFrame, String json){
		if(!current(handle) || !handle.ui || pending.attempt!=attempt || !mainFrame || !HumanCheckRequest.ORIGIN.equals(origin)
				|| !(pending.phase==Phase.LOADING || pending.phase==Phase.READY || pending.phase==Phase.VERIFYING)) return;
		Pending p=pending;
		if(SystemClock.elapsedRealtime()-p.attemptCreated>=ATTEMPT_TTL){ cancelAttempt(handle, R.string.human_check_expired); return; }
		String event;
		try{ event=HumanCheckRequest.parsePageEvent(json, p.challengeId); }catch(Exception invalid){ return; }
		switch(event){
			case "ready" -> { if(p.phase==Phase.LOADING){ p.ready=true; p.phase=Phase.READY; p.message=R.string.human_check_interact; } }
			case "verifying" -> { if(p.ready){ p.phase=Phase.VERIFYING; p.message=R.string.human_check_verifying; } }
			case "verified" -> {
				if(!p.ready) return;
				p.phase=Phase.COMPLETING; p.message=R.string.human_check_continuing; p.admitted=true;
				notifyChanged(p);
				send(p, HumanCheckRequest.complete(p.operation, p.verifier, p.challengeId), attempt); return;
			}
			case "failed" -> { cancelAttempt(handle, R.string.human_check_failed); return; }
			case "expired" -> { cancelAttempt(handle, R.string.human_check_expired); return; }
			default -> { return; }
		}
		notifyChanged(p);
	}
	public synchronized void componentFailure(Handle handle, int localMessage){ cancelAttempt(handle, localMessage); }
	public synchronized void cancelAttempt(Handle handle, int localMessage){
		if(!current(handle) || !handle.ui) return;
		Pending p=pending;
		if(!(p.phase==Phase.CREATING || p.phase==Phase.LOADING || p.phase==Phase.READY || p.phase==Phase.VERIFYING)) return;
		p.attempt++; p.ready=false; p.message=localMessage; p.phase=Phase.PROMPT;
		if(p.challengeId!=null){
			String id=p.challengeId; p.challengeId=null;
			send(p, HumanCheckRequest.cancelChallenge(p.operation, p.verifier, id), p.attempt);
		}
		// An in-flight /challenge is not silently dropped: its eventual opaque id is cancelled below.
		notifyChanged(p);
	}
	public synchronized void query(Handle handle){
		if(!current(handle)) return;
		Pending p=pending; Snapshot state=new Snapshot(p);
		if(!state.canQuery) return;
		// Only an explicit user action may recover a suppressed/ambiguous dispatch.
		p.suppressed=false; p.phase=Phase.PROCESSING; p.message=R.string.human_check_processing;
		send(p, HumanCheckRequest.status(p.operation, p.verifier), p.attempt); notifyChanged(p);
	}
	public synchronized void cancel(Handle handle){
		if(!owns(handle)) return;
		cancelParent(pending);
	}
	private void cancelParent(Pending p){
		if(p.phase==Phase.SENT){ p.suppressed=true; p.uiOwner=0; p.uiListener=null; return; }
		if(p.suppressed && (p.phase==Phase.CANCELLING || p.phase==Phase.CANCELLED || p.phase==Phase.CANCEL_UNKNOWN)) return;
		p.suppressed=true; p.attempt++; p.uiOwner=0; p.uiListener=null; p.challengeId=null; p.ready=false;
		p.phase=Phase.CANCELLING; p.message=p.admitted ? R.string.human_check_cancel_pending : R.string.human_check_cancelled;
		if(p.request!=null) p.request.cancel(); p.request=null; ++p.serial;
		if(p.verifier==null){ p.phase=Phase.CANCEL_UNKNOWN; p.message=R.string.human_check_unknown; notifyChanged(p); return; }
		send(p, HumanCheckRequest.cancel(p.operation, p.verifier), p.attempt); notifyChanged(p);
	}
	/** Recreation never reattaches a verification, auto-loads a widget or repeats a send. */
	public synchronized void invalidateGeneration(long expectedGeneration){ if(pending!=null && pending.generation==expectedGeneration) cancelParent(pending); }
	public synchronized void detachEntry(Handle handle){
		if(!owns(handle) || handle.ui) return;
		pending.entryListener=null; if(!pending.delivered) cancelParent(pending);
	}
	public synchronized void detachUi(Handle handle){ if(owns(handle) && handle.ui && pending.phase!=Phase.SENT) cancelParent(pending); }
	public synchronized boolean issueSentResult(Handle handle){
		if(!current(handle) || !handle.ui || pending.phase!=Phase.SENT || pending.suppressed || pending.resultIssued) return false;
		pending.resultIssued=true; return true;
	}
	public synchronized boolean consumeSent(Handle handle, String email, Action action, long resultGeneration, long resultOwner){
		if(!isEntry(handle, email, action) || resultGeneration!=handle.generation || resultOwner!=handle.owner
				|| pending.phase!=Phase.SENT || pending.suppressed || pending.delivered) return false;
		pending.delivered=true; pending.entryListener=null; pending.uiListener=null;
		pending.verifier=pending.email=pending.challengeId=null; return true;
	}
	private void send(Pending p, HumanCheckRequest request, long attempt){
		if(p.request!=null) p.request.cancel();
		p.request=request; long serial=++p.serial;
		request.setCallback(new Callback<>(){
			@Override public void onSuccess(Reply reply){ synchronized(HumanCheckCoordinator.this){
				if(!accepts(p, request, serial)) return;
				p.request=null;
				try{ HumanCheckRequest.validateReply(reply, "processing".equals(reply.state)
						&& (request.endpoint==HumanCheckRequest.Endpoint.SEND_CODE || request.endpoint==HumanCheckRequest.Endpoint.COMPLETE) ? 202 : 200, request.endpoint, p.operation); }
				catch(Exception invalid){ failure(p, request, new Failure(0, "PROTOCOL")); return; }
				success(p, request, reply, attempt);
			} }
			@Override public void onError(ErrorResponse error){ synchronized(HumanCheckCoordinator.this){
				if(!accepts(p, request, serial)) return;
				p.request=null; failure(p, request, error instanceof Failure known ? known : new Failure(0, "NETWORK"));
			} }
		}).execNoAuth(HumanCheckRequest.DOMAIN);
	}
	private boolean accepts(Pending p, HumanCheckRequest request, long serial){
		return pending==p && checkLifetime(p) && !p.expired && p.request==request && p.serial==serial;
	}
	private void success(Pending p, HumanCheckRequest request, Reply reply, long attempt){
		switch(request.endpoint){
			case CHALLENGE -> {
				if(p.attempt!=attempt || p.phase!=Phase.CREATING){
					send(p, HumanCheckRequest.cancelChallenge(p.operation, p.verifier, reply.challengeId), p.attempt); notifyChanged(p); return;
				}
				p.challengeId=reply.challengeId; p.phase=Phase.LOADING; p.message=R.string.human_check_loading;
			}
			case SEND_CODE, COMPLETE, STATUS -> {
				if("sent".equals(reply.state)){
					p.phase=Phase.SENT; p.message=R.string.human_check_sent; p.email=null;
				}else if("processing".equals(reply.state) || "unknown".equals(reply.state)){
					p.admitted=true; p.phase="processing".equals(reply.state) ? Phase.PROCESSING : Phase.UNKNOWN;
					p.message="processing".equals(reply.state) ? R.string.human_check_processing : R.string.human_check_unknown;
				}else if("challenge_required".equals(reply.state)){
					// A status query is never permission to create a widget or dispatch again.
					p.phase=Phase.FAILED; p.message=R.string.human_check_restart;
				}else{ p.phase="cancelled".equals(reply.state) ? Phase.CANCELLED : Phase.FAILED; p.message=R.string.human_check_restart; }
			}
			case CANCEL -> { p.phase=Phase.CANCELLED; p.message=R.string.human_check_cancelled; p.verifier=null; p.email=null; }
			case CHALLENGE_CANCEL -> { p.challengeId=null; p.phase=Phase.PROMPT; }
		}
		notifyChanged(p);
	}
	private void failure(Pending p, HumanCheckRequest request, Failure error){
		if(request.endpoint==HumanCheckRequest.Endpoint.CANCEL){ p.phase=Phase.CANCEL_UNKNOWN; p.message=R.string.human_check_cancel_pending; }
		else if(request.endpoint==HumanCheckRequest.Endpoint.CHALLENGE_CANCEL){ p.phase=Phase.PROMPT; p.message=error.message; }
		else if((request.endpoint==HumanCheckRequest.Endpoint.SEND_CODE || request.endpoint==HumanCheckRequest.Endpoint.COMPLETE || request.endpoint==HumanCheckRequest.Endpoint.STATUS)
				&& (error.status>=500 || "HUMAN_CHECK_DELIVERY_UNKNOWN".equals(error.code))){
			p.admitted=true; p.phase=Phase.UNKNOWN; p.message=R.string.human_check_unknown;
		}
		else if(request.endpoint==HumanCheckRequest.Endpoint.SEND_CODE && error.status==403 && "HUMAN_CHECK_REQUIRED".equals(error.code)
				&& p.operation.equals(error.operationId) && error.expiresIn==300){
			p.admitted=false; p.phase=Phase.PROMPT; p.message=R.string.human_check_description;
			p.deadline=Math.min(p.deadline, SystemClock.elapsedRealtime()+ATTEMPT_TTL);
		}else if("HUMAN_CHECK_POLICY_CHANGED".equals(error.code) || "HUMAN_CHECK_EXPIRED".equals(error.code)
				|| "HUMAN_CHECK_CANCELLED".equals(error.code) || "HUMAN_CHECK_INVALID_PROOF".equals(error.code)
				|| error.status==429 || error.status==503 || error.status==400 || error.status==413 || error.status==403){
			p.phase=Phase.FAILED; p.message=error.message;
		}else if(request.endpoint==HumanCheckRequest.Endpoint.SEND_CODE || request.endpoint==HumanCheckRequest.Endpoint.COMPLETE || request.endpoint==HumanCheckRequest.Endpoint.STATUS){
			p.admitted=true; p.phase=Phase.UNKNOWN; p.message=R.string.human_check_unknown;
		}else{ p.phase=Phase.PROMPT; p.message=error.message; }
		notifyChanged(p);
	}
	private boolean owns(Handle handle){ return handle!=null && pending!=null && handle.generation==pending.generation && handle.owner==(handle.ui ? pending.uiOwner : pending.entryOwner); }
	private boolean current(Handle handle){ return owns(handle) && checkLifetime(pending); }
	private boolean checkLifetime(Pending p){
		if(!p.identity.current()){
			if(pending==p){ retire(p); pending=null; } return false;
		}
		// TTL fences live proofs, not a server-confirmed terminal outcome. In particular, a
		// consumed SENT must not become UNKNOWN and permanently block a later resend.
		if(p.phase==Phase.SENT || p.phase==Phase.CANCELLED || p.phase==Phase.FAILED) return true;
		long now=SystemClock.elapsedRealtime();
		if(!p.expired && (now<p.created || now>=p.deadline)){
			p.expired=true;
			if(p.request!=null) p.request.cancel(); p.request=null; ++p.serial; p.attempt++;
			p.verifier=p.email=p.challengeId=null;
			p.phase=p.admitted ? Phase.UNKNOWN : Phase.FAILED; p.message=p.admitted ? R.string.human_check_unknown : R.string.human_check_expired;
		}
		return true;
	}
	private static boolean unresolved(Pending p){ return p.admitted && !(p.phase==Phase.SENT || p.phase==Phase.CANCELLED || p.phase==Phase.FAILED) || p.phase==Phase.CANCELLING || p.phase==Phase.CANCEL_UNKNOWN; }
	private void retire(Pending p){
		if(p.request!=null) p.request.cancel(); ++p.serial;
		// Best-effort terminal cancellation; no callback can navigate, create widgets or send mail.
		if(p.verifier!=null && p.phase!=Phase.SENT && p.phase!=Phase.CANCELLED){ HumanCheckRequest.cancel(p.operation, p.verifier).execNoAuth(HumanCheckRequest.DOMAIN); }
		p.verifier=p.email=p.challengeId=null; p.entryListener=p.uiListener=null; p.uiOwner=0;
	}
	private static void notifyChanged(Pending p){
		Listener ui=p.uiListener, entry=p.entryListener;
		if(ui!=null) ui.onChanged();
		if(entry!=null) entry.onChanged();
	}
	@Subscribe public synchronized void onAccountLoggedOut(AccountLoggedOutEvent event){ if(pending!=null){ retire(pending); pending=null; } }
	private static String newVerifier(){ byte[] bytes=new byte[32]; RANDOM.nextBytes(bytes); return Base64.encodeToString(bytes, Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING); }
	public static String s256(String value){
		try{ return Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)), Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING); }
		catch(Exception unavailable){ throw new IllegalStateException("SHA-256 unavailable"); }
	}
	private static String fingerprint(String email, Action action){ return s256(action.name()+"\nregister\n"+email); }
}
