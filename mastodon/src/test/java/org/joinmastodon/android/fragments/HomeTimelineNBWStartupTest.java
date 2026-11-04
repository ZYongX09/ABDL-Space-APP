package org.joinmastodon.android.fragments;

import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.CacheController;
import org.joinmastodon.android.api.HomeTimelineSnapshotCacheTest;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.api.NBWEndpointFixtures;
import org.joinmastodon.android.api.NBWV2RepositoryTest;
import org.joinmastodon.android.api.requests.nbw.NBWAppRequest;
import org.joinmastodon.android.api.requests.timelines.GetAllTimeline;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.api.session.NBWEndpointsRepository;
import org.joinmastodon.android.api.session.NBWForumOptionsRepository;
import org.joinmastodon.android.model.Instance;
import org.joinmastodon.android.model.InstanceV1;
import org.joinmastodon.android.model.NBWEndpoints;
import org.joinmastodon.android.model.Status;
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
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import androidx.recyclerview.widget.RecyclerView;
import me.grishka.appkit.api.APIRequest;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.fragments.BaseRecyclerFragment;
import me.grishka.appkit.utils.V;

import static org.junit.Assert.*;

/** Real Home/SQLite/session-policy callbacks with discovery deliberately unresolved at first mount. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class, qualifiers="en-w320dp-h720dp-mdpi",
		shadows=HomeTimelineHistoryTest.OfflineRequests.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class HomeTimelineNBWStartupTest{
	private AccountSession session, previousSession;
	private Map<String,AccountSession> sessions;
	private Map<String,Instance> instances;
	private Instance previousInstance;
	private String previousActive;
	private NBWEndpointsRepository previousEndpoints, endpoints;
	private final List<NBWForumOptionsRepository.Result<NBWEndpoints>> pendingDiscovery=new ArrayList<>();
	private NBWForumOptionsRepository repository;
	private CacheController cache;
	private ActivityController<HomeTimelineRefreshingLayoutTest.HostActivity> lifecycle;
	private HomeTimelineRefreshingLayoutTest.HostActivity activity;
	private HomeTimelineFragment fragment;
	private boolean previousLiquid, previousCWs;
	private static Field field(Class<?> type,String name) throws Exception{ return HomeTimelineSnapshotCacheTest.field(type,name); }
	private static void drain() throws Exception{ HomeTimelineSnapshotCacheTest.drain(); }

	@Before @SuppressWarnings("unchecked") public void setup() throws Exception{
		ShadowChoreographer.setPaused(true);
		MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context);
		org.joinmastodon.android.ui.utils.UiUtils.updateLocalizedDateFormatters(MastodonApp.context);
		Field singleton=field(NBWEndpointsRepository.class,"instance"); previousEndpoints=(NBWEndpointsRepository)singleton.get(null);
		endpoints=new NBWEndpointsRepository(result->{ pendingDiscovery.add(result); return ()->{}; },SystemClock::elapsedRealtime,Runnable::run);
		singleton.set(null,endpoints);
		var manager=AccountSessionManager.getInstance();
		sessions=(Map<String,AccountSession>)field(AccountSessionManager.class,"sessions").get(manager);
		instances=(Map<String,Instance>)field(AccountSessionManager.class,"instances").get(manager);
		session=HomeTimelineSnapshotCacheTest.session("cold-home.offline.example.test","cold-home-42");
		previousSession=sessions.put(session.getID(),session); previousActive=manager.getLastActiveAccountID();
		field(AccountSessionManager.class,"lastActiveAccountID").set(manager,session.getID());
		InstanceV1 instance=new InstanceV1(); instance.uri=instance.normalizedUri=session.domain; instance.version="4.3.0";
		previousInstance=instances.put(session.domain,instance);
		cache=session.getCacheController(); repository=session.getNBWForumOptionsRepository();
		previousLiquid=GlobalUserPreferences.useIosLiquidNavigation; GlobalUserPreferences.useIosLiquidNavigation=false;
		previousCWs=GlobalUserPreferences.showCWs; GlobalUserPreferences.showCWs=true;
		HomeTimelineHistoryTest.OfflineRequests.sent.clear(); HomeTimelineHistoryTest.OfflineRequests.canceled.clear();
	}
	@After public void cleanup() throws Exception{
		if(activity!=null){
			PostTranslationCoordinator coordinator=PostTranslationCoordinator.get(activity,session.getID());
			if(coordinator!=null) coordinator.dispose();
		}
		if(lifecycle!=null) lifecycle.close();
		repository.deactivate(); drain(); sessions.remove(session.getID()); cache.closeDatabase();
		MastodonApp.context.deleteDatabase(session.getID()+".db");
		if(previousSession!=null) sessions.put(session.getID(),previousSession);
		if(previousInstance==null) instances.remove(session.domain); else instances.put(session.domain,previousInstance);
		field(AccountSessionManager.class,"lastActiveAccountID").set(AccountSessionManager.getInstance(),previousActive);
		field(NBWEndpointsRepository.class,"instance").set(null,previousEndpoints);
		GlobalUserPreferences.useIosLiquidNavigation=previousLiquid; GlobalUserPreferences.showCWs=previousCWs;
		HomeTimelineHistoryTest.OfflineRequests.sent.clear(); HomeTimelineHistoryTest.OfflineRequests.canceled.clear();
	}
	private void activateCold(){
		repository.activate();
		assertEquals("Discovery must remain unresolved at startup",1,pendingDiscovery.size());
		assertNull(endpoints.getSnapshot().endpoints);
	}
	private Status post(String id){
		Status value=HomeTimelineSnapshotCacheTest.post(id);
		value.account.avatar=value.account.avatarStatic="https://cold-home.offline.example.test/avatar/"+id+".png";
		return value;
	}
	private void open() throws Exception{
		lifecycle=Robolectric.buildActivity(HomeTimelineRefreshingLayoutTest.HostActivity.class);
		activity=lifecycle.get(); activity.setTheme(org.joinmastodon.android.R.style.Theme_Mastodon_Light); lifecycle.setup();
		fragment=new HomeTimelineFragment(); Bundle args=new Bundle(); args.putString("account",session.getID()); args.putBoolean("noAutoLoad",true); fragment.setArguments(args);
		activity.showFragment(fragment); activity.getFragmentManager().executePendingTransactions(); settle();
		assertTrue(fragment.getView().isAttachedToWindow());
	}
	private void settle() throws Exception{
		for(int i=0;i<3;i++){
			ShadowLooper.idleMainLooper(32,TimeUnit.MILLISECONDS); drain(); layout(); ShadowLooper.idleMainLooper(32,TimeUnit.MILLISECONDS);
		}
	}
	private void layout() throws Exception{
		if(fragment==null || fragment.getView()==null) return;
		ViewGroup host=activity.findViewById(me.grishka.appkit.R.id.fragment_wrap);
		host.forceLayout(); host.measure(exact(320),exact(720)); host.layout(0,0,320,720);
		RecyclerView rows=list(); rows.forceLayout(); rows.measure(exact(320),exact(620)); rows.layout(0,0,320,620);
		ShadowLooper.idleMainLooper();
	}
	private static int exact(int size){ return View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY); }
	private RecyclerView list() throws Exception{ return (RecyclerView)field(BaseRecyclerFragment.class,"list").get(fragment); }
	@SuppressWarnings("unchecked") private List<Status> rows() throws Exception{ return (List<Status>)field(BaseRecyclerFragment.class,"data").get(fragment); }
	private List<String> ids() throws Exception{ return rows().stream().map(s->s.id).toList(); }
	private List<GetAllTimeline> heads(){ return HomeTimelineHistoryTest.OfflineRequests.sent.stream().filter(GetAllTimeline.class::isInstance).map(GetAllTimeline.class::cast).toList(); }
	private List<NBWAppRequest<?>> capabilities(){
		List<NBWAppRequest<?>> result=new ArrayList<>();
		for(MastodonAPIRequest<?> request:HomeTimelineHistoryTest.OfflineRequests.sent)
			if(request instanceof NBWAppRequest<?> app && app.getURL().getPath().equals("/api/app/capabilities")) result.add(app);
		return result;
	}
	@SuppressWarnings("unchecked") private void success(MastodonAPIRequest<?> request,Object result) throws Exception{
		((Callback<Object>)field(APIRequest.class,"callback").get(request)).onSuccess(result); settle();
	}
	@SuppressWarnings("unchecked") private void failure(MastodonAPIRequest<?> request,int code) throws Exception{
		((Callback<Object>)field(APIRequest.class,"callback").get(request)).onError(new MastodonErrorResponse("Offline startup error",code,null)); settle();
	}
	private void discover(String remote) throws Exception{
		assertEquals(1,pendingDiscovery.size());
		pendingDiscovery.get(0).success(NBWEndpointFixtures.endpoints("primary","startup-revision-a",remote)); settle();
	}
	private void assertVisible(String id) throws Exception{
		assertTrue("Expected local timeline row without a refresh gesture: "+ids(),ids().contains(id));
		boolean attached=false;
		for(int i=0;i<list().getChildCount();i++){
			var holder=list().getChildViewHolder(list().getChildAt(i));
			if(holder instanceof me.grishka.appkit.utils.BindableViewHolder<?> bindable
					&& bindable.getItem() instanceof org.joinmastodon.android.ui.displayitems.StatusDisplayItem item
					&& id.equals(item.parentID) && holder.itemView.isShown()) attached=true;
		}
		assertTrue("Local result must reach an attached production holder",attached);
	}
	private void seed(Status value) throws Exception{
		var lease=cache.beginHomeTimelineSnapshot(); assertNotNull(lease);
		cache.putHomeTimelineSnapshot(lease,List.of(value),"old-cached-cursor"); drain();
	}

	@Test public void lateDiscoveryRestartsInitialLocalHeadWithoutManualRefresh() throws Exception{
		activateCold(); open(); assertEquals(1,heads().size()); GetAllTimeline old=heads().get(0);
		discover("local_only");
		assertTrue("No capabilities are needed when public policy disables NBW",capabilities().isEmpty());
		success(old,List.of(post("p_obsolete")));
		assertEquals("A startup generation change must coalesce one fresh local /all request",2,heads().size());
		assertTrue(HomeTimelineHistoryTest.OfflineRequests.canceled.contains(old));
		assertFalse(ids().contains("p_obsolete"));
		success(heads().get(1),List.of(post("p_fresh"),post("c_fresh")));
		assertVisible("p_fresh"); assertVisible("c_fresh");
		assertFalse(fragment.isDataLoading()); assertEquals(2,heads().size());
	}
	@Test public void warmLocalHistorySurvivesRoutingInitializationUntilFreshAuthority() throws Exception{
		activateCold(); seed(post("p_history")); open(); assertEquals(List.of("p_history"),ids()); GetAllTimeline old=heads().get(0);
		discover("local_only"); success(old,List.of(post("p_late")));
		assertEquals("Remote policy initialization must not clear already visible local history",List.of("p_history"),ids());
		assertEquals(2,heads().size()); success(heads().get(1),List.of(post("p_authority"))); assertVisible("p_authority");
	}
	@Test public void discoveryBeforeQueuedHistoryCallbackStillDispatchesAHead() throws Exception{
		activateCold(); CountDownLatch occupied=new CountDownLatch(1),release=new CountDownLatch(1);
		CacheController.databaseThread.postRunnable(()->{ occupied.countDown(); try{ release.await(); }catch(InterruptedException e){ Thread.currentThread().interrupt(); } },0);
		assertTrue(occupied.await(5,TimeUnit.SECONDS));
		try{
			lifecycle=Robolectric.buildActivity(HomeTimelineRefreshingLayoutTest.HostActivity.class); activity=lifecycle.get(); activity.setTheme(org.joinmastodon.android.R.style.Theme_Mastodon_Light); lifecycle.setup();
			fragment=new HomeTimelineFragment(); Bundle args=new Bundle(); args.putString("account",session.getID()); args.putBoolean("noAutoLoad",true); fragment.setArguments(args);
			activity.showFragment(fragment); activity.getFragmentManager().executePendingTransactions(); ShadowLooper.idleMainLooper(32,TimeUnit.MILLISECONDS); layout();
			assertTrue(heads().isEmpty());
			pendingDiscovery.get(0).success(NBWEndpointFixtures.endpoints("primary","startup-revision-a","local_only")); ShadowLooper.idleMainLooper();
		}finally{ release.countDown(); }
		settle();
		assertEquals("A cancelled cache lease must not consume the only initial load",1,heads().size());
		success(heads().get(0),List.of(post("p_after_cache_race"))); assertVisible("p_after_cache_race");
	}
	@Test public void authenticatedLocalOnlyCompletionDoesNotRequireUserRefresh() throws Exception{
		activateCold(); open(); GetAllTimeline old=heads().get(0); discover("full");
		assertEquals(1,capabilities().size()); success(capabilities().get(0),NBWV2RepositoryTest.capabilities("local_only"));
		success(old,List.of(post("p_old"))); assertEquals(2,heads().size());
		success(heads().get(1),List.of(post("p_local_only"))); assertVisible("p_local_only"); assertFalse(repository.getSnapshot().canRead());
	}
	@Test public void accountSwitchAndDestroyedViewNeverStartRecoveryRequests() throws Exception{
		activateCold(); open(); GetAllTimeline old=heads().get(0);
		field(AccountSessionManager.class,"lastActiveAccountID").set(AccountSessionManager.getInstance(),"different-account"); discover("local_only"); success(old,List.of(post("p_wrong_account")));
		assertEquals(1,heads().size()); assertTrue(rows().isEmpty());
		field(AccountSessionManager.class,"lastActiveAccountID").set(AccountSessionManager.getInstance(),session.getID()); fragment.onRefresh(); GetAllTimeline latest=heads().get(1);
		lifecycle.pause().stop().destroy(); lifecycle=null; success(latest,List.of(post("p_destroyed"))); assertEquals(2,heads().size());
	}
	@Test public void networkFailureAfterRecoveryDoesNotCreateAnAutomaticRetryLoop() throws Exception{
		activateCold(); open(); GetAllTimeline old=heads().get(0); discover("local_only"); success(old,List.of(post("p_old")));
		assertEquals(2,heads().size()); failure(heads().get(1),503);
		ShadowLooper.idleMainLooper(2,TimeUnit.SECONDS); drain(); assertEquals("Network errors do not continuously reload",2,heads().size());
	}
	@Test public void warmSnapshotWrittenBeforeActivationStillRecoversWithFreshLocalAuthority() throws Exception{
		seed(post("p_before_activation")); activateCold(); open(); GetAllTimeline old=heads().get(0);
		discover("local_only"); success(old,List.of(post("p_late"))); assertEquals(2,heads().size());
		success(heads().get(1),List.of(post("p_restored"))); assertVisible("p_restored");
	}
	@Test public void initializedLocalOnlyNeedsExactlyOneAutomaticInitialHead() throws Exception{
		activateCold(); discover("local_only"); open(); assertEquals(1,heads().size());
		success(heads().get(0),List.of(post("p_ready"))); assertVisible("p_ready");
		ShadowLooper.idleMainLooper(1,TimeUnit.SECONDS); assertEquals(1,heads().size());
	}
	@Test public void identityTimerRecoversAStillHeldHeadAndRejectsDuplicateOldCallbacks() throws Exception{
		activateCold(); open(); GetAllTimeline old=heads().get(0); discover("local_only");
		ShadowLooper.idleMainLooper(700,TimeUnit.MILLISECONDS); drain(); layout();
		assertEquals("The user must not wait for the obsolete request to return",2,heads().size());
		success(old,List.of(post("nbw_old"))); success(old,List.of(post("p_duplicate_old")));
		assertEquals("Duplicate old delivery must not schedule more requests",2,heads().size());
		success(heads().get(1),List.of(post("p_timer_recovered"))); assertVisible("p_timer_recovered");
		assertFalse(ids().contains("nbw_old")); assertFalse(ids().contains("p_duplicate_old"));
	}
	@Test public void rotatingTokenBeforeMetadataCompletionNeverCreatesRecoveryRequest() throws Exception{
		activateCold(); open(); GetAllTimeline old=heads().get(0);
		session.token.accessToken="rotated-test-token"; discover("local_only"); success(old,List.of(post("p_old_token")));
		ShadowLooper.idleMainLooper(700,TimeUnit.MILLISECONDS); assertEquals(1,heads().size()); assertTrue(rows().isEmpty());
	}
	@Test public void sameGenerationCacheInvalidationNeverResubmitsAutomatically() throws Exception{
		activateCold(); discover("local_only"); open(); GetAllTimeline old=heads().get(0);
		cache.invalidateHomeTimelineSnapshot(); success(old,List.of(post("p_invalidated")));
		ShadowLooper.idleMainLooper(700,TimeUnit.MILLISECONDS); assertEquals(1,heads().size()); assertTrue(rows().isEmpty());
	}
	@Test public void temporarilyDetachedHomeResumesRecoveryWhenPagerReattaches() throws Exception{
		activateCold(); open(); GetAllTimeline old=heads().get(0);
		View view=fragment.getView(); ViewGroup parent=(ViewGroup)view.getParent();
		int index=parent.indexOfChild(view); ViewGroup.LayoutParams params=view.getLayoutParams();
		parent.removeView(view); assertFalse(view.isAttachedToWindow());
		discover("local_only"); success(old,List.of(post("p_detached_late")));
		assertEquals("A detached page must not issue a new request yet",1,heads().size());
		assertTrue("Temporary detachment must retain the recovery intent",field(HomeTimelineFragment.class,"homeRecoveryGeneration").getLong(fragment)>=0);
		parent.addView(view,index,params); settle();
		assertEquals("Reattach without Fragment.onShown must resume exactly one head",2,heads().size());
		success(heads().get(1),List.of(post("p_reattached"))); assertVisible("p_reattached");
	}
	@Test public void temporarilyHiddenHomeResumesRecoveryWhenVisibleAgain() throws Exception{
		activateCold(); open(); GetAllTimeline old=heads().get(0); View view=fragment.getView();
		view.setVisibility(View.INVISIBLE); discover("local_only"); success(old,List.of(post("p_hidden_late")));
		assertEquals(1,heads().size()); assertTrue(field(HomeTimelineFragment.class,"homeRecoveryGeneration").getLong(fragment)>=0);
		view.setVisibility(View.VISIBLE); view.getViewTreeObserver().dispatchOnPreDraw(); settle();
		assertEquals("Visibility recovery is event-driven, not another manual refresh",2,heads().size());
		success(heads().get(1),List.of(post("p_visible_again"))); assertVisible("p_visible_again");
	}
	@Test public void manualRefreshOwnsTheNewRequestAfterRoutingRecovery() throws Exception{
		activateCold(); open(); GetAllTimeline old=heads().get(0); discover("local_only"); success(old,List.of(post("p_old")));
		assertEquals(2,heads().size()); GetAllTimeline recovery=heads().get(1);
		fragment.onRefresh(); assertEquals(3,heads().size());
		success(recovery,List.of(post("p_late_recovery"))); success(old,List.of(post("p_late_first")));
		success(heads().get(2),List.of(post("p_manual_authority"))); assertEquals(List.of("p_manual_authority"),ids());
		assertVisible("p_manual_authority"); assertEquals(3,heads().size());
	}
}
