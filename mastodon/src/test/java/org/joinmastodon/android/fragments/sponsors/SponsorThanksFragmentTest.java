package org.joinmastodon.android.fragments.sponsors;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.JsonObject;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.requests.sponsors.SponsorRequest;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.model.sponsors.SponsorModels.ThanksItem;
import org.joinmastodon.android.model.sponsors.SponsorModels.ThanksPage;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.RealObject;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.imageloader.ImageCache;
import me.grishka.appkit.imageloader.ImageLoaderCallback;
import me.grishka.appkit.imageloader.requests.ImageLoaderRequest;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.V;

import static org.junit.Assert.*;

/** Real FragmentManager lifecycle, controller, adapter and widgets; only external effects are recorded. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=31, application=CompatibilityTestApplication.class, shadows={
        SponsorThanksFragmentTest.ApiExecution.class, SponsorThanksFragmentTest.Images.class,
        SponsorThanksFragmentTest.PendingImages.class, SponsorThanksFragmentTest.Navigation.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class SponsorThanksFragmentTest{
    private ActivityController<Activity> host;
    private boolean hostResumed;
    private SponsorThanksFragment fragment;
    private AccountSessionManager manager;
    private Map<String, AccountSession> sessions;
    private AccountSession previousSession;
    private String accountId, previousActive;

    @Before @SuppressWarnings("unchecked") public void setup() throws Exception{
        Context context=RuntimeEnvironment.getApplication();
        MastodonApp.context=context; V.setApplicationContext(context);
        ApiExecution.requests.clear(); ApiExecution.accounts.clear();
        Images.reset(); PendingImages.canceled.clear(); Navigation.reset();
        manager=AccountSessionManager.getInstance();
        sessions=ReflectionHelpers.getField(manager, "sessions");
        previousActive=manager.getLastActiveAccountID();
        Constructor<AccountSession> constructor=AccountSession.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        AccountSession session=constructor.newInstance(); session.domain="thanks-offline.example.test";
        session.self=new Account(); session.self.id="thanks-test"; session.token=new Token();
        accountId=session.getID(); previousSession=sessions.put(accountId, session);
        ReflectionHelpers.setField(manager, "lastActiveAccountID", accountId);

        host=Robolectric.buildActivity(Activity.class).setup(); hostResumed=true;
        Activity activity=host.get(); activity.setTheme(R.style.Theme_Mastodon_Dark);
        FrameLayout root=new FrameLayout(activity); root.setId(12345); activity.setContentView(root);
        fragment=new SponsorThanksFragment();
        Bundle args=new Bundle(); args.putString("account", accountId); fragment.setArguments(args);
        activity.getFragmentManager().beginTransaction().add(12345, fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        assertEquals(1, ApiExecution.requests.size());
    }

    @After public void cleanup(){
        if(host!=null){
            if(hostResumed) host.pause();
            host.stop().destroy();
        }
        if(sessions!=null){
            if(previousSession==null) sessions.remove(accountId); else sessions.put(accountId, previousSession);
            ReflectionHelpers.setField(manager, "lastActiveAccountID", previousActive);
        }
        ApiExecution.requests.clear(); ApiExecution.accounts.clear();
        Images.reset(); PendingImages.canceled.clear(); Navigation.reset();
    }

    @Test public void actualGridBindsFourColumnsWithServerNamesRanksAndAvatarUrls(){
        MastodonAPIRequest<?> request=last();
        assertEquals("GET", request.getMethod());
        assertTrue(request.getURL().getPath().endsWith("/sponsors/thanks"));
        assertEquals("100", request.getURL().getQueryParameter("limit"));
        assertEquals("0", request.getURL().getQueryParameter("offset"));
        assertEquals(accountId, ApiExecution.accounts.get(0));
        ThanksPage page=page("赞助者");
        reply(request, page);

        assertEquals(4, ((GridLayoutManager)grid().getLayoutManager()).getSpanCount());
        assertEquals(4, grid().getAdapter().getItemCount());
        for(int i=0;i<4;i++){
            View cell=bind(i);
            String expectedName=i==1 ? "user-2" : "赞助者"+(i+1);
            assertNotNull(text(cell, expectedName));
            assertNotNull(text(cell, String.valueOf(i+1)));
            assertEquals(host.get().getString(R.string.sponsor_thanks_accessibility, i+1, expectedName), cell.getContentDescription());
            UrlImageLoaderRequest image=Images.requests.get(Images.requests.size()-1);
            assertEquals(i==1 ? page.items.get(i).avatar : page.items.get(i).avatarStatic, image.uri.toString());
        }
    }

    @Test public void pauseCancelsPollingRequestAndAvatarsAndResumeImmediatelyRefreshes(){
        reply(last(), page("旧榜"));
        bind(0);
        ImageCache.PendingImageRequest avatar=Images.pending.get(Images.pending.size()-1);
        ShadowLooper.idleMainLooper(14999, TimeUnit.MILLISECONDS);
        assertEquals(1, ApiExecution.requests.size());
        ShadowLooper.idleMainLooper(1, TimeUnit.MILLISECONDS);
        assertEquals(2, ApiExecution.requests.size());
        MastodonAPIRequest<?> inFlight=last();

        host.pause(); hostResumed=false;
        assertTrue((Boolean)ReflectionHelpers.getField(inFlight, "canceled"));
        assertTrue(PendingImages.canceled.contains(avatar));
        assertNull(grid().getAdapter());
        ShadowLooper.idleMainLooper(45, TimeUnit.SECONDS);
        assertEquals(2, ApiExecution.requests.size());
        reply(inFlight, page("过期回调"));
        assertNull(grid().getAdapter());

        host.resume(); hostResumed=true;
        assertEquals(3, ApiExecution.requests.size());
        assertEquals("0", last().getURL().getQueryParameter("offset"));
        assertNotNull(grid().getAdapter());
        assertNotNull(text(bind(0), "旧榜1"));
        reply(last(), page("刷新榜"));
        assertNotNull(text(bind(0), "刷新榜1"));
    }

    @Test public void clickingActualSupportButtonNavigatesToCenterWithAccountAndStopsWork(){
        MastodonAPIRequest<?> inFlight=last();
        Button support=(Button)text(fragment.getView(), host.get().getString(R.string.sponsor_thanks_support));
        assertNotNull(support); assertTrue(support.performClick());
        assertSame(host.get(), Navigation.activity);
        assertEquals(SponsorCenterFragment.class, Navigation.destination);
        assertEquals(accountId, Navigation.arguments.getString("account"));
        assertTrue((Boolean)ReflectionHelpers.getField(inFlight, "canceled"));
        assertNull(grid().getAdapter());
        ShadowLooper.idleMainLooper(30, TimeUnit.SECONDS);
        assertEquals(1, ApiExecution.requests.size());
    }

    @Test public void actualRetryButtonShowsRequestErrorAndReloadsSuccessfully(){
        JsonObject error=new JsonObject(); error.addProperty("error", "服务暂不可用");
        callback(last()).onError(new SponsorRequest.SponsorError(error, 503));
        TextView status=ReflectionHelpers.getField(fragment, "status");
        Button retry=(Button)text(fragment.getView(), host.get().getString(R.string.sponsor_thanks_retry));
        assertEquals("服务暂不可用", status.getText().toString());
        assertEquals(View.VISIBLE, status.getVisibility());
        assertNotNull(retry); assertEquals(View.VISIBLE, retry.getVisibility()); assertTrue(retry.isEnabled());
        assertTrue(retry.performClick());
        assertEquals(2, ApiExecution.requests.size());
        assertEquals("0", last().getURL().getQueryParameter("offset"));
        assertEquals(host.get().getString(R.string.sponsor_thanks_loading), status.getText().toString());
        assertFalse(retry.isEnabled());
        reply(last(), page("恢复"));
        assertEquals(View.GONE, retry.getVisibility());
        assertEquals(View.GONE, status.getVisibility());
        assertEquals(4, grid().getAdapter().getItemCount());
        assertNotNull(text(bind(0), "恢复1"));
    }

    private RecyclerView grid(){ return ReflectionHelpers.getField(fragment, "grid"); }
    private MastodonAPIRequest<?> last(){ return ApiExecution.requests.get(ApiExecution.requests.size()-1); }
    @SuppressWarnings("unchecked") private Callback<ThanksPage> callback(MastodonAPIRequest<?> request){
        return ReflectionHelpers.getField(request, "callback");
    }
    private void reply(MastodonAPIRequest<?> request, ThanksPage page){ callback(request).onSuccess(page); }
    @SuppressWarnings({"rawtypes", "unchecked"}) private View bind(int position){
        // Exercise the real adapter/holder without relying on a simulated window's layout size.
        RecyclerView.Adapter adapter=grid().getAdapter();
        RecyclerView.ViewHolder holder=adapter.createViewHolder(grid(), adapter.getItemViewType(position));
        adapter.bindViewHolder(holder, position);
        return holder.itemView;
    }
    private static TextView text(View view, String value){
        if(view instanceof TextView label && value.contentEquals(label.getText())) return label;
        if(view instanceof ViewGroup group){
            for(int i=0;i<group.getChildCount();i++){
                TextView found=text(group.getChildAt(i), value); if(found!=null) return found;
            }
        }
        return null;
    }
    private static ThanksPage page(String prefix){
        ThanksPage page=new ThanksPage(); page.limit=100; page.offset=0; page.total=4; page.items=new ArrayList<>();
        for(int i=1;i<=4;i++){
            ThanksItem item=new ThanksItem(); item.accountId="sponsor-"+i; item.rank=i;
            item.displayName=i==2 ? "" : prefix+i; item.username="user-"+i; item.totalAmountCents=10000-i;
            item.avatar="https://avatars.example.test/"+i+".gif";
            item.avatarStatic=i==2 ? "" : "https://avatars.example.test/"+i+".png";
            page.items.add(item);
        }
        return page;
    }

    /** Never dispatch HTTP; retain real callbacks and real MastodonAPIRequest.cancel(). */
    @Implements(MastodonAPIRequest.class) public static class ApiExecution{
        static final List<MastodonAPIRequest<?>> requests=new ArrayList<>();
        static final List<String> accounts=new ArrayList<>();
        @RealObject MastodonAPIRequest<?> request;
        @Implementation public MastodonAPIRequest<?> exec(String accountId){
            requests.add(request); accounts.add(accountId); return request;
        }
    }
    /** Records the actual URL-loader entry point without opening disk caches or fetching images. */
    @Implements(ImageCache.class) public static class Images{
        static ImageCache instance;
        static final List<UrlImageLoaderRequest> requests=new ArrayList<>();
        static final List<ImageCache.PendingImageRequest> pending=new ArrayList<>();
        @RealObject ImageCache cache;
        static void reset(){ instance=null; requests.clear(); pending.clear(); }
        @Implementation protected void __constructor__(Context context){}
        @Implementation public static ImageCache getInstance(Context context){
            if(instance==null) instance=ReflectionHelpers.callConstructor(ImageCache.class,
                    ReflectionHelpers.ClassParameter.from(Context.class, context));
            return instance;
        }
        @Implementation public ImageCache.PendingImageRequest get(ImageLoaderRequest request,
                ImageCache.ProgressCallback progress, ImageLoaderCallback callback, boolean decode){
            requests.add((UrlImageLoaderRequest)request);
            try{
                Constructor<?> constructor=ImageCache.PendingImageRequest.class.getDeclaredConstructor(
                        ImageCache.class, Class.forName(ImageCache.class.getName()+"$ImageDownloadInfo"), ImageLoaderCallback.class);
                constructor.setAccessible(true);
                ImageCache.PendingImageRequest result=(ImageCache.PendingImageRequest)constructor.newInstance(cache, null, callback);
                pending.add(result); return result;
            }catch(ReflectiveOperationException error){ throw new AssertionError(error); }
        }
    }
    @Implements(ImageCache.PendingImageRequest.class) public static class PendingImages{
        static final List<ImageCache.PendingImageRequest> canceled=new ArrayList<>();
        @RealObject ImageCache.PendingImageRequest request;
        @Implementation public void cancel(){ canceled.add(request); }
    }
    @Implements(Nav.class) public static class Navigation{
        static Activity activity;
        static Class<? extends Fragment> destination;
        static Bundle arguments;
        static void reset(){ activity=null; destination=null; arguments=null; }
        @Implementation public static void go(Activity source, Class<? extends Fragment> target, Bundle args){
            activity=source; destination=target; arguments=new Bundle(args);
        }
    }
}
