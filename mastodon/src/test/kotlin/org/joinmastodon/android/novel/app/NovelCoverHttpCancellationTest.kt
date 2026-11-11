package org.joinmastodon.android.novel.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
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
    private fun view(key: NovelCoverKey = this.key) = NovelCoverImageView(host.activity).also {
        it.binding.bind(scope, key.entity, key, scope.loader(host.activity)); parent.addView(it, FrameLayout.LayoutParams(76, 110))
        it.measure(exact(76), exact(110)); it.layout(0, 0, 76, 110)
    }
    private fun await(advanceFrames: Boolean = true, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            // Finish native traversal barriers before processing the actual worker's main callback.
            if (advanceFrames) ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS) else ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        fail("Timed out awaiting actual AppKit HTTP/disk/cancellation pipeline")
    }
    private fun succeed(index: Int, width: Int = 24, height: Int = 32, color: Int = Color.RED) {
        val call = downloader.calls[index]
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val bytes = ByteArrayOutputStream()
        try { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) } finally { bitmap.recycle() }
        call.output.get().use { it.write(bytes.toByteArray()) }
        call.info.httpCall = null; call.success.run()
    }
    private fun failTransfer(index: Int) {
        val transfer = downloader.calls[index]
        transfer.info.httpCall = null
        transfer.failure.accept(IOException("Synthetic transient HTTP failure"))
    }
    private fun awaitSuccessCompletion(index: Int, advanceFrames: Boolean = true) {
        val cache = ImageCache.getInstance(host.activity)
        val loading = cache.javaClass.getDeclaredField("currentlyLoading").apply { isAccessible = true }.get(cache) as Map<*, *>
        val source = downloader.calls[index].request.diskCacheKey
        // AppKit removes this entry and invokes its callback under the same monitor. Once the
        // monitor is released the loader's main continuation is enqueued, not merely decoding.
        await(advanceFrames) { synchronized(loading) { !loading.containsKey(source) } }
        ShadowLooper.idleMainLooper()
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
    @Test fun successAfterSoleViewReleaseBeforeReplacementWithinGraceIsRetainedWithoutFadeOrNewHttpTransfer() {
        val first = view(); await { downloader.calls.size == 1 }
        val transfer = downloader.calls.single()
        val detachedAt = SystemClock.uptimeMillis()
        parent.removeView(first); first.binding.release()
        assertFalse(first.isAttachedToWindow); assertNull(first.drawable)
        ShadowLooper.idleMainLooper(32, TimeUnit.MILLISECONDS)
        succeed(0)
        // Freeze virtual time while the real disk decode finishes, so no replacement subscribes
        // and the 500ms grace cannot expire just because a worker was scheduled slowly.
        awaitSuccessCompletion(0, advanceFrames = false)
        assertTrue("Orphan success must finish before detach grace expires", SystemClock.uptimeMillis() - detachedAt < 500)
        assertNotNull("A completed success with no current subscriber must still populate the host scope", scope.image(key))
        assertEquals(1, scope.retainedCount)
        assertNull(first.drawable)
        val retained = (scope.image(key) as BitmapDrawable).bitmap
        ImageCache.getInstance(host.activity).clearTopLevel()
        val replacement = view()
        assertNotNull("The replacement must restore synchronously from the host scope", replacement.drawable)
        assertSame(retained, (replacement.drawable as BitmapDrawable).bitmap)
        assertEquals("A success completed before subscription must not fade in again", 1f, replacement.alpha, 0f)
        assertEquals(1, downloader.calls.size); assertFalse(transfer.call.isCanceled())
        ShadowLooper.idleMainLooper(700, TimeUnit.MILLISECONDS)
        assertNotNull(replacement.drawable); assertEquals(1f, replacement.alpha, 0f)
        assertEquals(1, downloader.calls.size); assertEquals(0, scope.failureCount(key))
    }

    @Test fun oneFailedSharedHttpFlightCountsOnceAndOneRetrySucceedsForThreeSimultaneousViews() {
        val views = List(3) { view() }
        await { downloader.calls.size == 1 }
        assertTrue(views.all { it.isAttachedToWindow && it.drawable == null })
        failTransfer(0)
        await { scope.failureCount(key) > 0 }
        assertEquals("Three subscribers share one failed HTTP response, not three attempts", 1, scope.failureCount(key))
        assertTrue(views.all { it.drawable == null })
        assertEquals(1, downloader.calls.size)
        ShadowLooper.idleMainLooper(650, TimeUnit.MILLISECONDS)
        await { downloader.calls.size == 2 }
        assertNotEquals("A failed source must not poison the retry", downloader.calls[0].request.diskCacheKey, downloader.calls[1].request.diskCacheKey)
        succeed(1)
        await { views.all { it.drawable != null } }
        val sharedPixels = (views.first().drawable as BitmapDrawable).bitmap
        views.forEach { cover ->
            assertSame(sharedPixels, (cover.drawable as BitmapDrawable).bitmap)
            assertSame(cover, cover.drawable.callback)
        }
        assertNotSame(views[0].drawable, views[1].drawable); assertNotSame(views[1].drawable, views[2].drawable)
        assertEquals(0, scope.failureCount(key))
        ShadowLooper.idleMainLooper(2000, TimeUnit.MILLISECONDS)
        assertTrue(views.all { it.drawable != null && it.alpha == 1f }); assertEquals(2, downloader.calls.size)
    }

    @Test fun fullSizeCoversExceedByteBudgetAndEvictedExactKeyRecoversFromCompletedDiskWithoutNewHttpTransfer() {
        val budget = 12L * 1024 * 1024
        val cache = ImageCache.getInstance(host.activity)
        val keys = List(10) { index -> key.copy(entity = "book-$index", url = "${key.url}?fixture-version=$index") }
        val expectedLru = mutableListOf<Pair<NovelCoverKey, Long>>()
        var successfulBytes = 0L
        var expectedRetainedBytes = 0L
        keys.forEachIndexed { index, coverKey ->
            val cover = view(coverKey)
            await { downloader.calls.size == index + 1 }
            assertEquals(500, downloader.calls[index].request.desiredMaxWidth)
            assertEquals(700, downloader.calls[index].request.desiredMaxHeight)
            succeed(index, width = 500, height = 700)
            await { cover.drawable != null }
            val pixels = (cover.drawable as BitmapDrawable).bitmap
            assertEquals(500, pixels.width); assertEquals(700, pixels.height)
            val bytes = pixels.allocationByteCount.toLong()
            assertEquals("Budget must use actual full-sized decoded allocations", 500L * 700 * 4, bytes)
            successfulBytes += bytes; expectedRetainedBytes += bytes
            expectedLru += coverKey to bytes
            while (expectedRetainedBytes > budget) expectedRetainedBytes -= expectedLru.removeAt(0).second
            assertEquals(expectedLru.size, scope.retainedCount)
            assertEquals(expectedRetainedBytes, scope.retainedBytes)
            assertTrue("Decoded host LRU must never exceed 12MiB", scope.retainedBytes <= budget)
            parent.removeView(cover); cover.binding.release()
            cache.clearTopLevel()
        }
        assertTrue("The fixture must really exceed the default byte budget", successfulBytes > budget)
        assertTrue("Byte pressure, not the 64-entry limit, must evict the first cover", scope.retainedCount < keys.size)
        assertNull(scope.image(keys.first()))
        val completedSource = downloader.calls.first().request
        val completedDiskFile = cache.getFile(completedSource)
        assertNotNull("The first successful HTTP source must have committed real disk bytes", completedDiskFile)
        assertTrue(requireNotNull(completedDiskFile).isFile && completedDiskFile.length() > 0)
        cache.clearTopLevel(); assertFalse(cache.isInTopCache(completedSource))
        val transfersBeforeRevisit = downloader.calls.size
        val replacement = view(keys.first().copy())
        // A regression must fail on the unexpected transfer instead of waiting for an HTTP reply
        // that this offline fixture deliberately never supplies.
        await { replacement.drawable != null || downloader.calls.size > transfersBeforeRevisit }
        assertEquals("Revisiting an evicted exact key must decode its completed AppKit disk source, not download again",
            transfersBeforeRevisit, downloader.calls.size)
        val recovered = (replacement.drawable as BitmapDrawable).bitmap
        assertEquals(500, recovered.width); assertEquals(700, recovered.height)
        val recoveredBytes = recovered.allocationByteCount.toLong()
        assertEquals(500L * 700 * 4, recoveredBytes)
        expectedLru += keys.first() to recoveredBytes; expectedRetainedBytes += recoveredBytes
        while (expectedRetainedBytes > budget) expectedRetainedBytes -= expectedLru.removeAt(0).second
        assertEquals(expectedLru.size, scope.retainedCount); assertEquals(expectedRetainedBytes, scope.retainedBytes)
        assertTrue(scope.retainedBytes <= budget); assertEquals(0, scope.failureCount(keys.first()))
        ShadowLooper.idleMainLooper(700, TimeUnit.MILLISECONDS)
        assertNotNull(replacement.drawable); assertEquals(transfersBeforeRevisit, downloader.calls.size)
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
