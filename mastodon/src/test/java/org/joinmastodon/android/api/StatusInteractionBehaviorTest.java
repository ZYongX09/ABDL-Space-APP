package org.joinmastodon.android.api;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.statuses.NBWStatusInteraction;
import org.joinmastodon.android.api.requests.statuses.SetStatusFavorited;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.events.StatusCountersUpdatedEvent;
import org.joinmastodon.android.fragments.settings.NBWPostRegisterActivity;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Instance;
import org.joinmastodon.android.model.InstanceV1;
import org.joinmastodon.android.model.Status;
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
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import me.grishka.appkit.api.APIRequest;
import me.grishka.appkit.api.Callback;

/** Real controller state transitions, UI dialog and navigation; only transport is captured. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class, shadows=StatusInteractionBehaviorTest.LocalRequests.class)
@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
public class StatusInteractionBehaviorTest{
	@Implements(MastodonAPIRequest.class) public static class LocalRequests{
		@RealObject MastodonAPIRequest<?> request;
		static final List<MastodonAPIRequest<?>> sent=new ArrayList<>();
		@Implementation public MastodonAPIRequest<?> exec(String account){ sent.add(request); return request; }
	}
	private AccountSession session, previousSession;
	private Instance previousInstance;
	private Map<String, AccountSession> sessions;
	private Map<String, Instance> instances;
	private ActivityController<Activity> lifecycle;
	private Activity activity;
	private StatusInteractionController interactions;

	@Before @SuppressWarnings("unchecked") public void setup() throws Exception{
		org.robolectric.shadows.ShadowChoreographer.setPaused(true);
		MastodonApp.context=RuntimeEnvironment.getApplication(); me.grishka.appkit.utils.V.setApplicationContext(MastodonApp.context);
		var ctor=AccountSession.class.getDeclaredConstructor(); ctor.setAccessible(true); session=ctor.newInstance();
		session.domain="interaction.offline.example.test"; session.self=new Account(); session.self.id="42";
		AccountSessionManager manager=AccountSessionManager.getInstance();
		sessions=(Map<String, AccountSession>)field(AccountSessionManager.class, "sessions").get(manager);
		instances=(Map<String, Instance>)field(AccountSessionManager.class, "instances").get(manager);
		previousSession=sessions.put(session.getID(), session);
		InstanceV1 instance=new InstanceV1(); instance.uri=instance.normalizedUri=session.domain; instance.version="4.3.0";
		previousInstance=instances.put(session.domain, instance);
		lifecycle=Robolectric.buildActivity(Activity.class); activity=lifecycle.get(); activity.setTheme(R.style.Theme_Mastodon_Light); lifecycle.setup();
		interactions=new StatusInteractionController(session.getID()); LocalRequests.sent.clear();
	}
	@After public void cleanup(){
		AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog(); if(dialog!=null) dialog.dismiss();
		lifecycle.close();
		if(previousSession==null) sessions.remove(session.getID()); else sessions.put(session.getID(), previousSession);
		if(previousInstance==null) instances.remove(session.domain); else instances.put(session.domain, previousInstance);
		LocalRequests.sent.clear();
		org.robolectric.shadows.ShadowChoreographer.setPaused(false);
	}

	private static Status status(String id){ Status s=Status.ofFake(id, "Unmodified body", Instant.EPOCH); s.favouritesCount=7; s.bookmarksCount=3; return s; }
	private static MastodonAPIRequest<?> last(){ return LocalRequests.sent.get(LocalRequests.sent.size()-1); }
	@SuppressWarnings("unchecked") private static Callback<Object> callback() throws Exception{ return (Callback<Object>)field(APIRequest.class, "callback").get(last()); }

	@Test public void failuresRestoreExactSnapshotForLikeAndBookmarkAndRepeatedCallbacksAreIgnored() throws Exception{
		for(int code:new int[]{401, 403, 500}){
			Status s=status("p_1"); interactions.setFavorited(s, true, ignored->{}, activity);
			assertTrue(last() instanceof SetStatusFavorited); assertTrue(s.favourited); assertEquals(8, s.favouritesCount);
			Callback<Object> pending=callback(); pending.onError(new MastodonErrorResponse("Permission failure", code, null));
			assertFalse(s.favourited); assertEquals(7, s.favouritesCount);
			pending.onError(new MastodonErrorResponse("Repeated callback", code, null)); assertEquals(7, s.favouritesCount);
			interactions.setBookmarked(s, true, activity); assertTrue(s.bookmarked); assertEquals(4, s.bookmarksCount);
			pending=callback(); pending.onError(new MastodonErrorResponse("Permission failure", code, null));
			assertFalse(s.bookmarked); assertEquals(3, s.bookmarksCount);
			pending.onError(new MastodonErrorResponse("Repeated callback", code, null)); assertEquals(3, s.bookmarksCount);
		}
	}

	@Test public void nbwUsesLightweightResponseAuthoritativeCountAndSinglePendingAction() throws Exception{
		Status s=status("nbw_123"); Status wrapper=status("boost"); wrapper.reblog=s;
		interactions.setFavorited(s, true, ignored->{}, activity); assertTrue(last() instanceof NBWStatusInteraction);
		int count=LocalRequests.sent.size(); interactions.setFavorited(s, false, ignored->{}, activity); assertEquals(count, LocalRequests.sent.size());
		NBWStatusInteraction.Result result=new NBWStatusInteraction.Result(); result.id=s.id; result.favourited=true; result.favouritesCount=42L;
		callback().onSuccess(result); assertEquals(42, s.favouritesCount); assertTrue(s.favourited);
		assertSame(s, wrapper.reblog); assertEquals("Unmodified body", wrapper.reblog.content); assertEquals(7, wrapper.favouritesCount);
		interactions.setBookmarked(s, true, activity); result=new NBWStatusInteraction.Result(); result.id=s.id; result.bookmarked=false;
		callback().onSuccess(result); assertFalse(s.bookmarked); assertEquals(3, s.bookmarksCount);
		Status copy=status(s.id); copy.update(new StatusCountersUpdatedEvent(s, StatusCountersUpdatedEvent.CounterType.BOOKMARKS));
		assertEquals(s.bookmarksCount, copy.bookmarksCount); assertEquals(s.bookmarked, copy.bookmarked);
	}

	@Test public void bindingErrorRollsBackAndDialogOpensBindingForOriginalAccount() throws Exception{
		Status s=status("nbw_123"); interactions.setFavorited(s, true, ignored->{}, activity);
		callback().onError(new MastodonErrorResponse.NBWBindingRequired("请先绑定宝宝新天地账号后再操作", 403));
		assertFalse(s.favourited); assertEquals(7, s.favouritesCount);
		AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog(); assertNotNull(dialog); assertTrue(dialog.isShowing());
		assertEquals(activity.getString(R.string.nbw_interaction_binding_required), ((android.widget.TextView)dialog.findViewById(android.R.id.message)).getText().toString());
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		org.robolectric.shadows.ShadowLooper.idleMainLooper();
		org.robolectric.shadows.ShadowActivity shadow=org.robolectric.shadow.api.Shadow.extract(activity);
		Intent intent=shadow.getNextStartedActivity();
		assertEquals(NBWPostRegisterActivity.class.getName(), intent.getComponent().getClassName());
		assertEquals(session.getID(), intent.getStringExtra("account")); assertTrue(intent.getBooleanExtra("show_back", false));
	}

	@Test public void ordinaryPermissionErrorShowsNoBindingDialog() throws Exception{
		Status s=status("nbw_123"); interactions.setBookmarked(s, true, activity);
		callback().onError(new MastodonErrorResponse("用户组权限不足", 403, null));
		assertFalse(s.bookmarked); assertEquals(3, s.bookmarksCount);
		assertNull(ShadowAlertDialog.getLatestAlertDialog()); assertEquals("用户组权限不足", ShadowToast.getTextOfLatestToast());
	}

	@Test public void unsupportedFloorIdsNeverSendOrMutateState() throws Exception{
		for(String id:List.of("nbw_0", "nbw_001", "nbw_123_456", "nbw_9223372036854775808")){
			Status s=status(id); interactions.setFavorited(s, true, ignored->{}, activity); interactions.setBookmarked(s, true, activity);
			assertFalse(s.favourited); assertFalse(s.bookmarked); assertEquals(7, s.favouritesCount); assertEquals(3, s.bookmarksCount);
		}
		assertTrue(LocalRequests.sent.isEmpty()); assertEquals(activity.getString(R.string.nbw_reply_interaction_unsupported), ShadowToast.getTextOfLatestToast());
	}
	@Test public void retiredBindingStateCanNeverAuthorizeOrBindAnything(){
		// The old V1 binding-state minting is retired by design: a legacy callback, replay,
		// tampered payload or expired transaction must not resurrect the old bind pipeline.
		assertEquals("retired", NBWPostRegisterActivity.createBindingState(activity, session.getID(), 42));
		assertNull(NBWPostRegisterActivity.consumeBindingAccount(activity, "retired"));
		assertNull(NBWPostRegisterActivity.consumeBindingAccount(activity, ""));
		assertNull(NBWPostRegisterActivity.consumeBindingAccount(activity, null));
		com.google.gson.JsonObject forged=new com.google.gson.JsonObject();
		forged.addProperty("uid", 42); forged.addProperty("account", session.getID());
		forged.addProperty("nonce", java.util.UUID.randomUUID().toString()); forged.addProperty("ts", System.currentTimeMillis());
		String encoded=android.util.Base64.encodeToString(forged.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
		assertNull("A structurally valid legacy state must not bind without the retired minting", NBWPostRegisterActivity.consumeBindingAccount(activity, encoded));
		assertTrue(activity.getSharedPreferences("nbw_bind", 0).getString("transaction_"+forged.get("nonce").getAsString(), "").isEmpty());
		sessions.remove(session.getID());
		assertNull(NBWPostRegisterActivity.consumeBindingAccount(activity, encoded));
		sessions.put(session.getID(), session);
	}

	private static Field field(Class<?> type, String name) throws Exception{ Field f=type.getDeclaredField(name); f.setAccessible(true); return f; }
}
