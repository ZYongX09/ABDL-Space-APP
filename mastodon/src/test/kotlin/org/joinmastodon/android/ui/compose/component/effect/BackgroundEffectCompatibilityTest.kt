package org.joinmastodon.android.ui.compose.component.effect

import android.app.ActivityManager
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.GlobalUserPreferences
import org.joinmastodon.android.MastodonApp
import org.joinmastodon.android.ui.compose.utils.BackgroundGraphicsSafety
import org.joinmastodon.android.ui.compose.utils.GraphicsSafety
import org.joinmastodon.android.ui.compose.utils.PageBlurGraphicsSafety
import org.joinmastodon.android.ui.utils.LiquidGlassCompatibility
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 35], application = CompatibilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackgroundEffectCompatibilityTest {
    @Before fun setup() {
        reset()
        MastodonApp.context = RuntimeEnvironment.getApplication()
        shadowOf(MastodonApp.context.getSystemService(ActivityManager::class.java)).setIsLowRamDevice(true)
        GlobalUserPreferences.useIosLiquidNavigation = false
    }

    @After fun teardown() { reset() }

    @Test fun lowRamAndFailedNavigationDoNotBlockBackgroundShaderConstructionOrAnimation() {
        LiquidGlassCompatibility.reportFailure("backdrop capture budget", IllegalStateException("16 MiB"))
        assertFalse(GraphicsSafety.isSupported())
        assertTrue(BackgroundGraphicsSafety.isSupported())
        val painter = BgEffectPainter(true)
        assertTrue(painter.prepare())
        val first = render(painter, 0f)
        val second = render(painter, 2f)
        assertTrue(BackgroundGraphicsSafety.isSupported())
        // API33/34 scalar uniforms are no-op stubs, leaving resolution/alpha undefined in pixels.
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            assertTrue(first.any { android.graphics.Color.alpha(it) > 0 })
            assertFalse(first.contentEquals(second))
        }
        painter.clear()
    }

    @Test fun failedPageBlurDoesNotBlockEitherShaderPreset() {
        PageBlurGraphicsSafety.guarded("texture blur", fallback = {}) { throw IllegalArgumentException("test") }
        assertFalse(PageBlurGraphicsSafety.isSupported())
        for (isOs3 in listOf(false, true)) {
            val painter = BgEffectPainter(isOs3)
            assertTrue(painter.prepare())
            val pixels = render(painter, 1f, isOs3)
            assertTrue(BackgroundGraphicsSafety.isSupported())
            if (android.os.Build.VERSION.SDK_INT >= 35) assertTrue(pixels.any { android.graphics.Color.alpha(it) > 0 })
            painter.clear()
        }
        assertTrue(GraphicsSafety.isSupported())
    }

    @Test fun backgroundFailureStillPreventsConstructionWithoutDisablingSettingsOrNavigation() {
        BackgroundGraphicsSafety.guarded("background driver", fallback = {}) { throw UnsatisfiedLinkError("test") }
        assertFalse(BgEffectPainter(true).prepare())
        assertTrue(LiquidGlassCompatibility.isSystemSupported())
        assertTrue(GraphicsSafety.isSupported())
        assertTrue(PageBlurGraphicsSafety.isSupported())
    }

    private fun render(painter: BgEffectPainter, time: Float, isOs3: Boolean = true): IntArray {
        val width = 64
        val height = 128
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val preset = BgEffectConfig.get(DeviceType.PHONE, false, isOs3)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(object : android.graphics.Canvas(bitmap) {
            // Native Skia evaluates the shader; only the framework hardware-entry check is simulated.
            override fun isHardwareAccelerated() = true
        }), Size(width.toFloat(), height.toFloat())) {
            BackgroundGraphicsSafety.drawEffect(this, "background test draw", fallback = { throw AssertionError("Shader fell back", ShadowLog.getLogsForTag("LiquidGlassCompatibility").lastOrNull()?.throwable) }) {
                painter.updateResolution(size.width, size.height)
                painter.updateBoundIfNeeded(size.height * .5f, size.height, size.width)
                painter.updatePresetIfNeeded(DeviceType.PHONE, false)
                painter.updateColors(preset, .5f)
                painter.updateAnimTime(time)
                painter.updatePointsAnim(time, preset)
                drawRect(painter.brush)
            }
        }
        return IntArray(width * height).also {
            bitmap.getPixels(it, 0, width, 0, 0, width, height)
            bitmap.recycle()
        }
    }

    private fun reset() {
        LiquidGlassCompatibility::class.java.getDeclaredMethod("resetForTests").apply { isAccessible = true }.invoke(null)
    }
}
