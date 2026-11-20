package org.joinmastodon.android.api;

import android.app.Activity;
import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.ContentAccessGate;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.api.session.ContentAccessCoordinator;
import org.joinmastodon.android.fragments.BaseStatusListFragment;
import org.joinmastodon.android.fragments.HomeTimelineFragment;
import org.joinmastodon.android.fragments.settings.SettingsMainFragment;
import org.joinmastodon.android.model.ContentAccess;
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
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import me.grishka.appkit.FragmentStackActivity;
import me.grishka.appkit.fragments.BaseRecyclerFragment;

import static org.junit.Assert.*;

/** Actual attached views, Activity restoration shield and Home data/preloader/holder state. HTTP only is captured. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=CompatibilityTestApplication.class,qualifiers="en-w320dp-h720dp-mdpi",shadows=ContentAccessUiTest.OfflineRequests.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ContentAccessUiTest{
	@Implements(MastodonAPIRequest.class) public static class OfflineRequests{
		@RealObject MastodonAPIRequest<?> request;
		@Implementation public MastodonAPIRequest<?> exec(String account){ return request; }
		@Implementation public void cancel(){} // Deliberately do not prevent late callbacks.
	}
	public static class HostActivity extends FragmentStackActivity{}
	public static class RestoredContentFragment extends Fragment{
		@Override public View onCreateView(LayoutInflater inflater,ViewGroup parent,Bundle state){ TextView text=new TextView(getActivity()); text.setText("PRIVATE RESTORED CONTENT"); return text; }
	}
	public static class ObservedHome extends HomeTimelineFragment{
		public void deliver(List<Status> posts){ onDataLoaded(posts,false); }
		public List<Status> rows(){ return data; }
		public List<Status> preload(){ return preloadedData; }
		public int renderedItems(){ return displayItems.size(); }
	}
	private AccountSession session, previous;
	private Map<String,AccountSession> sessions;
	private Map<String,Instance> instances;
	private Instance previousInstance;
	private String previousActive;
	private NBWEndpointFixtures endpoints;
	private ContentAccessCoordinator coordinator;
	private final List<ContentAccessCoordinator.Result> probes=new ArrayList<>();
	private ActivityController<HostActivity> lifecycle;
	private HostActivity activity;
	@Before @SuppressWarnings("unchecked") public void setup() throws Exception{
		ShadowChoreographer.setPaused(true); MastodonApp.context=RuntimeEnvironment.getApplication(); me.grishka.appkit.utils.V.setApplicationContext(MastodonApp.context); org.joinmastodon.android.ui.utils.UiUtils.updateLocalizedDateFormatters(MastodonApp.context);
		endpoints=new NBWEndpointFixtures(); var manager=AccountSessionManager.getInstance();
		sessions=(Map<String,AccountSession>)HomeTimelineSnapshotCacheTest.field(AccountSessionManager.class,"sessions").get(manager);
		instances=(Map<String,Instance>)HomeTimelineSnapshotCacheTest.field(AccountSessionManager.class,"instances").get(manager);
		previousActive=manager.getLastActiveAccountID(); session=HomeTimelineSnapshotCacheTest.session("api.abdl-space.top","content-ui-993"); previous=sessions.put(session.getID(),session);
		HomeTimelineSnapshotCacheTest.field(AccountSessionManager.class,"lastActiveAccountID").set(manager,session.getID());
		InstanceV1 instance=new InstanceV1(); instance.uri=instance.normalizedUri=session.domain; instance.version="4.3.0"; previousInstance=instances.put(session.domain,instance);
		coordinator=new ContentAccessCoordinator(session,(id,result)->{ probes.add(result); return ()->{}; },Runnable::run); HomeTimelineSnapshotCacheTest.field(AccountSession.class,"contentAccessCoordinator").set(session,coordinator);
		// The gate is a process singleton; the static persists across Robolectric tests in one
		// sandbox, so a stale install would leave this test's Activity without lifecycle callbacks.
		HomeTimelineSnapshotCacheTest.field(ContentAccessGate.class,"instance").set(null,null);
		ContentAccessGate.install(RuntimeEnvironment.getApplication());
		lifecycle=Robolectric.buildActivity(HostActivity.class); activity=lifecycle.get(); activity.setTheme(R.style.Theme_Mastodon_Light); lifecycle.setup().visible(); frame();
	}
	@After public void cleanup() throws Exception{
		if(lifecycle!=null) lifecycle.close(); HomeTimelineSnapshotCacheTest.drain(); session.deactivateNBW(); session.getCacheController().closeDatabase(); MastodonApp.context.deleteDatabase(session.getID()+".db");
		if(previous==null) sessions.remove(session.getID()); else sessions.put(session.getID(),previous); if(previousInstance==null) instances.remove(session.domain); else instances.put(session.domain,previousInstance);
		HomeTimelineSnapshotCacheTest.field(AccountSessionManager.class,"lastActiveAccountID").set(AccountSessionManager.getInstance(),previousActive); endpoints.close();
	}
	private void frame(){
		ShadowLooper.idleMainLooper(32,TimeUnit.MILLISECONDS); View decor=activity.getWindow().getDecorView(); decor.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY)); decor.layout(0,0,320,720); decor.getViewTreeObserver().dispatchOnPreDraw(); ShadowLooper.idleMainLooper(32,TimeUnit.MILLISECONDS);
	}
	private void open(){ coordinator.refresh(); probes.get(probes.size()-1).success(ContentAccessCoordinatorTest.access(ContentAccess.State.OPEN)); frame(); }
	private View shield(){ return activity.getWindow().getDecorView().findViewWithTag(ContentAccessGate.SHIELD_TAG); }
	@Test public void unknownAndRequiredShieldActualRestoredViewUntilFreshProbe(){
		RestoredContentFragment fragment=new RestoredContentFragment(); activity.showFragment(fragment); activity.getFragmentManager().executePendingTransactions(); frame();
		// Silent detection: no overlay is ever installed and existing views are never hidden.
		assertNull(shield()); assertTrue(fragment.getView().isShown());
		open(); assertNull(shield()); assertTrue(fragment.getView().isShown());
		coordinator.capture().receiveHeader("v1:baby_verification_required"); frame(); assertNull(shield()); assertTrue(fragment.getView().isShown());
		coordinator.capture().receiveHeader("v1:open"); frame(); assertTrue(fragment.getView().isShown());
		open(); assertNull(shield()); assertTrue(fragment.getView().isShown());
	}
	@Test public void nativeSettingsRemainsFunctionalWithoutExposingProtectedLowerStack(){
		RestoredContentFragment content=new RestoredContentFragment(); activity.showFragment(content); activity.getFragmentManager().executePendingTransactions(); frame();
		SettingsMainFragment settings=new SettingsMainFragment(); Bundle args=new Bundle(); args.putString("account",session.getID()); settings.setArguments(args); activity.showFragment(settings); activity.getFragmentManager().executePendingTransactions(); frame();
		assertTrue(ContentAccessGate.isAllowedFragment(settings)); assertNull(shield()); assertTrue(settings.getView().isShown()); assertFalse(coordinator.isOpen());
	}
	@Test public void homeClearsRealDataPreloadedAndDisplayItemsAndRejectsLateLocalPost() throws Exception{
		open(); ObservedHome home=new ObservedHome(); Bundle args=new Bundle(); args.putString("account",session.getID()); args.putBoolean("noAutoLoad",true); home.setArguments(args); activity.showFragment(home); activity.getFragmentManager().executePendingTransactions(); frame(); HomeTimelineSnapshotCacheTest.drain();
		home.deliver(List.of(HomeTimelineSnapshotCacheTest.post("p_visible"))); home.preload().add(HomeTimelineSnapshotCacheTest.post("p_preloaded")); frame();
		assertFalse(home.rows().isEmpty()); assertTrue(home.renderedItems()>0);
		coordinator.capture().receiveHeader("v1:baby_verification_required"); frame();
		assertTrue(home.rows().isEmpty()); assertTrue(home.preload().isEmpty()); assertEquals(0,home.renderedItems()); assertTrue(home.getView().isShown());
		home.deliver(List.of(HomeTimelineSnapshotCacheTest.post("p_late"))); home.onStatusCreated(HomeTimelineSnapshotCacheTest.post("p_local_late")); frame();
		assertTrue(home.rows().isEmpty()); assertTrue(home.preload().isEmpty()); assertEquals(0,home.renderedItems()); assertNull(session.getCacheController().beginHomeTimelineSnapshot());
	}
}
