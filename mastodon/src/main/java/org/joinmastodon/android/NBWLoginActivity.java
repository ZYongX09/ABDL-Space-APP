package org.joinmastodon.android;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.requests.nbw.NBWLoginRequest;
import org.joinmastodon.android.auth.AuthSessionInstaller;
import org.joinmastodon.android.auth.NBWLoginFlow;
import org.joinmastodon.android.security.AppSecurity;
import org.joinmastodon.android.ui.utils.UiUtils;

import androidx.annotation.Nullable;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;

/** Consent-gated native login coordinator. The external browser alone handles NBW credentials. */
public class NBWLoginActivity extends Activity{
	public static final String EXTRA_PERMISSION_CONFIRMED="permissionConfirmed";
	private static final String ACTION_COMPLETE="org.joinmastodon.android.NBW_LOGIN_COMPLETE";
	private static final String EXTRA_GENERATION="generation";
	private final NBWLoginFlow flow=NBWLoginFlow.getInstance();
	private final Handler handler=new Handler(Looper.getMainLooper());
	private NBWLoginFlow.Handle handle;
	private TextView status;
	private Button retry;
	private ProgressBar progress;
	private boolean terminal;
	private final Runnable expiry=this::render;

	/** Called only by OAuthActivity after strict URI validation and a live in-process pending check. */
	public static Intent completionIntent(Activity activity, long generation){
		return new Intent(activity, NBWLoginActivity.class).setAction(ACTION_COMPLETE)
				.putExtra(EXTRA_GENERATION, generation)
				.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
	}
	@Override protected void onCreate(@Nullable Bundle state){
		UiUtils.setUserPreferredTheme(this);
		super.onCreate(state);
		getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
		setContentView(R.layout.activity_nbw_login);
		status=findViewById(R.id.nbw_login_status); retry=findViewById(R.id.nbw_login_retry); progress=findViewById(R.id.nbw_login_progress);
		findViewById(R.id.nbw_login_cancel).setOnClickListener(v->cancel());
		retry.setOnClickListener(v->AppSecurity.runAfterUnlock(this, this::retry));
		AppSecurity.runAfterUnlock(this, ()->enter(state));
	}
	private void enter(@Nullable Bundle state){
		if(isFinishing() || isDestroyed()) return;
		Intent intent=getIntent();
		if(intent.getData()!=null){ fail(R.string.nbw_login_expired); return; }
		if(state!=null){
			handle=flow.attach(state.getLong(EXTRA_GENERATION, 0));
			if(handle==null){ fail(R.string.nbw_login_expired); return; }
			render(); // Never repeat a browser launch or exchange merely because the Activity was rebuilt.
		}else if(ACTION_COMPLETE.equals(intent.getAction())){
			handle=flow.attach(intent.getLongExtra(EXTRA_GENERATION, 0));
			if(handle==null){ fail(R.string.nbw_login_expired); return; }
			exchange(false);
		}else if(intent.getBooleanExtra(EXTRA_PERMISSION_CONFIRMED, false)){
			handle=flow.begin(); start();
		}else{
			finish();
		}
	}
	@Override protected void onNewIntent(Intent intent){
		super.onNewIntent(intent);
		setIntent(intent);
		AppSecurity.runAfterUnlock(this, ()->{
			if(isFinishing() || isDestroyed()) return;
			if(intent.getData()!=null){ return; }
			if(ACTION_COMPLETE.equals(intent.getAction())){
				long generation=intent.getLongExtra(EXTRA_GENERATION, 0);
				if(handle==null || handle.generation!=generation) return;
				exchange(false);
			}else if(intent.getBooleanExtra(EXTRA_PERMISSION_CONFIRMED, false)){
				terminal=false; handle=flow.begin(); start();
			}
		});
	}
	private boolean live(){ return !terminal && !isFinishing() && !isDestroyed() && flow.isCurrent(handle); }
	private void start(){
		if(!live()){ fail(R.string.nbw_login_expired); return; }
		NBWLoginRequest<NBWLoginRequest.Start> request=flow.start(handle);
		if(request==null){ render(); return; }
		NBWLoginFlow.Handle owner=handle;
		request.setCallback(new Callback<>(){
			@Override public void onSuccess(NBWLoginRequest.Start result){
				if(!sameOwner(owner)) return;
				Uri authorization=flow.acceptStart(owner, request, result);
				if(authorization==null){ fail(R.string.nbw_login_failed); return; }
				render();
				try{
					// No WebView, cookies, password form, real-device identifier or legacy mobile callback.
					Intent browser=new Intent(Intent.ACTION_VIEW, authorization);
					browser.addCategory(Intent.CATEGORY_BROWSABLE);
					startActivity(browser);
				}catch(ActivityNotFoundException unavailable){ flow.cancel(owner); fail(R.string.nbw_login_browser_unavailable); }
			}
			@Override public void onError(ErrorResponse error){ requestError(owner, request, error); }
		}).execNoAuth(NBWLoginRequest.DOMAIN);
		render();
	}
	private boolean sameOwner(NBWLoginFlow.Handle owner){
		return handle==owner && !terminal && !isFinishing() && !isDestroyed() && flow.isCurrent(owner);
	}
	private void exchange(boolean manual){
		if(!live()){ fail(R.string.nbw_login_expired); return; }
		NBWLoginRequest<NBWLoginRequest.Exchange> request=flow.exchange(handle, manual);
		if(request==null){ render(); return; }
		NBWLoginFlow.Handle owner=handle;
		request.setCallback(new Callback<>(){
			@Override public void onSuccess(NBWLoginRequest.Exchange result){
				if(!sameOwner(owner)) return;
				if(!flow.acceptExchange(owner, request, result)){ fail(R.string.nbw_login_failed); return; }
				render();
				MastodonAPIRequest<?> installation=AuthSessionInstaller.install(NBWLoginActivity.this, result.token, true, new AuthSessionInstaller.Listener(){
					@Override public void onInstalled(String accountID){
						// Installing itself changes account identity. Do not recheck that pre-install snapshot here.
						flow.complete(owner); terminal=true; handler.removeCallbacks(expiry); finish();
					}
					@Override public void onError(ErrorResponse error){
						if(!sameOwner(owner)) return;
						flow.cancel(owner); fail(R.string.nbw_login_failed);
					}
				}, ()->sameOwner(owner));
				flow.installation(owner, installation);
			}
			@Override public void onError(ErrorResponse error){ requestError(owner, request, error); }
		}).execNoAuth(NBWLoginRequest.DOMAIN);
		render();
	}
	private void requestError(NBWLoginFlow.Handle owner, MastodonAPIRequest<?> request, ErrorResponse error){
		if(!sameOwner(owner)) return;
		int message=flow.failure(owner, request, error);
		if(flow.isCurrent(owner)) render(); else fail(message);
	}
	private void retry(){
		if(!live()){ fail(R.string.nbw_login_expired); return; }
		NBWLoginFlow.Snapshot snapshot=flow.snapshot(handle);
		if(snapshot!=null && snapshot.phase==NBWLoginFlow.Phase.NEW) start(); else exchange(true);
	}
	private void render(){
		handler.removeCallbacks(expiry);
		if(terminal) return;
		NBWLoginFlow.Snapshot snapshot=flow.snapshot(handle);
		if(snapshot==null){ fail(R.string.nbw_login_expired); return; }
		status.setText(snapshot.message);
		boolean busy=snapshot.phase==NBWLoginFlow.Phase.STARTING || snapshot.phase==NBWLoginFlow.Phase.EXCHANGING || snapshot.phase==NBWLoginFlow.Phase.INSTALLING;
		progress.setVisibility(busy ? View.VISIBLE : View.GONE);
		retry.setVisibility(snapshot.canRetry() ? View.VISIBLE : View.GONE);
		retry.setEnabled(snapshot.canRetry());
		handler.postDelayed(expiry, Math.max(1, snapshot.remainingMillis));
	}
	private void fail(int message){
		terminal=true; handler.removeCallbacks(expiry);
		status.setText(message); progress.setVisibility(View.GONE); retry.setVisibility(View.GONE);
	}
	private void cancel(){ flow.cancel(handle); terminal=true; handler.removeCallbacks(expiry); finish(); }
	@Override public void onBackPressed(){ cancel(); }
	@Override protected void onResume(){
		super.onResume();
		if(handle!=null && !terminal) render(); // Browser return without a completion hint never exchanges automatically.
	}
	@Override protected void onSaveInstanceState(Bundle state){
		if(handle!=null) state.putLong(EXTRA_GENERATION, handle.generation);
		super.onSaveInstanceState(state);
	}
	@Override protected void onDestroy(){
		handler.removeCallbacks(expiry);
		flow.detach(handle, isChangingConfigurations() || !isFinishing());
		super.onDestroy();
	}
}
