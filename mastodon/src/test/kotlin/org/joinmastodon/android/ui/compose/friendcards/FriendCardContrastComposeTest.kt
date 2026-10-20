package org.joinmastodon.android.ui.compose.friendcards

import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.compose.animation.VectorConverter
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** Software fallback/animation tests, NOT a substitute for actual GPU-layer readback verification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w393dp-h852dp-mdpi", application = CompatibilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class FriendCardContrastComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun patchCoordinatesAreRelativeToContentAndClampedBeforeAnyReadback() {
        val sampler = FriendCardContrastSampler()
        assertNull(sampler.patchBounds(FriendCardContrastControl.Close))
        sampler.setContentBounds(Rect(100f, 200f, 600f, 800f))
        sampler.setControlBounds(FriendCardContrastControl.Close, Rect(116f, 206f, 160f, 250f))
        sampler.setControlBounds(FriendCardContrastControl.Manage, Rect(400f, 206f, 584f, 250f))
        assertEquals(Rect(16f, 6f, 60f, 50f), sampler.patchBounds(FriendCardContrastControl.Close))
        assertEquals(Rect(300f, 6f, 484f, 50f), sampler.patchBounds(FriendCardContrastControl.Manage))
        sampler.setControlBounds(FriendCardContrastControl.Close, Rect(90f, 190f, 144f, 244f))
        assertEquals(Rect(0f, 0f, 44f, 44f), sampler.patchBounds(FriendCardContrastControl.Close))
        sampler.setControlBounds(FriendCardContrastControl.Close, Rect(0f, 0f, 40f, 40f))
        assertNull(sampler.patchBounds(FriendCardContrastControl.Close))
        sampler.dispose()
    }

    @Test fun eachControlAnimatesTintInsteadOfJumpingAndReadbackFailureReturnsToSafeWhite() {
        val sampler = FriendCardContrastSampler()
        compose.setContent {
            val theme = remember { ThemeController(colorSchemeMode = ColorSchemeMode.Light,
                lightColors = lightColorScheme(), darkColors = darkColorScheme()) }
            MiuixTheme(controller = theme) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    FriendCardDetailControls(null, true, {}, {}, contrast = sampler)
                }
            }
        }
        compose.waitForIdle()
        fun icon(resource: Int): ImageView {
            fun search(view: View): ImageView? {
                if (view is ImageView && view.tag == resource) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) search(view.getChildAt(i))?.let { return it }
                return null
            }
            return requireNotNull(search(compose.activity.window.decorView))
        }
        fun tint(resource: Int) = requireNotNull(icon(resource).imageTintList).defaultColor
        compose.runOnIdle {
            assertEquals(AndroidColor.WHITE, tint(R.drawable.ic_fluent_dismiss_24_regular))
            assertEquals(AndroidColor.WHITE, tint(R.drawable.ic_friend_card))
        }
        val darkTint = Color(0xFF101416)
        fun beginTransition(change: () -> Unit) {
            compose.runOnIdle { change() }
            // runOnIdle synchronizes BEFORE its action. With autoAdvance=false, advancing the
            // scheduler alone does not publish the snapshot written in that action. Flush it
            // before the first frame, then separately compose the target and start its tween.
            compose.waitForIdle()
            compose.mainClock.advanceTimeByFrame() // Recomposition publishes the target.
            compose.waitForIdle()
            compose.mainClock.advanceTimeByFrame() // First animation frame: play time is zero.
            compose.waitForIdle()
        }
        fun assertTweenTint(from: Color, to: Color, playTimeMillis: Long) {
            val expected = TargetBasedAnimation(
                animationSpec = tween<Color>(220),
                typeConverter = Color.VectorConverter(to.colorSpace),
                initialValue = from,
                targetValue = to,
            ).getValueFromNanos(playTimeMillis * 1_000_000L).toArgb()
            compose.runOnIdle {
                assertEquals("Actual ImageView tint must follow the 220ms colour tween at ${playTimeMillis}ms",
                    expected, tint(R.drawable.ic_fluent_dismiss_24_regular))
                assertEquals("Manage retains its independent dark-patch white tint",
                    AndroidColor.WHITE, tint(R.drawable.ic_friend_card))
            }
        }
        compose.mainClock.autoAdvance = false
        try {
            beginTransition {
                sampler.accept(FriendCardContrastControl.Close, IntArray(256) { AndroidColor.WHITE })
                sampler.accept(FriendCardContrastControl.Manage, IntArray(256) { AndroidColor.BLACK })
            }
            assertTweenTint(Color.White, darkTint, 0)
            compose.mainClock.advanceTimeBy(96)
            assertTweenTint(Color.White, darkTint, 96)
            compose.runOnIdle {
                val transition = tint(R.drawable.ic_fluent_dismiss_24_regular)
                assertNotEquals("Animated tint must not remain at the old colour", AndroidColor.WHITE, transition)
                assertNotEquals("Animated tint must not jump directly to the final colour", darkTint.toArgb(), transition)
            }
            compose.mainClock.advanceTimeBy(96)
            assertTweenTint(Color.White, darkTint, 192)
            compose.mainClock.advanceTimeBy(32)
            assertTweenTint(Color.White, darkTint, 224)

            beginTransition { sampler.fail() }
            assertTweenTint(darkTint, Color.White, 0)
            compose.mainClock.advanceTimeBy(96)
            assertTweenTint(darkTint, Color.White, 96)
            compose.mainClock.advanceTimeBy(128)
            assertTweenTint(darkTint, Color.White, 224)
            compose.runOnIdle {
                assertEquals(FriendCardContrastInk.Unknown, sampler.closeInk)
                assertEquals(FriendCardContrastInk.Unknown, sampler.manageInk)
                assertNull(sampler.backdrop)
            }
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { sampler.dispose() }
        }
    }
}
