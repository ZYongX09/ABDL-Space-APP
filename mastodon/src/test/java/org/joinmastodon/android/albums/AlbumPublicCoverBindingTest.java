package org.joinmastodon.android.albums;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.StatusPrivacy;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.model.albums.AlbumModels;
import org.joinmastodon.android.ui.displayitems.AlbumUpdateStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.StatusDisplayItem;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;

import me.grishka.appkit.imageloader.ImageCache;
import me.grishka.appkit.utils.V;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AlbumPublicCoverBindingTest {
    private AccountSessionManager manager;
    private AccountSession session;
    private Map<String,AccountSession> sessions;
    private String accountID, previousActive;
    private AccountSession previousSession;

    private static Field field(Class<?> type, String name) throws Exception {
        Field field=type.getDeclaredField(name); field.setAccessible(true); return field;
    }

    @Before @SuppressWarnings("unchecked") public void setup() throws Exception {
        MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context);
        manager=AccountSessionManager.getInstance();
        sessions=(Map<String,AccountSession>)field(AccountSessionManager.class,"sessions").get(manager);
        var constructor=AccountSession.class.getDeclaredConstructor(); constructor.setAccessible(true);
        session=constructor.newInstance(); session.domain="cover-binding.fixture.invalid"; session.self=new Account(); session.self.id="42";
        session.token=new Token(); session.token.accessToken="offline-cover-binding"; session.activated=true;
        accountID=session.getID(); previousActive=manager.getLastActiveAccountID(); previousSession=sessions.put(accountID,session);
        field(AccountSessionManager.class,"lastActiveAccountID").set(manager,accountID);
        AlbumPublicCoverMemory.clearAll(); ImageCache.getInstance(MastodonApp.context).clearTopLevel();
    }

    @After public void cleanup() throws Exception {
        AlbumPublicCoverMemory.clearAll(); ImageCache.getInstance(MastodonApp.context).clearTopLevel();
        if(previousSession==null) sessions.remove(accountID); else sessions.put(accountID,previousSession);
        field(AccountSessionManager.class,"lastActiveAccountID").set(manager,previousActive);
    }

    private static String url(long start, String signature) {
        return "https://abdl-1339643562.cos.ap-shanghai.myqcloud.com/albums/42/11111111-1111-4111-8111-111111111111/22222222-2222-4222-8222-222222222222/preview.jpg"
                +"?q-sign-algorithm=sha1&q-ak=fixture-key&q-sign-time="+start+";"+(start+60)
                +"&q-key-time="+start+";"+(start+60)+"&q-header-list=host&q-url-param-list=&q-signature="+signature.repeat(40);
    }

    private Status status(String url) {
        Status status=Status.ofFake("p_album_cover", "album", Instant.now());
        status.visibility=StatusPrivacy.PUBLIC;
        status.albumUpdate=new AlbumModels.AlbumUpdate();
        status.albumUpdate.albumId="33333333-3333-4333-8333-333333333333";
        status.albumUpdate.albumName="Preview fixture"; status.albumUpdate.coverUrl=url;
        status.albumUpdate.photoCount=1; status.albumUpdate.width=800; status.albumUpdate.height=600;
        return status;
    }

    private AlbumUpdateStatusDisplayItem item(Activity activity, Status status) {
        return new AlbumUpdateStatusDisplayItem(status.id,new StatusDisplayItem.NoOpCallbacks(activity),activity,status,accountID);
    }

    private static ImageView image(AlbumUpdateStatusDisplayItem.Holder holder) {
        return (ImageView)((LinearLayout)((FrameLayout)holder.itemView).getChildAt(0)).getChildAt(0);
    }

    @Test public void newSignatureImmediatelyReusesPublicCoverPixelsWithoutChangingTheNetworkRequestUrl() {
        try(var lifecycle=Robolectric.buildActivity(Activity.class)) {
            Activity activity=lifecycle.get(); activity.setTheme(R.style.Theme_Mastodon_Light); lifecycle.setup();
            long now=System.currentTimeMillis()/1000;
            Status first=status(url(now-1,"a"));
            var holder=new AlbumUpdateStatusDisplayItem.Holder(activity,new FrameLayout(activity));
            holder.bind(item(activity,first));
            Bitmap bitmap=Bitmap.createBitmap(12,12,Bitmap.Config.ARGB_8888);
            holder.setImage(0,new BitmapDrawable(activity.getResources(),bitmap));
            ImageCache.getInstance(activity).clearTopLevel();
            Status refreshed=status(url(now,"b")); var next=item(activity,refreshed);
            holder.bind(next);
            assertNotNull(image(holder).getDrawable());
            assertSame(bitmap,((BitmapDrawable)image(holder).getDrawable()).getBitmap());
            assertTrue(ImageCache.getInstance(activity).isInTopCache(next.getImageRequest(0)));
            assertEquals(refreshed.albumUpdate.coverUrl,((me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest)next.getImageRequest(0)).uri.toString());
        }
    }

    @Test public void protectedProjectionPurgesAliasAndCannotRestoreItWhenLaterUnprotected() {
        try(var lifecycle=Robolectric.buildActivity(Activity.class)) {
            Activity activity=lifecycle.get(); activity.setTheme(R.style.Theme_Mastodon_Light); lifecycle.setup();
            long now=System.currentTimeMillis()/1000;
            var holder=new AlbumUpdateStatusDisplayItem.Holder(activity,new FrameLayout(activity));
            Status original=status(url(now-1,"a")); holder.bind(item(activity,original));
            Bitmap bitmap=Bitmap.createBitmap(12,12,Bitmap.Config.ARGB_8888);
            holder.setImage(0,new BitmapDrawable(activity.getResources(),bitmap));
            ImageCache.getInstance(activity).clearTopLevel();
            Status protectedStatus=status(url(now,"b")); protectedStatus.albumUpdate.downloadProtected=true;
            var denied=item(activity,protectedStatus); holder.bind(denied);
            assertEquals(0,denied.getImageCount());
            var lock=image(holder).getDrawable(); assertNotNull(lock);
            holder.setImage(0,new BitmapDrawable(activity.getResources(),bitmap)); assertSame(lock,image(holder).getDrawable());
            Status unprotected=status(url(now,"c")); holder.bind(item(activity,unprotected));
            assertNull(image(holder).getDrawable());
        }
    }
}
