package org.joinmastodon.android.ui.compose.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendUniverseToolbarModelTest {
	@Test
	fun largeTitleProgressClampsToScrollRange() {
		assertEquals(0f, friendUniverseCollapseProgress(-8, 3f), 0.001f)
		assertEquals(0.5f, friendUniverseCollapseProgress(108, 3f), 0.001f)
		assertEquals(1f, friendUniverseCollapseProgress(300, 3f), 0.001f)
	}

	@Test
	fun toolbarMetricsInterpolateFromMiuixLargeTitle() {
		assertEquals(32f, friendUniverseTitleSizeSp(0f), 0.001f)
		assertEquals(18f, friendUniverseTitleSizeSp(1f), 0.001f)
		assertEquals(128, friendUniverseTopPaddingDp(liquidMode = true))
		assertEquals(8, friendUniverseTopPaddingDp(liquidMode = false))
		assertEquals(128, friendUniverseCaptureHeightDp(searchExpanded = false))
		assertEquals(144, friendUniverseCaptureHeightDp(searchExpanded = true))
	}

	@Test
	fun onlyLatestSearchGenerationMayApply() {
		assertTrue(friendUniverseMayApplySearch(requestGeneration = 3, currentGeneration = 3))
		assertFalse(friendUniverseMayApplySearch(requestGeneration = 2, currentGeneration = 3))
	}

	@Test
	fun paginationWaitsForInitialLoadAndRealItems() {
		assertFalse(friendUniverseCanLoadMore(dataLoading = true, loadingMore = false, hasMore = true, itemCount = 20, lastVisibleItem = 19))
		assertFalse(friendUniverseCanLoadMore(dataLoading = false, loadingMore = false, hasMore = true, itemCount = 0, lastVisibleItem = 0))
		assertTrue(friendUniverseCanLoadMore(dataLoading = false, loadingMore = false, hasMore = true, itemCount = 20, lastVisibleItem = 17))
	}
}
