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
import org.robolectric.shadows.ShadowLooper;
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
@Config(sdk=28, application=CompatibilityTestApplication.class, shadows={NBWLoginActivityTest.LocalRequests.class,NBWLoginActivityTest.LocalSessions.class,NBWLoginActivityTest.UnlockedGate.class,NBWCallbackTaskFixtures.TaskActivity.class,NBWCallbackTaskFixtures.TaskDispatch.class})
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
	private org.joinmastodon.android.api.NBWEndpointFixtures endpoints;
	@Before public void setup() throws Exception{
		endpoints=new org.joinmastodon.android.api.NBWEndpointFixtures();
		MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context);
		GlobalUserPreferences.getPrefs().edit().clear().putBoolean("perAccountMigrationDone",true).commit(); GlobalUserPreferences.load();
		LocalSessions.active=null; LocalSessions.session=null; LocalSessions.additions=0; LocalRequests.sent.clear(); LocalRequests.domains.clear();
		NBWCallbackTaskFixtures.reset();
		flow=NBWLoginFlow.getInstance(); flow.cancel(flow.begin());
	}
	@After public void cleanup() throws Exception{
		for(var activity:activities) activity.close(); flow.cancel(flow.begin()); endpoints.close(); NBWCallbackTaskFixtures.reset();
	}
	private ActivityController<NBWLoginActivity> open(){ return open(71); }
	private ActivityController<NBWLoginActivity> open(int task){
		var lifecycle=Robolectric.buildActivity(NBWLoginActivity.class,new Intent(MastodonApp.context,NBWLoginActivity.class).putExtra(NBWLoginActivity.EXTRA_PERMISSION_CONFIRMED,true));
		NBWCallbackTaskFixtures.pin(lifecycle,task); lifecycle.setup(); activities.add(lifecycle); return lifecycle;
	}
	private Intent route(Uri uri){ return route(new Intent(Intent.ACTION_VIEW,uri),91); }
	private Intent route(Intent intent, int task){
		int before=NBWCallbackTaskFixtures.dispatches.size();
		var lifecycle=Robolectric.buildActivity(OAuthActivity.class,intent);
		NBWCallbackTaskFixtures.pin(lifecycle,task); lifecycle.create(); activities.add(lifecycle);
		assertTrue(lifecycle.get().isFinishing()); assertNull(lifecycle.get().getIntent().getData());
		assertNull("Router must not launch in its own task",org.robolectric.shadow.api.Shadow.<ShadowActivity>extract(lifecycle.get()).getNextStartedActivity());
		return before==NBWCallbackTaskFixtures.dispatches.size() ? null : NBWCallbackTaskFixtures.dispatches.get(before).intent;
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
		int before=NBWCallbackTaskFixtures.dispatches.size();
		Intent routed=route(NBWLoginFlowTest.completion());
		if(routed==null){ assertEquals(before,NBWCallbackTaskFixtures.dispatches.size()); return; }
		assertEquals(before+1,NBWCallbackTaskFixtures.dispatches.size());
		var dispatch=NBWCallbackTaskFixtures.dispatches.get(before);
		assertEquals(page.get().getTaskId(),dispatch.taskId); assertSame(page.get(),dispatch.owner);
		assertEquals(NBWLoginActivity.class.getName(),routed.getComponent().getClassName());
		assertNull(routed.getData()); assertEquals(java.util.Set.of("generation"),routed.getExtras().keySet());
		assertEquals(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP,routed.getFlags());
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
		var next=Robolectric.buildActivity(NBWLoginActivity.class,new Intent(MastodonApp.context,NBWLoginActivity.class));
		NBWCallbackTaskFixtures.pin(next,71); next.create(saved).start().resume(); activities.add(next);
		assertEquals(1,LocalRequests.sent.size()); assertNull(org.robolectric.shadow.api.Shadow.<ShadowActivity>extract(next.get()).getNextStartedActivity());
		assertEquals(View.GONE,next.get().findViewById(R.id.nbw_login_retry).getVisibility()); complete(next); assertEquals(2,LocalRequests.sent.size());
	}
	@Test public void savedStartServicePauseSurvivesRecreationWithoutInventingBrowserOwnership() throws Exception{
		var first=open();
		var error=new NBWLoginRequest.LoginError(503,"D1_DAILY_QUOTA_EXHAUSTED");
		var response=new okhttp3.Response.Builder().request(new okhttp3.Request.Builder().url("https://api.abdl-space.top/api/app/nbw/login/start").build())
				.protocol(okhttp3.Protocol.HTTP_1_1).code(503).message("paused").build();
		error.setServiceResourcePause(org.joinmastodon.android.api.ServiceResourcePause.fromResponse(response,"{\"code\":\"D1_DAILY_QUOTA_EXHAUSTED\"}"));
		assertNotNull(error.serviceResourcePause); callback(LocalRequests.sent.get(0)).onError(error);
		String message=((TextView)first.get().findViewById(R.id.nbw_login_status)).getText().toString();
		assertEquals(error.serviceResourcePause.message(first.get()),message);
		Bundle saved=new Bundle(); first.saveInstanceState(saved).pause().stop().destroy(); activities.remove(first);
		var rebuilt=Robolectric.buildActivity(NBWLoginActivity.class,new Intent(MastodonApp.context,NBWLoginActivity.class));
		NBWCallbackTaskFixtures.pin(rebuilt,71); rebuilt.create(saved).start().resume(); activities.add(rebuilt);
		assertEquals(message,((TextView)rebuilt.get().findViewById(R.id.nbw_login_status)).getText().toString());
		assertEquals(View.GONE,rebuilt.get().findViewById(R.id.nbw_login_retry).getVisibility());
		assertEquals(1,LocalRequests.sent.size()); assertNull(org.robolectric.shadow.api.Shadow.<ShadowActivity>extract(rebuilt.get()).getNextStartedActivity());
		assertNull(route(Uri.parse("abdl-space://callback?nbw_v2_login=complete")));
		assertNull(route(NBWLoginFlowTest.completion())); assertTrue(NBWCallbackTaskFixtures.dispatches.isEmpty());
		assertEquals(1,LocalRequests.sent.size()); assertEquals(message,((TextView)rebuilt.get().findViewById(R.id.nbw_login_status)).getText().toString());
	}
	@Test public void ordinaryTerminalStartFailureCannotBeRevivedBySavedStateOrCompletion() throws Exception{
		var first=open(); callback(LocalRequests.sent.get(0)).onError(new NBWLoginRequest.LoginError(500,"UNKNOWN_START_FAILURE"));
		assertEquals(first.get().getString(R.string.nbw_login_failed),((TextView)first.get().findViewById(R.id.nbw_login_status)).getText().toString());
		Bundle saved=new Bundle(); first.saveInstanceState(saved).pause().stop().destroy(); activities.remove(first);
		var rebuilt=Robolectric.buildActivity(NBWLoginActivity.class,new Intent(MastodonApp.context,NBWLoginActivity.class));
		NBWCallbackTaskFixtures.pin(rebuilt,71); rebuilt.create(saved).start().resume(); activities.add(rebuilt);
		assertEquals(rebuilt.get().getString(R.string.nbw_login_expired),((TextView)rebuilt.get().findViewById(R.id.nbw_login_status)).getText().toString());
		assertNull(route(Uri.parse("abdl-space://callback?nbw_v2_login=complete"))); assertNull(route(NBWLoginFlowTest.completion()));
		assertTrue(NBWCallbackTaskFixtures.dispatches.isEmpty()); assertEquals(1,LocalRequests.sent.size());
	}
	@Test public void browserTopInSameTaskAndRouterInOtherTaskRestoreOnlyCapturedOwner() throws Exception{
		for(int routerTask:new int[]{71,91}){
			LocalRequests.sent.clear(); NBWCallbackTaskFixtures.dispatches.clear();
			var lifecycle=open(); authorize(lifecycle.get()); lifecycle.pause();
			NBWCallbackTaskFixtures.task(71).browserOnTop();
			Intent hint=new Intent(Intent.ACTION_VIEW,NBWLoginFlowTest.completion()).putExtra("taskId",999).putExtra("account","foreign");
			Intent navigation=route(hint,routerTask); assertNotNull(navigation);
			assertEquals(1,NBWCallbackTaskFixtures.dispatches.size());
			var dispatch=NBWCallbackTaskFixtures.dispatches.get(0);
			assertEquals(71,dispatch.taskId); assertSame(lifecycle.get(),dispatch.owner);
			assertEquals(lifecycle.get().getComponentName(),NBWCallbackTaskFixtures.task(71).top);
			assertEquals(2,LocalRequests.sent.size());
			assertNull(route(NBWLoginFlowTest.completion())); assertEquals(1,NBWCallbackTaskFixtures.dispatches.size());
			lifecycle.resume(); assertEquals(2,LocalRequests.sent.size());
			lifecycle.get().findViewById(R.id.nbw_login_cancel).performClick(); lifecycle.close(); activities.remove(lifecycle);
		}
	}
	@Test @Config(sdk=33) public void modernTaskIdAlsoTargetsOriginalOwner() throws Exception{
		var lifecycle=open(); authorize(lifecycle.get()); complete(lifecycle);
		assertEquals(1,NBWCallbackTaskFixtures.dispatches.size()); assertEquals(71,NBWCallbackTaskFixtures.dispatches.get(0).taskId);
		assertEquals(2,LocalRequests.sent.size());
	}
	@Test public void missingDeniedForeignRootCanceledExpiredAndSwitchedOwnersNeverRouteOrExchange() throws Exception{
		for(int invalidation=0;invalidation<7;invalidation++){
			LocalSessions.active=null; LocalRequests.sent.clear(); NBWCallbackTaskFixtures.dispatches.clear();
			var lifecycle=open(); authorize(lifecycle.get());
			if(invalidation==0) NBWCallbackTaskFixtures.remove(71);
			else if(invalidation==1) NBWCallbackTaskFixtures.task(71).denied=true;
			else if(invalidation==2) NBWCallbackTaskFixtures.task(71).root=new android.content.ComponentName("foreign.package","foreign.Root");
			else if(invalidation==3) lifecycle.get().findViewById(R.id.nbw_login_cancel).performClick();
			else if(invalidation==4) ShadowSystemClock.advanceBy(Duration.ofMillis(NBWLoginFlow.TTL_MS));
			else if(invalidation==5) LocalSessions.active="switched-account";
			else{ flow.cancel(flow.begin()); }
			assertNull(route(NBWLoginFlowTest.completion())); assertTrue(NBWCallbackTaskFixtures.dispatches.isEmpty());
			assertEquals(1,LocalRequests.sent.size()); assertEquals(0,LocalSessions.additions);
			lifecycle.close(); activities.remove(lifecycle); NBWCallbackTaskFixtures.reset();
		}
	}
	@Test public void generationOnlySecondCoordinatorCannotStealOwnerOrStartAnotherExchange() throws Exception{
		var lifecycle=open(); authorize(lifecycle.get()); Bundle state=new Bundle(); lifecycle.saveInstanceState(state);
		Intent forged=NBWLoginActivity.completionIntent(lifecycle.get(),state.getLong("generation"));
		var duplicate=Robolectric.buildActivity(NBWLoginActivity.class,forged);
		NBWCallbackTaskFixtures.pin(duplicate,99); duplicate.create(); activities.add(duplicate);
		assertEquals(1,LocalRequests.sent.size());
		assertEquals(duplicate.get().getString(R.string.nbw_login_expired),((TextView)duplicate.get().findViewById(R.id.nbw_login_status)).getText().toString());
		complete(lifecycle); assertEquals(2,LocalRequests.sent.size());
		assertSame(lifecycle.get(),NBWCallbackTaskFixtures.dispatches.get(0).owner);
	}
	@Test public void backgroundExchangeAndInstallerSuccessStillInstallThenOpenMainBeforeFinishing() throws Exception{
		var lifecycle=open(); authorize(lifecycle.get()); complete(lifecycle); lifecycle.pause();
		callback(LocalRequests.sent.get(1)).onSuccess(NBWLoginFlowTest.exchangeResult());
		assertEquals(0,LocalSessions.additions); assertFalse(lifecycle.get().isFinishing());
		Account account=new Account(); account.id="42"; callback(LocalRequests.sent.get(2)).onSuccess(account);
		assertEquals(1,LocalSessions.additions);
		Intent main=org.robolectric.shadow.api.Shadow.<ShadowActivity>extract(lifecycle.get()).getNextStartedActivity();
		assertNotNull(main); assertEquals(MainActivity.class.getName(),main.getComponent().getClassName()); assertTrue(lifecycle.get().isFinishing());
		assertNull(route(NBWLoginFlowTest.completion())); assertEquals(1,NBWCallbackTaskFixtures.dispatches.size());
	}
	@Test public void processLossAndUnconfirmedLaunchFailClosed(){
		var first=Robolectric.buildActivity(NBWLoginActivity.class,new Intent(MastodonApp.context,NBWLoginActivity.class)).setup(); activities.add(first);
		// Unlock gating posts entry work to the main queue; the fail-closed decision happens there.
		ShadowLooper.idleMainLooper();
		assertTrue(first.get().isFinishing());
		// Public endpoint discovery is allowed on foreground; no authorization or exchange may start.
		assertNoAuthorizationRequests();
		Bundle saved=new Bundle(); saved.putLong("generation",42);
		var restored=Robolectric.buildActivity(NBWLoginActivity.class,new Intent(MastodonApp.context,NBWLoginActivity.class)).create(saved); activities.add(restored);
		ShadowLooper.idleMainLooper();
		assertEquals(restored.get().getString(R.string.nbw_login_expired), ((TextView)restored.get().findViewById(R.id.nbw_login_status)).getText().toString());
		assertNoAuthorizationRequests();
	}
	private static void assertNoAuthorizationRequests(){
		for(MastodonAPIRequest<?> request:LocalRequests.sent)
			assertTrue("Only public discovery is allowed: "+request.getClass().getSimpleName(),request instanceof org.joinmastodon.android.api.requests.nbw.GetNBWEndpoints);
	}
}
