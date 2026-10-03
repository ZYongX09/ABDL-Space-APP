package org.joinmastodon.android.sponsors;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ScrollView;
import android.view.View;
import android.view.ViewGroup;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.sponsors.SponsorPurchaseFragment;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.sponsors.SponsorModels;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import androidx.test.core.app.ApplicationProvider;
import me.grishka.appkit.utils.V;

import static org.junit.Assert.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26, 35}, application=CompatibilityTestApplication.class)
public class SponsorPurchaseQuizFlowTest{
	@Test public void correctAnswerLaunchesWhileModalOwnsFocusAndClosesQuiz() throws Exception{
		withFragment((activity, fragment, scroll)->{
			invoke(fragment, "showCheckoutQuiz");
			AlertDialog dialog=(AlertDialog)field("checkoutQuiz").get(fragment);
			assertNotNull(dialog); scroll.focus=false;
			choice(dialog.getWindow().getDecorView(), "私信按钮").performClick();
			Intent launched=((ShadowActivity)Shadow.extract(activity)).getNextStartedActivity();
			assertNotNull(launched); assertEquals(Intent.ACTION_VIEW, launched.getAction());
			assertEquals(((SponsorModels.Plan)field("plan").get(fragment)).purchaseUrl, launched.getDataString());
			assertFalse(dialog.isShowing()); assertNull(field("checkoutQuiz").get(fragment));
			assertNull(((ShadowActivity)Shadow.extract(activity)).getNextStartedActivity());
		});
	}

	@Test public void leavingPageDismissesQuizAndRejectsLateCorrectClick() throws Exception{
		withFragment((activity, fragment, scroll)->{
			invoke(fragment, "showCheckoutQuiz");
			AlertDialog dialog=(AlertDialog)field("checkoutQuiz").get(fragment);
			Button correct=choice(dialog.getWindow().getDecorView(), "私信按钮");
			fragment.onPause(); correct.performClick();
			assertFalse(dialog.isShowing()); assertNull(field("checkoutQuiz").get(fragment));
			assertNull(((ShadowActivity)Shadow.extract(activity)).getNextStartedActivity());
		});
	}

	@Test public void changedVerificationCannotUseOldCorrectAnswer() throws Exception{
		withFragment((activity, fragment, scroll)->{
			invoke(fragment, "showCheckoutQuiz");
			AlertDialog dialog=(AlertDialog)field("checkoutQuiz").get(fragment);
			field("fingerprint").set(fragment, "updated-config");
			choice(dialog.getWindow().getDecorView(), "私信按钮").performClick();
			assertNull(((ShadowActivity)Shadow.extract(activity)).getNextStartedActivity()); dialog.dismiss();
		});
	}

	@SuppressWarnings("unchecked") private void withFragment(Check check) throws Exception{
		V.setApplicationContext(ApplicationProvider.getApplicationContext());
		org.joinmastodon.android.MastodonApp.context=ApplicationProvider.getApplicationContext();
		try(var controller=Robolectric.buildActivity(Activity.class).create()){
			Activity activity=controller.get(); activity.setTheme(R.style.Theme_Mastodon_Light);
			var constructor=AccountSession.class.getDeclaredConstructor(); constructor.setAccessible(true);
			AccountSession session=constructor.newInstance(); session.domain="offline.example.test";
			session.self=new Account(); session.self.id="42";
			Field sessions=AccountSessionManager.class.getDeclaredField("sessions"); sessions.setAccessible(true);
			((Map<String, AccountSession>)sessions.get(AccountSessionManager.getInstance())).put(session.getID(), session);
			SponsorPurchaseFragment fragment=new SponsorPurchaseFragment();
			Bundle args=new Bundle(); args.putString("account", session.getID()); args.putString("plan", "monthly"); fragment.setArguments(args);
			activity.getFragmentManager().beginTransaction().add(fragment, "purchase").commit(); activity.getFragmentManager().executePendingTransactions();
			FocusScroll scroll=new FocusScroll(activity);
			field("scroll").set(fragment, scroll); field("resumed").setBoolean(fragment, true);
			field("loaded").setBoolean(fragment, true); field("fingerprint").set(fragment, "verified-config");
			SponsorModels.Plan plan=new SponsorModels.Plan(); plan.enabled=true; plan.afdianPlanId="server-plan"; plan.afdianSkuId="server-sku";
			plan.purchaseUrl="https://ifdian.net/order/create?product_type=1&plan_id=server-plan&sku="+URLEncoder.encode("[{\"sku_id\":\"server-sku\",\"count\":1}]", StandardCharsets.UTF_8)+"&viokrz_ex=0";
			field("plan").set(fragment, plan);
			SponsorModels.Catalog catalog=new SponsorModels.Catalog(); catalog.config=new SponsorModels.Config(); catalog.config.minimumReadSeconds=5;
			field("catalog").set(fragment, catalog);
			((ForegroundReadTimer)field("timer").get(fragment)).restore("verified-config", 5000, 5000);
			try{ check.run(activity, fragment, scroll); }
			finally{ fragment.onPause(); }
		}
	}
	private static Field field(String name) throws Exception{
		Field field=SponsorPurchaseFragment.class.getDeclaredField(name); field.setAccessible(true); return field;
	}
	private static void invoke(SponsorPurchaseFragment fragment, String name) throws Exception{
		var method=SponsorPurchaseFragment.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(fragment);
	}
	private static Button choice(View view, String text){
		if(view instanceof Button button && text.contentEquals(button.getText())) return button;
		if(view instanceof ViewGroup group) for(int i=0; i<group.getChildCount(); i++){
			Button found=choice(group.getChildAt(i), text); if(found!=null) return found;
		}
		return null;
	}
	private interface Check{ void run(Activity activity, SponsorPurchaseFragment fragment, FocusScroll scroll) throws Exception; }
	private static class FocusScroll extends ScrollView{
		boolean focus=true;
		FocusScroll(Activity activity){ super(activity); }
		@Override public boolean isShown(){ return true; }
		@Override public boolean hasWindowFocus(){ return focus; }
	}
}
