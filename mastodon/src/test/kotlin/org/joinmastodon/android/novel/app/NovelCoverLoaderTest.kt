package org.joinmastodon.android.novel.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.FrameLayout
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import me.grishka.appkit.imageloader.ImageCache
import me.grishka.appkit.imageloader.downloaders.ImageDownloader
import me.grishka.appkit.imageloader.requests.ImageLoaderRequest
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.MastodonApp
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

/** Real ImageCache/downloader/native attach lifecycle. Synthetic HTTPS requests NEVER reach HTTP. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w393dp-h852dp-notnight-mdpi", application = CompatibilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NovelCoverLoaderTest {
    private val fixture = NovelScreenshotFixture("cover-loader")
    @get:Rule val rule: org.junit.rules.TestRule = fixture
    private val host get() = fixture.host
    private val scope = NovelCoverScope("cover-real-loader-fixture", { true })
    private lateinit var parent: FrameLayout
    private lateinit var cache: ImageCache
    private val downloaders = mutableListOf<ControlledDownloader>()

    @Before fun setup() {
        cache = ImageCache.getInstance(host.activity); cache.clearTopLevel()
        parent = FrameLayout(host.activity)
        host.activity.addContentView(parent, FrameLayout.LayoutParams(393, 852))
        parent.measure(exact(393), exact(852)); parent.layout(0, 0, 393, 852)
        assertTrue(parent.isAttachedToWindow)
        ImageCache.registerDownloader(0, object : ImageDownloader() {
            override fun needsDiskCache() = false
            override fun canHandleRequest(request: ImageLoaderRequest) = request is NovelCoverRequest
            override fun getDrawable(request: ImageLoaderRequest, decode: Boolean, info: ImageCache.ImageDownloadInfo): Drawable =
                throw IOException("Unexpected fixture cover; no external HTTP permitted")
        })
    }
    @After fun cleanup() {
        downloaders.forEach { it.release.countDown() }
        scope.retire(); parent.removeAllViews(); cache.clearTopLevel()
    }
    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private fun view(key: NovelCoverKey, scope: NovelCoverScope = this.scope): NovelCoverImageView = NovelCoverImageView(host.activity).also {
        it.binding.bind(scope, key.entity, key, scope.loader(host.activity))
        parent.addView(it, FrameLayout.LayoutParams(76, 110))
        it.measure(exact(76), exact(110)); it.layout(0, 0, 76, 110)
    }
    private fun downloader(failures: Int = 0, gated: Boolean = false): ControlledDownloader = ControlledDownloader(host.activity, failures, gated)
        .also { downloaders += it; ImageCache.registerDownloader(0, it) }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            // Complete the scheduled native traversal: idle-current alone leaves its sync barrier
            // blocking the real worker's ordinary Handler callback while virtual time is paused.
            ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        fail("Timed out awaiting real AppKit callback")
    }
    private fun bitmap(view: NovelCoverImageView) = (view.drawable as? BitmapDrawable)?.bitmap

    @Test fun realSuccessRemainsOpaqueAcrossNativeDetachAttachAndNewFactoryAfterAppKitTopEviction() {
        val remote = downloader(); val view = view(remote.key)
        await { view.drawable != null }
        assertSame(remote.image.bitmap, bitmap(view)); assertEquals(1, remote.attempts.get())
        ShadowLooper.idleMainLooper(250, TimeUnit.MILLISECONDS)
        // Restore from the host LRU, not the downloader or an AppKit load/fade on every attach.
        cache.clearTopLevel()
        repeat(6) {
            parent.removeView(view); assertFalse(view.isAttachedToWindow)
            parent.addView(view); assertTrue(view.isAttachedToWindow)
            assertEquals(1f, view.alpha, 0f); assertSame(remote.image.bitmap, bitmap(view))
            view.binding.bind(scope, remote.key.entity, remote.key.copy(), scope.loader(host.activity))
        }
        assertEquals(1, remote.attempts.get())
        view.binding.release(); parent.removeView(view)
        val recreated = view(remote.key)
        assertSame(remote.image.bitmap, bitmap(recreated)); assertEquals(1f, recreated.alpha, 0f)
        assertEquals(1, remote.attempts.get()); assertEquals(1, scope.retainedCount)
    }

    @Test fun realFailureCallbackRetriesWhileAttachedWithoutRebindAndPermanentFailureIsBounded() {
        val recovering = downloader(failures = 1); val view = view(recovering.key)
        await { recovering.attempts.get() == 1 && scope.failureCount(recovering.key) == 1 }
        assertNull(view.drawable)
        ShadowLooper.idleMainLooper(650, TimeUnit.MILLISECONDS)
        await { view.drawable != null }
        assertEquals(2, recovering.attempts.get()); assertSame(recovering.image.bitmap, bitmap(view))
        val failed = downloader(failures = Int.MAX_VALUE); view.binding.bind(scope, failed.key.entity, failed.key, scope.loader(host.activity))
        assertNull(view.drawable)
        await { failed.attempts.get() == 1 && scope.failureCount(failed.key) == 1 }
        for (attempt in 2..3) {
            ShadowLooper.idleMainLooper(650, TimeUnit.MILLISECONDS)
            await { failed.attempts.get() == attempt && scope.failureCount(failed.key) == attempt }
        }
        repeat(10) { view.binding.bind(scope, failed.key.entity, failed.key.copy(), scope.loader(host.activity)) }
        ShadowLooper.idleMainLooper(2000, TimeUnit.MILLISECONDS)
        assertEquals(3, failed.attempts.get()); assertNull(view.drawable)
    }

    @Test fun cancellingRealPendingRequestOnDetachAndReplacingUrlRejectsDelayedOldSuccess() {
        val old = downloader(gated = true); val view = view(old.key)
        await { old.entered.count == 0L }
        parent.removeView(view) // Actual native onDetachedFromWindow invokes cancellation.
        assertFalse(view.isAttachedToWindow)
        val replacement = downloader(gated = true)
        view.binding.bind(scope, replacement.key.entity, replacement.key, scope.loader(host.activity))
        parent.addView(view)
        await { replacement.entered.count == 0L }
        old.release.countDown(); await { old.finished.count == 0L }
        ShadowLooper.idleMainLooper(); assertNull(view.drawable); assertNull(scope.image(old.key))
        replacement.release.countDown(); await { view.drawable != null }
        assertSame(replacement.image.bitmap, bitmap(view))
        assertFalse(old.image.bitmap.isRecycled)
    }

    @Test fun queuedRealCallbackCannotResurrectReleasedViewOrRevokedAuthority() {
        val remote = downloader(gated = true); val view = view(remote.key)
        await { remote.entered.count == 0L }
        scope.retire(); assertNull(view.drawable)
        remote.release.countDown(); await { remote.finished.count == 0L }
        ShadowLooper.idleMainLooper(); assertNull(view.drawable); assertEquals(0, scope.retainedCount)
        assertEquals(0, scope.retainedBytes)
    }

    @Test fun realSharedImageHasIndependentCallbacksAndBoundsForTwoConcurrentSizes() {
        val remote = downloader(); val first = view(remote.key)
        await { first.drawable != null }
        val second = view(remote.key)
        second.measure(exact(110), exact(153)); second.layout(90, 0, 200, 153)
        assertNotSame(first.drawable, second.drawable)
        assertSame(bitmap(first), bitmap(second)); assertEquals(1, remote.attempts.get())
        assertSame(first, first.drawable.callback); assertSame(second, second.drawable.callback)
        first.drawable.alpha = 12; assertEquals(255, second.drawable.alpha)
        first.binding.release(); parent.removeView(first)
        assertNotNull(second.drawable); assertSame(second, second.drawable.callback)
    }

    private class ControlledDownloader(context: Context, private val failures: Int, private val gated: Boolean) : ImageDownloader() {
        val key = NovelCoverKey("book-${UUID.randomUUID()}", "published", "https://cover-loader.example.test/${UUID.randomUUID()}.png?signature=fixture")
        val attempts = AtomicInteger()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
        val image = BitmapDrawable(context.resources, Bitmap.createBitmap(30, 40, Bitmap.Config.ARGB_8888))
        override fun needsDiskCache() = false
        override fun canHandleRequest(request: ImageLoaderRequest) = request is UrlImageLoaderRequest && request.uri.toString() == key.url
        override fun getDrawable(request: ImageLoaderRequest, decode: Boolean, info: ImageCache.ImageDownloadInfo): Drawable {
            val attempt = attempts.incrementAndGet(); entered.countDown()
            try {
                if (gated && !release.await(5, TimeUnit.SECONDS)) throw IOException("Synthetic test gate timeout")
                if (attempt <= failures) throw IOException("Synthetic offline failure")
                return image
            } catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw IOException(interrupted) }
            finally { finished.countDown() }
        }
    }
}
