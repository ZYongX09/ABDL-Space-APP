package org.joinmastodon.android;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.requests.accounts.GetOwnAccount;
import org.joinmastodon.android.api.requests.nbw.NBWLoginRequest;
import org.joinmastodon.android.api.session.AccountActivationInfo;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.auth.NBWLoginFlow;
import org.joinmastodon.android.auth.NBWLoginFlowTest;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Application;
import org.joinmastodon.android.model.Instance;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.security.AppSecurity;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowSystemClock;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import me.grishka.appkit.api.APIRequest;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.utils.V;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class, shadows={NBWLoginActivityTest.LocalRequests.class,NBWLoginActivityTest.LocalSessions.class,NBWLoginActivityTest.UnlockedGate.class})
public class NBWLoginActivityTest{
	@Implements(MastodonAPIRequest.class) public static class LocalRequests{
		@RealObject MastodonAPIRequest<?> request;
		static final List<MastodonAPIRequest<?>> sent=new ArrayList<>();
		static final List<String> domains=new ArrayList<>();
		@Implementation public MastodonAPIRequest<?> execNoAuth(String domain){ sent.add(request); domains.add(domain); return request; }
		@Implementation public MastodonAPIRequest<?> exec(String domain,Token token){ sent.add(request); domains.add(domain); return request; }
	}
	@Implements(AccountSessionManager.class) public static class LocalSessions{
		static String active;
		static AccountSession session;
		static int additions;
		@Implementation public String getLastActiveAccountID(){ return active; }
		@Implementation public AccountSession tryGetAccount(String id){ return session!=null && session.getID().equals(id) ? session : null; }
		@Implementation public void addAccount(Instance instance,Token token,Account account,Application app,AccountActivationInfo activation){
			try{
				var ctor=AccountSession.class.getDeclaredConstructor(); ctor.setAccessible(true); session=ctor.newInstance();
				session.domain=instance.getDomain(); session.self=account; session.token=token; active=session.getID(); additions++;
			}catch(Exception e){ throw new AssertionError(e); }
		}
	}
	@Implements(AppSecurity.class) public static class UnlockedGate{
		@Implementation public static void runAfterUnlock(Activity activity,Runnable continuation){ continuation.run(); }
	}
	private final List<ActivityController<?>> activities=new ArrayList<>();
	private NBWLoginFlow flow;
	@Before public void setup(){
		MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context);
		GlobalUserPreferences.getPrefs().edit().clear().putBoolean("perAccountMigrationDone",true).commit(); GlobalUserPreferences.load();
		LocalSessions.active=null; LocalSessions.session=null; LocalSessions.additions=0; LocalRequests.sent.clear(); LocalRequests.domains.clear();
		flow=NBWLoginFlow.getInstance(); flow.cancel(flow.begin());
	}
	@After public void cleanup(){
		for(var activity:activities) activity.close(); flow.cancel(flow.begin());
	}
	private ActivityController<NBWLoginActivity> open(){
		var lifecycle=Robolectric.buildActivity(NBWLoginActivity.class,new Intent(MastodonApp.context,NBWLoginActivity.class).putExtra(NBWLoginActivity.EXTRA_PERMISSION_CONFIRMED,true)).setup();
		activities.add(lifecycle); return lifecycle;
	}
	private Intent route(Uri uri){
		var lifecycle=Robolectric.buildActivity(OAuthActivity.class,new Intent(Intent.ACTION_VIEW,uri)).create(); activities.add(lifecycle);
		Intent routed=org.robolectric.shadow.api.Shadow.<ShadowActivity>extract(lifecycle.get()).getNextStartedActivity();
		assertTrue(lifecycle.get().isFinishing()); return routed;
	}
	@SuppressWarnings("unchecked") private static Callback<Object> callback(MastodonAPIRequest<?> request) throws Exception{
		Field field=APIRequest.class.getDeclaredField("callback"); field.setAccessible(true); return (Callback<Object>)field.get(request);
	}
	private void authorize(NBWLoginActivity page) throws Exception{
		callback(LocalRequests.sent.get(LocalRequests.sent.size()-1)).onSuccess(NBWLoginFlowTest.startResult());
		Intent browser=org.robolectric.shadow.api.Shadow.<ShadowActivity>extract(page).getNextStartedActivity();
		assertNotNull(browser); assertEquals(Intent.ACTION_VIEW,browser.getAction()); assertEquals("www.newbabyworld.top",browser.getData().getHost());
		assertEquals(View.GONE,page.findViewById(R.id.nbw_login_retry).getVisibility());
	}
	private void complete(ActivityController<NBWLoginActivity> page){
		Intent routed=route(NBWLoginFlowTest.completion()); assertNotNull(routed);
		assertEquals(NBWLoginActivity.class.getName(),routed.getComponent().getClassName());
		assertNull(routed.getData()); assertEquals(java.util.Set.of("generation"),routed.getExtras().keySet());
		page.newIntent(routed);
	}
	@Test public void callbackIsStrictAndNoPendingOrMarkerOnlyCanStartNetwork(){
		assertTrue(NBWLoginFlow.isCompletionCallback(NBWLoginFlowTest.completion()));
		String code="A".repeat(43);
		for(String uri:new String[]{
				"abdl-space://callback?nbw_v2_login=complete",
				"abdl-space://callback/?nbw_v2_login=complete&code="+code,
				"https://callback?nbw_v2_login=complete&code="+code,
				"abdl-space://callback.evil?nbw_v2_login=complete&code="+code,
				"abdl-space://user@callback?nbw_v2_login=complete&code="+code,
				"abdl-space://callback:443?nbw_v2_login=complete&code="+code,
				"abdl-space://callback?nbw_v2_login=complete&code="+code+"#fragment",
				"abdl-space://callback?nbw_v2_login=complete&code="+code+"&token=jwt",
				"abdl-space://callback?nbw_v2_login=complete&nbw_v2_login=complete&code="+code,
				"abdl-space://callback?nbw_v2_login=complete&code="+code+"&code="+code,
				"abdl-space://callback?nbw_v2_login=complete&code="+code+"&flow="+code,
				"abdl-space://callback?nbw_v2_login=complete&code="+"A".repeat(42)+"B"}){
			assertFalse(uri,NBWLoginFlow.isCompletionCallback(Uri.parse(uri))); assertNull(route(Uri.parse(uri)));
		}
		assertNull(route(NBWLoginFlowTest.completion())); assertTrue(LocalRequests.sent.isEmpty());
	}
	@Test public void realStartCallbackExchangeInstallerSequenceAndDuplicateHintAreSingleFlight() throws Exception{
		var lifecycle=open(); NBWLoginActivity page=lifecycle.get(); assertEquals(1,LocalRequests.sent.size()); authorize(page);
		assertNull(route(Uri.parse("abdl-space://callback?nbw_v2_login=complete"))); assertEquals(1,LocalRequests.sent.size());
		lifecycle.pause().resume(); assertEquals(1,LocalRequests.sent.size()); complete(lifecycle);
		assertEquals(2,LocalRequests.sent.size()); complete(lifecycle); assertEquals(2,LocalRequests.sent.size());
		callback(LocalRequests.sent.get(1)).onSuccess(NBWLoginFlowTest.exchangeResult());
		assertEquals(3,LocalRequests.sent.size()); assertTrue(LocalRequests.sent.get(2) instanceof GetOwnAccount);
		assertTrue(LocalRequests.sent.get(2).isSensitiveRequest()); assertTrue(LocalRequests.sent.get(2).requiresSystemTrust());
		Account account=new Account(); account.id="42"; callback(LocalRequests.sent.get(2)).onSuccess(account);
		assertEquals(1,LocalSessions.additions); assertEquals("abdl-space.top_42",LocalSessions.active);
		Intent next=org.robolectric.shadow.api.Shadow.<ShadowActivity>extract(page).getNextStartedActivity();
		assertNotNull(next); assertEquals(MainActivity.class.getName(),next.getComponent().getClassName()); assertTrue(page.isFinishing());
	}
	@Test public void realInstallerRechecksExpiryCancelNewGenerationAndAccountSwitchBeforeAdd() throws Exception{
		for(int invalidation=0;invalidation<4;invalidation++){
			LocalSessions.active=null; LocalSessions.session=null; LocalRequests.sent.clear();
			var lifecycle=open(); NBWLoginActivity page=lifecycle.get(); authorize(page); complete(lifecycle);
			callback(LocalRequests.sent.get(1)).onSuccess(NBWLoginFlowTest.exchangeResult()); var own=LocalRequests.sent.get(2);
			if(invalidation==0) ShadowSystemClock.advanceBy(Duration.ofMillis(NBWLoginFlow.COMPLETION_TTL_MS));
			else if(invalidation==1) page.findViewById(R.id.nbw_login_cancel).performClick();
			else if(invalidation==2) open();
			else LocalSessions.active="switched-account";
			Account account=new Account(); account.id="42"; callback(own).onSuccess(account);
			assertEquals("stale GetOwnAccount must never install",0,LocalSessions.additions);
			lifecycle.close(); activities.remove(lifecycle);
		}
	}
	@Test public void pendingRetriesOnlyOnRealButtonAndUnboundOrReauthNeverInstall() throws Exception{
		for(String terminal:new String[]{"NBW_LOGIN_NOT_BOUND","NBW_LOGIN_REAUTH_REQUIRED","NBW_LOGIN_INVALID_FLOW"}){
			LocalRequests.sent.clear(); var lifecycle=open(); var page=lifecycle.get(); authorize(page); complete(lifecycle);
			callback(LocalRequests.sent.get(1)).onError(new NBWLoginRequest.LoginError(409,"NBW_LOGIN_PENDING"));
			assertEquals(2,LocalRequests.sent.size()); complete(lifecycle); assertEquals(2,LocalRequests.sent.size());
			page.findViewById(R.id.nbw_login_retry).performClick(); assertEquals(3,LocalRequests.sent.size());
			callback(LocalRequests.sent.get(2)).onError(new NBWLoginRequest.LoginError(403,terminal));
			assertEquals(View.GONE,page.findViewById(R.id.nbw_login_retry).getVisibility());
			assertFalse(((TextView)page.findViewById(R.id.nbw_login_status)).getText().toString().contains(terminal));
			assertEquals(0,LocalSessions.additions); lifecycle.close(); activities.remove(lifecycle);
		}
	}
	@Test public void activityRecreationSavesOnlyCountersAndNeverRepeatsAuthorization() throws Exception{
		var first=open(); authorize(first.get()); Bundle saved=new Bundle(); first.saveInstanceState(saved).pause().stop().destroy(); activities.remove(first);
		String state=saved.toString(); assertFalse(state.contains("code_verifier")); assertFalse(state.contains("completion_code")); assertFalse(state.contains("flow"));
		var next=Robolectric.buildActivity(NBWLoginActivity.class,new Intent(MastodonApp.context,NBWLoginActivity.class)).create(saved).start().resume(); activities.add(next);
		assertEquals(1,LocalRequests.sent.size()); assertNull(org.robolectric.shadow.api.Shadow.<ShadowActivity>extract(next.get()).getNextStartedActivity());
		assertEquals(View.GONE,next.get().findViewById(R.id.nbw_login_retry).getVisibility()); complete(next); assertEquals(2,LocalRequests.sent.size());
	}
	@Test public void processLossAndUnconfirmedLaunchFailClosed(){
		var first=Robolectric.buildActivity(NBWLoginActivity.class,new Intent(MastodonApp.context,NBWLoginActivity.class)).setup(); activities.add(first);
		assertTrue(first.get().isFinishing()); assertTrue(LocalRequests.sent.isEmpty());
		Bundle saved=new Bundle(); saved.putLong("generation",42);
		var restored=Robolectric.buildActivity(NBWLoginActivity.class,new Intent(MastodonApp.context,NBWLoginActivity.class)).create(saved); activities.add(restored);
		assertTrue(((TextView)restored.get().findViewById(R.id.nbw_login_status)).getText().toString().contains("重新开始")); assertTrue(LocalRequests.sent.isEmpty());
	}
}
