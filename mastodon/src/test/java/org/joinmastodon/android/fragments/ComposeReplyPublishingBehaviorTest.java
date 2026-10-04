package org.joinmastodon.android.fragments;

import android.Manifest;
import android.content.Context;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.PopupWindow;
import android.widget.Toolbar;

import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.api.requests.accounts.GetOwnAccount;
import org.joinmastodon.android.api.requests.nbw.RecommendNBWForum;
import org.joinmastodon.android.api.requests.statuses.CreateStatus;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Attachment;
import org.joinmastodon.android.model.EmojiCategory;
import org.joinmastodon.android.model.Instance;
import org.joinmastodon.android.model.InstanceV1;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.StatusPrivacy;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.ui.utils.LocationUtils;
import org.joinmastodon.android.ui.viewcontrollers.ComposeMediaViewController;
import org.joinmastodon.android.ui.viewholders.ListItemViewHolder;
import org.joinmastodon.android.ui.views.ComposeEditText;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.parceler.Parcels;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import me.grishka.appkit.FragmentStackActivity;
import me.grishka.appkit.api.APIRequest;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.utils.V;
import okio.Buffer;

import static org.junit.Assert.*;

/** Real fragment lifecycle, editable body, native toolbar menu, and production publish pipeline. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class, qualifiers="en",
		shadows=ComposeReplyPublishingBehaviorTest.LocalRequests.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ComposeReplyPublishingBehaviorTest{
	private static final int[] BINDING_STATES={0, -1, -2, 1};
	private static final String BODY="An offline reply";
	private ActivityController<FragmentStackActivity> controller;
	private FragmentStackActivity activity;
	private ComposeFragment fragment;
	private ComposeEditText body;
	private Menu menu;
	private AccountSession session;
	private Map<String, AccountSession> sessions;
	private Map<String, Instance> instances;
	private Map<String, List<EmojiCategory>> emojis;
	private AccountSession previousSession;
	private Instance previousInstance;
	private List<EmojiCategory> previousEmojis;

	/** Only transport is replaced: real request bodies and callbacks remain observable. */
	@Implements(MastodonAPIRequest.class)
	public static class LocalRequests{
		@RealObject MastodonAPIRequest<?> request;
		static final List<MastodonAPIRequest<?>> sent=new ArrayList<>();
		static final List<String> accounts=new ArrayList<>();
		@Implementation public MastodonAPIRequest<?> exec(String account){
			sent.add(request);
			accounts.add(account);
			return request;
		}
		@Implementation public MastodonAPIRequest<?> execNoAuth(String domain){
			throw new AssertionError("Unexpected instance/network fetch: "+request.getClass().getSimpleName());
		}
		@Implementation public MastodonAPIRequest<?> exec(String domain, Token token){
			throw new AssertionError("Unexpected instance/network fetch: "+request.getClass().getSimpleName());
		}
	}

	@Before
	@SuppressWarnings("unchecked")
	public void setUp() throws Exception{
		Context context=RuntimeEnvironment.getApplication();
		MastodonApp.context=context;
		V.setApplicationContext(context);
		ShadowApplication appShadow=Shadow.extract(RuntimeEnvironment.getApplication());
		appShadow.denyPermissions(
				Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
		context.getSharedPreferences("location_cache", Context.MODE_PRIVATE).edit().clear().commit();
		GlobalUserPreferences.getPrefs().edit().clear().putBoolean("perAccountMigrationDone", true).commit();
		GlobalUserPreferences.load();
		var constructor=AccountSession.class.getDeclaredConstructor();
		constructor.setAccessible(true);
		session=constructor.newInstance();
		session.domain="compose-reply.offline.example.test";
		session.self=new Account();
		session.self.id="42";
		session.self.username=session.self.acct="offline";
		session.self.displayName="Offline account";
		session.self.emojis=List.of();
		// Inline reply previews require a non-null avatar URI; never use a remote image.
		session.self.avatar=session.self.avatarStatic="android.resource://"+context.getPackageName()+"/"+R.drawable.ic_nbw;
		session.infoLastUpdated=System.currentTimeMillis();
		AccountSessionManager manager=AccountSessionManager.getInstance();
		sessions=(Map<String, AccountSession>)field(AccountSessionManager.class, "sessions").get(manager);
		instances=(Map<String, Instance>)field(AccountSessionManager.class, "instances").get(manager);
		emojis=(Map<String, List<EmojiCategory>>)field(AccountSessionManager.class, "customEmojis").get(manager);
		previousSession=sessions.put(session.getID(), session);
		InstanceV1 instance=new InstanceV1();
		instance.uri=instance.normalizedUri=session.domain;
		instance.version="4.3.0";
		instance.maxTootChars=500;
		previousInstance=instances.put(session.domain, instance);
		// Nonempty local category list prevents ComposeFragment.onCreate's instance refresh.
		previousEmojis=emojis.put(session.domain, List.of(new EmojiCategory("Offline", List.of())));
		LocalRequests.sent.clear();
		LocalRequests.accounts.clear();
	}

	@After
	public void tearDown() throws Exception{
		try{
			closeScreen();
		}finally{
			if(sessions!=null) restore(sessions, session.getID(), previousSession);
			if(instances!=null) restore(instances, session.domain, previousInstance);
			if(emojis!=null) restore(emojis, session.domain, previousEmojis);
			LocalRequests.sent.clear();
			LocalRequests.accounts.clear();
			GlobalUserPreferences.getPrefs().edit().clear().putBoolean("perAccountMigrationDone", true).commit();
			GlobalUserPreferences.load();
			MastodonApp.context=null;
		}
	}

	@Test
	public void repliesEnableNativeMenuAndSendForEveryBindingStateAndBothTypedIds() throws Exception{
		for(String id:List.of("p_000123", "c_000456")){
			for(int state:BINDING_STATES){
				launch(id);
				assertEquals("Reply starts in the default checking state", 0, bindingState());
				setBindingState(state);
				setBody(BODY);
				setGeo(); // Even populated location data must not leak into a reply.
				assertEquals(View.GONE, fragment.getView().findViewById(R.id.btn_visibility).getVisibility());
				assertEquals(0, count(GetOwnAccount.class));
				assertEquals(0, count(RecommendNBWForum.class));
				assertTrue("Reply must not require NBW binding, state="+state, publishItem().isEnabled());
				clickPublish();
				assertReplyRequest(id, BODY);
				assertFalse("Sending still disables the menu", publishItem().isEnabled());
				Status reply=(Status)field(ComposeFragment.class, "replyTo").get(fragment);
				succeed(last(CreateStatus.class), status("c_created"));
				assertEquals(1, reply.repliesCount);
				assertNull(field(ComposeFragment.class, "sendingOverlay").get(fragment));
			}
		}
	}

	@Test
	public void replyMenuCallbackIndependentlyBypassesTheBindingClickGate() throws Exception{
		launch("c_opaque_007");
		setBody(BODY);
		// Use the actual inflated MenuItem, not publish()/actuallyPublish(). This independently
		// catches the old click gate even when the old enabled-state assertion fails elsewhere.
		assertTrue(fragment.onOptionsItemSelected(publishItem()));
		idle();
		assertReplyRequest("c_opaque_007", BODY);
	}

	@Test
	public void emptyOverLimitAndInFlightRepliesStillDisableNativePublishing() throws Exception{
		launch("p_123");
		for(int state:BINDING_STATES){
			setBindingState(state);
			for(String invalid:List.of("", " \n\t ", "x".repeat(501))){
				setBody(invalid);
				assertDisabledMenuDoesNotSend();
			}
			setBody("x".repeat(500));
			assertTrue("Exactly the character limit remains valid", publishItem().isEnabled());
			field(ComposeFragment.class, "aiRecommendationInFlight").setBoolean(fragment, true);
			fragment.updatePublishButtonState();
			assertDisabledMenuDoesNotSend();
			field(ComposeFragment.class, "aiRecommendationInFlight").setBoolean(fragment, false);
			fragment.updatePublishButtonState();
			assertTrue(publishItem().isEnabled());
		}
	}

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	public void unfinishedAttachmentsDisableRepliesButCompletedMediaOnlyReplyCanSend() throws Exception{
		launch("c_123");
		setBody(BODY);
		assertTrue(publishItem().isEnabled());
		ComposeMediaViewController media=(ComposeMediaViewController)field(ComposeFragment.class, "mediaViewController").get(fragment);
		Class<?> draftClass=Class.forName(ComposeMediaViewController.class.getName()+"$DraftMediaAttachment");
		var constructor=draftClass.getDeclaredConstructor();
		constructor.setAccessible(true);
		Object draft=constructor.newInstance();
		Attachment attachment=new Attachment();
		attachment.id="offline-media";
		attachment.type=Attachment.Type.AUDIO;
		field(draftClass, "serverAttachment").set(draft, attachment);
		field(draftClass, "description").set(draft, "Offline audio");
		// Restore a real draft and thumbnail instead of mocking media validity/count methods.
		((List<Object>)field(ComposeMediaViewController.class, "attachments").get(media)).add(draft);
		media.setView(fragment.getView(), null);
		fragment.updateMediaPollStates();
		Field uploadState=field(draftClass, "state");
		for(String state:List.of("QUEUED", "UPLOADING", "PROCESSING", "ERROR")){
			uploadState.set(draft, Enum.valueOf((Class<? extends Enum>)uploadState.getType(), state));
			fragment.updatePublishButtonState();
			assertEquals(1, media.getNonDoneAttachmentCount());
			assertDisabledMenuDoesNotSend();
		}
		uploadState.set(draft, Enum.valueOf((Class<? extends Enum>)uploadState.getType(), "DONE"));
		setBody("");
		assertEquals(0, media.getNonDoneAttachmentCount());
		assertTrue("Completed attachment still permits a media-only reply", publishItem().isEnabled());
		clickPublish();
		assertReplyRequest("c_123", "");
		assertEquals(List.of("offline-media"), sentStatus().mediaIds);
	}

	@Test
	public void invalidPollRepliesStayDisabledAndRealOptionEditsEnablePublishing() throws Exception{
		launch("p_123");
		setBody(BODY);
		assertTrue(fragment.getView().findViewById(R.id.btn_poll).performClick());
		android.view.ViewGroup options=fragment.getView().findViewById(R.id.poll_options);
		assertEquals(2, options.getChildCount());
		EditText first=options.getChildAt(0).findViewById(R.id.edit);
		EditText second=options.getChildAt(1).findViewById(R.id.edit);
		assertDisabledMenuDoesNotSend();
		first.setText("One");
		assertDisabledMenuDoesNotSend();
		second.setText("Two");
		assertTrue(publishItem().isEnabled());
		second.setText("");
		assertDisabledMenuDoesNotSend();
		second.setText("Two");
		clickPublish();
		assertReplyRequest("p_123", BODY);
		assertEquals(List.of("One", "Two"), sentStatus().poll.options);
	}

	@Test
	public void ordinarySynchronizedPostsStillBlockCheckingUnboundAndFailedBinding() throws Exception{
		launch(null);
		setBody(BODY);
		for(int state:new int[]{0, -1, -2}){
			if(state==-1){
				session.self.nbwUsername=null;
				succeed(last(GetOwnAccount.class), session.self);
			}else if(state==-2){
				callback(last(GetOwnAccount.class)).onError(new MastodonErrorResponse("Offline binding failure", 503, null));
				idle();
			}
			assertEquals(state, bindingState());
			assertDisabledMenuDoesNotSend();
			int checks=count(GetOwnAccount.class);
			// A stale/direct native menu callback must also recheck, never send or recommend.
			assertTrue(fragment.onOptionsItemSelected(publishItem()));
			assertEquals(checks+1, count(GetOwnAccount.class));
			assertEquals(0, count(CreateStatus.class));
			assertEquals(0, count(RecommendNBWForum.class));
		}
	}

	@Test
	public void disablingSyncImmediatelyEnablesUnboundPostsAndSendsNullNbwWithoutRechecking() throws Exception{
		launch(null);
		setBody(BODY);
		int checks=count(GetOwnAccount.class); // Ordinary-screen lifecycle checks are unchanged.
		for(int state:new int[]{0, -1, -2}){
			setBindingState(state);
			chooseForum(R.string.nbw_forum_none);
			assertTrue("No-sync selection enables without another text edit", publishItem().isEnabled());
			chooseForum(R.string.nbw_forum_share);
			assertDisabledMenuDoesNotSend();
		}
		chooseForum(R.string.nbw_forum_none);
		clickPublish();
		assertEquals(checks, count(GetOwnAccount.class));
		assertEquals(0, count(RecommendNBWForum.class));
		assertEquals(1, count(CreateStatus.class));
		assertNull(sentStatus().nbwFid);
		assertNull(sentStatus().inReplyToId);
		assertFalse(json(last(CreateStatus.class)).has("nbw_fid"));
	}

	@Test
	public void boundManualForumPostsKeepTheSelectedForumAndGeo() throws Exception{
		launch(null);
		bind();
		setBody(BODY);
		chooseForum(R.string.nbw_forum_share);
		setGeo();
		int checks=count(GetOwnAccount.class);
		clickPublish();
		assertEquals(checks, count(GetOwnAccount.class));
		assertEquals(0, count(RecommendNBWForum.class));
		assertEquals(1, count(CreateStatus.class));
		CreateStatus.Request request=sentStatus();
		assertEquals(Integer.valueOf(27), request.nbwFid);
		assertNull(request.inReplyToId);
		assertEquals("Offline province", request.geoProvince);
		assertEquals("Offline city", request.geoCity);
		assertEquals("Offline district", request.geoDistrict);
	}

	@Test
	public void boundAiForumPostsWaitForRecommendationAndSendItsResolvedForum() throws Exception{
		launch(null);
		bind();
		setBody("  "+BODY+"  ");
		int checks=count(GetOwnAccount.class);
		clickPublish();
		assertEquals(1, count(RecommendNBWForum.class));
		assertEquals(0, count(CreateStatus.class));
		assertDisabledMenuDoesNotSend();
		assertEquals(BODY, json(last(RecommendNBWForum.class)).get("content").getAsString());
		RecommendNBWForum.Response recommended=new RecommendNBWForum.Response();
		recommended.fid=26;
		recommended.forumName="Offline novel forum";
		succeed(last(RecommendNBWForum.class), recommended);
		assertEquals(checks, count(GetOwnAccount.class));
		assertEquals(1, count(CreateStatus.class));
		assertEquals(Integer.valueOf(26), sentStatus().nbwFid);
		assertEquals("  "+BODY+"  ", sentStatus().status);
	}

	private void launch(String replyId) throws Exception{
		closeScreen();
		LocalRequests.sent.clear();
		LocalRequests.accounts.clear();
		controller=Robolectric.buildActivity(FragmentStackActivity.class);
		activity=controller.get();
		activity.setTheme(R.style.Theme_Mastodon_Light);
		controller.create();
		fragment=new ComposeFragment();
		Bundle args=new Bundle();
		args.putString("account", session.getID());
		if(replyId!=null) args.putParcelable("replyTo", Parcels.wrap(status(replyId)));
		fragment.setArguments(args);
		activity.showFragment(fragment);
		controller.start().resume().visible();
		idle();
		assertTrue(fragment.isAdded());
		assertNotNull(fragment.getView());
		body=fragment.getView().findViewById(R.id.toot_text);
		assertNotNull(body);
		assertTrue(body.isEnabled());
		assertNotNull(body.getKeyListener());
		Toolbar toolbar=fragment.getView().findViewById(R.id.toolbar);
		assertNotNull(toolbar);
		menu=toolbar.getMenu(); // Inflated by the real AppKit lifecycle with its real click listener.
		assertNotNull(publishItem());
		assertSame(publishItem(), field(ComposeFragment.class, "publishButton").get(fragment));
	}

	private Status status(String id){
		Status status=Status.ofFake(id, "<p>Offline parent</p>", Instant.parse("2026-01-01T00:00:00Z"));
		status.account=session.self; // Own parent means no mention-autocomplete requests.
		status.uri="https://"+session.domain+"/statuses/"+id;
		return status;
	}

	private void setBody(String text){
		body.getText().replace(0, body.length(), text); // Production TextWatcher counts and validates.
		idle();
	}

	private MenuItem publishItem(){ return menu.findItem(R.id.publish); }
	private int bindingState() throws Exception{ return field(ComposeFragment.class, "newBabyWorldBindingState").getInt(fragment); }
	private void setBindingState(int state) throws Exception{
		field(ComposeFragment.class, "newBabyWorldBindingState").setInt(fragment, state);
		fragment.updatePublishButtonState();
	}

	private void clickPublish(){
		assertTrue("Native publish item must be enabled before dispatch", publishItem().isEnabled());
		assertTrue("Native menu must dispatch the production listener", menu.performIdentifierAction(R.id.publish, 0));
		idle();
	}

	private void assertDisabledMenuDoesNotSend(){
		assertFalse(publishItem().isEnabled());
		int before=LocalRequests.sent.size();
		assertFalse("Native disabled item must not dispatch", menu.performIdentifierAction(R.id.publish, 0));
		idle();
		assertEquals(before, LocalRequests.sent.size());
	}

	private void assertReplyRequest(String id, String text) throws Exception{
		assertEquals(1, count(CreateStatus.class));
		assertEquals(0, count(GetOwnAccount.class));
		assertEquals(0, count(RecommendNBWForum.class));
		CreateStatus.Request request=sentStatus();
		assertEquals(id, request.inReplyToId);
		assertEquals(text, request.status);
		assertEquals(StatusPrivacy.PUBLIC, request.visibility);
		assertNull(request.nbwFid);
		assertNull(request.geoProvince);
		assertNull(request.geoCity);
		assertNull(request.geoDistrict);
		JsonObject wire=json(last(CreateStatus.class));
		assertEquals(id, wire.get("in_reply_to_id").getAsString());
		for(String key:List.of("nbw_fid", "geo_province", "geo_city", "geo_district"))
			assertFalse("Reply wire payload must omit "+key, wire.has(key));
		assertEquals(List.of(session.getID()), LocalRequests.accounts);
	}

	private void bind() throws Exception{
		session.self.nbwUsername="offline-bound";
		succeed(last(GetOwnAccount.class), session.self);
		assertEquals(1, bindingState());
	}

	private void setGeo() throws Exception{
		field(ComposeFragment.class, "currentLocation").set(fragment,
				new LocationUtils.ResolvedLocation("Offline province", "Offline city", "Offline district"));
		field(ComposeFragment.class, "selectedLocationLevel").setInt(fragment, 2);
	}

	private void chooseForum(int titleResource){
		assertTrue(fragment.getView().findViewById(R.id.btn_visibility).performClick());
		idle();
		ShadowApplication appShadow=Shadow.extract(RuntimeEnvironment.getApplication());
		PopupWindow popup=appShadow.getLatestPopupWindow();
		assertNotNull(popup);
		assertTrue(popup.isShowing());
		RecyclerView list=(RecyclerView)popup.getContentView();
		list.measure(View.MeasureSpec.makeMeasureSpec(V.dp(240), View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(V.dp(640), View.MeasureSpec.EXACTLY));
		list.layout(0, 0, V.dp(240), V.dp(640));
		idle();
		for(int i=0; i<list.getAdapter().getItemCount(); i++){
			RecyclerView.ViewHolder holder=list.findViewHolderForAdapterPosition(i);
			assertTrue("Actual forum popup must bind its rows", holder instanceof ListItemViewHolder);
			ListItemViewHolder<?> item=(ListItemViewHolder<?>)holder;
			if(item.getItem().titleRes==titleResource){
				item.onClick(); // Actual UsableRecyclerView holder dispatch; no replaced callbacks.
				idle();
				assertFalse(popup.isShowing());
				return;
			}
		}
		fail("Missing forum selection "+titleResource);
	}

	private CreateStatus.Request sentStatus() throws Exception{
		return (CreateStatus.Request)field(MastodonAPIRequest.class, "requestBody").get(last(CreateStatus.class));
	}
	private static int count(Class<?> type){ return (int)LocalRequests.sent.stream().filter(type::isInstance).count(); }
	private static <T extends MastodonAPIRequest<?>> T last(Class<T> type){
		for(int i=LocalRequests.sent.size()-1; i>=0; i--)
			if(type.isInstance(LocalRequests.sent.get(i))) return type.cast(LocalRequests.sent.get(i));
		throw new AssertionError("No actual "+type.getSimpleName()+" request was executed");
	}
	private static JsonObject json(MastodonAPIRequest<?> request) throws Exception{
		Buffer buffer=new Buffer();
		request.getRequestBody().writeTo(buffer);
		return JsonParser.parseString(buffer.readUtf8()).getAsJsonObject();
	}
	@SuppressWarnings("unchecked")
	private static Callback<Object> callback(MastodonAPIRequest<?> request) throws Exception{
		Callback<Object> callback=(Callback<Object>)field(APIRequest.class, "callback").get(request);
		assertNotNull("Production request must install a callback", callback);
		return callback;
	}
	private static void succeed(MastodonAPIRequest<?> request, Object result) throws Exception{
		callback(request).onSuccess(result);
		idle();
	}
	private void closeScreen() throws Exception{
		if(controller==null) return;
		View overlay=(View)field(ComposeFragment.class, "sendingOverlay").get(fragment);
		if(overlay!=null && overlay.isAttachedToWindow()) activity.getSystemService(WindowManager.class).removeView(overlay);
		controller.close();
		controller=null;
		fragment=null;
	}
	private static <T> void restore(Map<String, T> map, String key, T value){
		if(value==null) map.remove(key); else map.put(key, value);
	}
	private static Field field(Class<?> type, String name) throws Exception{
		Field field=type.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}
	private static void idle(){ ShadowLooper.idleMainLooper(); }
}
