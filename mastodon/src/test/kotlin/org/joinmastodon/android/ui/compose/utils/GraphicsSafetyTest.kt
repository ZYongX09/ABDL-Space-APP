package org.joinmastodon.android.ui.compose.utils

import android.app.ActivityManager
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.MastodonApp
import org.joinmastodon.android.ui.utils.LiquidGlassCompatibility
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 32, 33, 35], application = CompatibilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GraphicsSafetyTest {
    private val supported get() = Build.VERSION.SDK_INT >= 33
    private val effects get() = listOf(GraphicsSafety, BackgroundGraphicsSafety, PageBlurGraphicsSafety)

    @Before fun setUp() {
        MastodonApp.context = RuntimeEnvironment.getApplication()
    }

    @Test fun capabilityIsOnlyAnSdkThreshold() {
        for (sdk in listOf(26, 32, 33, 35)) {
            assertEquals(sdk >= 33, LiquidGlassCompatibility.evaluate(sdk))
        }
        assertCapabilities()
    }

    @Test fun lowRamIsAdvisoryNotACapabilityGate() {
        shadowOf(MastodonApp.context.getSystemService(ActivityManager::class.java)).setIsLowRamDevice(true)
        assertTrue(LiquidGlassCompatibility.shouldWarnAboutPerformance())
        assertCapabilities()
    }

    @Test fun guardedReturnsTheSelectedValueAndNeverCallsOnFailure() {
        val token = Any()
        effects.forEach { safety ->
            var blocks = 0
            var fallbacks = 0
            assertSame(token, safety.guarded("normal", fallback = { fallbacks++; token },
                onFailure = { fail("onFailure is obsolete") }) { blocks++; token })
            assertEquals(if (supported) 1 else 0, blocks)
            assertEquals(if (supported) 0 else 1, fallbacks)
        }
    }

    @Test fun allSupportedFailuresPropagateWithoutChangingAnyCapability() {
        if (!supported) return
        val failures = listOf<Throwable>(
            IllegalArgumentException("shader"), UnsatisfiedLinkError("driver"),
            OutOfMemoryError("layer"), AssertionError("fatal"), StackOverflowError("fatal"),
            object : VirtualMachineError("fatal") {},
        )
        effects.forEach { safety ->
            failures.forEach { failure ->
                assertSame(failure, assertThrows(Throwable::class.java) {
                    safety.guarded("failure", fallback = { fail("no runtime fallback") },
                        onFailure = { fail("no failure cleanup") }) { throw failure }
                })
                assertCapabilities()
                assertEquals("later", safety.guarded("later", fallback = { "fallback" }) { "later" })
            }
        }
    }

    @Test fun unsupportedFallbackFailurePropagates() {
        if (supported) return
        val failure = IllegalArgumentException("application fallback")
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) {
            GraphicsSafety.guarded("old SDK", fallback = { throw failure },
                onFailure = { fail("no failure cleanup") }) { fail("unsupported effect") }
        })
        assertCapabilities()
    }

    @Test fun cleanupRunsOnEverySdkAndPropagatesEveryFailure() {
        effects.forEach { safety ->
            var calls = 0
            safety.cleanup("normal") { calls++ }
            assertEquals(1, calls)
            for (failure in listOf<Throwable>(IllegalStateException("release"),
                UnsatisfiedLinkError("release"), OutOfMemoryError("release"))) {
                assertSame(failure, assertThrows(Throwable::class.java) {
                    safety.cleanup("release") { throw failure }
                })
                assertCapabilities()
            }
        }
    }

    @Test fun normalDrawRestoresCanvasAndSizeWithoutFallback() {
        withCanvas { canvas, bitmap ->
            val saves = canvas.nativeCanvas.saveCount
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(8f, 8f)) {
                GraphicsSafety.drawEffect(this, "normal", fallback = { drawRect(Color.Blue) }) {
                    drawRect(Color.Red)
                    drawContext.canvas.save()
                    drawContext.canvas.translate(100f, 100f)
                    drawContext.size = Size(1f, 1f)
                }
                assertEquals(Size(8f, 8f), size)
            }
            assertEquals(saves, canvas.nativeCanvas.saveCount)
            assertEquals(if (supported) android.graphics.Color.RED else android.graphics.Color.BLUE,
                bitmap.getPixel(4, 4))
        }
    }

    @Test fun failedDrawRestoresCanvasAndPropagatesWithoutPaintingFallback() {
        if (!supported) return
        withCanvas { canvas, bitmap ->
            val saves = canvas.nativeCanvas.saveCount
            val failure = IllegalStateException("draw failure")
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(8f, 8f)) {
                val scopeSaves = canvas.nativeCanvas.saveCount
                assertSame(failure, assertThrows(IllegalStateException::class.java) {
                    GraphicsSafety.drawEffect(this, "draw", fallback = { fail("no runtime fallback") },
                        onFailure = { fail("no failure cleanup") }) {
                        drawContext.canvas.save()
                        drawContext.canvas.translate(100f, 100f)
                        drawContext.size = Size(1f, 1f)
                        throw failure
                    }
                })
                assertEquals(Size(8f, 8f), size)
                assertEquals(scopeSaves, canvas.nativeCanvas.saveCount)
                assertEquals(android.graphics.Color.TRANSPARENT, bitmap.getPixel(4, 4))
                drawRect(Color.Green) // Restoration makes the same scope usable after failure.
            }
            assertEquals(saves, canvas.nativeCanvas.saveCount)
            assertEquals(android.graphics.Color.GREEN, bitmap.getPixel(4, 4))
            assertCapabilities()
        }
    }

    @Test fun oldSdkDrawsFallbackOnlyAndDoesNotInvokeFailureHook() {
        if (supported) return
        withCanvas { canvas, bitmap ->
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(8f, 8f)) {
                GraphicsSafety.drawEffect(this, "old SDK", fallback = { drawRect(Color.Blue) },
                    onFailure = { fail("no failure cleanup") }) { fail("unsupported draw") }
            }
            assertEquals(android.graphics.Color.BLUE, bitmap.getPixel(4, 4))
        }
    }

    @Test fun oldSdkDrawFallbackFailurePropagatesUnchanged() {
        if (supported) return
        val failure = IllegalArgumentException("fallback draw")
        withCanvas { canvas, _ ->
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(8f, 8f)) {
                assertSame(failure, assertThrows(IllegalArgumentException::class.java) {
                    GraphicsSafety.drawEffect(this, "old SDK", fallback = { throw failure }) {
                        fail("unsupported draw")
                    }
                })
            }
        }
        assertCapabilities()
    }

    @Test fun unsupportedModifierFactoriesAreNotCalledAndRecordingPreservesReceiver() {
        if (supported) return
        val receiver = Modifier.then(object : Modifier.Element {})
        assertSame(receiver, receiver.safeBackdropRecording { throw AssertionError("unsupported recorder") })
        val fallback: DrawScope.() -> Unit = { drawRect(Color.Blue) }
        val modifiers = listOf(
            Modifier.safeGraphicsEffect("old SDK", fallback) { throw AssertionError("unsupported factory") },
            Modifier.safeBackdropEffect("old SDK", fallback) { throw AssertionError("unsupported factory") },
        )
        modifiers.forEach { modifier ->
            var contentDraws = 0
            val element = modifier.foldIn(null as androidx.compose.ui.node.ModifierNodeElement<*>?) { _, value ->
                value as androidx.compose.ui.node.ModifierNodeElement<*>
            }
            val node = checkNotNull(element).create() as androidx.compose.ui.node.DrawModifierNode
            withCanvas { canvas, bitmap ->
                CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(8f, 8f)) {
                    val scope = object : androidx.compose.ui.graphics.drawscope.ContentDrawScope, DrawScope by this {
                        override fun drawContent() { contentDraws++ }
                    }
                    with(node) { scope.draw() }
                }
                assertEquals(android.graphics.Color.BLUE, bitmap.getPixel(4, 4))
                assertEquals(1, contentDraws)
            }
        }
    }

    private fun assertCapabilities() {
        assertEquals(supported, LiquidGlassCompatibility.isSystemSupported())
        assertEquals(supported, LiquidGlassCompatibility.isSupported())
        effects.forEach { assertEquals(supported, it.isSupported()) }
    }

    private fun withCanvas(block: (Canvas, Bitmap) -> Unit) {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try { block(Canvas(android.graphics.Canvas(bitmap)), bitmap) } finally { bitmap.recycle() }
    }
}
