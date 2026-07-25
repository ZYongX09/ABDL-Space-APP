package org.joinmastodon.android.ui.compose.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.joinmastodon.android.R
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
}
