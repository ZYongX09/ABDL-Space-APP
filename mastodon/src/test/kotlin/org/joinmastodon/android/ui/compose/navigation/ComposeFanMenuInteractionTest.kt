package org.joinmastodon.android.ui.compose.navigation

import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.Canvas
import org.joinmastodon.android.GlobalUserPreferences
import org.junit.After
import org.robolectric.annotation.GraphicsMode
import java.io.File
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.viewinterop.AndroidView
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.MastodonApp
import org.joinmastodon.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.function.Consumer
import java.util.function.IntConsumer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = CompatibilityTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ComposeFanMenuInteractionTest {
	@get:Rule val compose = createComposeRule()
	private lateinit var menu: ComposeFanMenuController
	private val actions = mutableListOf<Int>()
	private val expandedStates = mutableListOf<Boolean>()
	private var previousTheme = GlobalUserPreferences.theme

	@Before fun setUp() {
		MastodonApp.context = RuntimeEnvironment.getApplication()
		previousTheme = GlobalUserPreferences.theme
		val dark = MastodonApp.context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
		GlobalUserPreferences.theme = if (dark) GlobalUserPreferences.ThemePreference.DARK else GlobalUserPreferences.ThemePreference.LIGHT
		val configuration = android.content.res.Configuration(MastodonApp.context.resources.configuration).apply { fontScale = 1.6f }
		MastodonApp.context.resources.updateConfiguration(configuration, MastodonApp.context.resources.displayMetrics)
		compose.setContent {
			AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
				context.setTheme(if (dark) R.style.Theme_Mastodon_Dark else R.style.Theme_Mastodon_Light)
				menu = ComposeFanMenuController(
					context,
					IntConsumer { actions.add(it) },
					Consumer { expandedStates.add(it) },
				)
				menu.view
			})
		}
		compose.waitForIdle()
	}

	@After fun restoreTheme() { GlobalUserPreferences.theme = previousTheme }

	@Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "en-w320dp-h720dp-night-mdpi")
	fun darkFanUsesOneActualPaintedBackgroundForAllFiveDestinations() { renderPalette(true) }

	@Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers = "en-w320dp-h720dp-notnight-mdpi")
	fun lightFanKeepsItsExistingAccentsAndAllFiveRealComposeClicks() { renderPalette(false) }

	private fun renderPalette(dark: Boolean) {
		expand()
		lateinit var image: Bitmap
		compose.runOnIdle {
			assertTrue(menu.view.isAttachedToWindow)
			assertEquals(1.6f, menu.view.resources.configuration.fontScale, .001f)
			image = Bitmap.createBitmap(menu.view.width, menu.view.height, Bitmap.Config.ARGB_8888)
			menu.view.draw(Canvas(image))
		}
		try {
			val titles = listOf(R.string.compose_menu_post, R.string.compose_menu_friend_request, R.string.compose_menu_friend_card,
				R.string.baby_albums_compose_upload, R.string.compose_menu_novel)
			val colors = titles.map { resource ->
				val node = compose.onNodeWithContentDescription(MastodonApp.context.getString(resource)).fetchSemanticsNode()
				assertTrue("Keep the full 48dp touch target", node.boundsInRoot.height >= 48f && node.boundsInRoot.width >= 48f)
				// Sample inside the circle but outside its central icon, in the attached rendered menu.
				val menuOrigin = IntArray(2)
				compose.runOnIdle { menu.view.getLocationInWindow(menuOrigin) }
				val x = (node.boundsInWindow.center.x - menuOrigin[0] - node.boundsInWindow.width * .32f).toInt()
				val y = (node.boundsInWindow.center.y - menuOrigin[1]).toInt()
				assertTrue(x in 0 until image.width && y in 0 until image.height)
				image.getPixel(x, y)
			}
			if (dark) assertEquals("Friend request must not retain its secondary accent", 1, colors.distinct().size)
			else assertTrue("Light-mode accent design stays unchanged", colors.distinct().size > 1)
			System.getProperty("beta35RenderDir")?.let { directory ->
				val output = File(directory, "fan/fan-${if (dark) "dark" else "light"}-320-font160.png")
				assertTrue(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs())
				output.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
			}
		} finally { image.recycle() }
		everyOptionDispatchesItsDestinationId()
	}

	@Test fun everyOptionDispatchesItsDestinationId() {
		val expected = listOf(
			R.string.compose_menu_post to R.id.compose_post,
			R.string.compose_menu_friend_request to R.id.compose_friend_request,
			R.string.compose_menu_friend_card to R.id.compose_friend_card,
			R.string.baby_albums_compose_upload to R.id.compose_album,
			R.string.compose_menu_novel to R.id.compose_novel,
		)
		for ((titleRes, id) in expected) {
			expand()
			val title = RuntimeEnvironment.getApplication().getString(titleRes)
			if (id == R.id.compose_novel) compose.runOnIdle {
				assertTrue("Fan paints the unified novel resource, not a generic fallback icon",
					imageViews(menu.view).any { it.tag == R.drawable.ic_novel_rebuilt && it.isAttachedToWindow })
			}
			compose.onNodeWithContentDescription(title).performClick()
			compose.waitForIdle()
			compose.runOnIdle { assertEquals(id, actions.lastOrNull()) }
		}
		assertEquals(5, actions.size)
		// Selecting an option folds the fan away.
		compose.runOnIdle { assertFalse(menu.isExpanded) }
	}

	@Test fun tappingTheDimmedAreaDismissesWithoutDispatching() {
		expand()
		tap(4f, 4f)
		compose.waitForIdle()
		compose.runOnIdle {
			assertTrue(actions.isEmpty())
			assertFalse(menu.isExpanded)
		}
	}

	@Test fun collapsingHidesTheOverlaySoItStopsInterceptingTouches() {
		expand()
		compose.runOnIdle { assertTrue(menu.isExpanded) }
		val title = RuntimeEnvironment.getApplication().getString(R.string.compose_menu_post)
		assertEquals(1, compose.onAllNodesWithContentDescription(title).fetchSemanticsNodes().size)
		compose.runOnIdle { menu.collapse() }
		compose.mainClock.advanceTimeBy(1_000)
		compose.waitForIdle()
		compose.runOnIdle { assertEquals(View.GONE, menu.view.visibility) }
	}

	@Test fun expandAndCollapseNotifyTheHostExactlyOncePerTransition() {
		compose.runOnIdle {
			assertFalse(menu.collapseIfExpanded())
			assertFalse(menu.isExpanded)
		}
		expand()
		compose.runOnIdle {
			assertTrue(menu.collapseIfExpanded())
			assertFalse(menu.collapseIfExpanded())
		}
		compose.runOnIdle { assertEquals(listOf(true, false), expandedStates) }
	}

	private fun imageViews(view: View): List<android.widget.ImageView> = buildList {
		if (view is android.widget.ImageView) add(view)
		if (view is android.view.ViewGroup) repeat(view.childCount) { addAll(imageViews(view.getChildAt(it))) }
	}

	private fun expand() {
		compose.runOnIdle { menu.expand() }
		compose.mainClock.advanceTimeBy(1_000)
		compose.waitForIdle()
	}

	private fun tap(x: Float, y: Float) {
		compose.runOnIdle {
			val time = SystemClock.uptimeMillis()
			dispatch(MotionEvent.ACTION_DOWN, x, y, time, time)
			dispatch(MotionEvent.ACTION_UP, x, y, time, time + 60)
		}
		compose.waitForIdle()
	}

	private fun dispatch(action: Int, x: Float, y: Float, downTime: Long, time: Long) {
		val event = MotionEvent.obtain(downTime, time, action, x, y, 0)
		try { menu.view.dispatchTouchEvent(event) } finally { event.recycle() }
	}
}
