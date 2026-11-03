package org.joinmastodon.android.ui.compose.navigation

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
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
import java.util.function.IntConsumer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], qualifiers = "zh-rCN-w393dp-h852dp-mdpi", application = CompatibilityTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class HomeLiquidToolbarMenuInteractionTest {
	@get:Rule val compose = createComposeRule()
	private lateinit var toolbar: HomeLiquidToolbarController
	private val actions = mutableListOf<Int>()

	@Before fun setUp() {
		MastodonApp.context = RuntimeEnvironment.getApplication()
		compose.setContent {
			AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
				toolbar = HomeLiquidToolbarController(context, IntConsumer {}, Runnable {}, Runnable {}, IntConsumer { actions.add(it) }, Runnable {})
				toolbar.setComposeMenu(org.joinmastodon.android.fragments.HomeFragment.composeMenuItems(context))
				toolbar.view
			})
		}
		compose.waitForIdle()
	}

	@Test fun allRenderedRowsDispatchTheirActionWithAndWithoutStatusInset() {
		for(inset in listOf(0, 72)) {
			for(item in org.joinmastodon.android.fragments.HomeFragment.composeMenuItems(toolbar.view.context)) {
				val title = item.title
				val id = item.id
				openCompose(inset)
				// Use the actual rendered text position, but dispatch through the outer View gate.
				val center = composeRowCenter(title)
				if (id == R.id.compose_novel) compose.runOnIdle {
					assertEquals(R.drawable.ic_novel_rebuilt, item.iconRes)
					assertTrue("The actual liquid row paints the unified novel drawable",
						imageViews(toolbar.view).any { it.drawable != null && org.robolectric.Shadows.shadowOf(it.drawable).createdFromResId == R.drawable.ic_novel_rebuilt && it.isAttachedToWindow })
				}
				tap(center.x, center.y)
				compose.runOnIdle {
					assertEquals(id, actions.lastOrNull())
					assertFalse(toolbar.onBackPressed())
				}
			}
		}
		assertEquals(10, actions.size)
	}

	@Test fun outsideTapDismissesWithoutActivatingRow() {
		openCompose(72)
		tap(1f, 1f)
		compose.runOnIdle {
			assertTrue(actions.isEmpty())
			assertFalse(toolbar.onBackPressed())
		}
	}

	@Test fun dragJustBelowSecondRowStartSelectsSecondRow() {
		openCompose(72)
		val first = composeRowCenter(toolbar.view.context.getString(R.string.compose_menu_post))
		val density = toolbar.view.resources.displayMetrics.density
		val secondRowY = 72 + (ToolbarMenuGeometry.TOP_OFFSET_DP + ToolbarMenuGeometry.CONTENT_TOP_PADDING_DP + ToolbarMenuGeometry.ROW_HEIGHT_DP + 2) * density
		compose.runOnIdle {
			val downTime = SystemClock.uptimeMillis()
			dispatch(MotionEvent.ACTION_DOWN, first.x, first.y, downTime, downTime)
			dispatch(MotionEvent.ACTION_MOVE, first.x, secondRowY, downTime, downTime + 300)
			dispatch(MotionEvent.ACTION_UP, first.x, secondRowY, downTime, downTime + 600)
		}
		compose.waitForIdle()
		assertEquals(listOf(R.id.compose_friend_request), actions)
	}

private fun composeRowCenter(title: String): Offset {
		// The collapsed overflow glass also composes a clipped copy of the active page.
		// Select the full-width row in the compose glass, not that hidden semantics copy.
		val density = toolbar.view.resources.displayMetrics.density
		val rows = compose.onAllNodesWithText(title).fetchSemanticsNodes().filter {
			abs(it.boundsInRoot.width - 200 * density) < density
		}
		assertEquals("Exactly one full-width compose row must be rendered", 1, rows.size)
		return rows.single().boundsInRoot.center
	}

	private fun imageViews(view: android.view.View): List<android.widget.ImageView> = buildList {
		if (view is android.widget.ImageView) add(view)
		if (view is android.view.ViewGroup) repeat(view.childCount) { addAll(imageViews(view.getChildAt(it))) }
	}

	private fun openCompose(inset: Int) {
		compose.runOnIdle { toolbar.setStatusBarInset(inset) }
		compose.waitForIdle()
		val density = toolbar.view.resources.displayMetrics.density
		tap(toolbar.view.width - 140 * density, inset + 32 * density)
		compose.waitForIdle()
	}

	private fun tap(x: Float, y: Float) {
		compose.runOnIdle {
			val time = SystemClock.uptimeMillis()
			dispatch(MotionEvent.ACTION_DOWN, x, y, time, time)
			dispatch(MotionEvent.ACTION_UP, x, y, time, time + 100)
		}
		compose.waitForIdle()
	}

	private fun dispatch(action: Int, x: Float, y: Float, downTime: Long, time: Long) {
		val event = MotionEvent.obtain(downTime, time, action, x, y, 0)
		try { toolbar.view.dispatchTouchEvent(event) } finally { event.recycle() }
	}
}
