package org.joinmastodon.android.api.session;

import android.net.Uri;

import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.api.requests.GetContentAccess;
import org.joinmastodon.android.model.ContentAccess;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;

/** One session, one serial policy probe. No IP logic, durable allow, certificate shortcut or POST replay. */
public final class ContentAccessCoordinator{
	public static final String HEADER="X-ABDL-Content-Access";
	public interface Cancelable{ void cancel(); }
	public interface Result{ void success(ContentAccess access); void failure(); }
	public interface Transport{ Cancelable load(String accountId, Result result); }
	public static final class Snapshot{
		/** null means unknown. Both unknown and unavailable withhold all protected content. */
		public final ContentAccess.State state;
		public final long generation;
		public final boolean loading;
		Snapshot(ContentAccess.State state, long generation, boolean loading){ this.state=state; this.generation=generation; this.loading=loading; }
		public boolean isOpen(){ return state==ContentAccess.State.OPEN && !loading; }
		/** Only a confirmed requirement withholds content. Unknown/unavailable/loading are not decisions;
		 * the server remains authoritative for every protected read. */
		public boolean withholdsContent(){ return state==ContentAccess.State.BABY_VERIFICATION_REQUIRED; }
	}
	/** Captured at dispatch, never reconstructed from whichever account is active at response time. */
	public static final class Owner{
		private final ContentAccessCoordinator coordinator;
		private final AccountSession session;
		private final Token token;
		private final String value;
		private final long generation;
		private Owner(ContentAccessCoordinator owner){ coordinator=owner; session=owner.session; token=session.token; value=token==null ? null : token.accessToken; generation=owner.generation; }
		public boolean isCurrent(){ return coordinator.current(this); }
		public boolean sameIdentity(){ return session==coordinator.session && token==session.token && Objects.equals(value, token==null ? null : token.accessToken) && coordinator.identityCurrent(); }
		public boolean canRead(){ return isCurrent() && coordinator.getSnapshot().isOpen(); }
		/** Media dispatch and already-fetched media views only stop on a confirmed requirement; the
		 * server still authorizes every byte, so an in-flight probe must not blank avatars or photos. */
		public boolean canFetchMedia(){ return sameIdentity() && canDispatch(); }
		/** Only a confirmed "verification required" blocks dispatch. Unknown/unavailable states let the
		 * server answer authoritatively (it withholds content itself), so a probe outage no longer turns
		 * every startup request into a synthetic 503 toast. Local cache reads stay strict via canRead(). */
		/** Only a confirmed requirement blocks dispatch. Identity/generation changes (session refresh,
		 * account switch during splash) must not fabricate 503s; the server enforces content itself. */
		public boolean canDispatch(){ return coordinator.getSnapshot().state!=ContentAccess.State.BABY_VERIFICATION_REQUIRED; }
		/** Stable across process restarts for the same account credential; policy refreshes must not
		 * rotate the image cache namespace. */
		public String mediaScope(){ return coordinator.accountId+"-"+Integer.toHexString(java.util.Objects.hashCode(value)); }
		/** An open header is never authority to clear a gate. Only the latest GET can do that. */
		public void receiveHeader(String header){ coordinator.dispatch.accept(()->coordinator.applyHeader(this, header)); }
	}

	private final AccountSession session;
	private final String mediaScope=java.util.UUID.randomUUID().toString();
	private final String accountId;
	private final Transport transport;
	private final Consumer<Runnable> dispatch;
	private final Set<Consumer<Snapshot>> observers=new HashSet<>();
	private Token token;
	private String tokenValue;
	private volatile long generation=1;
	private volatile ContentAccess.State state;
	private volatile boolean loading;
	private Cancelable pending;
	private boolean requiredPromptClaimed;

	public static boolean isFirstParty(Uri uri){
		return uri!=null && "https".equals(uri.getScheme()) && uri.getUserInfo()==null && (uri.getPort()==-1 || uri.getPort()==443)
				&& ("abdl-space.top".equalsIgnoreCase(uri.getHost()) || "api.abdl-space.top".equalsIgnoreCase(uri.getHost()));
	}
	public static boolean applies(AccountSession session){
		return session!=null && ("abdl-space.top".equalsIgnoreCase(session.domain) || "api.abdl-space.top".equalsIgnoreCase(session.domain));
	}
	public static boolean canRead(String accountId){
		AccountSession session=AccountSessionManager.getInstance().tryGetAccount(accountId);
		return session!=null && (!applies(session) || session.getContentAccessCoordinator().isOpen());
	}
	/** Request dispatch and already-authorized UI only stop on a confirmed requirement. Unknown and
	 * unavailable are not verdicts; the server answers each protected request authoritatively. */
	public static boolean allowsContent(String accountId){
		AccountSession session=AccountSessionManager.getInstance().tryGetAccount(accountId);
		return session!=null && (!applies(session) || !session.getContentAccessCoordinator().getSnapshot().withholdsContent());
	}
	/** First-party API is content by default; only account/utility operations bypass the content gate. */
	public static boolean isProtectedRoute(Uri uri){ return isProtectedRoute(uri,"GET"); }
	public static boolean isProtectedRoute(Uri uri,String method){
		if(!isFirstParty(uri)) return false;
		String path=uri.getPath();
		if(path==null || !path.startsWith("/api/")) return false;
		if(path.equals("/api/auth/nbw/recommend-fid") || path.equals("/api/v1/accounts/update_credentials") || path.equals("/api/users/me") && !"GET".equals(method)) return true;
		return !(path.equals("/api/access") || path.startsWith("/api/auth/") || path.startsWith("/api/app/nbw/login/")
				|| path.equals("/api/nbw/endpoints") || path.equals("/api/app/nbw/authorization") || path.startsWith("/api/app/nbw/authorization/")
				|| path.startsWith("/api/v1/baby-verification/") || path.startsWith("/api/webauthn/") || path.equals("/api/webauthn")
				|| path.equals("/api/v1/accounts/verify_credentials") || path.equals("/api/users/me")
				|| path.equals("/api/v1/preferences") || path.equals("/api/v1/instance") || path.equals("/api/v2/instance")
				|| path.equals("/api/v1/apps") || path.startsWith("/api/v1/push/")
				|| path.equals("/api/v1/sponsors/catalog") || path.equals("/api/v1/sponsors/payment-options")
				|| path.equals("/api/v1/sponsors/payment-orders") || path.startsWith("/api/v1/sponsors/payment-orders/")
				|| path.equals("/api/v1/sponsors/me") || path.startsWith("/api/v1/sponsors/me/")
				|| path.equals("/api/v1/sponsors/redemptions") || path.equals("/api/v1/sponsors/redeem")
				|| path.equals("/api/v1/sponsors/color") || path.equals("/api/v1/sponsors/claims") || path.startsWith("/api/v1/sponsors/claims/")
				|| path.startsWith("/api/help/") || path.startsWith("/api/broadcast/") || path.startsWith("/api/app/human-check/"));
	}
	public static ErrorResponse blockedError(){ return new MastodonErrorResponse(org.joinmastodon.android.MastodonApp.context.getString(org.joinmastodon.android.R.string.content_access_unavailable), 503, null); }

	public ContentAccessCoordinator(AccountSession session){
		this(session, (id, result)->{
			GetContentAccess request=new GetContentAccess();
			request.setCallback(new Callback<>(){
				@Override public void onSuccess(ContentAccess access){ result.success(access); }
				@Override public void onError(ErrorResponse error){ result.failure(); }
			}).exec(id);
			return request::cancel;
		}, UiUtils::runOnUiThread);
	}
	public ContentAccessCoordinator(AccountSession session, Transport transport, Consumer<Runnable> dispatch){
		this.session=session; accountId=session.getID(); this.transport=transport; this.dispatch=dispatch;
		token=session.token; tokenValue=token==null ? null : token.accessToken;
	}
	private boolean identityCurrent(){
		return AccountSessionManager.getInstance().tryGetAccount(accountId)==session
				&& accountId.equals(AccountSessionManager.getInstance().getLastActiveAccountID())
				&& session.token==token && token!=null && tokenValue!=null && !tokenValue.isBlank() && Objects.equals(tokenValue, token.accessToken);
	}
	private boolean current(Owner owner){
		return owner!=null && owner.coordinator==this && owner.session==session && owner.generation==generation
				&& owner.token==token && Objects.equals(owner.value, tokenValue) && identityCurrent();
	}
	public Snapshot getSnapshot(){ return new Snapshot(identityCurrent() ? state : null, generation, identityCurrent() && loading); }
	public boolean isOpen(){ return getSnapshot().isOpen(); }
	public Owner capture(){ return identityCurrent() ? new Owner(this) : null; }
	public Cancelable observe(Consumer<Snapshot> observer){ observers.add(observer); observer.accept(getSnapshot()); return ()->observers.remove(observer); }
	private void emit(){ Snapshot snapshot=getSnapshot(); for(Consumer<Snapshot> observer:new ArrayList<>(observers)) observer.accept(snapshot); }
	private void cancelPending(){ Cancelable request=pending; pending=null; if(request!=null) request.cancel(); }

	/** Called on initial entry, foreground, account/credential changes, network switches and explicit refresh. */
	public void refresh(){
		cancelPending();
		token=session.token; tokenValue=token==null ? null : token.accessToken;
		generation++;
		// A known requirement may remain visible during revalidation; never retain an allow.
		if(state!=ContentAccess.State.BABY_VERIFICATION_REQUIRED) state=null;
		loading=true;
		// Revalidation is not revocation: dropping local timelines here wiped the home/notifications
		// cache on every resume and forced a full network reload (and avatar rebind) each time.
		emit();
		if(!identityCurrent()){ loading=false; emit(); return; }
		Owner owner=new Owner(this);
		Cancelable request=transport.load(accountId, new Result(){
			@Override public void success(ContentAccess access){ dispatch.accept(()->complete(owner, access)); }
			@Override public void failure(){ dispatch.accept(()->complete(owner, null)); }
		});
		if(current(owner) && loading) pending=request; else request.cancel();
	}
	private void complete(Owner owner, ContentAccess access){
		if(!current(owner) || !loading) return;
		pending=null; loading=false;
		try{ if(access!=null) access.postprocess(); }catch(Exception invalid){ access=null; }
		ContentAccess.State previous=state;
		state=access==null ? (state==ContentAccess.State.BABY_VERIFICATION_REQUIRED ? state : ContentAccess.State.UNAVAILABLE) : access.state;
		if(state==ContentAccess.State.OPEN) requiredPromptClaimed=false;
		// Only a confirmed requirement revokes locally cached content.
		if(state==ContentAccess.State.BABY_VERIFICATION_REQUIRED && previous!=ContentAccess.State.BABY_VERIFICATION_REQUIRED)
			session.getCacheController().invalidateContentAccess();
		emit();
	}
	private void applyHeader(Owner owner, String header){
		if(!current(owner)) return;
		ContentAccess.State next=switch(header==null ? "" : header){
			case "v1:baby_verification_required" -> ContentAccess.State.BABY_VERIFICATION_REQUIRED;
			case "v1:unavailable" -> ContentAccess.State.UNAVAILABLE;
			default -> null;
		};
		if(next==null) return;
		cancelPending(); generation++; loading=false;
		ContentAccess.State previous=state;
		if(state!=ContentAccess.State.BABY_VERIFICATION_REQUIRED || next==ContentAccess.State.BABY_VERIFICATION_REQUIRED) state=next;
		// An unavailable probe is not a revocation; the server still answers each request.
		if(next==ContentAccess.State.BABY_VERIFICATION_REQUIRED && previous!=ContentAccess.State.BABY_VERIFICATION_REQUIRED)
			session.getCacheController().invalidateContentAccess();
		emit();
	}
	/** Invalidate an old selected-account epoch, including an A -> B -> A switch. */
	public void deactivate(){
		cancelPending(); generation++; loading=false;
		if(state!=ContentAccess.State.BABY_VERIFICATION_REQUIRED) state=null;
		session.getCacheController().invalidateContentAccess(); emit();
	}
	/** One forced generic prompt per required episode, not per retry or Activity recreation. */
	public boolean claimRequiredPrompt(){
		if(getSnapshot().state!=ContentAccess.State.BABY_VERIFICATION_REQUIRED || requiredPromptClaimed) return false;
		requiredPromptClaimed=true; return true;
	}
}
