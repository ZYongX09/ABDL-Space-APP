package org.joinmastodon.android.fragments.sponsors;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.MastodonToolbarFragment;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.sponsors.AlipaySdkAdapterShadow;
import org.joinmastodon.android.sponsors.SponsorPaymentFixtures;
import org.joinmastodon.android.sponsors.SponsorPaymentUi;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.RealObject;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import me.grishka.appkit.FragmentStackActivity;
import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.utils.V;

import static org.junit.Assert.*;

/** Real AppKit stack/results/back; only HTTP and SDK effects are offline test seams. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=31, application=CompatibilityTestApplication.class, shadows={SponsorPaymentNavigationTest.Api.class, AlipaySdkAdapterShadow.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class SponsorPaymentNavigationTest{
	private ActivityController<FragmentStackActivity> host;
	private AccountSessionManager manager;
	private Map<String, AccountSession> sessions;
	private String accountID, oldActive;
	private AccountSession previous;
	private SponsorPaymentMethodFragment method;
	public static class CenterFixture extends MastodonToolbarFragment{
		@Override public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle state){ return new FrameLayout(getActivity()); }
	}
	@Before @SuppressWarnings("unchecked") public void setup() throws Exception{
		Context context=RuntimeEnvironment.getApplication(); MastodonApp.context=context; V.setApplicationContext(context); Api.requests.clear();
		manager=AccountSessionManager.getInstance(); sessions=ReflectionHelpers.getField(manager, "sessions"); oldActive=manager.getLastActiveAccountID();
		Constructor<AccountSession> constructor=AccountSession.class.getDeclaredConstructor(); constructor.setAccessible(true);
		AccountSession session=constructor.newInstance(); session.domain="sponsor-navigation-offline.example.test";
		session.self=new Account(); session.self.id="payment-navigation-fixture"; session.token=new Token(); session.token.accessToken="offline-fixture-token";
		accountID=session.getID(); previous=sessions.put(accountID, session); ReflectionHelpers.setField(manager, "lastActiveAccountID", accountID);
		context.getSharedPreferences("sponsor_payment_recovery", Context.MODE_PRIVATE).edit().clear().commit();
		AlipaySdkAdapterShadow.configure(false, "UNKNOWN");
		host=Robolectric.buildActivity(FragmentStackActivity.class); host.get().setTheme(R.style.Theme_Mastodon_Light); host.setup();
		host.get().showFragment(new CenterFixture()); settle();
		Bundle args=new Bundle(); args.putString("account", accountID); args.putString("plan", SponsorPaymentFixtures.plan().id); args.putBoolean("returnToCenter", true);
		Nav.go(host.get(), SponsorPaymentMethodFragment.class, args); settle();
		method=find(SponsorPaymentMethodFragment.class); assertNotNull(method); loadMethod();
	}
	@After public void cleanup(){
		if(host!=null) host.pause().stop().destroy();
		if(sessions!=null){ if(previous==null) sessions.remove(accountID); else sessions.put(accountID, previous); ReflectionHelpers.setField(manager, "lastActiveAccountID", oldActive); }
		Api.requests.clear();
	}
	private void loadMethod(){
		reply(last(), SponsorPaymentFixtures.catalog()); reply(last(), SponsorPaymentFixtures.me(false));
		reply(last(), SponsorPaymentFixtures.options("limits_unverified")); settle();
	}
	private void openGuide(){
		SponsorPaymentUi ui=ReflectionHelpers.getField(method, "ui"); assertTrue(ui.pay.isEnabled()); ui.pay.performClick(); settle();
		assertNotNull(find(SponsorPurchaseFragment.class));
	}
	@Test public void ordinaryGuideBackReturnsToMethodNotCenterAndDoesNotCreatePayment(){
		openGuide(); host.get().onBackPressed(); settle();
		assertNull(find(SponsorPurchaseFragment.class)); assertNotNull(find(SponsorPaymentMethodFragment.class));
		assertEquals(0, Api.requests.stream().filter(r->"POST".equals(r.getMethod())).count());
	}
	@Test public void inPlaceTokenReplacementRejectsOldPageWithoutPaymentOrEntitlementCallback(){
		int requests=Api.requests.size();
		AccountSession session=sessions.get(accountID); session.token.accessToken="different-fixture-token";
		SponsorPaymentUi ui=ReflectionHelpers.getField(method, "ui"); ui.pay.performClick();
		assertEquals(requests, Api.requests.size()); assertFalse(ui.pay.isEnabled());
		assertEquals(host.get().getString(R.string.sponsor_payment_session_changed), ui.status.getText().toString());
		assertNull(session.self.sponsor);
	}
	@Test public void directRedeemUsesActualSynchronousResultThenPopsBothPagesToOriginalCenter() throws Exception{
		openGuide(); SponsorPurchaseFragment purchase=find(SponsorPurchaseFragment.class);
		Method open=SponsorPurchaseFragment.class.getDeclaredMethod("openCenter"); open.setAccessible(true); open.invoke(purchase); settle();
		assertNull(find(SponsorPurchaseFragment.class)); assertNull(find(SponsorPaymentMethodFragment.class)); assertNotNull(find(CenterFixture.class));
		assertEquals(1, host.get().getFragmentManager().getFragments().stream().filter(f->f instanceof CenterFixture).count());
		assertEquals(0, Api.requests.stream().filter(r->"POST".equals(r.getMethod())).count());
	}
	private MastodonAPIRequest<?> last(){ return Api.requests.get(Api.requests.size()-1); }
	@SuppressWarnings("unchecked") private void reply(MastodonAPIRequest<?> request, Object value){ ((Callback<Object>)ReflectionHelpers.getField(request, "callback")).onSuccess(value); }
	private void settle(){
		host.get().getFragmentManager().executePendingTransactions(); ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS); host.get().getFragmentManager().executePendingTransactions();
	}
	private <T> T find(Class<T> type){
		return host.get().getFragmentManager().getFragments().stream().filter(type::isInstance).filter(f->f.isAdded()).map(type::cast).findFirst().orElse(null);
	}
	@Implements(MastodonAPIRequest.class) public static class Api{
		static final List<MastodonAPIRequest<?>> requests=new ArrayList<>(); @RealObject MastodonAPIRequest<?> request;
		@Implementation public MastodonAPIRequest<?> exec(String account){ requests.add(request); return request; }
	}
}
