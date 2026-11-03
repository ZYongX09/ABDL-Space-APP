package org.joinmastodon.android.fragments;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toolbar;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.lifecycle.ViewModelStore;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.savedstate.SavedStateRegistry;
import androidx.savedstate.SavedStateRegistryController;
import androidx.savedstate.SavedStateRegistryOwner;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.HomeTimelineSnapshotCacheTest;
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

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import me.grishka.appkit.FragmentStackActivity;
import me.grishka.appkit.utils.V;

import static org.junit.Assert.*;

/** Real attached HomeTab -> pager -> HomeTimeline -> scroller/content hierarchy and real offline
 * cache hydration. No mHost injection, replacement refresh view, delayed-handler suppression,
 * attach/GPU gate relaxation, or fixed test-only refresh positioning. */
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

    @Before @SuppressWarnings("unchecked") public void setup() throws Exception{
        ShadowChoreographer.setPaused(true);
        MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context);
        org.joinmastodon.android.ui.utils.UiUtils.updateLocalizedDateFormatters(MastodonApp.context);
        AccountSessionManager manager=AccountSessionManager.getInstance();
        sessions=(Map<String,AccountSession>)HomeTimelineSnapshotCacheTest.field(AccountSessionManager.class,"sessions").get(manager);
        instances=(Map<String,Instance>)HomeTimelineSnapshotCacheTest.field(AccountSessionManager.class,"instances").get(manager);
        session=HomeTimelineSnapshotCacheTest.session("refresh-layout.offline.example.test","refresh-layout-42");
        session.self.sponsor=new org.joinmastodon.android.model.Account.PublicSponsor();
        session.self.sponsor.active=session.self.sponsor.permanent=true;
        previousSession=sessions.put(session.getID(),session);
        previousActive=manager.getLastActiveAccountID(); HomeTimelineSnapshotCacheTest.field(AccountSessionManager.class,"lastActiveAccountID").set(manager,session.getID());
        session.getLocalPreferences().timelines=new ArrayList<>(List.of(TimelineDefinition.HOME_TIMELINE.copy()));
        InstanceV1 instance=new InstanceV1(); instance.uri=instance.normalizedUri=session.domain; instance.version="4.3.0";
        previousInstance=instances.put(session.domain,instance);
        previousLiquid=GlobalUserPreferences.useIosLiquidNavigation; GlobalUserPreferences.useIosLiquidNavigation=false;
        previousReduceMotion=GlobalUserPreferences.reduceMotion; GlobalUserPreferences.reduceMotion=true;
        HomeTimelineHistoryTest.OfflineRequests.sent.clear(); HomeTimelineHistoryTest.OfflineRequests.canceled.clear();
        Status post=HomeTimelineSnapshotCacheTest.post("p_refresh_layout");
        post.account.avatar=post.account.avatarStatic="https://refresh-layout.offline.example.test/fictional.png";
        session.getCacheController().putHomeTimelineSnapshot(session.getCacheController().beginHomeTimelineSnapshot(),List.of(post),null);
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
        HomeTimelineHistoryTest.OfflineRequests.sent.clear(); HomeTimelineHistoryTest.OfflineRequests.canceled.clear();
    }

    private void open(boolean embedded) throws Exception{
        lifecycle=Robolectric.buildActivity(HostActivity.class); activity=lifecycle.get(); activity.setTheme(R.style.Theme_Mastodon_Light);
        lifecycle.setup().visible();
        Bundle args=new Bundle(); args.putString("account",session.getID()); args.putBoolean("noAutoLoad",true);
        if(embedded){
            home=new HomeTabFragment(); home.setArguments(args); activity.showFragment(home);
        }else{
            timeline=new HomeTimelineFragment(); timeline.setArguments(args); activity.showFragment(timeline);
        }
        activity.getFragmentManager().executePendingTransactions();
        for(int i=0;i<4;i++){
            if(home!=null) home.getChildFragmentManager().executePendingTransactions();
            HomeTimelineSnapshotCacheTest.drain(); settle(8);
        }
        if(home!=null) timeline=(HomeTimelineFragment)home.getCurrentFragment();
        assertNotNull(timeline);
        assertTrue(timeline.getView().isAttachedToWindow());
        assertEquals(View.VISIBLE,pill().getVisibility());
        assertTrue("Use a real in-flight head, not a manually shown hint", HomeTimelineSnapshotCacheTest.field(HomeTimelineFragment.class,"homeHeadInFlight").getBoolean(timeline));
    }

    private void settle(int frames){
        for(int i=0;i<frames;i++){
            ShadowLooper.idleMainLooper(16,TimeUnit.MILLISECONDS);
            View root=activity.getWindow().getDecorView(); root.forceLayout();
            root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
            root.layout(0,0,width,height);
            root.getViewTreeObserver().dispatchOnPreDraw();
        }
    }
    private TextView pill(){ return timeline.getView().findViewById(R.id.home_timeline_refreshing); }
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
    private Rect assertSafe(int topInset, Rect obstruction){
        TextView text=pill(); assertEquals(TextView.class,text.getClass());
        assertEquals(View.VISIBLE,text.getVisibility()); assertEquals(1f,text.getAlpha(),0f);
        assertFalse(text.isClickable()); assertFalse(text.isFocusable());
        for(var drawable:text.getCompoundDrawables()) assertNull(drawable);
        Rect hint=bounds(text), root=bounds(activity.getWindow().getDecorView());
        assertTrue("Hint is measured and readable: "+hint, hint.width()>0 && hint.height()>0);
        assertTrue("No status-bar overlap: "+hint, hint.top>=topInset);
        assertTrue("Hint stays inside the real viewport: "+hint+" / "+root, root.contains(hint));
        if(obstruction!=null){
            assertTrue("Known toolbar bottom must be cleared: "+hint+" / "+obstruction, hint.top>=obstruction.bottom);
            assertFalse("No real toolbar/glass overlap",Rect.intersects(hint,obstruction));
        }
        assertNotNull(text.getLayout());
        for(int line=0;line<text.getLineCount();line++) assertEquals(0,text.getLayout().getEllipsisCount(line));
        return hint;
    }

    @Test public void embeddedClassicUsesKnownActualParentToolbarAndRecoversAfterHideResizeAndScroll() throws Exception{
        open(true);
        assertSame(home,timeline.getParentFragment());
        assertNull("AppKit removes a tab's own toolbar; parent geometry is required",timeline.getToolbar());
        Toolbar bar=toolbar(); ViewGroup.LayoutParams lp=bar.getLayoutParams(); lp.height=91; bar.setLayoutParams(lp);
        insets(0,37,0,24);
        Rect barBounds=bounds(bar); assertEquals(91,barBounds.height());
        Rect before=assertSafe(37,barBounds);
        NestedRecyclerScrollView scroller=timeline.getView().findViewById(R.id.scroller);
        scroller.scrollTo(0,29); settle(8); assertSafe(37,bounds(bar));
        bar.setVisibility(View.GONE); settle(8); Rect hidden=assertSafe(37,null);
        assertTrue("Removed toolbar must release its occupied space",hidden.top<before.top);
        bar.setVisibility(View.VISIBLE); lp.height=117; bar.setLayoutParams(lp); settle(8);
        assertSafe(37,bounds(bar));
        width=720; height=320; settle(10); assertSafe(37,bounds(bar));
        assertEquals("Layout changes must not start extra home head requests",1,
                HomeTimelineHistoryTest.OfflineRequests.sent.stream().filter(GetAllTimeline.class::isInstance).count());
    }

    @Test @Config(qualifiers="zh-rCN-w320dp-h720dp-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void standaloneScrollerLargeTextAndSideSystemInsetsKeepExactPureTextVisible() throws Exception{
        android.content.res.Configuration config=new android.content.res.Configuration(MastodonApp.context.getResources().getConfiguration());
        config.fontScale=1.6f; MastodonApp.context.getResources().updateConfiguration(config,MastodonApp.context.getResources().getDisplayMetrics());
        open(false); insets(18,43,26,24);
        assertEquals("正在刷新中......",pill().getText().toString());
        assertEquals(1.6f,pill().getResources().getConfiguration().fontScale,.001f);
        Toolbar bar=toolbar(); ViewGroup.LayoutParams lp=bar.getLayoutParams(); lp.height=103; bar.setLayoutParams(lp); settle(8);
        assertSafe(43,bounds(bar));
        NestedRecyclerScrollView scroller=timeline.getView().findViewById(R.id.scroller);
        scroller.scrollTo(0,71); settle(8); Rect hint=assertSafe(43,bounds(bar));
        assertTrue(hint.left>=18 && hint.right<=width-26);
        capture("home-refresh-standalone-zh-font160");
        scroller.scrollTo(0,0); settle(8); assertSafe(43,bounds(bar));
    }

    @Test public void attachedActualLiquidGlassBoundsReplaceClassicGeometryAndDisappearWithoutStaleOffset() throws Exception{
        open(true);
        glass=new HomeLiquidToolbarController(activity,home::onLiquidTimelineSelected,home::onLiquidNewPosts,home::onLiquidCompose,home::onLiquidMenuItem,home::openSearch);
        glass.setTimelines(List.of(new HomeToolbarTimeline(0,"Home",R.drawable.ic_fluent_home_24_regular)),0);
        glass.setComposeMenu(HomeFragment.composeMenuItems(activity)); glass.setStatusBarInset(61);
        ViewGroup host=(ViewGroup)home.getView().getParent();
        host.addView(glass.getView(),new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        home.setLiquidToolbarController(glass);
        // Exercise the installed overlay hierarchy directly, without pretending the software test
        // window passed HomeFragment's production hardware-attachment gate.
        toolbar().setVisibility(View.GONE); timeline.setLiquidToolbarMode(true);
        insets(0,61,0,24); toolbar().setVisibility(View.GONE); settle(60);
        Rect actualGlass=new Rect(); assertTrue("Actual attached Compose glass must be measured",glass.getVisibleBoundsInWindow(actualGlass));
        assertTrue(actualGlass.bottom>61 && actualGlass.height()<glass.getView().getHeight());
        Rect beforeMove=assertSafe(61,actualGlass);
        moveWindowOrigin(410,1050); settle(8); assertWindowOrigin(410,1050);
        assertTrue(glass.getVisibleBoundsInWindow(actualGlass));
        assertEquals("Real glass and hint remain window-relative in a floating window",beforeMove,assertSafe(61,actualGlass));
        width=720; height=320; insets(23,31,39,19); toolbar().setVisibility(View.GONE);
        glass.setStatusBarInset(31); moveWindowOrigin(250,880); settle(60);
        assertTrue(glass.getVisibleBoundsInWindow(actualGlass)); assertSafe(31,actualGlass);
        width=320; height=720; insets(0,61,0,24); toolbar().setVisibility(View.GONE);
        glass.setStatusBarInset(61); settle(60);
        assertTrue(glass.getVisibleBoundsInWindow(actualGlass)); assertSafe(61,actualGlass);
        glass.getView().setVisibility(View.GONE); settle(8); Rect released=assertSafe(61,null);
        assertTrue("Hidden glass must not leave a stale inset",released.top<actualGlass.bottom);
        glass.getView().setVisibility(View.VISIBLE); glass.setStatusBarInset(29); settle(60);
        assertTrue(glass.getVisibleBoundsInWindow(actualGlass)); assertSafe(61,actualGlass);
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

    @Test public void nonzeroFreeformWindowOriginDoesNotHideEmbeddedHintAcrossRotationInsetsAndToolbarChanges() throws Exception{
        open(true);
        ViewGroup.LayoutParams lp=toolbar().getLayoutParams(); lp.height=93; toolbar().setLayoutParams(lp);
        insets(13,39,21,25);
        Rect baseline=assertSafe(39,bounds(toolbar()));
        moveWindowOrigin(430,1100); settle(8); assertWindowOrigin(430,1100);
        assertEquals("Screen displacement must not be subtracted from window-local hint",baseline,assertSafe(39,bounds(toolbar())));
        Rect actual=new Rect(); assertTrue(home.getVisibleHomeToolbarBoundsInWindow(actual));
        assertEquals("Parent toolbar and hint must use the same window coordinates",bounds(toolbar()),actual);
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
