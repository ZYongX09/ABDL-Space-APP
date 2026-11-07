package org.joinmastodon.android.fragments.sponsors;

import android.app.AlertDialog;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.MastodonToolbarFragment;
import org.joinmastodon.android.model.sponsors.SponsorModels.Me;
import org.joinmastodon.android.model.sponsors.SponsorPaymentModels.Order;
import org.joinmastodon.android.sponsors.SponsorPaymentCoordinator;
import org.joinmastodon.android.sponsors.SponsorPaymentResultFragment;
import org.joinmastodon.android.sponsors.SponsorPaymentUi;
import org.joinmastodon.android.sponsors.PaymentReceipt;
import org.joinmastodon.android.sponsors.SponsorRefresh;

import me.grishka.appkit.Nav;

/** Channel selection is deliberately between the unchanged catalogue and the original Afdian guide. */
public class SponsorPaymentMethodFragment extends MastodonToolbarFragment{
	public static final String RESULT_OPEN_CENTER="sponsorPaymentOpenCenter";
	private static final int AFDIAN_RESULT=4701;
	private String accountID, planID, sessionToken;
	private AccountSession session;
	private SponsorPaymentUi ui;
	private SponsorPaymentCoordinator coordinator;
	private AlertDialog closeDialog;
	private boolean resumed, shown=true, leaving;
	private final Handler handler=new Handler(Looper.getMainLooper());

	@Override public void onCreate(Bundle state){
		super.onCreate(state);
		accountID=getArguments().getString("account"); planID=getArguments().getString("plan");
		session=AccountSessionManager.getInstance().tryGetAccount(accountID);
		sessionToken=session==null || session.token==null ? null : session.token.accessToken;
		setTitle(R.string.sponsor_payment_title);
	}
	@Override public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle state){
		ui=new SponsorPaymentUi(getActivity(), new SponsorPaymentUi.Actions(){
			@Override public void select(String channel){ if(coordinator!=null) coordinator.select(channel); }
			@Override public void pay(){ if(coordinator!=null) coordinator.pay(); }
			@Override public void retry(){ if(coordinator!=null) coordinator.retry(); }
			@Override public void switchChannel(){ confirmClose(); }
		});
		coordinator=new SponsorPaymentCoordinator(accountID, planID, new SponsorPaymentCoordinator.HttpSource(accountID),
				SponsorPaymentCoordinator.launcher(getActivity()), new SponsorPaymentCoordinator.PreferencesStore(getActivity()),
				SponsorPaymentCoordinator.scheduler(handler), this::sessionValid, new SponsorPaymentCoordinator.Listener(){
			@Override public void changed(){ render(); }
			@Override public void granted(Me state){
				if(!sessionValid() || ui==null) return;
				SponsorRefresh.apply(accountID, state);
				navigateToResult();
			}
			@Override public void openAfdian(){ navigateAfdian(); }
		});
		render(); return ui.root;
	}
	private boolean sessionValid(){
		AccountSessionManager manager=AccountSessionManager.getInstance();
		return session!=null && manager.tryGetAccount(accountID)==session && accountID.equals(manager.getLastActiveAccountID())
				&& sessionToken!=null && !sessionToken.isBlank() && session.token!=null && sessionToken.equals(session.token.accessToken);
	}
	private void render(){ if(ui!=null && coordinator!=null) ui.render(coordinator); }
	private void resumeCoordinator(){ if(resumed && shown && !leaving && coordinator!=null) coordinator.resume(); }
	@Override public void onResume(){ super.onResume(); resumed=true; resumeCoordinator(); }
	@Override public void onPause(){ resumed=false; dismissClose(); if(coordinator!=null) coordinator.pause(); super.onPause(); }
	@Override protected void onShown(){ super.onShown(); shown=true; leaving=false; resumeCoordinator(); }
	@Override protected void onHidden(){ shown=false; dismissClose(); if(coordinator!=null) coordinator.pause(); super.onHidden(); }
	@Override public void onHiddenChanged(boolean hidden){
		super.onHiddenChanged(hidden); shown=!hidden;
		if(hidden){ dismissClose(); if(coordinator!=null) coordinator.pause(); } else resumeCoordinator();
	}
	@Override public void onConfigurationChanged(Configuration configuration){
		dismissClose(); super.onConfigurationChanged(configuration); render();
	}
	@Override public void onApplyWindowInsets(WindowInsets insets){
		if(ui!=null) ui.insets(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
		super.onApplyWindowInsets(insets.replaceSystemWindowInsets(0, insets.getSystemWindowInsetTop(), 0, 0));
	}
	private void confirmClose(){
		if(coordinator==null || !coordinator.canSwitch() || !resumed || !shown || closeDialog!=null) return;
		closeDialog=new AlertDialog.Builder(getActivity())
				.setTitle(R.string.sponsor_payment_close_switch)
				.setMessage(R.string.sponsor_payment_close_explanation)
				.setNegativeButton(R.string.cancel, null)
				.setPositiveButton(R.string.sponsor_payment_close_confirm, (dialog, which)->{
					if(coordinator!=null && sessionValid() && resumed && shown) coordinator.switchChannel();
				}).create();
		closeDialog.setOnDismissListener(ignored->closeDialog=null); closeDialog.show();
	}
	private void dismissClose(){ if(closeDialog!=null){ AlertDialog old=closeDialog; closeDialog=null; old.dismiss(); } }
	private void navigateToResult(){
		if(coordinator==null || coordinator.order()==null) return;
		Order o=coordinator.order();
		if(!"GRANTED".equals(o.fulfillmentState)) return;
		var receipt=new PaymentReceipt(o.orderId, o.amountMinor, o.currency, o.planName, o.channel, o.tradeNo, o.paidAt!=null ? o.paidAt : 0);
		handler.post(()->{
			android.os.Bundle args=new android.os.Bundle();
			args.putSerializable("receipt", receipt);
			Nav.finish(this);
			Nav.go(getActivity(), SponsorPaymentResultFragment.class, args);
		});
	}
	private void navigateAfdian(){
		if(!resumed || !shown || !sessionValid() || leaving) return;
		leaving=true; coordinator.pause();
		Bundle args=new Bundle(); args.putString("account", accountID); args.putString("plan", planID);
		args.putBoolean("returnToCenter", getArguments().getBoolean("returnToCenter", false));
		args.putBoolean("paymentMethodParent", true);
		Nav.goForResult(getActivity(), SponsorPurchaseFragment.class, args, AFDIAN_RESULT, this);
	}
	@Override public void onFragmentResult(int requestCode, boolean success, Bundle result){
		super.onFragmentResult(requestCode, success, result);
		leaving=false;
		if(requestCode==AFDIAN_RESULT && success && result!=null && result.getBoolean(RESULT_OPEN_CENTER, false)){
			if(getArguments().getBoolean("returnToCenter", false)){
				// First pop the guide, then this page; do not create a duplicate sponsor center.
				handler.post(()->{ if(getActivity()!=null && sessionValid()) Nav.finish(this); });
			}else{
				handler.post(()->{
					if(getActivity()==null || !sessionValid()) return;
					Bundle args=new Bundle(); args.putString("account", accountID); Nav.go(getActivity(), SponsorCenterFragment.class, args);
				});
			}
		}
	}
	@Override public void onDestroyView(){
		dismissClose(); handler.removeCallbacksAndMessages(null);
		if(coordinator!=null) coordinator.destroy(); coordinator=null; ui=null;
		super.onDestroyView();
	}
}
