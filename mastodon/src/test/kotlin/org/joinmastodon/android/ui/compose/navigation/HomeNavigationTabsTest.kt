package org.joinmastodon.android.ui.compose.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.joinmastodon.android.R
import org.joinmastodon.android.ui.compose.navigation.animation.stabilizeDragVelocity
import org.junit.Test

class HomeNavigationTabsTest {
	@Test
	fun mapsEveryTabIdToItsStableIndex() {
		HomeNavigationTabs.ids.forEachIndexed { index, id ->
			assertEquals(index, HomeNavigationTabs.indexOf(id))
		}
	}

	@Test
	fun fallsBackToHomeForUnknownTabId() {
		assertEquals(0, HomeNavigationTabs.indexOf(Int.MIN_VALUE))
	}

	@Test
	fun containsOnlyPrimaryHomeDestinations() {
		assertArrayEquals(
			intArrayOf(
				R.id.tab_home,
				R.id.tab_search,
				R.id.tab_diaper,
				R.id.tab_friend_request,
				R.id.tab_profile,
			),
			HomeNavigationTabs.ids,
		)
		assertEquals(0, HomeNavigationTabs.indexOf(R.id.tab_messages))
	}

	@Test
	fun dragReleaseAlwaysSnapsToAValidTab() {
		assertEquals(1, snapNavigationDragTarget(1.47f, 5))
		assertEquals(2, snapNavigationDragTarget(1.5f, 5))
		assertEquals(0, snapNavigationDragTarget(-0.4f, 5))
		assertEquals(4, snapNavigationDragTarget(4.8f, 5))
	}

	@Test
	fun dragShapeVelocityDoesNotFlipDirectionInOneFrame() {
		assertEquals(4f, stabilizeDragVelocity(8f, -8f), 0f)
		assertEquals(-4f, stabilizeDragVelocity(-8f, 8f), 0f)
		assertEquals(2f, stabilizeDragVelocity(0f, 8f), 0f)
		assertEquals(7.5f, stabilizeDragVelocity(8f, 6f), 0f)
	}
}
