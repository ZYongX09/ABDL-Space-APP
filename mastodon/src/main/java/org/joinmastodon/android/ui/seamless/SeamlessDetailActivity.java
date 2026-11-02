package org.joinmastodon.android.ui.seamless;

import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.lifecycle.ViewModelStore;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.lifecycle.ViewTreeViewModelStoreOwner;
import androidx.savedstate.SavedStateRegistry;
import androidx.savedstate.SavedStateRegistryController;
import androidx.savedstate.SavedStateRegistryOwner;

import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.ThreadFragment;
import org.joinmastodon.android.fragments.albums.AlbumDetailFragment;
import org.joinmastodon.android.security.AppSecurity;
import org.joinmastodon.android.ui.compose.ComposeLifecycleHelperKt;
import org.joinmastodon.android.ui.utils.AppLanguageManager;
import org.joinmastodon.android.ui.utils.UiUtils;

import me.grishka.appkit.FragmentStackActivity;

/** Two existing native detail roots; no Home startup, exported dispatch or arbitrary class names. */
public final class SeamlessDetailActivity extends FragmentStackActivity implements LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner{
	static final String POST="post", ALBUM="album";
	private static final String ROUTE="detail_route", ARGUMENTS="detail_arguments", ACCOUNT="detail_account", ENTITY="detail_entity", AUTH="detail_auth_digest";
	private final LifecycleRegistry lifecycle=new LifecycleRegistry(this);
	private final SavedStateRegistryController savedState=SavedStateRegistryController.create(this);
	private final ViewModelStore models=new ViewModelStore();
	private String account, entity, route;
	private AccountSession session;
	private byte[] authentication;
	private Bundle pendingRestore;
	private boolean restoreViewsAllowed, restoreRecreationRequested, restoreUnlockQueued;

	@Override public Lifecycle getLifecycle(){ return lifecycle; }
	@Override public SavedStateRegistry getSavedStateRegistry(){ return savedState.getSavedStateRegistry(); }
	@Override public ViewModelStore getViewModelStore(){ return models; }
	@Override protected void attachBaseContext(Context base){ super.attachBaseContext(AppLanguageManager.wrap(base)); }

	static Intent intent(Context context, String route, Bundle args){
		if(!POST.equals(route) && !ALBUM.equals(route)) throw new IllegalArgumentException("Unknown detail route");
		return new Intent(context, SeamlessDetailActivity.class).putExtra(ROUTE, route).putExtra(ARGUMENTS, args);
	}

	@Override protected void onCreate(@Nullable Bundle state){
		Bundle args=null;
		try{
			route=state==null ? getIntent().getStringExtra(ROUTE) : state.getString(ROUTE);
			args=state==null ? getIntent().getBundleExtra(ARGUMENTS) : null;
			account=state==null ? args==null ? null : args.getString("account") : state.getString(ACCOUNT);
			entity=state==null ? args==null ? null : ALBUM.equals(route) ? args.getString("albumId") : statusId(args) : state.getString(ENTITY);
		}catch(RuntimeException invalid){}
		// The original bounded Parcel belongs only to the root Fragment after this point.
		getIntent().removeExtra(ARGUMENTS);
		getIntent().removeExtra(ROUTE);
		session=account==null ? null : AccountSessionManager.getInstance().tryGetAccount(account);
		authentication=authenticationDigest(account, session);
		// AppKit/platform FragmentManager have no public deferred whole-stack restore API.
		// Reject old credentials before super; defer an otherwise valid stack while the lock is unknown.
		byte[] previous=state==null ? null : state.getByteArray(AUTH);
		boolean restoreAllowed=state==null || (previous!=null && previous.length==32 && authentication!=null
				&& MessageDigest.isEqual(previous, authentication) && validAccount());
		if(state!=null && restoreAllowed && AppSecurity.isLocked()) pendingRestore=state;
		Bundle acceptedState=restoreAllowed && pendingRestore==null ? state : null;
		restoreViewsAllowed=acceptedState!=null;
		savedState.performAttach();
		savedState.performRestore(acceptedState);
		UiUtils.setUserPreferredTheme(this, session);
		super.onCreate(acceptedState);
		lifecycle.setCurrentState(Lifecycle.State.CREATED);
		ComposeLifecycleHelperKt.installComposeLifecycle(this);
		ViewTreeViewModelStoreOwner.set(getWindow().getDecorView(), this);
		if(!restoreAllowed || !validRoute() || !validAccount() || authentication==null || entity==null
				|| (state==null && !SeamlessTransitions.argumentsFit(args))){ finish(); return; }
		if(pendingRestore!=null){
			requestRestoreAfterUnlock();
		}else if(state==null){
			SeamlessTransitions.attach(this, account, entity);
			Bundle rootArgs=args;
			AppSecurity.runAfterUnlock(this, ()->{
				if(!validAccount() || isFinishing() || isDestroyed()) return;
				Fragment root=POST.equals(route) ? new ThreadFragment() : new AlbumDetailFragment();
				root.setArguments(rootArgs);
				showFragment(root);
			});
		}
	}
	private void requestRestoreAfterUnlock(){
		if(pendingRestore==null || restoreUnlockQueued || restoreRecreationRequested || isFinishing() || isDestroyed()) return;
		restoreUnlockQueued=true;
		AppSecurity.runAfterUnlock(this, ()->{ restoreUnlockQueued=false; recreateAfterUnlock(); });
	}
	private void recreateAfterUnlock(){
		if(pendingRestore==null || restoreRecreationRequested || isFinishing() || isDestroyed()) return;
		if(!validAccount()){ pendingRestore=null; finish(); return; }
		if(AppSecurity.isLocked() || !lifecycle.getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) return;
		// The next Activity gets the original flat state through onSaveInstanceState, never through an Intent.
		restoreRecreationRequested=true;
		recreate();
	}
	@Override protected void onRestoreInstanceState(Bundle state){
		// Android also restores window/view state after onStart, independently of super.onCreate's argument.
		if(restoreViewsAllowed) super.onRestoreInstanceState(state);
	}
	private boolean validRoute(){ return POST.equals(route) || ALBUM.equals(route); }
	private boolean validAccount(){
		byte[] current=authenticationDigest(account, session);
		return session!=null && SeamlessTransitions.currentAccount(account) && session==AccountSessionManager.getInstance().tryGetAccount(account)
				&& authentication!=null && current!=null && MessageDigest.isEqual(authentication, current);
	}
	private static byte[] authenticationDigest(String account, AccountSession session){
		if(account==null || session==null || session.token==null || session.token.accessToken==null || session.token.accessToken.isBlank()) return null;
		try{
			MessageDigest digest=MessageDigest.getInstance("SHA-256");
			digest.update(account.getBytes(StandardCharsets.UTF_8)); digest.update((byte)0);
			return digest.digest(session.token.accessToken.getBytes(StandardCharsets.UTF_8));
		}catch(NoSuchAlgorithmException unavailable){ return null; }
	}
	private static String statusId(Bundle args){
		org.joinmastodon.android.model.Status status=org.parceler.Parcels.unwrap(args.getParcelable("status"));
		return status==null ? null : status.id;
	}
	@Override protected void onStart(){ super.onStart(); lifecycle.setCurrentState(Lifecycle.State.STARTED); }
	@Override protected void onResume(){
		super.onResume(); lifecycle.setCurrentState(Lifecycle.State.RESUMED);
		if(!validAccount()){ pendingRestore=null; finish(); }
		else requestRestoreAfterUnlock();
	}
	@Override protected void onPause(){ lifecycle.setCurrentState(Lifecycle.State.STARTED); super.onPause(); }
	@Override protected void onStop(){ lifecycle.setCurrentState(Lifecycle.State.CREATED); super.onStop(); }
	@Override protected void onSaveInstanceState(Bundle state){
		state.putString(ROUTE, route); state.putString(ACCOUNT, account); state.putString(ENTITY, entity);
		if(authentication!=null) state.putByteArray(AUTH, authentication.clone());
		savedState.performSave(state);
		super.onSaveInstanceState(state); // AppKit saves the existing root once; no extra Parcel/pixel/token copy.
		if(pendingRestore!=null){
			if(validAccount()){
				// Replace the empty waiting host's state, not nest/copy the root Parcel or expose it to the Intent.
				state.clear(); state.putAll(pendingRestore);
			}else{ pendingRestore=null; state.clear(); finish(); }
		}
	}
	@Override protected void onDestroy(){
		pendingRestore=null;
		SeamlessTransitions.protectedContent(this);
		lifecycle.setCurrentState(Lifecycle.State.DESTROYED); models.clear(); super.onDestroy();
	}
	@Override public void finish(){
		// Internal Nav.finish/pop never reaches this until the root/Activity actually closes.
		SeamlessTransitions.beforeFinish(this);
		super.finish();
	}
}
