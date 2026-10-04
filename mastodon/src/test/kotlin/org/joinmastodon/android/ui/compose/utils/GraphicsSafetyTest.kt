package org.joinmastodon.android.ui.compose.utils

import android.app.ActivityManager
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.MastodonApp
import org.joinmastodon.android.ui.utils.LiquidGlassCompatibility
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = CompatibilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class GraphicsSafetyTest {
    private fun reset() {
        LiquidGlassCompatibility::class.java.getDeclaredMethod("resetForTests").apply {
            isAccessible = true
        }.invoke(null)
    }

    @Before fun setUp() {
        reset()
        MastodonApp.context = RuntimeEnvironment.getApplication()
        shadowOf(MastodonApp.context.getSystemService(ActivityManager::class.java)).setIsLowRamDevice(false)
        assertTrue(GraphicsSafety.isSupported())
    }

    @After fun tearDown() { reset() }

    @Test fun evaluateRejectsOnlyOldApiAndFailedEffectNotLowRam() {
        for (sdk in listOf(26, 32, 33, 35)) {
            for (disabled in listOf(false, true)) {
                assertEquals(sdk >= 33 && !disabled, LiquidGlassCompatibility.evaluate(sdk, disabled))
            }
        }
        shadowOf(MastodonApp.context.getSystemService(ActivityManager::class.java)).setIsLowRamDevice(true)
        assertTrue(LiquidGlassCompatibility.shouldWarnAboutPerformance())
        assertTrue(GraphicsSafety.isSupported())
        assertTrue(BackgroundGraphicsSafety.isSupported())
    }

    @Test fun failuresAndNotificationsAreIsolatedByEffect() {
        val effects = listOf(GraphicsSafety, BackgroundGraphicsSafety, PageBlurGraphicsSafety)
        for (failedEffect in effects) {
            reset()
            val notifications = IntArray(effects.size)
            val listeners = effects.mapIndexed { index, effect ->
                Runnable { notifications[index]++ }.also(effect::addFailureListener)
            }
            failedEffect.guarded("isolated failure", fallback = {}) { throw IllegalStateException("test") }
            assertTrue(LiquidGlassCompatibility.isSystemSupported())
            effects.forEach { effect -> assertEquals(effect !== failedEffect, effect.isSupported()) }
            assertTrue(notifications.all { it == 0 })
            shadowOf(Looper.getMainLooper()).idle()
            effects.forEachIndexed { index, effect ->
                assertEquals(if (effect === failedEffect) 1 else 0, notifications[index])
                effect.removeFailureListener(listeners[index])
            }
        }
    }

    @Test fun normalOperationPassesThroughWithoutFallbackOrCleanup() {
        val token = Any()
        val result = GraphicsSafety.guarded("normal", fallback = { fail("fallback"); token },
            onFailure = { fail("cleanup") }) { token }
        assertSame(token, result)
        assertTrue(GraphicsSafety.isSupported())
    }

    @Test fun recoverableFailuresDisableImmediatelyAndNotifyOnlyOnMainQueue() {
        for (error in listOf<Throwable>(IllegalArgumentException("shader"), UnsatisfiedLinkError("effect"), OutOfMemoryError("layer"))) {
            reset()
            var calls = 0
            val listener = Runnable { assertSame(Looper.getMainLooper(), Looper.myLooper()); calls++ }
            LiquidGlassCompatibility.addFailureListener(listener)
            var cleaned = false
            val result = GraphicsSafety.guarded("construction", fallback = {
                assertFalse(GraphicsSafety.isSupported())
                assertTrue(cleaned)
                "fallback"
            }, onFailure = { cleaned = true }) { throw error }
            assertEquals("fallback", result)
            assertEquals(0, calls)
            var entered = false
            GraphicsSafety.guarded("later", fallback = {}) { entered = true }
            assertFalse(entered)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, calls)
            LiquidGlassCompatibility.removeFailureListener(listener)
        }
    }

    @Test fun removalCancelsPendingNotificationAndLateListenerIsDeferred() {
        var calls = 0
        val removed = Runnable { calls++ }
        LiquidGlassCompatibility.addFailureListener(removed)
        LiquidGlassCompatibility.reportFailure("effect", IllegalStateException())
        LiquidGlassCompatibility.removeFailureListener(removed)
        val late = Runnable { calls++ }
        LiquidGlassCompatibility.addFailureListener(late)
        assertEquals(0, calls)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, calls)
        LiquidGlassCompatibility.removeFailureListener(late)
    }

    @Test fun unknownFatalErrorsAreRethrownAndDoNotDisableEffects() {
        for (error in listOf<Error>(AssertionError("fatal"), StackOverflowError("fatal"), object : VirtualMachineError("fatal") {})) {
            try {
                GraphicsSafety.guarded("fatal", fallback = { fail("must rethrow") }) { throw error }
                fail("fatal error was swallowed")
            } catch (caught: Error) {
                assertSame(error, caught)
            }
            assertTrue(GraphicsSafety.isSupported())
        }
    }

    @Test fun fallbackFailurePropagatesRatherThanBeingReportedAsGraphicsFailure() {
        LiquidGlassCompatibility.reportFailure("disabled", IllegalStateException())
        val failure = IllegalArgumentException("application fallback")
        try {
            GraphicsSafety.guarded("skipped", fallback = { throw failure }) { fail("effect must not run") }
            fail("fallback exception was swallowed")
        } catch (caught: IllegalArgumentException) {
            assertSame(failure, caught)
        }
    }

    @Test fun failingDrawRestoresCanvasAndPaintsFallbackInTheSameFrame() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(android.graphics.Canvas(bitmap))
        val originalSaveCount = canvas.nativeCanvas.saveCount
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(8f, 8f)) {
            GraphicsSafety.drawEffect(this, "draw", fallback = { drawRect(Color.Red) }) {
                // Simulate an effect leaving nested saves, clipping and transforms behind.
                drawContext.canvas.save()
                drawContext.canvas.translate(100f, 100f)
                drawContext.size = Size(1f, 1f)
                throw IllegalStateException("draw failure")
            }
            assertEquals(Size(8f, 8f), size)
        }
        assertEquals(originalSaveCount, canvas.nativeCanvas.saveCount)
        assertEquals(android.graphics.Color.RED, bitmap.getPixel(4, 4))
        assertFalse(GraphicsSafety.isSupported())
        bitmap.recycle()
    }

    @Test fun disabledSessionNeverExecutesEffectDrawing() {
        LiquidGlassCompatibility.reportFailure("disabled", IllegalStateException())
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(android.graphics.Canvas(bitmap))
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(4f, 4f)) {
            GraphicsSafety.drawEffect(this, "skipped", fallback = { drawRect(Color.Blue) }) {
                fail("disabled shader draw")
            }
        }
        assertEquals(android.graphics.Color.BLUE, bitmap.getPixel(2, 2))
        bitmap.recycle()
    }
}
