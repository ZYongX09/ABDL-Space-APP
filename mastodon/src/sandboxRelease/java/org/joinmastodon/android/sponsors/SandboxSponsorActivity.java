package org.joinmastodon.android.sponsors;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import org.joinmastodon.android.R;
import org.joinmastodon.android.model.sponsors.SponsorModels.Me;

/** Dedicated launcher: no production Activity/Fragment/session, Intent imports or automatic pairing. */
public final class SandboxSponsorActivity extends Activity implements SandboxSponsorUi.Actions{
	private final Handler handler=new Handler(Looper.getMainLooper());
	private SandboxSession session;
	private SandboxSponsorSource source;
	private SandboxSponsorUi ui;
	private SponsorPaymentCoordinator coordinator;
	private SponsorPaymentCoordinator.Cancelable pairCall;
	private AlertDialog closeDialog;
	private boolean resumed, pairing;
	private long pairGeneration;
	private final Runnable expiry=this::expired;

	@Override protected void onCreate(Bundle ignored){
		super.onCreate(null); // Never restore an input code/signed data through saved instance state.
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
		WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
		SandboxApplication app=(SandboxApplication)getApplication(); session=app.session(); source=app.source();
		ui=new SandboxSponsorUi(this, this); installSafeInsets(ui.root); setContentView(ui.root);
		ViewCompat.requestApplyInsets(ui.root);
		// Deliberately do not inspect Intent extras/data/ClipData, even on a launcher restart.
		if(session.lease()==null) entry(session.hasRecovery() ? R.string.sandbox_recovery_required : R.string.sandbox_unpaired);
		else checkout();
	}
	/** The root survives entry/checkout child replacement. Apply absolute, never additive padding;
	 * consuming here prevents double application to the shared payment content/footer. */
	static void installSafeInsets(View root){
		ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets)->{
			Insets safe=insets.getInsets(WindowInsetsCompat.Type.systemBars()
					| WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
			view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
			return WindowInsetsCompat.CONSUMED;
		});
	}
	private void entry(int message){
		destroyCoordinator();
		ui.entry(session.origin, getString(message));
	}
	@Override public void pair(String code){
		if(pairing || !resumed) return;
		pairing=true; ui.pairing(getString(R.string.sandbox_pairing), true);
		long generation=++pairGeneration;
		pairCall=source.pair(code, new SponsorPaymentCoordinator.Result<>(){
			@Override public void success(String owner){
				if(generation!=pairGeneration || isDestroyed()) return;
				pairing=false; pairCall=null; checkout();
			}
			@Override public void failure(SponsorPaymentCoordinator.Failure failure){
				if(generation!=pairGeneration || isDestroyed()) return;
				pairing=false; pairCall=null;
				ui.pairing(getString("pending_owner_changed".equals(failure.code) || "storage_failed".equals(failure.code)
						? R.string.sandbox_owner_changed : R.string.sandbox_pair_failed), false);
			}
		});
	}
	@Override public void showPair(){ if(resumed && !pairing) entry(session.hasRecovery() ? R.string.sandbox_recovery_required : R.string.sandbox_unpaired); }
	private void checkout(){
		destroyCoordinator();
		SandboxSession.Lease lease=session.lease();
		if(lease==null){ entry(R.string.sandbox_expired); return; }
		ui.checkout(session.origin);
		coordinator=new SponsorPaymentCoordinator(lease.owner, SandboxProtocol.PLAN, source, new SandboxAlipayLauncher(this),
				new SponsorPaymentCoordinator.PreferencesStore(this), SponsorPaymentCoordinator.scheduler(handler),
				()->session.valid(lease), new SponsorPaymentCoordinator.Listener(){
			@Override public void changed(){ render(); }
			@Override public void granted(Me freshMe){ if(session.valid(lease) && ui!=null) ui.granted(); }
			@Override public void openAfdian(){ /* Sandbox never opens a browser or a production purchase guide. */ }
		});
		render();
		if(resumed) coordinator.resume();
		watchExpiry();
	}
	private void render(){
		if(ui==null || coordinator==null) return;
		ui.render(coordinator);
		if(session.lease()==null) handler.post(expiry);
	}
	private void watchExpiry(){
		handler.removeCallbacks(expiry);
		SandboxSession.Lease lease=session.lease();
		if(resumed && lease!=null) handler.postDelayed(expiry, Math.max(1, (lease.expiresAt-session.now())*1000));
	}
	private void expired(){ if(ui!=null && session.lease()==null) entry(R.string.sandbox_expired); else watchExpiry(); }
	@Override public void select(String channel){ if("alipay".equals(channel) && coordinator!=null) coordinator.select(channel); }
	@Override public void pay(){ if(coordinator!=null && resumed) coordinator.pay(); }
	@Override public void retry(){ if(coordinator!=null && resumed) coordinator.retry(); }
	@Override public void switchChannel(){
		if(coordinator==null || !coordinator.canSwitch() || !resumed || closeDialog!=null) return;
		closeDialog=new AlertDialog.Builder(this).setTitle(R.string.sandbox_close_title).setMessage(R.string.sandbox_close_message)
				.setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.sandbox_close_confirm, (dialog, which)->{
					if(coordinator!=null && resumed) coordinator.switchChannel();
				}).create();
		closeDialog.setOnDismissListener(ignored->closeDialog=null); closeDialog.show();
	}
	private void dismissClose(){ if(closeDialog!=null){ AlertDialog old=closeDialog; closeDialog=null; old.dismiss(); } }
	private void destroyCoordinator(){
		dismissClose(); handler.removeCallbacks(expiry);
		if(coordinator!=null) coordinator.destroy(); coordinator=null;
	}
	@Override protected void onResume(){
		super.onResume(); resumed=true;
		if(coordinator!=null){ if(session.lease()==null) expired(); else coordinator.resume(); }
		watchExpiry();
	}
	@Override protected void onPause(){
		resumed=false; dismissClose(); handler.removeCallbacks(expiry);
		if(coordinator!=null) coordinator.pause();
		super.onPause();
	}
	@Override protected void onSaveInstanceState(Bundle state){ /* No tokens, codes, signed data or view state. */ }
	@Override protected void onDestroy(){
		pairGeneration++;
		if(pairCall!=null) pairCall.cancel(); pairCall=null;
		destroyCoordinator(); handler.removeCallbacksAndMessages(null); ui=null;
		super.onDestroy();
	}
}
