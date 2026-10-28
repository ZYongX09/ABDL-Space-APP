package org.joinmastodon.android.fragments.albums;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
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

import java.util.ArrayList;
import java.util.List;

import me.grishka.appkit.imageloader.ImageCache;
import me.grishka.appkit.imageloader.ImageLoaderCallback;
import me.grishka.appkit.imageloader.requests.ImageLoaderRequest;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.V;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class, shadows=AlbumUiImageCacheTest.DeferredMiss.class)
public class AlbumUiImageCacheTest {
    @Implements(ImageCache.class)
    public static class DeferredMiss {
        static final List<ImageLoaderRequest> requests=new ArrayList<>();
        static final List<ImageLoaderCallback> callbacks=new ArrayList<>();
        @Implementation public ImageCache.PendingImageRequest get(ImageLoaderRequest request, ImageCache.ProgressCallback progress, ImageLoaderCallback callback, boolean decode) {
            requests.add(request); callbacks.add(callback);
            return null;
        }
    }

    @Before public void setup() {
        MastodonApp.context=RuntimeEnvironment.getApplication();
        V.setApplicationContext(MastodonApp.context);
        ImageCache.getInstance(MastodonApp.context).clearTopLevel();
        DeferredMiss.requests.clear(); DeferredMiss.callbacks.clear();
    }

    @After public void cleanup() {
        ImageCache.getInstance(MastodonApp.context).clearTopLevel();
    }

    private Drawable image(Activity activity) {
        return new BitmapDrawable(activity.getResources(), Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888));
    }

    @Test public void exactMemoryHitIsVisibleBeforeAnyQueueOrLoaderRequest() {
        try (ActivityController<Activity> lifecycle=Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity=lifecycle.get();
            ImageView view=new ImageView(activity);
            String url="https://image.fixture.invalid/public-preview.jpg";
            Drawable cached=image(activity);
            ImageCache.getInstance(activity).put(new UrlImageLoaderRequest(url, 120, 120), cached);
            AlbumUi.loadImage(view, url, 120);
            assertSame(cached, view.getDrawable());
            assertFalse(AlbumUi.isImageLoading(view));
            assertTrue(DeferredMiss.requests.isEmpty());
        }
    }

    @Test public void oldAsyncResultCannotOverwriteANewerWarmBinding() {
        try (ActivityController<Activity> lifecycle=Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity=lifecycle.get();
            ImageView view=new ImageView(activity);
            AlbumUi.loadImage(view, "https://image.fixture.invalid/old.jpg", 120);
            assertEquals(1, DeferredMiss.requests.size());
            assertTrue(AlbumUi.isImageLoading(view));
            Drawable fresh=image(activity);
            String url="https://image.fixture.invalid/new.jpg";
            ImageCache.getInstance(activity).put(new UrlImageLoaderRequest(url, 120, 120), fresh);
            AlbumUi.loadImage(view, url, 120);
            assertSame(fresh, view.getDrawable());
            DeferredMiss.callbacks.get(0).onImageLoaded(DeferredMiss.requests.get(0), image(activity));
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertSame(fresh, view.getDrawable());
            assertFalse(AlbumUi.isImageLoading(view));
        }
    }

    @Test public void changedUrlOrDecodeSizeDoesNotReuseThePreviousImage() {
        try (ActivityController<Activity> lifecycle=Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity=lifecycle.get();
            ImageView view=new ImageView(activity);
            String old="https://image.fixture.invalid/old.jpg";
            ImageCache.getInstance(activity).put(new UrlImageLoaderRequest(old, 120, 120), image(activity));
            AlbumUi.loadImage(view, old, 120);
            assertNotNull(view.getDrawable());
            AlbumUi.loadImage(view, old, 240);
            assertNull(view.getDrawable());
            assertEquals(1, DeferredMiss.requests.size());
            AlbumUi.loadImage(view, "https://image.fixture.invalid/new.jpg", 120);
            assertNull(view.getDrawable());
            assertEquals(2, DeferredMiss.requests.size());
            AlbumUi.clearImages(view);
            assertFalse(AlbumUi.isImageLoading(view));
            DeferredMiss.callbacks.get(1).onImageLoaded(DeferredMiss.requests.get(1), image(activity));
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertNull(view.getDrawable());
        }
    }
}
