package org.joinmastodon.android.ui.compose.component.effect

import android.app.ActivityManager
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.MastodonApp
import org.joinmastodon.android.ui.compose.utils.BackgroundGraphicsSafety
import org.joinmastodon.android.ui.compose.utils.GraphicsSafety
import org.joinmastodon.android.ui.compose.utils.PageBlurGraphicsSafety
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
@Config(sdk = [33, 35], application = CompatibilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackgroundEffectCompatibilityTest {
    @Before fun setup() {
        MastodonApp.context = RuntimeEnvironment.getApplication()
        shadowOf(MastodonApp.context.getSystemService(ActivityManager::class.java)).setIsLowRamDevice(true)
    }

    @Test fun lowRamDoesNotBlockRealShaderConstructionOrAnimation() {
        assertTrue(LiquidGlassCompatibility.shouldWarnAboutPerformance())
        val painter = BgEffectPainter(true)
        try {
            assertTrue(painter.prepare())
            val first = render(painter, 0f)
            val second = render(painter, 2f)
            if (Build.VERSION.SDK_INT >= 35) {
                assertTrue(first.any { android.graphics.Color.alpha(it) > 0 })
                assertFalse("Real animation changes native shader pixels", first.contentEquals(second))
            }
            assertSupported()
        } finally { painter.clear() }
    }

    @Test fun navigationFailurePropagatesAndBothRealShaderPresetsRemainUsable() {
        assertFailureAndRender(GraphicsSafety, IllegalStateException("backdrop capture"))
    }

    @Test fun pageBlurFailurePropagatesAndBothRealShaderPresetsRemainUsable() {
        assertFailureAndRender(PageBlurGraphicsSafety, IllegalArgumentException("texture blur"))
    }

    @Test fun backgroundFailurePropagatesWithoutPreventingLaterRealShaderConstruction() {
        assertFailureAndRender(BackgroundGraphicsSafety, UnsatisfiedLinkError("driver"))
    }

    private fun assertFailureAndRender(
        safety: org.joinmastodon.android.ui.compose.utils.GraphicsEffectSafety,
        failure: Throwable,
    ) {
        assertSame(failure, assertThrows(Throwable::class.java) {
            safety.guarded("failure", fallback = { fail("no runtime fallback") },
                onFailure = { fail("no cleanup hook") }) { throw failure }
        })
        assertSupported()
        for (isOs3 in listOf(false, true)) {
            val painter = BgEffectPainter(isOs3)
            try {
                assertTrue(painter.prepare())
                val pixels = render(painter, 1f, isOs3)
                if (Build.VERSION.SDK_INT >= 35) {
                    assertTrue(pixels.any { android.graphics.Color.alpha(it) > 0 })
                }
            } finally { painter.clear() }
        }
        assertSupported()
    }

    @Test fun clearingAndPreparingAFreshRealPainterDoesNotChangeCapability() {
        for (isOs3 in listOf(false, true)) {
            val painter = BgEffectPainter(isOs3)
            assertTrue(painter.prepare())
            painter.clear()
            assertThrows(IllegalStateException::class.java) { painter.brush }
            val fresh = BgEffectPainter(isOs3)
            try {
                assertTrue(fresh.prepare())
                render(fresh, 0f, isOs3)
            } finally { fresh.clear() }
        }
        assertSupported()
    }

    @Test @Config(sdk = [26, 32]) fun oldSdkDoesNotConstructRuntimeShaders() {
        for (isOs3 in listOf(false, true)) {
            val painter = BgEffectPainter(isOs3)
            assertFalse(painter.prepare())
            painter.clear()
        }
        assertFalse(LiquidGlassCompatibility.isSystemSupported())
        assertFalse(GraphicsSafety.isSupported())
        assertFalse(BackgroundGraphicsSafety.isSupported())
        assertFalse(PageBlurGraphicsSafety.isSupported())
    }

    private fun render(painter: BgEffectPainter, time: Float, isOs3: Boolean = true): IntArray {
        val width = 64
        val height = 128
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val preset = BgEffectConfig.get(DeviceType.PHONE, false, isOs3)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(object : android.graphics.Canvas(bitmap) {
                // Native Skia evaluates the real shader; simulate only the framework hardware-entry check.
                override fun isHardwareAccelerated() = true
            }), Size(width.toFloat(), height.toFloat())) {
                BackgroundGraphicsSafety.drawEffect(this, "real background draw", fallback = {
                    fail("Supported RuntimeShader must never fall back")
                }) {
                    painter.updateResolution(size.width, size.height)
                    painter.updateBoundIfNeeded(size.height * .5f, size.height, size.width)
                    painter.updatePresetIfNeeded(DeviceType.PHONE, false)
                    painter.updateColors(preset, .5f)
                    painter.updateAnimTime(time)
                    painter.updatePointsAnim(time, preset)
                    drawRect(painter.brush)
                }
            }
            // API33 scalar uniforms are Robolectric no-op stubs: test construction and no fallback,
            // but do not read or assert pixels until API35's native uniform implementation.
            if (Build.VERSION.SDK_INT < 35) return intArrayOf()
            return IntArray(width * height).also {
                bitmap.getPixels(it, 0, width, 0, 0, width, height)
            }
        } finally { bitmap.recycle() }
    }

    private fun assertSupported() {
        assertTrue(LiquidGlassCompatibility.isSystemSupported())
        assertTrue(GraphicsSafety.isSupported())
        assertTrue(BackgroundGraphicsSafety.isSupported())
        assertTrue(PageBlurGraphicsSafety.isSupported())
    }
}
