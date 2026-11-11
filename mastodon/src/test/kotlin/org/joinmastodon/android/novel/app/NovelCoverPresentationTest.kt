package org.joinmastodon.android.novel.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.events.AccountLoggedOutEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Presentation state uses the existing binding, not an AndroidView or independently trusted URL. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = CompatibilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NovelCoverPresentationTest {
    private fun key() = requireNotNull(novelCoverKey("book", "published", "https://covers.example.test/render.png?signature=fixture"))
    private fun image() = BitmapDrawable(RuntimeEnvironment.getApplication().resources,
        Bitmap.createBitmap(24, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) })

    @Test fun explicitSoftwareDecodePreservesDimensionsAndFullIdentity() {
        val scope = NovelCoverScope("account", { true })
        val a = NovelCoverRequest(scope, key())
        val b = NovelCoverRequest(scope, key().copy(url = "https://covers.example.test/render.png?signature=other"))
        assertEquals(Bitmap.Config.ARGB_8888, a.desiredConfig)
        assertEquals(500, a.desiredMaxWidth); assertEquals(700, a.desiredMaxHeight)
        assertNotEquals(a.memoryCacheKey, b.memoryCacheKey); assertNotEquals(a.diskCacheKey, b.diskCacheKey)
        scope.retire()
    }

    @Test fun presentationReleaseClearsAndNextWarmPresentationRestoresWithoutAnotherRequest() {
        val scope = NovelCoverScope("account", { true }); val loader = ControlledNovelCoverLoader()
        val a = NovelCoverPresentation(); val key = key()
        a.attach(scope, key.entity, key, loader)
        assertTrue(a.attached); assertNull(a.image); assertEquals(1, loader.calls.size)
        val source = image(); loader.succeed(0, source)
        assertSame(source.bitmap, (a.image as BitmapDrawable).bitmap)
        a.release(); assertFalse(a.attached); assertNull(a.image); assertNull(a.coordinates)
        val b = NovelCoverPresentation(); b.attach(scope, key.entity, key.copy(), loader)
        assertSame(source.bitmap, (b.image as BitmapDrawable).bitmap); assertEquals(1, loader.calls.size)
        scope.onLogout(AccountLoggedOutEvent("account")); assertNull(b.image)
        loader.succeed(0, source); assertNull(a.image); assertNull(b.image)
        b.release()
    }

    @Test fun drawTimeAuthorityValidationClearsSuccessAndSyntheticAnimatedStateStillHasIndependentOwnership() {
        var authorized = true
        val scope = NovelCoverScope("account", { authorized }); val loader = ControlledNovelCoverLoader()
        val state = NovelCoverPresentation(); val key = key()
        state.attach(scope, key.entity, key, loader)
        val animated = SyntheticAnimatedCover(); loader.succeed(0, animated)
        assertNotNull(state.image); assertNotSame(animated, state.image)
        assertEquals(0, scope.retainedCount)
        authorized = false
        assertFalse(state.binding.validate()); assertNull(state.image); assertFalse(scope.active)
        state.release()
    }
}
