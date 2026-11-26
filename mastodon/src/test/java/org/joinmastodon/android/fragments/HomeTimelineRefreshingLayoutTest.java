package org.joinmastodon.android.fragments;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toolbar;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.lifecycle.ViewModelStore;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.savedstate.SavedStateRegistry;
import androidx.savedstate.SavedStateRegistryController;
import androidx.savedstate.SavedStateRegistryOwner;
import androidx.swiperefreshlayout.widget.CircularProgressDrawable;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewpager2.widget.ViewPager2;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.HomeTimelineSnapshotCacheTest;
import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.api.requests.timelines.GetAllTimeline;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Instance;
import org.joinmastodon.android.model.InstanceV1;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.TimelineDefinition;
import org.joinmastodon.android.translation.PostTranslationCoordinator;
import org.joinmastodon.android.ui.compose.ComposeLifecycleHelperKt;
import org.joinmastodon.android.ui.compose.navigation.HomeLiquidToolbarController;
import org.joinmastodon.android.ui.compose.navigation.HomeToolbarTimeline;
import org.joinmastodon.android.ui.views.HomeTimelineTabStrip;
import org.joinmastodon.android.ui.views.NestedRecyclerScrollView;
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
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import me.grishka.appkit.FragmentStackActivity;
import me.grishka.appkit.api.APIRequest;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.utils.V;
import me.grishka.appkit.views.RecursiveSwipeRefreshLayout;

import static org.junit.Assert.*;

/** Real attached HomeTab -> pager -> HomeTimeline -> scroller/content hierarchy and real offline
 * cache hydration. The indicator is SwipeRefreshLayout's real built-in CircleImageView; only HTTP
 * is intercepted, with no replacement refresh view or fixed test-only positioning. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, qualifiers="en-w320dp-h720dp-mdpi", application=CompatibilityTestApplication.class,
        shadows=HomeTimelineHistoryTest.OfflineRequests.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class HomeTimelineRefreshingLayoutTest{
    /** Same Compose lifecycle bridge as MainActivity, without its unrelated startup/account UI. */
    public static class HostActivity extends FragmentStackActivity implements LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner{
        private final LifecycleRegistry lifecycle=new LifecycleRegistry(this);
        private final ViewModelStore models=new ViewModelStore();
        private final SavedStateRegistryController saved=SavedStateRegistryController.create(this);
        @Override public Lifecycle getLifecycle(){ return lifecycle; }
        @Override public ViewModelStore getViewModelStore(){ return models; }
        @Override public SavedStateRegistry getSavedStateRegistry(){ return saved.getSavedStateRegistry(); }
        @Override protected void onCreate(Bundle state){
            saved.performRestore(state); lifecycle.setCurrentState(Lifecycle.State.CREATED);
            super.onCreate(state); ComposeLifecycleHelperKt.installComposeLifecycle(this);
        }
        @Override protected void onStart(){ super.onStart(); lifecycle.setCurrentState(Lifecycle.State.STARTED); }
        @Override protected void onResume(){ super.onResume(); lifecycle.setCurrentState(Lifecycle.State.RESUMED); }
        @Override protected void onPause(){ lifecycle.setCurrentState(Lifecycle.State.STARTED); super.onPause(); }
        @Override protected void onStop(){ lifecycle.setCurrentState(Lifecycle.State.CREATED); super.onStop(); }
        @Override protected void onDestroy(){ lifecycle.setCurrentState(Lifecycle.State.DESTROYED); models.clear(); super.onDestroy(); }
    }

    private ActivityController<HostActivity> lifecycle;
    private HostActivity activity;
    private HomeTabFragment home;
    private HomeTimelineFragment timeline;
    private HomeLiquidToolbarController glass;
    private AccountSession session, previousSession;
    private Map<String,AccountSession> sessions;
    private Map<String,Instance> instances;
    private Instance previousInstance;
    private String previousActive;
    private boolean previousLiquid, previousReduceMotion;
    private int width=320, height=720;
    private boolean darkTheme;
    private GlobalUserPreferences.ThemePreference previousTheme;

    @Before @SuppressWarnings("unchecked") public void setup() throws Exception{
        ShadowChoreographer.setPaused(true);
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context);
        previousTheme=GlobalUserPreferences.theme;
        org.joinmastodon.android.ui.utils.UiUtils.updateLocalizedDateFormatters(MastodonApp.context);
        AccountSessionManager manager=AccountSessionManager.getInstance();
        sessions=(Map<String,AccountSession>)HomeTimelineSnapshotCacheTest.field(AccountSessionManager.class,"sessions").get(manager);
        instances=(Map<String,Instance>)HomeTimelineSnapshotCacheTest.field(AccountSessionManager.class,"instances").get(manager);
        session=HomeTimelineSnapshotCacheTest.session("refresh-layout.offline.example.test","refresh-layout-42");
        session.self.sponsor=new org.joinmastodon.android.model.Account.PublicSponsor();
        session.self.sponsor.active=session.self.sponsor.permanent=true;
        previousSession=sessions.put(session.getID(),session);
        previousActive=manager.getLastActiveAccountID(); HomeTimelineSnapshotCacheTest.field(AccountSessionManager.class,"lastActiveAccountID").set(manager,session.getID());
        InstanceV1 instance=new InstanceV1(); instance.uri=instance.normalizedUri=session.domain; instance.version="4.3.0";
        previousInstance=instances.put(session.domain,instance);
        session.getLocalPreferences().timelines=new ArrayList<>(List.of(TimelineDefinition.HOME_TIMELINE.copy()));
        MastodonApp.context.getSharedPreferences("location_cache",0).edit().putString("province","测试省").commit();
        previousLiquid=GlobalUserPreferences.useIosLiquidNavigation; GlobalUserPreferences.useIosLiquidNavigation=false;
        previousReduceMotion=GlobalUserPreferences.reduceMotion; GlobalUserPreferences.reduceMotion=true;
        HomeTimelineHistoryTest.OfflineRequests.sent.clear(); HomeTimelineHistoryTest.OfflineRequests.canceled.clear();
        session.getCacheController().putHomeTimelineSnapshot(session.getCacheController().beginHomeTimelineSnapshot(),List.of(post("p_refresh_layout")),null);
        HomeTimelineSnapshotCacheTest.drain();
    }

    @After public void cleanup() throws Exception{
        if(glass!=null) glass.dispose();
        if(activity!=null){
            PostTranslationCoordinator coordinator=PostTranslationCoordinator.get(activity,session.getID());
            if(coordinator!=null) coordinator.dispose();
        }
        if(lifecycle!=null) lifecycle.close();
        HomeTimelineSnapshotCacheTest.drain();
        sessions.remove(session.getID()); session.getCacheController().closeDatabase(); MastodonApp.context.deleteDatabase(session.getID()+".db");
        if(previousSession!=null) sessions.put(session.getID(),previousSession);
        if(previousInstance==null) instances.remove(session.domain); else instances.put(session.domain,previousInstance);
        HomeTimelineSnapshotCacheTest.field(AccountSessionManager.class,"lastActiveAccountID").set(AccountSessionManager.getInstance(),previousActive);
        GlobalUserPreferences.useIosLiquidNavigation=previousLiquid; GlobalUserPreferences.reduceMotion=previousReduceMotion;
        GlobalUserPreferences.theme=previousTheme;
        MastodonApp.context.getSharedPreferences("location_cache",0).edit().remove("province").commit();
        HomeTimelineHistoryTest.OfflineRequests.sent.clear(); HomeTimelineHistoryTest.OfflineRequests.canceled.clear();
    }

    private void open(boolean embedded) throws Exception{
        GlobalUserPreferences.theme=darkTheme ? GlobalUserPreferences.ThemePreference.DARK : GlobalUserPreferences.ThemePreference.LIGHT;
        lifecycle=Robolectric.buildActivity(HostActivity.class); activity=lifecycle.get(); activity.setTheme(darkTheme ? R.style.Theme_Mastodon_Dark : R.style.Theme_Mastodon_Light);
        lifecycle.setup().visible();
        Bundle args=new Bundle(); args.putString("account",session.getID()); args.putBoolean("noAutoLoad",true);
        if(embedded){
            home=new HomeTabFragment(); home.setArguments(args); activity.showFragment(home);
        }else{
            timeline=new HomeTimelineFragment(); timeline.setArguments(args); activity.showFragment(timeline);
        }
        activity.getFragmentManager().executePendingTransactions();
        for(int i=0;i<4;i++){
            if(home!=null){
                home.getChildFragmentManager().executePendingTransactions();
                timeline=(HomeTimelineFragment)home.getCurrentFragment();
            }
            HomeTimelineSnapshotCacheTest.drain(); settle(8);
        }
        assertNotNull(timeline);
        assertTrue(timeline.getView().isAttachedToWindow());
        assertTrue("Use a real in-flight head", HomeTimelineSnapshotCacheTest.field(HomeTimelineFragment.class,"homeHeadInFlight").getBoolean(timeline));
        assertTrue(refresh().isRefreshing());
        assertEquals(View.VISIBLE,circle().getVisibility());
    }

    private void settle(int frames){
        // Layout alone does not advance a legacy child animation's drawing time. Draw only
        // the real CircleImageView (not the glass RuntimeShader) at each attached-window vsync.
        Bitmap frame=timeline==null ? null : Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        Canvas canvas=frame==null ? null : new Canvas(frame);
        try{
            for(int i=0;i<frames;i++){
                long[] vsync={-1};
                android.view.Choreographer.getInstance().postFrameCallback(time->vsync[0]=time);
                ShadowLooper.idleMainLooper(16,TimeUnit.MILLISECONDS);
                assertTrue("Advance a real attached-window vsync, not only manual layout",vsync[0]>=0);
                View root=activity.getWindow().getDecorView(); root.forceLayout();
                root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
                root.layout(0,0,width,height);
                root.getViewTreeObserver().dispatchOnPreDraw();
                if(canvas==null || timeline.getView()==null || !refresh().isShown()) continue;
                ImageView indicator=circle();
                if(indicator.getAnimation()==null) continue;
                // ShadowView can initialize the Animation before the first draw, preventing
                // View from setting the flag that dispatches CircleImageView.onAnimationEnd.
                if(indicator.getAnimation().isInitialized())
                    ReflectionHelpers.callInstanceMethod(View.class,indicator,"onAnimationStart");
                ReflectionHelpers.callInstanceMethod(ViewGroup.class,refresh(),"drawChild",
                        ClassParameter.from(Canvas.class,canvas),ClassParameter.from(View.class,indicator),
                        ClassParameter.from(long.class,TimeUnit.NANOSECONDS.toMillis(vsync[0])));
            }
        }finally{
            if(frame!=null) frame.recycle();
        }
    }
    private Status post(String id){
        Status post=HomeTimelineSnapshotCacheTest.post(id);
        post.account.avatar=post.account.avatarStatic="https://refresh-layout.offline.example.test/fictional.png";
        return post;
    }
    private List<GetAllTimeline> heads(){
        return HomeTimelineHistoryTest.OfflineRequests.sent.stream().filter(GetAllTimeline.class::isInstance).map(GetAllTimeline.class::cast).toList();
    }
    @SuppressWarnings("unchecked") private Callback<List<Status>> callback(GetAllTimeline request) throws Exception{
        return (Callback<List<Status>>)HomeTimelineSnapshotCacheTest.field(APIRequest.class,"callback").get(request);
    }
    private void failHead(GetAllTimeline request,int code) throws Exception{
        callback(request).onError(new MastodonErrorResponse("Offline layout test",code,null));
    }
    private SwipeRefreshLayout refresh(){ return timeline.getView().findViewById(R.id.refresh_layout); }
    private RecyclerView rows(){ return timeline.getView().findViewById(R.id.list); }
    private ImageView circle(){ return nativeCircle(refresh()); }
    static ImageView nativeCircle(SwipeRefreshLayout refresh){
        assertTrue(refresh instanceof RecursiveSwipeRefreshLayout);
        assertEquals("One AppKit content child and one native indicator",2,refresh.getChildCount());
        for(int i=0;i<refresh.getChildCount();i++)
            if(refresh.getChildAt(i) instanceof ImageView image && image.getDrawable() instanceof CircularProgressDrawable) return image;
        throw new AssertionError("Missing the actual SwipeRefreshLayout circle");
    }
    private Toolbar toolbar(){ return home==null ? timeline.getToolbar() : home.getView().findViewById(me.grishka.appkit.R.id.toolbar); }
    private static Rect bounds(View view){
        int[] position=new int[2]; view.getLocationInWindow(position);
        return new Rect(position[0],position[1],position[0]+view.getWidth(),position[1]+view.getHeight());
    }
    private void insets(int left,int top,int right,int bottom){
        WindowInsets insets=new WindowInsets.Builder().setInsets(WindowInsets.Type.systemBars(),Insets.of(left,top,right,bottom))
                .setSystemWindowInsets(Insets.of(left,top,right,bottom)).build();
        if(home!=null){
            home.onApplyWindowInsets(insets);
            timeline.onApplyWindowInsets(insets.consumeSystemWindowInsets());
        }else timeline.onApplyWindowInsets(insets);
        settle(6);
    }
    private static Rect visibleViewportInWindow(View wrapper){
        Rect viewport=new Rect(); assertTrue(wrapper.getGlobalVisibleRect(viewport));
        int[] rootOrigin=new int[2]; wrapper.getRootView().getLocationInWindow(rootOrigin);
        viewport.offset(rootOrigin[0],rootOrigin[1]);
        return viewport;
    }

    private Rect assertSafe(int topInset, Rect obstruction){
        ImageView indicator=circle();
        assertTrue(refresh().isRefreshing());
        assertEquals(View.VISIBLE,indicator.getVisibility()); assertEquals(1f,indicator.getAlpha(),0f);
        assertFalse(indicator.isClickable()); assertFalse(indicator.isFocusable());
        Rect visible=bounds(indicator), root=bounds(activity.getWindow().getDecorView());
        assertEquals(refresh().getProgressCircleDiameter(),visible.width());
        assertEquals(visible.width(),visible.height());
        assertTrue("No status-bar overlap: "+visible,visible.top>=topInset);
        assertTrue("Circle stays inside the real viewport: "+visible+" / "+root,root.contains(visible));
        if(obstruction!=null){
            assertTrue("Known toolbar or tab strip must be cleared: "+visible+" / "+obstruction,visible.top>=obstruction.bottom);
            assertFalse("No real toolbar/glass overlap",Rect.intersects(visible,obstruction));
        }
        return visible;
    }

    @Test public void embeddedClassicUsesKnownActualParentToolbarAndRecoversAfterHideResizeAndScroll() throws Exception{
        open(true);
        assertSame(home,timeline.getParentFragment());
        assertNull("AppKit removes a tab's own toolbar; parent geometry is required",timeline.getToolbar());
        Toolbar bar=toolbar(); ViewGroup.LayoutParams lp=bar.getLayoutParams(); lp.height=91; bar.setLayoutParams(lp);
        insets(0,37,0,24);
        Rect barBounds=bounds(bar); assertEquals(91,barBounds.height());
        HomeTimelineTabStrip strip=org.robolectric.util.ReflectionHelpers.getField(home,"timelineTabs");
        ViewPager2 pager=org.robolectric.util.ReflectionHelpers.getField(home,"pager");
        View logo=bar.findViewById(R.id.home_brand_logo);
        assertNotNull(logo);
        assertTrue(logo.isShown());
        assertEquals(barBounds.exactCenterX(),bounds(logo).exactCenterX(),1f);
        assertEquals(V.dp(35),logo.getWidth());
        assertEquals(V.dp(35),logo.getHeight());
        assertEquals(0,activity.getResources().getIdentifier("search_action","id",activity.getPackageName()));
        assertEquals(barBounds.bottom,bounds(strip).top);
        assertEquals(V.dp(48),strip.getHeight());
        assertEquals(bounds(strip).bottom,bounds(pager).top);
        Rect before=assertSafe(37,bounds(strip));
        assertEquals("Classic lists reserve no extra refresh space",0,rows().getPaddingTop());
        NestedRecyclerScrollView scroller=timeline.getView().findViewById(R.id.scroller);
        scroller.scrollTo(0,29); settle(8); assertSafe(37,bounds(strip));
        bar.setVisibility(View.GONE); settle(8); Rect hidden=assertSafe(37,null);
        assertTrue("Removed toolbar must release its occupied space",hidden.top<before.top);
        bar.setVisibility(View.VISIBLE); lp.height=117; bar.setLayoutParams(lp); settle(8);
        assertSafe(37,bounds(bar));
        width=720; height=320; settle(10); assertSafe(37,bounds(bar));
        assertEquals("Layout changes must not start extra home head requests",1,
                HomeTimelineHistoryTest.OfflineRequests.sent.stream().filter(GetAllTimeline.class::isInstance).count());
    }

    @Test @Config(qualifiers="zh-rCN-w320dp-h720dp-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void standaloneScrollerKeepsNativeCircleClearOfSideInsetsAtLargeFont() throws Exception{
        android.content.res.Configuration config=new android.content.res.Configuration(MastodonApp.context.getResources().getConfiguration());
        config.fontScale=1.6f; MastodonApp.context.getResources().updateConfiguration(config,MastodonApp.context.getResources().getDisplayMetrics());
        open(false); insets(18,43,26,24);
        assertEquals(1.6f,circle().getResources().getConfiguration().fontScale,.001f);
        assertEquals(V.dp(40),refresh().getProgressCircleDiameter());
        Toolbar bar=toolbar(); ViewGroup.LayoutParams lp=bar.getLayoutParams(); lp.height=103; bar.setLayoutParams(lp); settle(8);
        assertSafe(43,bounds(bar));
        NestedRecyclerScrollView scroller=timeline.getView().findViewById(R.id.scroller);
        scroller.scrollTo(0,71); settle(8); Rect visible=assertSafe(43,bounds(bar));
        assertTrue(visible.left>=18 && visible.right<=width-26);
        capture("home-refresh-standalone-zh-font160");
        scroller.scrollTo(0,0); settle(8); assertSafe(43,bounds(bar));
    }

    @Test public void attachedActualLiquidGlassBoundsReplaceClassicGeometryAndDisappearWithoutStaleOffset() throws Exception{
        open(true);
        glass=new HomeLiquidToolbarController(activity,home::onLiquidTimelineSelected,home::onLiquidNewPosts,home::onLiquidCompose,home::onLiquidMenuItem);
        glass.setTimelines(List.of(new HomeToolbarTimeline(0,"Home",R.drawable.ic_fluent_home_24_regular)),0);
        glass.setComposeMenu(HomeFragment.composeMenuItems(activity)); glass.setStatusBarInset(61);
        ViewGroup host=(ViewGroup)home.getView().getParent();
        host.addView(glass.getView(),new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        // Exercise the installed overlay hierarchy directly, without pretending the software test
        // window passed HomeFragment's production hardware-attachment gate.
        GlobalUserPreferences.useIosLiquidNavigation=true;
        home.setLiquidToolbarController(glass);
        insets(0,61,0,24); toolbar().setVisibility(View.GONE); settle(60);
        Rect actualGlass=new Rect(); assertTrue("Actual attached Compose glass must be measured",glass.getVisibleBoundsInWindow(actualGlass));
        assertTrue(actualGlass.bottom>61 && actualGlass.height()<glass.getView().getHeight());
        Rect beforeMove=assertSafe(61,actualGlass);
        moveWindowOrigin(410,1050); settle(8); assertWindowOrigin(410,1050);
        assertTrue(glass.getVisibleBoundsInWindow(actualGlass));
        assertEquals("Real glass and the circle remain window-relative in a floating window",beforeMove,assertSafe(61,actualGlass));
        width=720; height=320; insets(23,31,39,19); toolbar().setVisibility(View.GONE);
        glass.setStatusBarInset(31); moveWindowOrigin(250,880); settle(60);
        assertTrue(glass.getVisibleBoundsInWindow(actualGlass)); assertSafe(31,actualGlass);
        width=320; height=720; insets(0,61,0,24); toolbar().setVisibility(View.GONE);
        // Leave an ample, real glass obstruction over the viewport so the disappearance
        // case is not vacuous even if the app window is already inset.
        glass.setStatusBarInset(85); settle(60);
        assertTrue(glass.getVisibleBoundsInWindow(actualGlass));
        assertTrue("Glass must extend below the viewport's unobstructed top",
                actualGlass.bottom>Math.max(61,visibleViewportInWindow(refresh()).top));
        Rect withGlass=assertTightClearance(61,actualGlass,false);
        glass.getView().setVisibility(View.GONE); settle(8);
        Rect hiddenBounds=new Rect();
        assertFalse("Hidden controller has no visible glass",glass.getVisibleBoundsInWindow(hiddenBounds));
        assertFalse("Neither glass nor the classic toolbar occupies the viewport",home.getVisibleHomeToolbarBoundsInWindow(hiddenBounds));
        Rect released=assertTightClearance(61,null,false);
        assertTrue("Removing a real obstruction moves the circle up while preserving its native gap",released.top<withGlass.top);
        glass.getView().setVisibility(View.VISIBLE); glass.setStatusBarInset(29); settle(60);
        assertTrue(glass.getVisibleBoundsInWindow(actualGlass)); assertTightClearance(61,actualGlass,true);
        int glassPadding=rows().getPaddingTop();
        Rect listBounds=bounds(rows()), firstBounds=bounds(rows().getChildAt(0));
        failHead(heads().get(0),503); settle(12);
        assertFalse(refresh().isRefreshing());
        assertEquals(glassPadding,rows().getPaddingTop());
        assertEquals(listBounds,bounds(rows()));
        assertEquals(firstBounds,bounds(rows().getChildAt(0)));
        timeline.onRefresh(); settle(20);
        assertTightClearance(61,actualGlass,true);
        assertEquals(glassPadding,rows().getPaddingTop());
        assertEquals(firstBounds,bounds(rows().getChildAt(0)));
    }

    @Test public void tooShortViewportHidesOnlyTheCircleUntilItFitsAgain() throws Exception{
        open(true);
        assertSafe(0,bounds(toolbar()));
        height=bounds(refresh()).top+refresh().getProgressCircleDiameter()-4;
        settle(8);
        assertTrue(refresh().isRefreshing());
        assertEquals(0f,circle().getAlpha(),0f);
        assertTrue(rows().isShown());
        assertEquals(0,rows().getPaddingTop());
        height=720; settle(12);
        assertSafe(0,bounds(toolbar()));
        assertEquals(1,heads().size());
    }

    @Test public void foregroundRevalidationUsesTheSameCircleWithoutMovingRows() throws Exception{
        open(true);
        callback(heads().get(0)).onSuccess(List.of(post("p_refresh_layout"))); settle(12);
        assertFalse(refresh().isRefreshing());
        Rect listBounds=bounds(rows()), firstBounds=bounds(rows().getChildAt(0));
        timeline.getArguments().putBoolean("noAutoLoad",false);
        timeline.onShown(); settle(12);
        assertEquals("Returning to a fresh feed must not repeat the head GET",1,heads().size());
        ShadowLooper.idleMainLooper(31,TimeUnit.SECONDS);
        timeline.onShown(); settle(12);
        assertEquals(2,heads().size());
        assertTrue(refresh().isRefreshing());
        assertEquals(listBounds,bounds(rows())); assertEquals(firstBounds,bounds(rows().getChildAt(0)));
        failHead(heads().get(1),503); settle(12);
        assertFalse(refresh().isRefreshing());
        assertEquals(listBounds,bounds(rows())); assertEquals(firstBounds,bounds(rows().getChildAt(0)));
    }

    @Test public void automaticHeadKeepsTheVisiblePostAnchorWhenAuthorityPrepends() throws Exception{
        List<Status> cached=new ArrayList<>();
        for(int i=0;i<12;i++) cached.add(post("p_anchor_"+i));
        var cache=session.getCacheController();
        cache.putHomeTimelineSnapshot(cache.beginHomeTimelineSnapshot(),cached,null);
        HomeTimelineSnapshotCacheTest.drain();
        open(true);
        LinearLayoutManager manager=(LinearLayoutManager)rows().getLayoutManager();
        manager.scrollToPositionWithOffset(10,-11); settle(8);
        int position=manager.findFirstVisibleItemPosition();
        assertTrue(position>0);
        var visible=timeline.getDisplayItems().get(position);
        int top=manager.getDecoratedTop(manager.findViewByPosition(position));
        List<Status> authority=new ArrayList<>(List.of(post("p_arrival")));
        authority.addAll(cached);
        callback(heads().get(0)).onSuccess(authority); settle(12);
        assertFalse(refresh().isRefreshing());
        assertEquals(0,rows().getPaddingTop());
        int after=manager.findFirstVisibleItemPosition();
        assertEquals(visible.parentID,timeline.getDisplayItems().get(after).parentID);
        assertEquals(visible.getType(),timeline.getDisplayItems().get(after).getType());
        assertEquals(top,manager.getDecoratedTop(manager.findViewByPosition(after)));
        assertEquals(1,heads().size());
    }

    @Test public void nativeGestureCoalescesWithAnActiveHeadAndRestartsAfterFailure() throws Exception{
        open(true);
        SwipeRefreshLayout wrapper=refresh();
        Rect before=bounds(rows().getChildAt(0));
        GetAllTimeline first=heads().get(0);
        pullToRefresh(wrapper); settle(20);
        assertEquals("An active native refresh doesn't dispatch a duplicate gesture",1,heads().size());
        assertTrue(wrapper.isRefreshing());
        failHead(first,503); settle(20);
        assertFalse(wrapper.isRefreshing());
        assertEquals("The finished native scale-out clears the old indicator",View.GONE,circle().getVisibility());
        assertEquals(before,bounds(rows().getChildAt(0)));
        assertTrue("Native refresh remains enabled after the first failure",wrapper.isEnabled());
        assertFalse("The pull starts at the top of the feed",wrapper.canChildScrollUp());
        pullToRefresh(wrapper); settle(1);
        assertTrue("SwipeRefreshLayout must accept the pull as its animation starts",wrapper.isRefreshing());
        settle(36);
        assertEquals("The existing SwipeRefreshLayout listener must dispatch a new head",2,heads().size());
        assertTrue(wrapper.isRefreshing());
        assertEquals(before,bounds(rows().getChildAt(0)));
        callback(first).onSuccess(List.of(post("p_discard")));
        assertTrue("Late old delivery cannot stop the current refresh",wrapper.isRefreshing());
        callback(heads().get(1)).onSuccess(List.of(post("p_refresh_layout"))); settle(12);
        assertFalse(wrapper.isRefreshing());
    }

    @Test public void temporaryDetachStopsTheDrawableButReattachUsesTheSamePendingHead() throws Exception{
        open(false);
        SwipeRefreshLayout wrapper=refresh();
        ImageView indicator=circle();
        View view=timeline.getView();
        ViewGroup parent=(ViewGroup)view.getParent();
        int index=parent.indexOfChild(view);
        ViewGroup.LayoutParams params=view.getLayoutParams();
        parent.removeView(view);
        assertFalse(wrapper.isAttachedToWindow());
        assertFalse(wrapper.isRefreshing());
        assertEquals(View.GONE,indicator.getVisibility());
        assertEquals(1,heads().size());
        parent.addView(view,index,params); settle(24);
        assertSame(indicator,circle()); assertSafe(0,bounds(toolbar()));
        assertEquals(1,heads().size());
        failHead(heads().get(0),503); settle(12);
        assertFalse(wrapper.isRefreshing());
    }

    private void pullToRefresh(SwipeRefreshLayout wrapper){
        // Start on a measured post row, not an inert gap or an overlaid action.
        Rect start=bounds(rows().getChildAt(0));
        Rect wrapperBounds=bounds(wrapper);
        assertTrue("The first post intersects the refresh wrapper",start.intersect(wrapperBounds));
        float x=start.exactCenterX()-wrapperBounds.left;
        float y=start.exactCenterY()-wrapperBounds.top;
        long now=SystemClock.uptimeMillis();
        assertTrue("The real refresh hierarchy handles touch down",sendTouch(wrapper,now,0,MotionEvent.ACTION_DOWN,x,y));
        sendTouch(wrapper,now,20,MotionEvent.ACTION_MOVE,x,y+30);
        sendTouch(wrapper,now,60,MotionEvent.ACTION_MOVE,x,y+205);
        sendTouch(wrapper,now,80,MotionEvent.ACTION_UP,x,y+205);
    }

    private boolean sendTouch(View view,long down,long elapsed,int action,float x,float y){
        MotionEvent event=MotionEvent.obtain(down,down+elapsed,action,x,y,0);
        try{ return view.dispatchTouchEvent(event); }
        finally{ event.recycle(); }
    }

    @Test public void timelineStripHidesAndReleasesPagerSpaceOnlyWithInstalledLiquidToolbar() throws Exception{
        open(true);
        HomeTimelineTabStrip strip=org.robolectric.util.ReflectionHelpers.getField(home,"timelineTabs");
        ViewPager2 pager=org.robolectric.util.ReflectionHelpers.getField(home,"pager");
        GlobalUserPreferences.useIosLiquidNavigation=true;
        home.setLiquidToolbarController(null);
        assertEquals(View.VISIBLE,strip.getVisibility());
        assertEquals(V.dp(48),((FrameLayout.LayoutParams)pager.getLayoutParams()).topMargin);
        glass=new HomeLiquidToolbarController(activity,home::onLiquidTimelineSelected,home::onLiquidNewPosts,home::onLiquidCompose,home::onLiquidMenuItem);
        home.setLiquidToolbarController(glass);
        assertEquals(View.GONE,strip.getVisibility());
        assertEquals(0,((FrameLayout.LayoutParams)pager.getLayoutParams()).topMargin);
        home.setLiquidToolbarController(null);
        settle(8);
        assertEquals(View.VISIBLE,strip.getVisibility());
        assertEquals(bounds(strip).bottom,bounds(pager).top);
    }

    @Test public void timelineStripPlusOpensEditorWithTheCurrentAccountAndDoesNotSelectATab() throws Exception{
        open(true);
        HomeTimelineTabStrip strip=org.robolectric.util.ReflectionHelpers.getField(home,"timelineTabs");
        ViewPager2 pager=org.robolectric.util.ReflectionHelpers.getField(home,"pager");
        View plus=strip.getChildAt(1);
        assertEquals(activity.getString(R.string.sk_edit_timelines),plus.getContentDescription());
        assertTrue(plus.performClick());
        activity.getFragmentManager().executePendingTransactions(); settle(32);
        EditTimelinesFragment editor=activity.getFragmentManager().getFragments().stream()
                .filter(EditTimelinesFragment.class::isInstance).filter(f->f.isAdded()).map(EditTimelinesFragment.class::cast).findFirst().orElseThrow();
        assertEquals(session.getID(),editor.getArguments().getString("account"));
        assertEquals(0,pager.getCurrentItem());
        activity.onBackPressed(); settle(32);
        assertTrue(home.isAdded());
        assertEquals(0,pager.getCurrentItem());
    }

    @Test public void restoresTimelineByDefinitionWhenNbwIsHidden() throws Exception{
        session.getLocalPreferences().timelines=new ArrayList<>(List.of(TimelineDefinition.HOME_TIMELINE.copy(),
                TimelineDefinition.NBW_TIMELINE.copy(),TimelineDefinition.FOLLOWING_TIMELINE.copy(),TimelineDefinition.ofPopular(),TimelineDefinition.ofGeo("测试省")));
        open(true);
        ViewPager2 pager=org.robolectric.util.ReflectionHelpers.getField(home,"pager");
        assertEquals(4,pager.getAdapter().getItemCount());
        Bundle legacyState=new Bundle(); legacyState.putInt("selectedTab",1);
        home.onViewStateRestored(legacyState); settle(8);
        assertEquals(1,pager.getCurrentItem());
        assertTrue(home.getCurrentFragment() instanceof FollowingTimelineFragment);
        Bundle saved=new Bundle(); home.onSaveInstanceState(saved);
        assertEquals(2,saved.getInt("selectedTimelineId"));
        pager.setCurrentItem(0,false); settle(8);
        home.onViewStateRestored(saved); settle(8);
        assertEquals(1,pager.getCurrentItem());
        assertTrue(home.getCurrentFragment() instanceof FollowingTimelineFragment);
    }

    @Test public void realHomeFabFanClickOpensCreatorHostWithCurrentAccount() throws Exception{
        open(true);
        assertTrue(home.getFab().performClick()); settle(60);
        org.joinmastodon.android.ui.compose.navigation.ComposeFanMenuController fan=
                (org.joinmastodon.android.ui.compose.navigation.ComposeFanMenuController)HomeTimelineSnapshotCacheTest.field(HomeTabFragment.class,"fanMenu").get(home);
        assertTrue(fan.isExpanded());
        android.widget.ImageView novelIcon=findNovelIcon(fan.getView());
        assertNotNull("Actual folded-out fan must paint the unified novel glyph",novelIcon);
        assertTrue(novelIcon.isAttachedToWindow());
        Rect icon=bounds(novelIcon), overlay=bounds(fan.getView());
        float x=icon.exactCenterX()-overlay.left, y=icon.exactCenterY()-overlay.top;
        long now=android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,x,y,0);
        android.view.MotionEvent up=android.view.MotionEvent.obtain(now,now+60,android.view.MotionEvent.ACTION_UP,x,y,0);
        try{ fan.getView().dispatchTouchEvent(down); fan.getView().dispatchTouchEvent(up); }
        finally{ down.recycle(); up.recycle(); }
        settle(8);
        android.content.Intent entry=((org.robolectric.shadows.ShadowActivity)org.robolectric.shadow.api.Shadow.extract(activity)).getNextStartedActivity();
        assertNotNull("Real FAB -> real fan -> shared route must start the novel host",entry);
        assertEquals(org.joinmastodon.android.novel.app.NovelAppActivity.class.getName(),entry.getComponent().getClassName());
        assertEquals(session.getID(),entry.getStringExtra("account"));
        assertTrue(entry.getBooleanExtra("creator_center",false));
        assertNull(entry.getStringExtra("work_id")); assertNull(entry.getStringExtra("chapter_id"));
        assertFalse(fan.isExpanded());
    }

    private static android.widget.ImageView findNovelIcon(View view){
        if(view instanceof android.widget.ImageView image && Integer.valueOf(R.drawable.ic_novel_rebuilt).equals(image.getTag())) return image;
        if(view instanceof ViewGroup group) for(int i=0;i<group.getChildCount();i++){
            android.widget.ImageView found=findNovelIcon(group.getChildAt(i));
            if(found!=null) return found;
        }
        return null;
    }

    /** Set the real attached window's physical origin (as ViewRoot does for split/freeform mode).
     * Do not mock getGlobalVisibleRect: its root coordinates must stay distinct from screen ones. */
    private void moveWindowOrigin(int x,int y) throws Exception{
        View decor=activity.getWindow().getDecorView();
        Object attach=HomeTimelineSnapshotCacheTest.field(View.class,"mAttachInfo").get(decor);
        assertNotNull("Window must really be attached",attach);
        Object viewRoot=decor.getParent();
        Rect frame=(Rect)HomeTimelineSnapshotCacheTest.field(viewRoot.getClass(),"mWinFrame").get(viewRoot);
        frame.offsetTo(x,y);
        HomeTimelineSnapshotCacheTest.field(attach.getClass(),"mWindowLeft").setInt(attach,x);
        HomeTimelineSnapshotCacheTest.field(attach.getClass(),"mWindowTop").setInt(attach,y);
        int[] screen=new int[2],window=new int[2];
        decor.getLocationOnScreen(screen); decor.getLocationInWindow(window);
        assertEquals(x,screen[0]-window[0]); assertEquals(y,screen[1]-window[1]);
    }

    private void assertWindowOrigin(int x,int y){
        View decor=activity.getWindow().getDecorView(); int[] screen=new int[2],window=new int[2];
        decor.getLocationOnScreen(screen); decor.getLocationInWindow(window);
        assertEquals("Keep physical origin during layout",x,screen[0]-window[0]);
        assertEquals("Keep physical origin during layout",y,screen[1]-window[1]);
    }

    @Test public void nonzeroFreeformWindowOriginDoesNotHideEmbeddedCircleAcrossRotationInsetsAndToolbarChanges() throws Exception{
        open(true);
        ViewGroup.LayoutParams lp=toolbar().getLayoutParams(); lp.height=93; toolbar().setLayoutParams(lp);
        insets(13,39,21,25);
        Rect baseline=assertSafe(39,bounds(toolbar()));
        moveWindowOrigin(430,1100); settle(8); assertWindowOrigin(430,1100);
        assertEquals("Screen displacement must not be subtracted from the window-local circle",baseline,assertSafe(39,bounds(toolbar())));
        Rect actual=new Rect(); assertTrue(home.getVisibleHomeToolbarBoundsInWindow(actual));
        assertEquals("Parent toolbar and circle must use the same window coordinates",bounds(toolbar()),actual);
        width=720; height=320; insets(31,23,47,19); moveWindowOrigin(210,900); settle(8); assertWindowOrigin(210,900);
        Rect rotated=assertSafe(23,bounds(toolbar()));
        assertTrue(rotated.left>=31 && rotated.right<=width-47);
        toolbar().setVisibility(View.GONE); settle(8); assertSafe(23,null);
        toolbar().setVisibility(View.VISIBLE); lp.height=67; toolbar().setLayoutParams(lp); settle(8);
        assertSafe(23,bounds(toolbar()));
    }

    @Test public void nonzeroFreeformOriginKeepsStandaloneScrolledViewportInWindowCoordinates() throws Exception{
        open(false); insets(17,41,29,27);
        ViewGroup.LayoutParams lp=toolbar().getLayoutParams(); lp.height=111; toolbar().setLayoutParams(lp); settle(6);
        NestedRecyclerScrollView scroller=timeline.getView().findViewById(R.id.scroller);
        scroller.scrollTo(0,73); settle(6);
        Rect baseline=assertSafe(41,bounds(toolbar()));
        moveWindowOrigin(350,1200); settle(6); assertWindowOrigin(350,1200);
        assertEquals(baseline,assertSafe(41,bounds(toolbar())));
        width=720; height=320; insets(27,21,43,19); moveWindowOrigin(190,830); settle(6); assertWindowOrigin(190,830);
        Rect rotated=assertSafe(21,bounds(toolbar()));
        assertTrue(rotated.left>=27 && rotated.right<=width-43);
    }

    // Native raster tests use classic chrome. Robolectric's software canvas does not support
    // the liquid toolbar's RuntimeShader; its measured geometry is tested above without rasterizing it.
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void attachedClassicLight320Font160UsesActualToolbarAndNativeCircle() throws Exception{ renderTopGeometry(false); }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers="zh-rCN-w320dp-h720dp-night-mdpi")
    public void attachedClassicDark320Font160UsesActualToolbarAndNativeCircle() throws Exception{ renderTopGeometry(true); }

    private void renderTopGeometry(boolean dark) throws Exception{
        darkTheme=dark;
        android.content.res.Configuration config=new android.content.res.Configuration(MastodonApp.context.getResources().getConfiguration());
        config.fontScale=1.6f; MastodonApp.context.getResources().updateConfiguration(config,MastodonApp.context.getResources().getDisplayMetrics());
        open(true);
        insets(0,37,0,24); settle(64);
        Rect obstruction=new Rect();
        assertTrue(home.getVisibleHomeToolbarBoundsInWindow(obstruction));
        assertTightClearance(37,obstruction,true);
        capture("home-top-classic-"+(dark?"dark":"light")+"-320-font160");
        NestedRecyclerScrollView scroller=timeline.getView().findViewById(R.id.scroller);
        scroller.scrollTo(0,29); settle(12);
        assertTrue(home.getVisibleHomeToolbarBoundsInWindow(obstruction)); assertTightClearance(37,obstruction,false);
        width=720; height=320; insets(19,23,31,19); settle(12);
        assertTrue(home.getVisibleHomeToolbarBoundsInWindow(obstruction)); assertTightClearance(23,obstruction,false);
        assertEquals("Geometry must not refresh data",1,heads().size());
    }

    private Rect assertTightClearance(int topInset, Rect obstruction, boolean checkHeader){
        ImageView indicator=circle();
        assertEquals("Measure the actual native circle after its scale-in",1f,indicator.getScaleY(),0.001f);
        assertTrue("The native circle must be running",((CircularProgressDrawable)indicator.getDrawable()).isRunning());
        Rect visible=assertSafe(topInset,obstruction);
        SwipeRefreshLayout wrapper=refresh();
        int clearanceTop=Math.max(topInset,visibleViewportInWindow(wrapper).top);
        if(obstruction!=null) clearanceTop=Math.max(clearanceTop,obstruction.bottom);
        int nativeOffset=wrapper.getProgressViewStartOffset()+wrapper.getProgressViewEndOffset();
        assertEquals("Use the native resting offset below the real viewport, not a second toolbar slot",clearanceTop+nativeOffset,visible.top);
        int gap=activity.getResources().getDimensionPixelSize(R.dimen.home_toolbar_content_gap);
        int padding=obstruction!=null && home.hasVisibleLiquidToolbar() ? Math.max(0,clearanceTop+gap-bounds(rows()).top) : 0;
        assertEquals("Only liquid glass, never the refresh circle, adds list clearance",padding,rows().getPaddingTop());
        if(checkHeader){
            View first=((LinearLayoutManager)rows().getLayoutManager()).findViewByPosition(0);
            assertNotNull("Cached first post is actually attached",first);
            assertTrue("The first post doesn't reserve the height of the circle",bounds(first).top<visible.bottom);
        }
        return visible;
    }

    private void capture(String name) throws Exception{
        String directory=System.getProperty("beta35RenderDir",System.getProperty("homeHistoryRenderDir"));
        if(directory==null) return;
        File file=new File(directory,name+".png"); assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        View root=activity.getWindow().getDecorView(); Bitmap image=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(image));
        try(FileOutputStream output=new FileOutputStream(file)){ assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,output)); }
        image.recycle();
    }
}
