package org.joinmastodon.android.fragments;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.CacheController;
import org.joinmastodon.android.api.HomeTimelineSnapshotCacheTest;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.api.requests.statuses.GetStatusesByIDs;
import org.joinmastodon.android.api.requests.statuses.TranslateStatus;
import org.joinmastodon.android.api.requests.timelines.GetAllTimeline;
import org.joinmastodon.android.api.requests.translation.GetTranslationPreferences;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.events.AccountLoggedOutEvent;
import org.joinmastodon.android.model.Instance;
import org.joinmastodon.android.model.InstanceV1;
import org.joinmastodon.android.model.Quote;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.TranslationPreferences;
import org.joinmastodon.android.translation.PostTranslationCoordinator;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
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
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import androidx.recyclerview.widget.RecyclerView;
import me.grishka.appkit.FragmentStackActivity;
import me.grishka.appkit.api.APIRequest;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.fragments.BaseRecyclerFragment;
import me.grishka.appkit.utils.V;

import static org.junit.Assert.*;

/** Actual Home fragment/SQLite/AppKit callbacks and native rendering. Only HTTP is captured. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class, qualifiers="en-w320dp-h720dp-mdpi", shadows=HomeTimelineHistoryTest.OfflineRequests.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class HomeTimelineHistoryTest{
	@Implements(MastodonAPIRequest.class) public static class OfflineRequests{
		@RealObject MastodonAPIRequest<?> request;
		static final List<MastodonAPIRequest<?>> sent=new ArrayList<>(), canceled=new ArrayList<>();
		@Implementation public MastodonAPIRequest<?> exec(String account){ sent.add(request); return request; }
		@Implementation public void cancel(){ canceled.add(request); }
	}
	public static class HostActivity extends FragmentStackActivity{}
	private static final class ObservedCache extends CacheController{
		int reads, callbacks;
		List<String> lastSnapshotIds;
		ObservedCache(String account){ super(account); }
		@Override public void getHomeTimelineSnapshot(HomeTimelineLease lease, java.util.function.Consumer<org.joinmastodon.android.api.cache.HomeTimelineSnapshot> callback){
			reads++;
			super.getHomeTimelineSnapshot(lease, snapshot->{
				callbacks++;
				lastSnapshotIds=snapshot==null ? null : snapshot.posts.stream().map(status->status.id).toList();
				callback.accept(snapshot);
			});
		}
	}
	private AccountSession session, previousSession;
	private Map<String,AccountSession> sessions;
	private Map<String,Instance> instances;
	private Instance previousInstance;
	private String previousActive;
	private boolean previousCWs, previousLiquid;
	private ActivityController<HostActivity> lifecycle;
	private HostActivity activity;
	private HomeTimelineFragment fragment;
	private CacheController cache;
	private int theme=R.style.Theme_Mastodon_Light;
	private static Field field(Class<?> type,String name) throws Exception{ return HomeTimelineSnapshotCacheTest.field(type,name); }
	private static void drain() throws Exception{ HomeTimelineSnapshotCacheTest.drain(); }
	/** The initial load is asynchronous: AppKit's deferred load, the database lease read and the
	 * network dispatch each cross a queue. Settle every hop with real production callbacks. */
	private void settle() throws Exception{
		for(int i=0;i<3;i++){
			ShadowLooper.idleMainLooper(32,TimeUnit.MILLISECONDS);
			drain();
			layout();
			ShadowLooper.idleMainLooper(32,TimeUnit.MILLISECONDS);
		}
	}
	@Before @SuppressWarnings("unchecked") public void setup() throws Exception{
		ShadowChoreographer.setPaused(true); MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context);
		org.joinmastodon.android.ui.utils.UiUtils.updateLocalizedDateFormatters(MastodonApp.context);
		var manager=AccountSessionManager.getInstance(); sessions=(Map<String,AccountSession>)field(AccountSessionManager.class,"sessions").get(manager);
		instances=(Map<String,Instance>)field(AccountSessionManager.class,"instances").get(manager);
		session=HomeTimelineSnapshotCacheTest.session("home-history.offline.example.test","home-history-42");
		session.self.sponsor=new org.joinmastodon.android.model.Account.PublicSponsor(); session.self.sponsor.active=true; session.self.sponsor.permanent=true;
		previousSession=sessions.put(session.getID(),session); previousActive=manager.getLastActiveAccountID(); field(AccountSessionManager.class,"lastActiveAccountID").set(manager,session.getID());
		InstanceV1 instance=new InstanceV1(); instance.uri=instance.normalizedUri=session.domain; instance.version="4.3.0"; previousInstance=instances.put(session.domain,instance);
		previousCWs=GlobalUserPreferences.showCWs; GlobalUserPreferences.showCWs=true;
		previousLiquid=GlobalUserPreferences.isIosLiquidNavigationEnabled();
		cache=new ObservedCache(session.getID());
		field(AccountSession.class,"cacheController").set(session,cache);
		OfflineRequests.sent.clear(); OfflineRequests.canceled.clear();
	}
	@After public void cleanup() throws Exception{
		PostTranslationCoordinator coordinator=activity==null ? null : PostTranslationCoordinator.get(activity,session.getID()); if(coordinator!=null) coordinator.dispose();
		if(lifecycle!=null) lifecycle.close(); drain(); sessions.remove(session.getID()); cache.closeDatabase(); MastodonApp.context.deleteDatabase(session.getID()+".db");
		if(previousSession!=null) sessions.put(session.getID(),previousSession);
		if(previousInstance==null) instances.remove(session.domain); else instances.put(session.domain,previousInstance);
		field(AccountSessionManager.class,"lastActiveAccountID").set(AccountSessionManager.getInstance(),previousActive); GlobalUserPreferences.showCWs=previousCWs;
		OfflineRequests.sent.clear(); OfflineRequests.canceled.clear();
	}
	private Status post(String id){
		Status post=HomeTimelineSnapshotCacheTest.post(id); post.language="zh"; post.content="<p>A fictional afternoon walk. The park was quiet and the weather was pleasant.</p>";
		post.account.avatar=post.account.avatarStatic="https://home-history.offline.example.test/avatar/"+id+".png";
		return post;
	}
	private void seed(Status... posts) throws Exception{
		CacheController.HomeTimelineLease lease=cache.beginHomeTimelineSnapshot();
		assertNotNull("Seed lease must be available for the current account",lease);
		Object identity=field(CacheController.HomeTimelineLease.class,"identity").get(lease);
		String encoded=org.joinmastodon.android.api.cache.HomeTimelineSnapshot.encode(List.of(posts),"cached-opaque-cursor",(String)identity,System.currentTimeMillis(),false);
		assertNotNull("Fixture posts must be cache-encodable: "+(posts.length==0?"none":snapshotFields(posts[0])),encoded);
		assertTrue("Lease must be current before the write",cache.isHomeTimelineLeaseCurrent(lease));
		cache.putHomeTimelineSnapshot(lease,List.of(posts),"cached-opaque-cursor"); drain();
		assertTrue("Lease must stay current across the write",cache.isHomeTimelineLeaseCurrent(lease));
	}
	private static String snapshotFields(Status s){
		return "vis="+s.visibility+",uri="+(s.uri!=null)+",created="+(s.createdAt!=null)
				+",account="+(s.account!=null)+",accountId="+(s.account==null?null:s.account.id)
				+",username="+(s.account==null?null:s.account.username)+",acct="+(s.account==null?null:s.account.acct)
				+",media="+(s.mediaAttachments!=null)+",mentions="+(s.mentions!=null)+",tags="+(s.tags!=null)+",emojis="+(s.emojis!=null)
				+",ad="+(s.advertisement!=null)+",friendRequest="+(s.friendRequest!=null)+",friendCard="+(s.friendCard!=null)
				+",novel="+(s.novel!=null)+",album="+(s.albumUpdate!=null)+",moved="+(s.account!=null&&s.account.moved!=null)
				+",reblog="+(s.reblog!=null)+",quote="+(s.quote!=null);
	}
	private void open() throws Exception{
		lifecycle=Robolectric.buildActivity(HostActivity.class); activity=lifecycle.get(); activity.setTheme(theme); lifecycle.setup();
		fragment=new HomeTimelineFragment(); Bundle args=new Bundle(); args.putString("account",session.getID()); args.putBoolean("noAutoLoad",true); fragment.setArguments(args);
		activity.showFragment(fragment); activity.getFragmentManager().executePendingTransactions(); settle();
	}
	@SuppressWarnings("unchecked") private List<Status> rows() throws Exception{ return (List<Status>)field(BaseRecyclerFragment.class,"data").get(fragment); }
	@SuppressWarnings("unchecked") private List<Status> preloaded() throws Exception{ return (List<Status>)field(BaseRecyclerFragment.class,"preloadedData").get(fragment); }
	private List<String> ids() throws Exception{ return rows().stream().map(s->s.id).toList(); }
	private String diagnostic() throws Exception{
		String state="requests="+requests().size()+",rows="+ids()+",preloaded="+preloaded().size()
				+",pill="+(pill()==null?-1:pill().getVisibility())+",headInFlight="+field(HomeTimelineFragment.class,"homeHeadInFlight").getBoolean(fragment)
				+",historyAttempted="+field(HomeTimelineFragment.class,"homeHistoryAttempted").getBoolean(fragment)
				+",showingHistory="+field(HomeTimelineFragment.class,"showingHomeHistory").getBoolean(fragment)
				+",lease="+(field(HomeTimelineFragment.class,"homeLease").get(fragment)!=null)
				+",dataLoading="+fragment.dataLoading+",destroyed="+field(HomeTimelineFragment.class,"homeViewDestroyed").getBoolean(fragment)
				+",cacheReads="+((ObservedCache)cache).reads+",cacheCallbacks="+((ObservedCache)cache).callbacks
				+",cacheIds="+((ObservedCache)cache).lastSnapshotIds;
		return state;
	}
	private RecyclerView list() throws Exception{ return (RecyclerView)field(BaseRecyclerFragment.class,"list").get(fragment); }
	private TextView pill(){ return fragment.getView().findViewById(R.id.home_timeline_refreshing); }
	private List<GetAllTimeline> requests(){ return OfflineRequests.sent.stream().filter(GetAllTimeline.class::isInstance).map(GetAllTimeline.class::cast).toList(); }
	private <T> long count(Class<T> type){ return OfflineRequests.sent.stream().filter(type::isInstance).count(); }
	@SuppressWarnings("unchecked") private void success(MastodonAPIRequest<?> request,Object value) throws Exception{ ((Callback<Object>)field(APIRequest.class,"callback").get(request)).onSuccess(value); ShadowLooper.idleMainLooper(); layout(); }
	@SuppressWarnings("unchecked") private void failure(MastodonAPIRequest<?> request,int code) throws Exception{ ((Callback<Object>)field(APIRequest.class,"callback").get(request)).onError(new MastodonErrorResponse("Offline test error",code,null)); ShadowLooper.idleMainLooper(); layout(); }
	private void cursor(GetAllTimeline request,String cursor) throws Exception{ field(GetAllTimeline.class,"nextMaxID").set(request,cursor); }
	private void invoke(Class<?> type,String name) throws Exception{ Method method=type.getDeclaredMethod(name); method.setAccessible(true); method.invoke(fragment); }
	private void layout() throws Exception{
		if(fragment==null || fragment.getView()==null) return;
		ViewGroup host=activity.findViewById(me.grishka.appkit.R.id.fragment_wrap); host.forceLayout(); host.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY)); host.layout(0,0,320,720);
		RecyclerView list=list(); if(list!=null){ list.forceLayout(); list.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(620,View.MeasureSpec.EXACTLY)); list.layout(0,0,320,620); }
		ShadowLooper.idleMainLooper();
	}

	@Test public void uiFixtureCodecRoundTripRetainsLanguageAndAuthor() throws Exception{
		Status original=post("p_codec");
		String identity=org.joinmastodon.android.api.cache.HomeTimelineSnapshot.identity(session.getID(),session.token.accessToken);
		long now=System.currentTimeMillis();
		String encoded=org.joinmastodon.android.api.cache.HomeTimelineSnapshot.encode(List.of(original),"opaque-cursor",identity,now,false);
		assertNotNull("Ordinary UI fixture must encode",encoded);
		var json=com.google.gson.JsonParser.parseString(encoded).getAsJsonObject();
		Status decoded=org.joinmastodon.android.api.MastodonAPIController.gson.fromJson(json.getAsJsonArray("posts").get(0),Status.class);
		decoded.postprocess();
		assertEquals("zh",decoded.language);
		assertNotNull("Bounded codec must accept its own output; now="+now+", encoded="+encoded,
				org.joinmastodon.android.api.cache.HomeTimelineSnapshot.decode(encoded,identity,now,false));
	}

	@Test public void historyAppearsWithoutSpinnerWithIndependentPureTextAndNoOldCursorPaging() throws Exception{
		seed(post("p_history"),post("c_history")); open();
		assertEquals(diagnostic(),List.of("p_history","c_history"),ids()); assertEquals(diagnostic(),1,requests().size());
		assertEquals("A real database callback must hydrate the head",1,((ObservedCache)cache).callbacks);
		assertEquals(List.of("p_history","c_history"),((ObservedCache)cache).lastSnapshotIds);
		assertEquals(TextView.class,pill().getClass()); assertEquals("Refreshing…",pill().getText().toString()); assertEquals(View.VISIBLE,pill().getVisibility());
		assertFalse(pill().isClickable()); assertFalse(pill().isFocusable()); for(var drawable:pill().getCompoundDrawables()) assertNull(drawable);
		assertEquals(View.GONE,fragment.getView().findViewById(me.grishka.appkit.R.id.loading).getVisibility());
		fragment.setLiquidToolbarMode(true); assertEquals(View.VISIBLE,pill().getVisibility());
		fragment.onScrolledToLastItem(); fragment.loadData(2,20); assertEquals(1,requests().size()); assertFalse(requests().get(0).getURL().toString().contains("max_id="));
	}
	@Test public void authorityReplacesDataAndPreloaderExactlyOnceAndUsesOnlyServerCursor() throws Exception{
		seed(post("p_old")); open(); preloaded().add(post("p_stale_preload")); GetAllTimeline request=requests().get(0); cursor(request,"server-opaque+/next");
		success(request,List.of(post("p_fresh"),post("c_fresh"))); assertEquals(List.of("p_fresh","c_fresh"),ids()); assertTrue(preloaded().isEmpty()); assertEquals(View.GONE,pill().getVisibility());
		success(request,List.of(post("p_duplicate_callback"))); assertEquals(List.of("p_fresh","c_fresh"),ids());
		fragment.loadData(2,20); assertTrue(requests().get(1).getURL().toString().contains("max_id=server-opaque"));
	}
	@Test public void authoritativeEmptyClearsVisibleHistoryAndPersistedHead() throws Exception{
		seed(post("p_old")); open(); success(requests().get(0),List.of()); drain(); assertTrue(rows().isEmpty()); assertTrue(preloaded().isEmpty()); assertTrue(fragment.shouldAutomaticallyTranslateVisibleContent());
		var result=new java.util.concurrent.atomic.AtomicReference<org.joinmastodon.android.api.cache.HomeTimelineSnapshot>(); cache.getHomeTimelineSnapshot(cache.beginHomeTimelineSnapshot(),result::set); drain(); assertNull(result.get());
	}
	@Test public void networkFailureKeepsHistoryButStopsHintAndStillDeniesAutoNetwork() throws Exception{
		Status parent=post("p_old"); parent.quote=new Quote(); parent.quote.state=Quote.State.ACCEPTED; parent.quote.quotedStatus=post("p_quote"); parent.quote.quotedStatus.quote=new Quote(); parent.quote.quotedStatus.quote.quotedStatusId="p_missing"; parent.quote.quotedStatus.quote.state=Quote.State.ACCEPTED;
		seed(parent); open(); assertEquals(0,count(GetStatusesByIDs.class)); failure(requests().get(0),503);
		assertEquals(List.of("p_old"),ids()); assertEquals(View.GONE,pill().getVisibility()); assertFalse(fragment.shouldAutomaticallyTranslateVisibleContent()); assertFalse(fragment.shouldFetchMissingNestedQuotes());
		fragment.onScrolledToLastItem(); fragment.loadData(1,20); assertEquals(1,requests().size());
		invoke(HomeTimelineFragment.class,"loadNewPosts"); assertEquals(1,requests().size());
	}
	@Test public void authorizationErrorsClearHistoryAndUseExistingAuthorizationErrorUi() throws Exception{
		seed(post("p_old")); open(); failure(requests().get(0),403); drain(); assertTrue(rows().isEmpty()); assertTrue(fragment.doReauthOnErrorRetry); assertEquals(View.GONE,pill().getVisibility()); assertFalse(fragment.loaded);
	}
	@Test public void refreshInvalidatesPendingSnapshotReadAndOldResponseCannotBackfill() throws Exception{
		seed(post("p_old")); open(); GetAllTimeline old=requests().get(0); fragment.onRefresh(); GetAllTimeline fresh=requests().get(1);
		success(fresh,List.of(post("p_new"))); success(old,List.of(post("p_late"))); assertEquals(List.of("p_new"),ids()); assertEquals(2,requests().size());
	}
	@Test public void cacheReadCallbackAfterFastRefreshCannotOverwriteAuthority() throws Exception{
		seed(post("p_old")); CountDownLatch occupied=new CountDownLatch(1),release=new CountDownLatch(1);
		CacheController.databaseThread.postRunnable(()->{ occupied.countDown(); try{ release.await(); }catch(InterruptedException e){ Thread.currentThread().interrupt(); } },0); assertTrue(occupied.await(5,TimeUnit.SECONDS));
		try{
			lifecycle=Robolectric.buildActivity(HostActivity.class); activity=lifecycle.get(); activity.setTheme(theme); lifecycle.setup();
			fragment=new HomeTimelineFragment(); Bundle args=new Bundle(); args.putString("account",session.getID()); fragment.setArguments(args); activity.showFragment(fragment); activity.getFragmentManager().executePendingTransactions();
			ShadowLooper.idleMainLooper(32,TimeUnit.MILLISECONDS); layout();
			assertTrue("Initial request waits for its bounded history read",requests().isEmpty());
			fragment.onRefresh();
			assertEquals(1,requests().size());
			success(requests().get(0),List.of(post("p_fast")));
			assertEquals(List.of("p_fast"),ids());
		}finally{ release.countDown(); }
		settle(); assertEquals("A late cache read cannot overwrite the authoritative head",List.of("p_fast"),ids()); assertEquals(1,requests().size());
	}
	@Test public void switchedAccountAndLogoutRejectLateCallbacksWithoutRecreatingDatabase() throws Exception{
		seed(post("p_old")); open(); GetAllTimeline old=requests().get(0);
		field(AccountSessionManager.class,"lastActiveAccountID").set(AccountSessionManager.getInstance(),"another-account"); success(old,List.of(post("p_late"))); assertTrue(rows().isEmpty());
		field(AccountSessionManager.class,"lastActiveAccountID").set(AccountSessionManager.getInstance(),session.getID()); fragment.onRefresh(); old=requests().get(1);
		sessions.remove(session.getID()); cache.closeDatabase(); MastodonApp.context.deleteDatabase(session.getID()+".db"); success(old,List.of(post("p_after_logout"))); drain();
		assertFalse(MastodonApp.context.getDatabasePath(session.getID()+".db").exists()); assertEquals(View.GONE,pill().getVisibility());
	}
	@Test public void cancelDestroyAndReentryStopHintAndLateCallbacksAreSafe() throws Exception{
		seed(post("p_old")); open(); GetAllTimeline old=requests().get(0); fragment.cancelLoading(); assertEquals(View.GONE,pill().getVisibility()); assertFalse(fragment.shouldAutomaticallyTranslateVisibleContent());
		fragment.onRefresh(); assertEquals(2,requests().size()); success(old,List.of(post("p_late"))); assertEquals(List.of("p_old"),ids());
		GetAllTimeline inflight=requests().get(1); lifecycle.pause().stop().destroy(); lifecycle=null; success(inflight,List.of(post("p_after_destroy"))); assertTrue(OfflineRequests.canceled.contains(inflight));
		open(); assertEquals(3,requests().size()); assertEquals(List.of("p_old"),ids()); assertEquals(View.VISIBLE,pill().getVisibility());
	}
	@Test public void visibleHistoryDoesNotSendAutomaticAiEvenWithCapabilityEnabled() throws Exception{
		seed(post("p_old")); open();
		for(MastodonAPIRequest<?> request:new ArrayList<>(OfflineRequests.sent)) if(request instanceof GetTranslationPreferences){
			TranslationPreferences preferences=new TranslationPreferences(); preferences.readingLanguage="en"; preferences.revision="test"; preferences.enabled=preferences.providerConfigured=preferences.autoTranslateAllowed=preferences.autoTranslateEnabled=true; success(request,preferences);
		}
		invoke(BaseStatusListFragment.class,"checkVisibleTranslations"); assertEquals(0,count(TranslateStatus.class));
		failure(requests().get(0),503); invoke(BaseStatusListFragment.class,"checkVisibleTranslations"); assertEquals(0,count(TranslateStatus.class));
	}
	@Test public void optionalRetiredSnapshotStoreDoesNotBlockFreshNetwork() throws Exception{
		cache.new HomeSnapshotEvents().loggedOut(new AccountLoggedOutEvent(session.getID())); open(); assertEquals(1,requests().size()); success(requests().get(0),List.of(post("p_network_only"))); assertEquals(List.of("p_network_only"),ids());
	}
	@Test @GraphicsMode(GraphicsMode.Mode.NATIVE) public void actualLightHistoryAt320LargeTextRenders() throws Exception{ render(false); }
	@Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers="zh-rCN-w320dp-h720dp-mdpi") public void actualDarkChineseHistoryAt320LargeTextRenders() throws Exception{ render(true); }
	private void render(boolean dark) throws Exception{
		theme=dark ? R.style.Theme_Mastodon_Dark : R.style.Theme_Mastodon_Light;
		android.content.res.Configuration configuration=new android.content.res.Configuration(MastodonApp.context.getResources().getConfiguration()); configuration.fontScale=1.3f;
		MastodonApp.context.getResources().updateConfiguration(configuration,MastodonApp.context.getResources().getDisplayMetrics());
		seed(post("p_fictional_walk"),post("c_fictional_book")); open();
		assertEquals(dark ? "正在刷新中......" : "Refreshing…",pill().getText().toString()); assertEquals(View.VISIBLE,pill().getVisibility()); assertFalse(rows().isEmpty());
		String directory=System.getProperty("homeHistoryRenderDir"); if(directory!=null){
			File output=new File(directory,dark ? "home-history-dark-zh-320-large.png" : "home-history-light-en-320-large.png"); assertTrue(output.getParentFile().isDirectory() || output.getParentFile().mkdirs());
			View root=fragment.getView(); Bitmap bitmap=Bitmap.createBitmap(Math.max(320,root.getWidth()),Math.max(720,root.getHeight()),Bitmap.Config.ARGB_8888); root.draw(new Canvas(bitmap));
			try(FileOutputStream stream=new FileOutputStream(output)){ assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream)); } bitmap.recycle();
		}
	}
}
