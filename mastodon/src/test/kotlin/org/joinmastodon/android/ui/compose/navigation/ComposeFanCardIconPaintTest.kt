package org.joinmastodon.android.ui.compose.navigation

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.GlobalUserPreferences
import org.joinmastodon.android.MastodonApp
import org.joinmastodon.android.R
import org.joinmastodon.android.ui.utils.UiUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.function.IntConsumer
import kotlin.math.abs
import kotlin.math.roundToInt

/** Real controller/MiuixAppTheme, Android ImageView and native Canvas; no substituted fan UI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w393dp-h852dp-mdpi", application = CompatibilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ComposeFanCardIconPaintTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun expandedControllerPaintsTheActualCardGlyphNotJustItsCircleInBothThemes() {
        MastodonApp.context = RuntimeEnvironment.getApplication()
        val previousTheme = GlobalUserPreferences.theme
        val previousReduceMotion = GlobalUserPreferences.reduceMotion
        val captures = mutableListOf<Pair<Int, Int>>()
        try {
            for (dark in listOf(false, true)) {
                val mode = if (dark) "dark" else "light"
                lateinit var menu: ComposeFanMenuController
                compose.activityRule.scenario.onActivity { activity ->
                    GlobalUserPreferences.theme = if (dark) GlobalUserPreferences.ThemePreference.DARK else GlobalUserPreferences.ThemePreference.LIGHT
                    GlobalUserPreferences.reduceMotion = true
                    activity.setTheme(if (dark) R.style.Theme_Mastodon_Dark else R.style.Theme_Mastodon_Light)
                    activity.window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
                    activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    menu = ComposeFanMenuController(activity, IntConsumer { throw AssertionError("Paint must not dispatch a destination") })
                    // Mount the real controller directly, avoiding a second outer Compose semantics root.
                    activity.setContentView(menu.view)
                }
                compose.waitForIdle()
                compose.runOnIdle { menu.expand() }
                compose.mainClock.advanceTimeBy(1_000)
                compose.waitForIdle()
                val descriptions = listOf(R.string.compose_menu_post, R.string.compose_menu_friend_request,
                    R.string.compose_menu_friend_card, R.string.baby_albums_compose_upload)
                    .map { compose.activity.getString(it) }
                assertEquals("扩列卡", descriptions[2])
                descriptions.forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed() }
                val cardBounds = compose.onNodeWithContentDescription("扩列卡").fetchSemanticsNode().boundsInRoot
                compose.runOnIdle {
                    assertTrue(menu.isExpanded)
                    assertEquals(View.VISIBLE, menu.view.visibility)
                    val density = menu.view.resources.displayMetrics.density
                    assertEquals(1f, density, .001f)
                    assertEquals(48f * density, cardBounds.width, .01f)
                    assertEquals(48f * density, cardBounds.height, .01f)
                    val nativeIcons = imageViews(menu.view).filter { it.tag == R.drawable.ic_friend_card }
                    assertEquals("The actual fan must use exactly one native card resource", 1, nativeIcons.size)
                    val image = nativeIcons.single()
                    assertTrue(image.isAttachedToWindow)
                    assertEquals(R.drawable.ic_friend_card, image.tag)
                    assertEquals(ImageView.ScaleType.FIT_CENTER, image.scaleType)
                    assertFalse(image.isClickable)
                    assertNotNull(image.drawable)
                    val tint = UiUtils.getThemeColor(image.context, R.attr.colorM3OnTertiaryContainer)
                    val background = UiUtils.getThemeColor(image.context, R.attr.colorM3TertiaryContainer)
                    assertEquals("MiuixAppTheme must propagate the real Android tertiary content tint", tint,
                        requireNotNull(image.imageTintList).defaultColor)
                    assertEquals(255, Color.alpha(tint))
                    assertTrue("The theme must give the glyph contrast, not unresolved equal attributes", distance(tint, background) > 60)
                    captures.add(background to tint)
                    val size = (24 * density).roundToInt()
                    assertEquals(size, image.width)
                    assertEquals(size, image.height)

                    // Semantics bounds are relative to this controller's inner Compose root, not
                    // an arbitrary onRoot() or decor/status-bar origin. Account for its native offset.
                    val composeRoot = (menu.view.getChildAt(0) as ViewGroup).getChildAt(0)
                    val rootLocation = IntArray(2).also { composeRoot.getLocationInWindow(it) }
                    val menuLocation = IntArray(2).also { menu.view.getLocationInWindow(it) }
                    val circle = Rect(cardBounds.left + rootLocation[0] - menuLocation[0],
                        cardBounds.top + rootLocation[1] - menuLocation[1],
                        cardBounds.right + rootLocation[0] - menuLocation[0],
                        cardBounds.bottom + rootLocation[1] - menuLocation[1])
                    val left = (circle.center.x - size / 2f).roundToInt()
                    val top = (circle.center.y - size / 2f).roundToInt()
                    val imageLocation = IntArray(2).also { image.getLocationInWindow(it) }
                    assertEquals("Native icon must be centered inside the described circle", left,
                        imageLocation[0] - menuLocation[0])
                    assertEquals(top, imageLocation[1] - menuLocation[1])
                    val full = draw(menu.view)
                    assertTrue(circle.left >= 0 && circle.top >= 0 && circle.right <= full.width && circle.bottom <= full.height)
                    val crop = Bitmap.createBitmap(full, left, top, size, size)
                    // Independent native reference loads the original @color/fluent... vector,
                    // resolves the same Android theme, then applies the expected ImageView tint.
                    val reference = ImageView(image.context).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setBackgroundColor(background)
                        setImageResource(R.drawable.ic_friend_card)
                        imageTintList = ColorStateList.valueOf(tint)
                        measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY))
                        layout(0, 0, size, size)
                    }
                    val expected = draw(reference)
                    try {
                        save(full, "fan-$mode")
                        save(crop, "fan-$mode-card")
                        save(expected, "fan-$mode-card-native-reference")
                        val ring = buildList {
                            for (y in 0 until circle.height.roundToInt()) for (x in 0 until circle.width.roundToInt()) {
                                val dx = x + .5f - circle.width / 2f
                                val dy = y + .5f - circle.height / 2f
                                val radiusSquared = dx * dx + dy * dy
                                if (radiusSquared in (16f * density) * (16f * density)..(20f * density) * (20f * density)) {
                                    add(full.getPixel(circle.left.roundToInt() + x, circle.top.roundToInt() + y))
                                }
                            }
                        }
                        assertTrue("$mode must really paint the surrounding circle", ring.size > 100)
                        assertTrue("$mode circle ring must match the actual theme container", ring.all { distance(it, background) <= 3 })
                        val threshold = (distance(tint, background) / 4).coerceAtLeast(8)
                        val actualInk = ink(crop, background, threshold)
                        val expectedInk = ink(expected, background, threshold)
                        assertTrue("$mode native resource reference must contain nonuniform glyph pixels", expectedInk.size > 40)
                        assertTrue("$mode actual 24dp center must contain glyph pixels, not only the circle: ${actualInk.size}",
                            actualInk.size >= expectedInk.size * .75f && actualInk.size <= expectedInk.size * 1.25f)
                        assertTrue("$mode center must preserve the glyph's cutouts/background", actualInk.size < size * size * .8f)
                        assertTrue("$mode supplied card paths must match the native reference (1px tolerance)",
                            coverage(expectedInk, actualInk) >= .9f && coverage(actualInk, expectedInk) >= .9f)
                        val matchingTint = actualInk.count { (x, y) -> distance(crop.getPixel(x, y), tint) <= 8 }
                        assertTrue("$mode visible glyph must use the requested tint, not just its untinted source color", matchingTint > 20)
                    } finally {
                        full.recycle()
                        crop.recycle()
                        expected.recycle()
                    }
                }
            }
            assertEquals(2, captures.size)
            assertTrue("The dark capture must actually switch Android/Miuix colors", captures[0] != captures[1])
        } finally {
            compose.activityRule.scenario.onActivity { activity -> activity.setContentView(FrameLayout(activity)) }
            GlobalUserPreferences.theme = previousTheme
            GlobalUserPreferences.reduceMotion = previousReduceMotion
        }
    }

    private fun imageViews(view: View): List<ImageView> = buildList {
        if (view is ImageView) add(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(imageViews(view.getChildAt(index)))
    }

    private fun draw(view: View): Bitmap {
        assertTrue("Native view must be measured before capture", view.width > 0 && view.height > 0)
        return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    private fun distance(a: Int, b: Int): Int = maxOf(abs(Color.red(a) - Color.red(b)),
        abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b)))

    private fun ink(bitmap: Bitmap, background: Int, threshold: Int): Set<Pair<Int, Int>> = buildSet {
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.alpha(pixel) > 240 && distance(pixel, background) > threshold) add(x to y)
        }
    }

    private fun coverage(expected: Set<Pair<Int, Int>>, actual: Set<Pair<Int, Int>>): Float =
        expected.count { (x, y) -> (-1..1).any { dx -> (-1..1).any { dy -> (x + dx to y + dy) in actual } } }
            .toFloat() / expected.size.coerceAtLeast(1)

    private fun save(bitmap: Bitmap, name: String) {
        val directory = File("/tmp/abdl-map-profile-card-renders")
        assertTrue("Cannot create $directory", directory.isDirectory || directory.mkdirs())
        val output = File(directory, "$name.png")
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue("Capture is empty: $output", output.length() > 0)
    }
}
