package org.joinmastodon.android.fragments;

import android.Manifest;
import android.content.Context;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
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
import org.joinmastodon.android.api.NBWV2RepositoryTest;
import org.joinmastodon.android.api.requests.accounts.GetOwnAccount;
import org.joinmastodon.android.api.requests.nbw.NBWAppRequest;
import org.joinmastodon.android.api.requests.nbw.RecommendNBWForum;
import org.joinmastodon.android.api.requests.statuses.CreateStatus;
import org.joinmastodon.android.api.requests.statuses.EditStatus;
import org.joinmastodon.android.api.requests.statuses.GetStatusSourceText;
import org.joinmastodon.android.api.requests.friendcards.FriendCardRequest;
import org.joinmastodon.android.api.requests.friendcards.FriendCardResponses;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.api.session.NBWForumOptionsRepository;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Attachment;
import org.joinmastodon.android.model.EmojiCategory;
import org.joinmastodon.android.model.FriendCard;
import org.joinmastodon.android.model.FriendCardAttachment;
import org.joinmastodon.android.model.Instance;
import org.joinmastodon.android.model.InstanceV1;
import org.joinmastodon.android.model.NBWSelection;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.StatusPrivacy;
import org.joinmastodon.android.model.Token;
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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

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

/** Real editor lifecycle, local validation, native publish menu and serialized requests.
 * Metadata fixtures enter the session repository through real NBWAppRequest callbacks.
 * Direct repository selection below is a publishing input fixture, NOT a GUI assertion;
 * NBWClassificationMenuInteractionTest separately clicks the production Compose menu.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class, qualifiers="en",
		shadows=ComposeReplyPublishingBehaviorTest.LocalRequests.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ComposeReplyPublishingBehaviorTest{
	private static final String BODY="An offline reply";
	private static final String CAPS="/api/app/capabilities", OPTIONS="/api/app/nbw/options";
	private ActivityController<FragmentStackActivity> controller;
	private FragmentStackActivity activity;
	private ComposeFragment fragment;
	private ComposeEditText body;
	private Menu menu;
	private AccountSession session;
	private NBWForumOptionsRepository repository;
	private Map<String, AccountSession> sessions;
	private Map<String, Instance> instances;
	private Map<String, List<EmojiCategory>> emojis;
	private int activityTheme=R.style.Theme_Mastodon_Light;
	private AccountSession previousSession;
	private Instance previousInstance;
	private List<EmojiCategory> previousEmojis;

	/** Transport only: no replacement fragment, repository, menu, validity or request body. */
	@Implements(MastodonAPIRequest.class)
	public static class LocalRequests{
		@RealObject MastodonAPIRequest<?> request;
		public static final List<MastodonAPIRequest<?>> sent=new ArrayList<>();
		public static final List<String> accounts=new ArrayList<>();
		@Implementation public MastodonAPIRequest<?> exec(String account){
			sent.add(request);
			accounts.add(account);
			return request;
		}
		@Implementation public MastodonAPIRequest<?> execNoAuth(String domain){
			throw new AssertionError("Unexpected unauthenticated/network fetch: "+request.getClass().getSimpleName());
		}
		@Implementation public MastodonAPIRequest<?> exec(String domain, Token token){
			throw new AssertionError("Unexpected instance/network fetch: "+request.getClass().getSimpleName());
		}
	}

	@Before
	@SuppressWarnings("unchecked")
	public void setUp() throws Exception{
		// Prevent the real sending overlay/editor's preDraw loop from advancing forever.
		org.robolectric.shadows.ShadowChoreographer.setPaused(true);
		Context context=RuntimeEnvironment.getApplication();
		MastodonApp.context=context;
		V.setApplicationContext(context);
		ShadowApplication appShadow=Shadow.extract(context);
		appShadow.denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
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
		session.self.avatar=session.self.avatarStatic="android.resource://"+context.getPackageName()+"/"+R.drawable.ic_nbw;
		session.infoLastUpdated=System.currentTimeMillis();
		session.getRawLocalPreferences().edit().clear().commit();
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
		previousEmojis=emojis.put(session.domain, List.of(new EmojiCategory("Offline", List.of())));
		repository=session.getNBWForumOptionsRepository();
		LocalRequests.sent.clear();
		LocalRequests.accounts.clear();
		ShadowToast.reset();
	}

	@After
	public void tearDown() throws Exception{
		try{
			closeScreen();
			if(repository!=null) repository.deactivate();
		}finally{
			if(sessions!=null) restore(sessions, session.getID(), previousSession);
			if(instances!=null) restore(instances, session.domain, previousInstance);
			if(emojis!=null) restore(emojis, session.domain, previousEmojis);
			if(session!=null) session.getRawLocalPreferences().edit().clear().commit();
			LocalRequests.sent.clear();
			LocalRequests.accounts.clear();
			GlobalUserPreferences.getPrefs().edit().clear().putBoolean("perAccountMigrationDone", true).commit();
			GlobalUserPreferences.load();
			MastodonApp.context=null;
			org.robolectric.shadows.ShadowChoreographer.setPaused(false);
		}
	}

	@Test
	public void typedRepliesPublishLocallyAcrossRealMetadataAvailabilityStates() throws Exception{
		for(String id:List.of("p_000123", "c_opaque_007", "nbw_123", "nbw_123_456")){
			for(String state:List.of("unavailable", "loading", "failed", "local_only", "read_only", "full")){
				repository.deactivate();
				cacheGeo();
				launch(id);
				metadata(state);
				setBody(BODY);
				assertEquals(View.GONE, fragment.getView().findViewById(R.id.btn_visibility).getVisibility());
				assertEquals(View.GONE, fragment.getView().findViewById(R.id.btn_location).getVisibility());
				clickPublish();
				assertReplyRequest(id, BODY);
				fragment.updatePublishButtonState();
				assertDisabledMenuDoesNotSend();
				Status parent=(Status)field(ComposeFragment.class, "replyTo").get(fragment);
				succeed(last(CreateStatus.class), status("c_created"));
				assertEquals(1, parent.repliesCount);
				assertNull(field(ComposeFragment.class, "sendingOverlay").get(fragment));
				assertEquals("Remote sync outcomes must not toast on local reply success", 0, ShadowToast.shownToastCount());
			}
		}
	}

	@Test
	public void realReplyMenuCallbackHasNoSeparateBindingOrAiClickGate() throws Exception{
		launch("c_opaque_007");
		metadata("loading");
		setBody(BODY);
		assertTrue(fragment.onOptionsItemSelected(publishItem()));
		idle();
		assertReplyRequest("c_opaque_007", BODY);
	}

	@Test
	public void emptyOverLimitAndActuallyInFlightRepliesDisableNativePublishing() throws Exception{
		launch("p_123");
		for(String invalid:List.of("", " \n\t ", "x".repeat(501))){
			setBody(invalid);
			assertDisabledMenuDoesNotSend();
		}
		setBody("x".repeat(500));
		assertTrue("Exactly the local limit is valid", publishItem().isEnabled());
		clickPublish(); // The real pending CreateStatus, not a fake AI/binding flag, owns in-flight state.
		fragment.updatePublishButtonState();
		assertDisabledMenuDoesNotSend();
		assertTrue(fragment.onOptionsItemSelected(publishItem()));
		assertEquals(1, count(CreateStatus.class));
	}

	@Test
	public void contentWarningEditsCountTowardLocalLimitAndSurviveRequestSerialization() throws Exception{
		for(String parent:new String[]{null, "c_123"}){
			launch(parent);
			setBody("x".repeat(499));
			assertTrue(fragment.getView().findViewById(R.id.btn_spoiler).performClick());
			EditText warning=fragment.getView().findViewById(R.id.content_warning);
			warning.setText("CW");
			assertDisabledMenuDoesNotSend();
			warning.setText("C");
			clickPublish();
			JsonObject wire=json(last(CreateStatus.class));
			assertEquals("C", wire.get("spoiler_text").getAsString());
			assertEquals("x".repeat(499), wire.get("status").getAsString());
			if(parent!=null) assertReplyRequest(parent, "x".repeat(499));
		}
	}

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	public void unfinishedAttachmentsDisableRepliesButCompletedMediaOnlyReplyCanSend() throws Exception{
		launch("c_123");
		setBody(BODY);
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
		// Restore a real upload draft/thumbnail, never replace the media validity methods.
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
	public void ordinaryPostsNeverWaitForBindingCapabilitiesOptionsConsentOrAi() throws Exception{
		for(String state:List.of("unavailable", "loading", "options_loading", "failed", "local_only", "read_only", "full")){
			repository.deactivate();
			launch(null);
			metadata(state);
			setBody("  "+BODY+"  ");
			int metadataCalls=count(NBWAppRequest.class);
			clickPublish();
			assertEquals(1, count(CreateStatus.class));
			assertEquals(0, count(GetOwnAccount.class));
			assertEquals(0, count(RecommendNBWForum.class));
			assertEquals("Publish does not start or wait for another metadata/consent request", metadataCalls, count(NBWAppRequest.class));
			JsonObject wire=json(last(CreateStatus.class));
			assertEquals("ai", wire.get("nbw_classification").getAsString());
			assertFalse(wire.has("nbw_fid"));
			assertFalse(wire.has("nbw_typeid"));
			assertEquals("  "+BODY+"  ", wire.get("status").getAsString());
			assertEquals(0, appCount("/api/app/nbw/fallback-consent"));
		}
	}

	@Test
	public void manualPublishingFixtureRequiresReadyOptionsAndCompleteLegalPairAndKeepsExplicitGeo() throws Exception{
		assertFalse(repository.select(NBWSelection.manual(27, 41)));
		metadata("options_loading");
		assertFalse(repository.select(NBWSelection.manual(27, 41)));
		succeed(lastAppRequest(OPTIONS), NBWV2RepositoryTest.options());
		assertEquals(NBWForumOptionsRepository.State.READY, repository.getSnapshot().state);
		for(NBWSelection invalid:List.of(NBWSelection.manual(27, 0), NBWSelection.manual(27, 42), NBWSelection.manual(18, 41), NBWSelection.manual(999, 1)))
			assertFalse(repository.select(invalid));
		assertTrue(repository.select(NBWSelection.manual(27, 41)));
		cacheGeo();
		launch(null); // Snapshot is consumed by the real fragment; this is not a menu click claim.
		setBody(BODY);
		chooseLocation(activity.getString(R.string.location_district, "Offline district"), true);
		assertEquals("分享 / 经验分享", ((android.widget.TextView)fragment.getView().findViewById(R.id.visibility_text1)).getText().toString());
		clickPublish();
		JsonObject wire=json(last(CreateStatus.class));
		assertEquals("manual", wire.get("nbw_classification").getAsString());
		assertEquals(27, wire.get("nbw_fid").getAsInt());
		assertEquals(41, wire.get("nbw_typeid").getAsInt());
		assertEquals("Offline province", wire.get("geo_province").getAsString());
		assertEquals("Offline city", wire.get("geo_city").getAsString());
		assertEquals("Offline district", wire.get("geo_district").getAsString());
		assertEquals(0, count(GetOwnAccount.class));
		assertEquals(0, count(RecommendNBWForum.class));
		assertEquals(0, appCount("/api/app/nbw/fallback-consent"));
	}

	@Test
	public void anExpiredManualPairNeverLeaksRemoteIdsAndStillAllowsLocalPublishing() throws Exception{
		manualPublishingFixture();
		repository.deactivate(); // The catalog really disappears; no field mutation or fallback selection.
		launch(null);
		setBody(BODY);
		clickPublish();
		JsonObject wire=json(last(CreateStatus.class));
		assertEquals("manual", wire.get("nbw_classification").getAsString());
		assertFalse(wire.has("nbw_fid"));
		assertFalse(wire.has("nbw_typeid"));
	}

	@Test
	@GraphicsMode(GraphicsMode.Mode.NATIVE)
	public void sixToolbarButtonsKeepEqual24DpGlyphsAndScrollWithPinnedCounterAt320Dp() throws Exception{
		launch(null);
		ImageButton cardButton=insertFriendCardButton();
		android.view.ViewGroup buttons=(android.view.ViewGroup)cardButton.getParent();
		assertEquals(6, buttons.getChildCount());
		assertTrue(buttons.getParent() instanceof HorizontalScrollView);
		HorizontalScrollView scroller=(HorizontalScrollView)buttons.getParent();
		View row=(View)scroller.getParent();
		View counter=fragment.getView().findViewById(R.id.char_counter);
		assertSame(row, counter.getParent());
		row.measure(View.MeasureSpec.makeMeasureSpec(V.dp(320), View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(V.dp(48), View.MeasureSpec.EXACTLY));
		row.layout(0, 0, V.dp(320), V.dp(48));
		String geometry="density="+row.getResources().getDisplayMetrics().density+", 320dp="+V.dp(320)
				+", row="+row.getWidth()+", buttons="+buttons.getWidth()+", viewport="+scroller.getWidth()
				+", counter="+counter.getWidth();
		assertEquals(geometry, V.dp(320), row.getWidth());
		for(int i=0; i<buttons.getChildCount(); i++){
			assertTrue(buttons.getChildAt(i) instanceof ImageButton);
			ImageButton button=(ImageButton)buttons.getChildAt(i);
			assertEquals(V.dp(36), button.getMeasuredWidth());
			assertEquals(V.dp(36), button.getMeasuredHeight());
			assertEquals(V.dp(6), button.getPaddingLeft());
			assertEquals(V.dp(6), button.getPaddingRight());
			assertEquals(geometry+", button="+i, V.dp(24), button.getMeasuredWidth()-button.getPaddingLeft()-button.getPaddingRight());
			assertEquals(ImageView.ScaleType.CENTER_INSIDE, button.getScaleType());
			assertNotNull(button.getContentDescription());
			assertNotNull(button.getTooltipText());
		}
		assertTrue("320dp retains scrollable buttons: "+geometry, buttons.getWidth()>scroller.getWidth());
		assertTrue(geometry, counter.getWidth()>0);
		assertTrue(geometry, counter.getRight()<=V.dp(320));
		int counterLeft=counter.getLeft();
		scroller.scrollTo(buttons.getWidth(), 0);
		assertTrue(geometry, scroller.getScrollX()>0);
		assertTrue("Sixth button remains reachable: "+geometry, cardButton.getRight()-scroller.getScrollX()<=scroller.getWidth());
		assertEquals(geometry, counterLeft, counter.getLeft());
		assertEquals(View.VISIBLE, body.getVisibility());
	}

	@Test
	public void insertPreviewRemoveAndCardOnlyPublishUseCurrentAccountAndNeverSyncNbw() throws Exception{
		manualPublishingFixture(); // A real previous manual preference must not classify a friend card.
		launch(null);
		assertFriendCardAction(R.string.friend_card_compose_insert);
		clickInsertFriendCard();
		assertEquals(session.getID(), LocalRequests.accounts.get(LocalRequests.accounts.size()-1));
		FriendCardResponses.Me response=new FriendCardResponses.Me();
		response.card=friendCard();
		succeed(last(FriendCardRequest.class), response);
		assertEquals(View.VISIBLE, fragment.getView().findViewById(R.id.compose_friend_card).getVisibility());
		assertEquals("Current card", ((android.widget.TextView)fragment.getView().findViewById(R.id.compose_friend_card_title)).getText().toString());
		assertFriendCardAction(R.string.friend_card_compose_replace);
		assertEquals(View.GONE, fragment.getView().findViewById(R.id.btn_visibility).getVisibility());
		assertEquals(View.GONE, fragment.getView().findViewById(R.id.newbabyworld_card).getVisibility());
		assertTrue(publishItem().isEnabled());
		assertTrue(fragment.getView().findViewById(R.id.compose_friend_card_remove).performClick());
		assertEquals(View.GONE, fragment.getView().findViewById(R.id.compose_friend_card).getVisibility());
		assertFriendCardAction(R.string.friend_card_compose_insert);
		assertDisabledMenuDoesNotSend();
		clickInsertFriendCard();
		succeed(last(FriendCardRequest.class), response);
		clickPublish();
		JsonObject wire=json(last(CreateStatus.class));
		assertEquals("current-card", wire.get("friend_card_id").getAsString());
		assertEquals("", wire.get("status").getAsString());
		assertNoClassification(wire);
		assertEquals(0, count(GetOwnAccount.class));
		assertEquals(0, count(RecommendNBWForum.class));
		assertEquals(0, count(NBWAppRequest.class));
	}

	@Test
	public void editingFriendCardPreservesReferenceUntilRemoveSendsExplicitNull() throws Exception{
		launch(null, true);
		assertEquals(BODY, body.getText().toString());
		assertEquals(View.VISIBLE, fragment.getView().findViewById(R.id.compose_friend_card).getVisibility());
		clickPublish();
		JsonObject unchanged=json(last(EditStatus.class));
		assertFalse("Omission preserves the original link", unchanged.has("friend_card_id"));
		assertNoClassification(unchanged);
		launch(null, true);
		assertTrue(fragment.getView().findViewById(R.id.compose_friend_card_remove).performClick());
		clickPublish();
		JsonObject removed=json(last(EditStatus.class));
		assertTrue(removed.has("friend_card_id"));
		assertTrue("Null survives actual serialization", removed.get("friend_card_id").isJsonNull());
		assertNoClassification(removed);
		assertEquals(0, count(GetOwnAccount.class));
		assertEquals(0, count(RecommendNBWForum.class));
	}

	@Test
	public void failedInsertDoesNotInventAnEmptyCardOrEnableCardOnlyPublishing() throws Exception{
		launch(null);
		clickInsertFriendCard();
		callback(last(FriendCardRequest.class)).onError(new MastodonErrorResponse("Unavailable", 503, null));
		idle();
		assertEquals(View.GONE, fragment.getView().findViewById(R.id.compose_friend_card).getVisibility());
		assertDisabledMenuDoesNotSend();
		assertEquals(0, count(CreateStatus.class));
	}

	@Test
	public void ordinaryEditPreservesSourceFieldsAndNeverImplicitlyRepostsOrGeolocates() throws Exception{
		manualPublishingFixture();
		cacheGeo(); // A different current cached location must not overwrite source metadata.
		Status edited=status("p_edit");
		edited.visibility=StatusPrivacy.PRIVATE;
		edited.sensitive=true; edited.mentalCrisis=true; edited.language="zh"; edited.spoilerText="Original CW";
		Attachment audio=new Attachment();
		audio.id="kept-audio"; audio.type=Attachment.Type.AUDIO; audio.description="Original alt";
		edited.mediaAttachments=List.of(audio);
		GetStatusSourceText.Response source=source(edited);
		source.nbwFid=26; source.nbwTid=123L; source.nbwSyncEnabled=true;
		source.geoProvince="Original province"; source.geoCity="Original city";
		launch(null, false, edited, source);
		assertEquals(View.GONE, fragment.getView().findViewById(R.id.btn_visibility).getVisibility());
		setBody("Edited body");
		clickPublish();
		JsonObject patch=json(last(EditStatus.class));
		assertEquals("Edited body", patch.get("status").getAsString());
		assertEquals("zh", patch.get("language").getAsString());
		assertEquals("Original CW", patch.get("spoiler_text").getAsString());
		assertTrue(patch.get("sensitive").getAsBoolean());
		assertTrue(patch.get("mental_crisis").getAsBoolean());
		assertEquals("kept-audio", patch.getAsJsonArray("media_ids").get(0).getAsString());
		assertNoClassification(patch);
		for(String key:List.of("geo_province", "geo_city", "geo_district", "visibility")) assertFalse(key, patch.has(key));
		assertEquals(0, count(GetOwnAccount.class));
		assertEquals(0, count(RecommendNBWForum.class));
		assertEquals(0, count(CreateStatus.class));
	}

	@Test
	public void legacyEditWithoutSourceDoesNotRequireMetadataOrInventRemoteEditIntent() throws Exception{
		launch(null, false, status("p_legacy"), null);
		clickPublish();
		JsonObject patch=json(last(EditStatus.class));
		assertNoClassification(patch);
		for(String key:List.of("geo_province", "geo_city", "geo_district", "visibility")) assertFalse(key, patch.has(key));
		assertEquals(0, count(GetOwnAccount.class));
		assertEquals(0, count(NBWAppRequest.class));
		assertEquals(0, count(RecommendNBWForum.class));
		assertEquals(0, count(CreateStatus.class));
	}

	@Test
	public void commentEditPreservesOpaqueParentAndBypassesRootClassificationAndGeo() throws Exception{
		cacheGeo();
		for(String parent:List.of("p_123", "c_456", "nbw_123", "nbw_123_456")){
			Status edited=status("c_edit");
			edited.inReplyToId=parent;
			launch(null, false, edited, null);
			assertEquals(View.GONE, fragment.getView().findViewById(R.id.btn_visibility).getVisibility());
			assertEquals(View.GONE, fragment.getView().findViewById(R.id.btn_location).getVisibility());
			clickPublish();
			JsonObject patch=json(last(EditStatus.class));
			assertEquals(parent, patch.get("in_reply_to_id").getAsString());
			assertNoClassification(patch);
			for(String key:List.of("geo_province", "geo_city", "geo_district")) assertFalse(key, patch.has(key));
			assertEquals(0, count(GetOwnAccount.class));
			assertEquals(0, count(RecommendNBWForum.class));
			assertEquals(0, count(CreateStatus.class));
		}
	}

	@Test
	public void remoteReplySyncFailureIsSilentAfterSuccessfulLocalCreation() throws Exception{
		for(String id:List.of("nbw_123", "nbw_123_456")){
			ShadowToast.reset();
			launch(id);
			setBody(BODY);
			clickPublish();
			assertReplyRequest(id, BODY);
			Status result=status("c_created");
			result.inReplyToId=id;
			result.nbwSync=new Status.NBWSync();
			result.nbwSync.state="unsupported";
			result.nbwSync.reason="reply_api_unavailable";
			succeed(last(CreateStatus.class), result);
			assertEquals(0, ShadowToast.shownToastCount());
			assertNull(ShadowToast.getTextOfLatestToast());
			assertNull(field(ComposeFragment.class, "sendingOverlay").get(fragment));
		}
	}

	@Test
	public void legalManualPairBodyCwAndChosenGeoSurviveRealActivityRecreation() throws Exception{
		manualPublishingFixture();
		cacheGeo();
		launch(null);
		setBody(BODY);
		assertTrue(fragment.getView().findViewById(R.id.btn_spoiler).performClick());
		((EditText)fragment.getView().findViewById(R.id.content_warning)).setText("Saved CW");
		chooseLocation(activity.getString(R.string.location_district, "Offline district"), true);
		Bundle fragmentState=new Bundle();
		fragment.onSaveInstanceState(fragmentState);
		assertEquals("manual", fragmentState.getString("nbwV2Mode"));
		assertEquals(27, fragmentState.getInt("nbwV2Fid"));
		assertEquals(41, fragmentState.getInt("nbwV2Typeid"));
		recreate();
		assertEquals(BODY, body.getText().toString());
		assertEquals("Saved CW", ((EditText)fragment.getView().findViewById(R.id.content_warning)).getText().toString());
		assertEquals("分享 / 经验分享", ((android.widget.TextView)fragment.getView().findViewById(R.id.visibility_text1)).getText().toString());
		clickPublish();
		JsonObject wire=json(last(CreateStatus.class));
		assertEquals(27, wire.get("nbw_fid").getAsInt());
		assertEquals(41, wire.get("nbw_typeid").getAsInt());
		assertEquals("Saved CW", wire.get("spoiler_text").getAsString());
		assertEquals("Offline district", wire.get("geo_district").getAsString());
	}

	@Test
	public void explicitEditGeoRemovalSurvivesRecreationAndSendsNullNotAnImplicitRepost() throws Exception{
		Status edited=status("p_recreate");
		GetStatusSourceText.Response source=source(edited);
		source.geoProvince="Original province"; source.geoCity="Original city"; source.geoDistrict="Original district";
		launch(null, false, edited, source);
		chooseLocation(activity.getString(R.string.location_none), false);
		recreate();
		assertEquals(activity.getString(R.string.location_none), ((android.widget.TextView)fragment.getView().findViewById(R.id.location_text)).getText().toString());
		clickPublish();
		JsonObject patch=json(last(EditStatus.class));
		assertNoClassification(patch);
		for(String key:List.of("geo_province", "geo_city", "geo_district")){
			assertTrue(key, patch.has(key));
			assertTrue(key, patch.get(key).isJsonNull());
		}
		assertEquals(0, count(CreateStatus.class));
	}

	/** Actual forSession transport installs the callbacks; no repository fields are overwritten. */
	private void metadata(String state) throws Exception{
		if("unavailable".equals(state)) return;
		repository.activate();
		assertEquals(1, appCount(CAPS));
		if("loading".equals(state)) return;
		if("failed".equals(state)){
			callback(lastAppRequest(CAPS)).onError(new MastodonErrorResponse("Offline metadata unavailable", 503, null));
			idle();
			assertEquals(NBWForumOptionsRepository.State.LOCAL_ONLY, repository.getSnapshot().state);
			return;
		}
		String mode="options_loading".equals(state) ? "full" : state;
		succeed(lastAppRequest(CAPS), NBWV2RepositoryTest.capabilities(mode));
		if("local_only".equals(mode)){
			assertEquals(0, appCount(OPTIONS));
			return;
		}
		assertEquals(1, appCount(OPTIONS));
		if(!"options_loading".equals(state)){
			succeed(lastAppRequest(OPTIONS), NBWV2RepositoryTest.options());
			assertEquals(NBWForumOptionsRepository.State.READY, repository.getSnapshot().state);
		}
	}

	private void manualPublishingFixture() throws Exception{
		metadata("full");
		assertEquals(NBWForumOptionsRepository.State.READY, repository.getSnapshot().state);
		assertTrue(repository.select(NBWSelection.manual(27, 41)));
	}

	private void cacheGeo(){
		RuntimeEnvironment.getApplication().getSharedPreferences("location_cache", Context.MODE_PRIVATE).edit()
				.putString("province", "Offline province").putString("city", "Offline city")
				.putString("district", "Offline district").putLong("last_update", System.currentTimeMillis()).commit();
	}

	/** Real existing geo popup/holder dispatch. This helper is not used for NBW classification. */
	private void chooseLocation(String title, boolean allowCachedDistrict){
		if(allowCachedDistrict){
			ShadowApplication appShadow=Shadow.extract(RuntimeEnvironment.getApplication());
			appShadow.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION);
		}
		assertTrue(fragment.getView().findViewById(R.id.btn_location).performClick());
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
			assertTrue("Real location rows must be bound", holder instanceof ListItemViewHolder);
			ListItemViewHolder<?> item=(ListItemViewHolder<?>)holder;
			if(title.equals(item.getItem().title)){
				assertTrue(item.isEnabled());
				item.onClick();
				idle();
				assertFalse(popup.isShowing());
				return;
			}
		}
		fail("Missing real location row "+title);
	}

	private FriendCard friendCard(){
		FriendCard card=new FriendCard();
		card.id="current-card"; card.schemaVersion=1; card.title="Current card"; card.state="active";
		return card;
	}

	private GetStatusSourceText.Response source(Status edited){
		GetStatusSourceText.Response source=new GetStatusSourceText.Response();
		source.id=edited.id; source.text=BODY; source.spoilerText=edited.spoilerText;
		return source;
	}

	private void launch(String replyId) throws Exception{ launch(replyId, false); }
	private void launch(String replyId, boolean editingFriendCard) throws Exception{ launch(replyId, editingFriendCard, null, null); }
	private void launch(String replyId, boolean editingFriendCard, Status editedStatus, GetStatusSourceText.Response source) throws Exception{
		closeScreen();
		LocalRequests.sent.clear();
		LocalRequests.accounts.clear();
		controller=Robolectric.buildActivity(FragmentStackActivity.class);
		activity=controller.get();
		activity.setTheme(activityTheme);
		controller.create();
		fragment=new ComposeFragment();
		Bundle args=new Bundle();
		args.putString("account", session.getID());
		if(replyId!=null) args.putParcelable("replyTo", Parcels.wrap(status(replyId)));
		if(editingFriendCard){
			Status edited=status("p_edit");
			edited.friendCard=new FriendCardAttachment();
			edited.friendCard.schemaVersion=1; edited.friendCard.mode="link"; edited.friendCard.id="current-card";
			edited.friendCard.available=true; edited.friendCard.title="Current card";
			args.putParcelable("editStatus", Parcels.wrap(edited));
			args.putString("sourceText", BODY); args.putString("sourceSpoiler", "");
			args.putString("sourceFriendCardId", "current-card");
		}
		if(editedStatus!=null){
			args.putParcelable("editStatus", Parcels.wrap(editedStatus));
			args.putString("sourceText", BODY);
			args.putString("sourceSpoiler", editedStatus.spoilerText);
			if(source!=null) args.putParcelable("editSource", Parcels.wrap(source));
		}
		fragment.setArguments(args);
		activity.showFragment(fragment);
		controller.start().resume().visible();
		idle();
		assertTrue(fragment.isAdded());
		bindViews();
		assertEquals("Opening an editor only observes metadata; it must not fetch it", 0, count(NBWAppRequest.class));
	}

	/** Shared real-editor fixture for the Compose popup interaction/render tests. */
	public ComposeFragment launchClassificationEditor() throws Exception{
		launch(null);
		return fragment;
	}
	public ComposeFragment launchClassificationEditor(int theme) throws Exception{
		activityTheme=theme;
		return launchClassificationEditor();
	}
	public AccountSession classificationSession(){ return session; }
	public NBWForumOptionsRepository classificationRepository(){ return repository; }
	public FragmentStackActivity classificationActivity(){ return activity; }

	private void bindViews(){
		assertNotNull(fragment.getView());
		body=fragment.getView().findViewById(R.id.toot_text);
		assertNotNull(body);
		assertEquals(View.VISIBLE, body.getVisibility());
		assertTrue(body.isEnabled());
		assertNotNull(body.getKeyListener());
		Toolbar toolbar=fragment.getView().findViewById(R.id.toolbar);
		assertNotNull(toolbar);
		menu=toolbar.getMenu();
		assertNull("Friend-card insertion belongs to the bottom toolbar", menu.findItem(R.id.compose_insert_friend_card));
		assertNotNull(insertFriendCardButton());
		assertTrue(insertFriendCardButton().hasOnClickListeners());
		assertNotNull(publishItem());
	}

	private void recreate(){
		Bundle saved=new Bundle();
		controller.pause().saveInstanceState(saved).stop().destroy();
		controller=Robolectric.buildActivity(FragmentStackActivity.class);
		activity=controller.get();
		activity.setTheme(R.style.Theme_Mastodon_Light);
		controller.create(saved).start().restoreInstanceState(saved).postCreate(saved).resume().visible();
		idle();
		fragment=(ComposeFragment)activity.getFragmentManager().getFragments().stream().filter(ComposeFragment.class::isInstance).findFirst().orElseThrow();
		bindViews();
	}

	private Status status(String id){
		Status status=Status.ofFake(id, "<p>Offline parent</p>", Instant.parse("2026-01-01T00:00:00Z"));
		status.account=session.self;
		status.uri="https://"+session.domain+"/statuses/"+id;
		return status;
	}

	private void setBody(String text){ body.getText().replace(0, body.length(), text); idle(); }
	private ImageButton insertFriendCardButton(){ return fragment.getView().findViewById(R.id.compose_insert_friend_card); }
	private void clickInsertFriendCard(){
		assertTrue("Dispatch the real bottom-toolbar listener", insertFriendCardButton().performClick());
		idle();
	}
	private void assertFriendCardAction(int title){
		String expected=activity.getString(title);
		assertEquals(expected, insertFriendCardButton().getContentDescription().toString());
		assertEquals(expected, insertFriendCardButton().getTooltipText().toString());
	}
	private MenuItem publishItem(){ return menu.findItem(R.id.publish); }
	private void clickPublish(){
		assertTrue("Native publish item must be enabled", publishItem().isEnabled());
		assertTrue("Native menu dispatches production listener", menu.performIdentifierAction(R.id.publish, 0));
		idle();
	}
	private void assertDisabledMenuDoesNotSend(){
		assertFalse(publishItem().isEnabled());
		int before=LocalRequests.sent.size();
		assertFalse("Native disabled item must not dispatch", menu.performIdentifierAction(R.id.publish, 0));
		idle();
		assertEquals(before, LocalRequests.sent.size());
	}
	private static void assertNoClassification(JsonObject wire){
		for(String key:List.of("nbw_classification", "nbw_fid", "nbw_typeid")) assertFalse(key, wire.has(key));
	}
	private void assertReplyRequest(String id, String text) throws Exception{
		assertEquals(1, count(CreateStatus.class));
		assertEquals(0, count(GetOwnAccount.class));
		assertEquals(0, count(RecommendNBWForum.class));
		CreateStatus.Request request=sentStatus();
		assertEquals(id, request.inReplyToId);
		assertEquals(text, request.status);
		assertEquals(StatusPrivacy.PUBLIC, request.visibility);
		JsonObject wire=json(last(CreateStatus.class));
		assertEquals(id, wire.get("in_reply_to_id").getAsString());
		assertNoClassification(wire);
		for(String key:List.of("geo_province", "geo_city", "geo_district")) assertFalse(key, wire.has(key));
		assertEquals(0, appCount("/api/app/nbw/fallback-consent"));
		assertTrue(LocalRequests.accounts.stream().allMatch(session.getID()::equals));
	}
	private CreateStatus.Request sentStatus() throws Exception{
		return org.joinmastodon.android.api.MastodonAPIController.gson.fromJson(json(last(CreateStatus.class)), CreateStatus.Request.class);
	}
	public static int count(Class<?> type){ return (int)LocalRequests.sent.stream().filter(type::isInstance).count(); }
	public static int appCount(String path){
		return (int)LocalRequests.sent.stream().filter(NBWAppRequest.class::isInstance).filter(r->path.equals(r.getURL().getPath())).count();
	}
	public static NBWAppRequest<?> lastAppRequest(String path){
		for(int i=LocalRequests.sent.size()-1; i>=0; i--){
			MastodonAPIRequest<?> request=LocalRequests.sent.get(i);
			if(request instanceof NBWAppRequest<?> app && path.equals(app.getURL().getPath())) return app;
		}
		throw new AssertionError("No real NBWAppRequest for "+path);
	}
	private static <T extends MastodonAPIRequest<?>> T last(Class<T> type){
		for(int i=LocalRequests.sent.size()-1; i>=0; i--)
			if(type.isInstance(LocalRequests.sent.get(i))) return type.cast(LocalRequests.sent.get(i));
		throw new AssertionError("No actual "+type.getSimpleName()+" request was executed");
	}
	public static JsonObject json(MastodonAPIRequest<?> request) throws Exception{
		Buffer buffer=new Buffer();
		request.getRequestBody().writeTo(buffer);
		return JsonParser.parseString(buffer.readUtf8()).getAsJsonObject();
	}
	@SuppressWarnings("unchecked")
	public static Callback<Object> callback(MastodonAPIRequest<?> request) throws Exception{
		Callback<Object> callback=(Callback<Object>)field(APIRequest.class, "callback").get(request);
		assertNotNull("Production request must install its callback", callback);
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
