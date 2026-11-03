package org.joinmastodon.android.novel.app

import android.graphics.Bitmap
import android.view.View
import android.widget.FrameLayout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import me.grishka.appkit.imageloader.ImageCache
import me.grishka.appkit.imageloader.OutputStreamSupplier
import me.grishka.appkit.imageloader.downloaders.ImageDownloader
import me.grishka.appkit.imageloader.requests.ImageLoaderRequest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.joinmastodon.android.CompatibilityTestApplication
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

/** AppKit's REAL needsDiskCache=true/get/cancel-worker path, deterministic and entirely offline.
 * HTTP Call.cancel parks before AppKit marks its old ImageDownloadInfo cancelled. Reattaching in
 * that window used to join the doomed source and leave loading=true forever without a callback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w393dp-h852dp-notnight-mdpi", application = CompatibilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NovelCoverHttpCancellationTest {
    private val fixture = NovelScreenshotFixture("cover-http")
    @get:Rule val rule: org.junit.rules.TestRule = fixture
    private val host get() = fixture.host
    private val scope = NovelCoverScope("http-cover-fixture", { true })
    private lateinit var parent: FrameLayout
    private lateinit var downloader: HttpPathDownloader
    private val key = NovelCoverKey("book", "published", "https://cover-cancel.example.test/${UUID.randomUUID()}.png")
    @Before fun setup() {
        parent = FrameLayout(host.activity); host.activity.addContentView(parent, FrameLayout.LayoutParams(393, 852))
        downloader = HttpPathDownloader()
        ImageCache.registerDownloader(0, downloader)
        parent.measure(exact(393), exact(852)); parent.layout(0, 0, 393, 852)
    }
    @After fun cleanup() {
        downloader.calls.forEach { it.call.releaseCancel.countDown() }
        scope.retire(); parent.removeAllViews(); ImageCache.getInstance(host.activity).clearTopLevel()
    }
    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private fun view() = NovelCoverImageView(host.activity).also {
        it.binding.bind(scope, key.entity, key, scope.loader(host.activity)); parent.addView(it, FrameLayout.LayoutParams(76, 110))
        it.measure(exact(76), exact(110)); it.layout(0, 0, 76, 110)
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do { ShadowLooper.idleMainLooper(); if (condition()) return; Thread.sleep(5) } while (System.nanoTime() < deadline)
        fail("Timed out awaiting actual AppKit HTTP/disk/cancellation pipeline")
    }
    private fun succeed(index: Int) {
        val call = downloader.calls[index]
        val bitmap = Bitmap.createBitmap(24, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val bytes = ByteArrayOutputStream()
        try { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) } finally { bitmap.recycle() }
        call.output.get().use { it.write(bytes.toByteArray()) }
        call.info.httpCall = null; call.success.run()
    }
    @Test fun shortDetachAndFactoryReplacementRejoinSameLiveHttpFlightWithoutCancelOrExtraDownload() {
        val first = view(); await { downloader.calls.size == 1 }
        parent.removeView(first); first.binding.release()
        val replacement = view()
        assertEquals(1, downloader.calls.size); assertFalse(downloader.calls[0].call.isCanceled())
        ShadowLooper.idleMainLooper(700, TimeUnit.MILLISECONDS)
        assertEquals(1, downloader.calls.size); assertFalse(downloader.calls[0].call.isCanceled())
        succeed(0); await { replacement.drawable != null }
        ShadowLooper.idleMainLooper(250, TimeUnit.MILLISECONDS)
        assertEquals(1f, replacement.alpha, .01f)
        assertEquals(1, downloader.calls.size)
    }
    @Test fun longDetachThenAttachDuringInProgressHttpCancellationUsesNewSourceAndEventuallySucceeds() {
        val first = view(); await { downloader.calls.size == 1 }
        val old = downloader.calls[0]
        parent.removeView(first)
        ShadowLooper.idleMainLooper(550, TimeUnit.MILLISECONDS)
        await { old.call.cancelEntered.count == 0L } // cancellation worker is now parked, old disk source still registered
        parent.addView(first)
        await { downloader.calls.size == 2 }
        assertNotEquals("New subscription cannot coalesce with an asynchronously cancelling HTTP source", old.request.diskCacheKey, downloader.calls[1].request.diskCacheKey)
        old.call.releaseCancel.countDown()
        val canceled = old.info.javaClass.getDeclaredField("canceled").apply { isAccessible = true }
        await { canceled.getBoolean(old.info) }
        old.failure.accept(IOException("Cancelled old HTTP attempt"))
        succeed(1); await { first.drawable != null }
        ShadowLooper.idleMainLooper(1500, TimeUnit.MILLISECONDS)
        assertNotNull(first.drawable); assertEquals(2, downloader.calls.size); assertEquals(0, scope.failureCount(key))
    }
    @Test fun scopeRetirementImmediatelyCancelsHttpAndLateCallbackCannotRestoreImage() {
        val view = view(); await { downloader.calls.size == 1 }; val old = downloader.calls[0]
        scope.retire(); await { old.call.cancelEntered.count == 0L }
        assertNull(view.drawable); old.call.releaseCancel.countDown()
        // Invoke the late HTTP error continuation after AppKit's cancellation worker finishes.
        // A real cancelled HTTP call must not commit a previously-unopened disk OutputStream.
        val cancelled = old.info.javaClass.getDeclaredField("canceled").apply { isAccessible = true }
        await { cancelled.getBoolean(old.info) }
        old.failure.accept(IOException("Cancelled on logout")); ShadowLooper.idleMainLooper(1000, TimeUnit.MILLISECONDS)
        assertNull(view.drawable); assertEquals(0, scope.retainedCount); assertEquals(1, downloader.calls.size)
    }
    @Test fun missingHttpCallbackTimesOutToFallbackAndRetriesAreBoundedAcrossFreshSources() {
        val view = view(); await { downloader.calls.size == 1 }
        for (attempt in 0..2) {
            downloader.calls[attempt].call.releaseCancel.countDown()
            ShadowLooper.idleMainLooper(30_001, TimeUnit.MILLISECONDS)
            assertNull(view.drawable)
            if (attempt < 2) { ShadowLooper.idleMainLooper(650, TimeUnit.MILLISECONDS); await { downloader.calls.size == attempt + 2 } }
        }
        ShadowLooper.idleMainLooper(100_000, TimeUnit.MILLISECONDS)
        assertEquals(3, downloader.calls.size); assertEquals(3, scope.failureCount(key)); assertNull(view.drawable)
    }

    private class ParkedCall : Call {
        private var cancelled = false
        val cancelEntered = CountDownLatch(1); val releaseCancel = CountDownLatch(1)
        override fun cancel() {
            cancelEntered.countDown()
            if (!releaseCancel.await(5, TimeUnit.SECONDS)) throw AssertionError("Test cancellation gate timeout")
            cancelled = true
        }
        override fun isCanceled() = cancelled
        override fun isExecuted() = true
        override fun request(): Request = Request.Builder().url("https://cover-cancel.example.test/fixture").build()
        override fun execute(): Response = throw AssertionError("No real HTTP permitted")
        override fun enqueue(callback: Callback) = throw AssertionError("No real HTTP permitted")
        override fun timeout() = Timeout()
        public override fun clone(): Call = ParkedCall()
    }
    private class HttpPathDownloader : ImageDownloader() {
        data class Transfer(val request: ImageLoaderRequest, val output: OutputStreamSupplier, val info: ImageCache.ImageDownloadInfo,
            val success: Runnable, val failure: Consumer<Throwable>, val call: ParkedCall)
        val calls = CopyOnWriteArrayList<Transfer>()
        override fun needsDiskCache() = true
        override fun canHandleRequest(request: ImageLoaderRequest) = request is NovelCoverRequest
        override fun downloadFile(request: ImageLoaderRequest, output: OutputStreamSupplier, progress: ImageCache.ProgressCallback?, info: ImageCache.ImageDownloadInfo,
            onSuccess: Runnable, onError: Consumer<Throwable>) {
            val call = ParkedCall(); info.httpCall = call
            calls += Transfer(request, output, info, onSuccess, onError, call)
        }
    }
}
