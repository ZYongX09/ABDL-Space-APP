package org.joinmastodon.android.ui.compose.navigation

import android.os.SystemClock
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

	@Before fun setUp() {
		MastodonApp.context = RuntimeEnvironment.getApplication()
		compose.setContent {
			AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
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

	@Test fun everyOptionDispatchesItsDestinationId() {
		val expected = listOf(
			R.string.compose_menu_post to R.id.compose_post,
			R.string.compose_menu_friend_request to R.id.compose_friend_request,
			R.string.compose_menu_friend_card to R.id.compose_friend_card,
			R.string.baby_albums_compose_upload to R.id.compose_album,
		)
		for ((titleRes, id) in expected) {
			expand()
			val title = RuntimeEnvironment.getApplication().getString(titleRes)
			compose.onNodeWithContentDescription(title).performClick()
			compose.waitForIdle()
			compose.runOnIdle { assertEquals(id, actions.lastOrNull()) }
		}
		assertEquals(4, actions.size)
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
