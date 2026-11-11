package org.joinmastodon.android.novel.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.util.concurrent.TimeUnit
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.events.AccountLoggedOutEvent
import org.joinmastodon.android.novel.NovelAccountDataCleaner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

/** Cancellation is deliberately advisory: the controlled loader can deliver ANY late callback. */
internal class SyntheticAnimatedCover : Drawable(), Animatable {
    private var running = false
    override fun draw(canvas: Canvas) {}
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun start() { running = true }
    override fun stop() { running = false }
    override fun isRunning() = running
    override fun getConstantState(): ConstantState = object : ConstantState() {
        override fun newDrawable(): Drawable = SyntheticAnimatedCover()
        override fun getChangingConfigurations() = 0
    }
}

internal class ControlledNovelCoverLoader : NovelCoverLoader {
    class Call(val request: NovelCoverRequest, val result: (Drawable?) -> Unit) { var cancelled = false }
    val calls = mutableListOf<Call>()
    override fun load(request: NovelCoverRequest, result: (Drawable?) -> Unit): NovelCoverCancellation {
        val call = Call(request, result).also(calls::add)
        return NovelCoverCancellation { call.cancelled = true }
    }
    fun succeed(index: Int, image: Drawable) = calls[index].result(image)
    fun fail(index: Int) = calls[index].result(null)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = CompatibilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NovelCoverBindingTest {
    private fun key(entity: String = "book-a", url: String = "https://covers.example.test/a.png?signature=one", projection: String = "published") =
        requireNotNull(novelCoverKey(entity, projection, url))
    private fun bitmap(size: Int = 16) = BitmapDrawable(RuntimeEnvironment.getApplication().resources,
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888))
    private class Display {
        var image: Drawable? = null
        var animated = 0
        val frames = mutableListOf<Drawable?>()
        val binding = NovelCoverBinding(show = { value, animate -> image = value; frames += value; if (animate) animated++ })
    }
    private fun pixels(image: Drawable?) = (image as? BitmapDrawable)?.bitmap

    @Test fun firstLoadAndIdempotentRecompositionDetachReattachAndFactoryRebuildRetainSuccessWithoutNewFade() {
        val scope = NovelCoverScope("account-a", { true }); val loader = ControlledNovelCoverLoader(); val display = Display(); val key = key()
        display.binding.bind(scope, key.entity, key, loader)
        assertNull(display.image); assertTrue(loader.calls.isEmpty())
        display.binding.attach(); assertEquals(1, loader.calls.size); assertNull(display.image)
        repeat(20) { display.binding.bind(scope, key.entity, key.copy(), loader) }
        assertEquals(1, loader.calls.size)
        val source = bitmap(); loader.succeed(0, source)
        assertSame(source.bitmap, pixels(display.image)); assertNotSame(source, display.image); assertEquals(1, display.animated)
        display.frames.clear()
        repeat(10) { display.binding.bind(scope, key.entity, key.copy(), loader); display.binding.detach(); display.binding.attach() }
        assertEquals(1, loader.calls.size); assertEquals(1, display.animated); assertFalse(display.frames.contains(null))
        display.binding.release()
        val replacement = Display(); replacement.binding.bind(scope, key.entity, key, loader); replacement.binding.attach()
        assertSame(source.bitmap, pixels(replacement.image)); assertEquals(0, replacement.animated); assertEquals(1, loader.calls.size)
        scope.retire(); assertNull(replacement.image); assertEquals(0, scope.retainedBytes)
    }

    @Test fun changedUrlIncludingSignatureOrContentQueryImmediatelyFallsBackAndRejectsOldSuccessFailureAndAba() {
        val scope = NovelCoverScope("account-a", { true }); val loader = ControlledNovelCoverLoader(); val view = Display()
        val a = key(); val b = key(url = "https://covers.example.test/a.png?signature=two&version=2")
        view.binding.bind(scope, a.entity, a, loader); view.binding.attach()
        view.binding.bind(scope, b.entity, b, loader)
        assertTrue(loader.calls[0].cancelled); assertNull(view.image)
        val current = bitmap(); loader.succeed(1, current); val drawn = view.image
        loader.succeed(0, bitmap()); loader.fail(0); assertSame(drawn, view.image)
        view.binding.bind(scope, a.entity, a, loader); assertNull(view.image)
        loader.succeed(0, bitmap()); assertNull(view.image) // A -> B -> A is still a new attempt nonce.
        assertEquals(3, loader.calls.size); loader.succeed(2, current); assertSame(current.bitmap, pixels(view.image))
        scope.retire()
    }

    @Test fun twoViewsChangingSameBookToSameSignedUrlAcceptBothReplacementCallbacksWithoutStayingBlank() {
        val scope = NovelCoverScope("account-a", { true }); val loader = ControlledNovelCoverLoader()
        val views = List(2) { Display() }; val old = key()
        val next = old.copy(url = "https://covers.example.test/a.png?signature=replacement&version=2")
        try {
            views.forEach { view -> view.binding.bind(scope, old.entity, old, loader); view.binding.attach() }
            assertEquals(2, loader.calls.size)
            val original = bitmap(); repeat(2) { loader.succeed(it, original) }
            views.forEach { assertSame(original.bitmap, pixels(it.image)) }
            // Both observers transition before either new callback; the second bind must not
            // revoke the same replacement generation already captured by the first observer.
            views.forEach { view -> view.binding.bind(scope, next.entity, next.copy(), loader); assertNull(view.image) }
            assertEquals(4, loader.calls.size)
            val replacement = bitmap()
            loader.succeed(2, replacement); loader.succeed(3, replacement)
            views.forEach { view ->
                assertSame("Every observer must accept its replacement callback", replacement.bitmap, pixels(view.image))
                assertTrue(view.binding.validate())
            }
            assertNotSame(views[0].image, views[1].image)
            assertSame(replacement.bitmap, pixels(scope.image(next))); assertNull(scope.image(old))
            repeat(5) { views.forEach { it.binding.bind(scope, next.entity, next.copy(), loader) } }
            ShadowLooper.idleMainLooper(2000, TimeUnit.MILLISECONDS)
            views.forEach { assertSame(replacement.bitmap, pixels(it.image)) }
            assertEquals("Recovery must not depend on a later bind or retry", 4, loader.calls.size)
            assertFalse(original.bitmap.isRecycled); assertFalse(replacement.bitmap.isRecycled)
            assertTrue(scope.isActive()); assertEquals(0, scope.failureCount(next))
        } finally { scope.retire() }
    }

    @Test fun pendingLiveBindingKeepsGenerationAndAcceptsSuccessAfterUnrelatedRequestMetadataPressure() {
        val scope = NovelCoverScope("account-a", { true }); val loader = ControlledNovelCoverLoader()
        val view = Display(); val original = key()
        try {
            view.binding.bind(scope, original.entity, original, loader); view.binding.attach()
            assertEquals(1, loader.calls.size); assertNull(view.image)
            // The default generation budget is twice the 64-entry decoded-cache limit. Creating
            // unrelated requests must trim unobserved entities, never the live pending observer.
            repeat(160) { index -> NovelCoverRequest(scope, original.copy(entity = "unobserved-book-$index")) }
            assertEquals(1, loader.calls.size); assertFalse(loader.calls.single().cancelled)
            val source = bitmap(); loader.succeed(0, source)
            assertSame("Metadata pressure must not silently reject a still-current callback", source.bitmap, pixels(view.image))
            assertSame(source.bitmap, pixels(scope.image(original)))
            assertFalse(source.bitmap.isRecycled); assertTrue(scope.isActive()); assertTrue(view.binding.validate())
            assertEquals(1, scope.retainedCount); assertEquals(source.bitmap.allocationByteCount.toLong(), scope.retainedBytes)
            assertEquals(0, scope.failureCount(original))
            ShadowLooper.idleMainLooper(2000, TimeUnit.MILLISECONDS)
            assertSame(source.bitmap, pixels(view.image)); assertEquals(1, loader.calls.size)
        } finally { scope.retire() }
    }

    @Test fun remountAtOlderUrlThenRepeatPriorTransitionInvalidatesItsOldCompletedPixels() {
        val scope = NovelCoverScope("account", { true }); val loader = ControlledNovelCoverLoader()
        val a = key(); val b = key(url = "https://covers.example.test/a.png?signature=replacement")
        val first = Display(); first.binding.bind(scope, a.entity, a, loader); first.binding.attach()
        loader.succeed(0, bitmap()); first.binding.bind(scope, b.entity, b, loader); loader.succeed(1, bitmap())
        first.binding.release()
        val remounted = Display(); remounted.binding.bind(scope, a.entity, a, loader); remounted.binding.attach()
        val reopened = bitmap(); loader.succeed(2, reopened); assertSame(reopened.bitmap, pixels(remounted.image))
        remounted.binding.bind(scope, b.entity, b, loader)
        assertNull(remounted.image); assertNull(scope.image(a))
        loader.succeed(2, reopened); assertNull(remounted.image)
        val replacement = bitmap(); loader.succeed(3, replacement)
        assertSame(replacement.bitmap, pixels(remounted.image)); assertEquals(4, loader.calls.size)
        scope.retire()
    }

    @Test fun changingBookSameUrlAndPermissionProjectionOrRemovingCoverNeverRestoresOldImage() {
        val scope = NovelCoverScope("account-a", { true }); val loader = ControlledNovelCoverLoader(); val view = Display(); val a = key()
        view.binding.bind(scope, a.entity, a, loader); view.binding.attach(); loader.succeed(0, bitmap())
        val other = a.copy(entity = "book-b"); view.binding.bind(scope, other.entity, other, loader); assertNull(view.image)
        loader.succeed(1, bitmap()); val changed = other.copy(projection = "withdrawn")
        view.binding.bind(scope, changed.entity, changed, loader); assertNull(view.image)
        loader.succeed(2, bitmap()); view.binding.bind(scope, changed.entity, null, loader); assertNull(view.image)
        loader.succeed(2, bitmap()); loader.fail(2); assertNull(view.image)
        // A revoked/missing URL also evicts that book's reusable success.
        assertNull(scope.image(changed)); view.binding.bind(scope, changed.entity, changed, loader); assertNull(view.image)
        assertEquals(4, loader.calls.size); scope.retire()
    }

    @Test fun realFailureShowsFallbackRetriesTwiceAndRecompositionsCannotResetFailureBudget() {
        val scope = NovelCoverScope("account-a", { true }); val loader = ControlledNovelCoverLoader(); val view = Display(); val key = key()
        view.binding.bind(scope, key.entity, key, loader); view.binding.attach()
        for (attempt in 0..2) {
            loader.fail(attempt); assertNull(view.image)
            repeat(30) { view.binding.bind(scope, key.entity, key.copy(), loader) }
            ShadowLooper.idleMainLooper(601, TimeUnit.MILLISECONDS)
        }
        repeat(5) { view.binding.detach(); view.binding.attach(); ShadowLooper.idleMainLooper(1000, TimeUnit.MILLISECONDS) }
        assertEquals(3, loader.calls.size); assertEquals(0, scope.retainedCount)
        val fresh = key.copy(url = "https://covers.example.test/replacement.png")
        view.binding.bind(scope, fresh.entity, fresh, loader); assertEquals(4, loader.calls.size)
        val image = bitmap(); loader.succeed(3, image); assertSame(image.bitmap, pixels(view.image))
        // A duplicate/late failed callback cannot clear a completed success or schedule a loop.
        loader.fail(3); ShadowLooper.idleMainLooper(1000, TimeUnit.MILLISECONDS); assertNotNull(view.image); assertEquals(4, loader.calls.size)
        scope.retire()
    }

    @Test fun detachedAndReleasedRequestsCancelAndLateRepliesCannotPopulateReusableMemory() {
        val scope = NovelCoverScope("account-a", { true }); val loader = ControlledNovelCoverLoader(); val view = Display(); val key = key()
        view.binding.bind(scope, key.entity, key, loader); view.binding.attach(); view.binding.detach()
        assertTrue(loader.calls[0].cancelled); loader.succeed(0, bitmap()); assertNull(view.image); assertNull(scope.image(key))
        view.binding.attach(); assertEquals(2, loader.calls.size)
        view.binding.release(); assertTrue(loader.calls[1].cancelled); loader.succeed(1, bitmap()); loader.fail(1)
        ShadowLooper.idleMainLooper(1000, TimeUnit.MILLISECONDS); assertNull(view.image); assertNull(scope.image(key)); assertEquals(2, loader.calls.size)
        scope.retire()
    }

    @Test fun accountAndSameIdReloginAuthorityAreIsolatedAndLogoutRetiresAllViewsAndCache() {
        var current = true
        val first = NovelCoverScope("account-a", { current }); val second = NovelCoverScope("account-b", { true })
        val loader = ControlledNovelCoverLoader(); val view = Display(); val key = key()
        view.binding.bind(first, key.entity, key, loader); view.binding.attach(); val image = bitmap(); loader.succeed(0, image)
        view.binding.bind(second, key.entity, key, loader); assertNull(view.image); assertEquals(2, loader.calls.size)
        assertNotEquals(loader.calls[0].request.memoryCacheKey, loader.calls[1].request.memoryCacheKey)
        assertNotEquals(loader.calls[0].request.diskCacheKey, loader.calls[1].request.diskCacheKey)
        first.onLogout(AccountLoggedOutEvent("unrelated")); assertTrue(first.active)
        first.onLogout(AccountLoggedOutEvent("account-a")); assertFalse(first.active); assertEquals(0, first.retainedCount)
        loader.succeed(0, image); assertNull(view.image)
        loader.succeed(1, image); second.onLogout(AccountLoggedOutEvent("account-b")); assertNull(view.image); assertEquals(0, second.retainedBytes)
        // Same account string is insufficient: a new session/generation gets another namespace.
        val replacement = NovelCoverScope("account-a", { true }); view.binding.bind(replacement, key.entity, key, loader)
        assertNull(view.image); assertNotEquals(loader.calls[0].request.diskCacheKey, loader.calls[2].request.diskCacheKey)
        current = false; assertFalse(first.isActive()); replacement.retire()
    }

    @Test fun authorityRevokedBeforeReplyAndRealGenerationChangeRejectAllPixelsWithoutLogoutEvent() {
        val account = "cover-generation-fixture"
        val generation = NovelAccountDataCleaner.captureGeneration(account)
        val scope = NovelCoverScope(account, { NovelAccountDataCleaner.isGenerationValid(account, generation) })
        val loader = ControlledNovelCoverLoader(); val view = Display(); val key = key()
        view.binding.bind(scope, key.entity, key, loader); view.binding.attach()
        NovelAccountDataCleaner.invalidate(account)
        loader.succeed(0, bitmap()); assertNull(view.image); assertFalse(scope.active); assertEquals(0, scope.retainedCount)
    }

    @Test fun byteBoundedLruEvictsLeastRecentAndDoesNotRecycleVisibleOrOversizeBitmaps() {
        val source = bitmap(); val bytes = source.bitmap.allocationByteCount.toLong()
        val scope = NovelCoverScope("account", { true }, maxBytes = bytes * 2, maxEntries = 3)
        val a = key(); val b = a.copy(entity = "b"); val c = a.copy(entity = "c")
        scope.remember(a, source); scope.remember(b, bitmap()); assertNotNull(scope.image(a)); scope.remember(c, bitmap())
        assertNull(scope.image(b)); assertNotNull(scope.image(a)); assertNotNull(scope.image(c)); assertEquals(bytes * 2, scope.retainedBytes)
        val huge = bitmap(128); scope.remember(a.copy(entity = "huge"), huge); assertEquals(2, scope.retainedCount); assertFalse(huge.bitmap.isRecycled)
        val recycled = bitmap(); scope.remember(a, recycled); recycled.bitmap.recycle(); assertNull(scope.image(a)); assertTrue(scope.retainedBytes >= 0)
        scope.retire(); assertFalse(source.bitmap.isRecycled)
    }

    @Test fun successAfterTwoFailuresResetsRetryBudgetAfterByteLruEvictionAndLaterTransientFailure() {
        val successful = bitmap(); val bytes = successful.bitmap.allocationByteCount.toLong()
        // Keep failure history entries while evicting pixels, so entry-count eviction cannot
        // accidentally mask a stale failure budget for the successful key.
        val scope = NovelCoverScope("account", { true }, maxBytes = bytes, maxEntries = 4)
        val loader = ControlledNovelCoverLoader(); val first = Display(); val original = key()
        try {
            first.binding.bind(scope, original.entity, original, loader); first.binding.attach()
            repeat(2) { attempt ->
                loader.fail(attempt)
                assertNull(first.image); assertEquals(attempt + 1, scope.failureCount(original))
                ShadowLooper.idleMainLooper(601, TimeUnit.MILLISECONDS)
                assertEquals(attempt + 2, loader.calls.size)
            }
            loader.succeed(2, successful)
            assertSame(successful.bitmap, pixels(first.image))
            assertEquals(bytes, scope.retainedBytes)
            first.binding.release()
            val other = original.copy(entity = "book-b", url = "https://covers.example.test/b.png?signature=fixture")
            val pressure = Display(); pressure.binding.bind(scope, other.entity, other, loader); pressure.binding.attach()
            assertEquals(4, loader.calls.size); loader.succeed(3, bitmap())
            assertNull("A second allocation must evict the successful original pixels", scope.image(original))
            assertNotNull(scope.image(other)); assertEquals(1, scope.retainedCount); assertEquals(bytes, scope.retainedBytes)
            pressure.binding.release()
            val revisited = Display(); revisited.binding.bind(scope, original.entity, original.copy(), loader); revisited.binding.attach()
            assertEquals(5, loader.calls.size); assertNull(revisited.image)
            loader.fail(4)
            assertEquals("An old recovered flight's two failures must not poison a later transient failure", 1, scope.failureCount(original))
            assertNull(revisited.image)
            ShadowLooper.idleMainLooper(601, TimeUnit.MILLISECONDS)
            assertEquals("The later transient failure must still have an automatic retry", 6, loader.calls.size)
            val recovered = bitmap(); loader.succeed(5, recovered)
            assertSame(recovered.bitmap, pixels(revisited.image)); assertEquals(0, scope.failureCount(original))
            assertEquals(bytes, scope.retainedBytes); assertFalse(successful.bitmap.isRecycled)
            ShadowLooper.idleMainLooper(2000, TimeUnit.MILLISECONDS)
            assertEquals(6, loader.calls.size)
        } finally { scope.retire() }
    }

    @Test fun sharedCoverUsesIndependentDrawableStateAndReleaseCannotClearAnotherView() {
        val scope = NovelCoverScope("account", { true }); val loader = ControlledNovelCoverLoader(); val a = Display(); val b = Display(); val key = key()
        a.binding.bind(scope, key.entity, key, loader); a.binding.attach(); val original = bitmap(); loader.succeed(0, original)
        b.binding.bind(scope, key.entity, key, loader); b.binding.attach()
        assertEquals(1, loader.calls.size); assertNotSame(a.image, b.image); assertNotSame(original, b.image)
        assertSame(pixels(a.image), pixels(b.image))
        a.image!!.alpha = 17; a.image!!.setBounds(0, 0, 76, 110)
        b.image!!.setBounds(0, 0, 150, 210)
        assertEquals(255, b.image!!.alpha); assertEquals(150, b.image!!.bounds.width())
        a.binding.release(); assertSame(original.bitmap, pixels(b.image)); assertNull(a.image)
        scope.retire(); assertNull(b.image)
    }

    @Test fun animatedAndNonCloneableDrawablesAreNeverSharedOrCountedAsTinyStaticBitmaps() {
        val scope = NovelCoverScope("account", { true }); val loader = ControlledNovelCoverLoader(); val view = Display(); val key = key()
        view.binding.bind(scope, key.entity, key, loader); view.binding.attach()
        val animated = SyntheticAnimatedCover(); loader.succeed(0, animated)
        assertNotNull(view.image); assertNotSame(animated, view.image); assertEquals(0, scope.retainedCount); assertEquals(0, scope.retainedBytes)
        view.binding.release()
        val second = Display(); second.binding.bind(scope, key.entity, key, loader); second.binding.attach()
        assertNull(second.image); assertEquals(2, loader.calls.size)
        val uncloneable = object : Drawable() {
            override fun draw(canvas: Canvas) {}
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(filter: ColorFilter?) {}
            @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
        loader.succeed(1, uncloneable); assertNull(second.image); assertNull(uncloneable.callback)
        assertEquals(0, scope.retainedCount); scope.retire()
    }

    @Test fun invalidUrlsAreNeverRequestsAndOpaqueSignedUrlsHaveDistinctKeysWithoutLeakingInLogs() {
        for (url in listOf("http://covers.example.test/a", "file:///private/a", "content://private/a", "https://", "")) assertNull(novelCoverKey("a", "published", url))
        val scope = NovelCoverScope("account", { true }); val a = NovelCoverRequest(scope, key()); val copy = NovelCoverRequest(scope, key())
        assertEquals(a, copy); assertTrue(a.sourcesEqual(copy))
        for (b in listOf(key(entity = "b"), key(projection = "private"), key(url = "https://covers.example.test/a.png?signature=two"))) {
            val next = NovelCoverRequest(scope, b); assertNotEquals(a, next); assertFalse(a.sourcesEqual(next)); assertNotEquals(a.diskCacheKey, next.diskCacheKey)
        }
        assertFalse(a.toString().contains("signature")); scope.retire()
    }
}
