package org.joinmastodon.android.ui.compose.friendcards

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.model.FriendCard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = CompatibilityTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class FocusImagePositionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** Native View/Drawable callback wiring only; no GPU sampling or rendered-pixel assertions. */
    @Test
    @Config(shadows = [FriendCardRedesignComposeTest.SyntheticImages::class])
    fun contentCallbacksFollowDrawableAlphaAnimatorAndRelease() {
        val loadedViews = FriendCardRedesignComposeTest.SyntheticImages.loadedViews
        loadedViews.clear()
        var firstCalls = 0
        var refreshedCalls = 0
        val callback = mutableStateOf<(() -> Unit)?>({ firstCalls++ })
        val visible = mutableStateOf(true)
        val card = FriendCard().apply {
            title = "Callback fixture"
            publicFields = emptyList()
            coverUrl = "file:///synthetic-friend-card-fixture/geometric-cover.png"
        }
        lateinit var image: FocusImage
        lateinit var update: ValueAnimator.AnimatorUpdateListener
        val animator = ValueAnimator.ofFloat(0f, 1f)
        try {
            compose.setContent {
                val theme = remember { ThemeController(colorSchemeMode = ColorSchemeMode.Light,
                    lightColors = lightColorScheme(), darkColors = darkColorScheme()) }
                MiuixTheme(controller = theme) {
                    CompositionLocalProvider(LocalFriendCardContentInvalidated provides callback.value) {
                        if (visible.value) FriendCardPortrait(card)
                    }
                }
            }
            compose.runOnIdle {
                image = loadedViews.single() as FocusImage
                assertTrue("The loader's image assignment already notified the callback", firstCalls > 0)
                fun assertNotifies(label: String, action: () -> Unit) {
                    val before = firstCalls
                    action()
                    assertTrue(label, firstCalls > before)
                }
                val drawable = AnimationDrawable().apply {
                    addFrame(ColorDrawable(Color.BLACK), 100)
                    addFrame(ColorDrawable(Color.WHITE), 100)
                }
                assertNotifies("Drawable assignment", { image.setImageDrawable(drawable) })
                assertNotifies("Animated drawable invalidates through its real View callback", { drawable.invalidateSelf() })
                assertNotifies("Explicit View alpha change", { image.alpha = .5f })
                assertNotifies("Image size change", { image.layout(0, 0, 123, 87) })
                update = requireNotNull(ReflectionHelpers.getField<ValueAnimator.AnimatorUpdateListener?>(
                    image.animate(), "mUpdateListener"))
                // Exercise the installed production listener, not a timer or a simulated GPU frame.
                assertNotifies("Property animator listener is installed independently of setAlpha", {
                    update.onAnimationUpdate(animator)
                })
                callback.value = { refreshedCalls++ }
            }
            compose.runOnIdle {
                assertSame("Callback refresh must retain the cover View", image, loadedViews.single())
                val oldCalls = firstCalls
                val before = refreshedCalls
                update.onAnimationUpdate(animator)
                assertEquals("The animator must not retain the old callback", oldCalls, firstCalls)
                assertTrue(refreshedCalls > before)
                visible.value = false
            }
            compose.runOnIdle {
                assertNull(image.onContentInvalidated)
                assertNull(ReflectionHelpers.getField<ValueAnimator.AnimatorUpdateListener?>(
                    image.animate(), "mUpdateListener"))
                val before = refreshedCalls
                image.alpha = .25f
                image.drawable.invalidateSelf()
                update.onAnimationUpdate(animator)
                assertEquals("Released images and retained listeners cannot notify", before, refreshedCalls)
            }
        } finally {
            loadedViews.clear()
        }
    }

    private fun checkPosition(width: Int, height: Int, imageWidth: Int, imageHeight: Int) {
        val view = FocusImage(RuntimeEnvironment.getApplication())
        view.setImageDrawable(object : ColorDrawable(Color.BLACK) {
            override fun getIntrinsicWidth() = imageWidth
            override fun getIntrinsicHeight() = imageHeight
        })
        view.layout(0, 0, width, height)
        val scale = maxOf(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val lowerX = (width - imageWidth * scale).coerceAtMost(0f)
        val lowerY = (height - imageHeight * scale).coerceAtMost(0f)
        for (x in listOf(0f, .5f, 1f)) for (y in listOf(0f, .4f, 1f)) {
            view.focusX = x; view.focusY = y
            view.position()
            val matrix = FloatArray(9).also { view.imageMatrix.getValues(it) }
            assertTrue("All matrix entries must remain finite", matrix.all { it.isFinite() })
            assertEquals(scale, matrix[Matrix.MSCALE_X], .000001f)
            assertEquals(scale, matrix[Matrix.MSCALE_Y], .000001f)
            assertTrue("X translation stays in its non-inverted interval", matrix[Matrix.MTRANS_X] in lowerX..0f)
            assertTrue("Y translation stays in its non-inverted interval", matrix[Matrix.MTRANS_Y] in lowerY..0f)
        }
    }

    @Test fun landscapeFloatUndershootDoesNotInvertXClamp() {
        val scale = maxOf(888f / 541, 404f / 512)
        assertTrue("Regression dimensions exercise a positive rounding remainder", 888 - 541 * scale > 0f)
        checkPosition(888, 404, 541, 512)
    }

    @Test fun portraitFloatUndershootDoesNotInvertYClamp() {
        val scale = maxOf(404f / 512, 888f / 541)
        assertTrue("Transposed dimensions exercise the Y rounding remainder", 888 - 541 * scale > 0f)
        checkPosition(404, 888, 512, 541)
    }

    @Test fun exactAndExtremeAspectRatiosKeepValidFocusTranslations() {
        checkPosition(400, 400, 400, 400)
        checkPosition(1, 1024, 1024, 1)
        checkPosition(1024, 1, 1, 1024)
        checkPosition(1080, 720, 2048, 1536)
    }
}
