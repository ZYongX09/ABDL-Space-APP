package org.joinmastodon.android.ui.compose.navigation

private const val COLLAPSE_RANGE_DP = 72f

fun friendUniverseCollapseProgress(scrollY: Int, density: Float): Float =
	(scrollY / (COLLAPSE_RANGE_DP * density.coerceAtLeast(1f))).coerceIn(0f, 1f)

fun friendUniverseTitleSizeSp(progress: Float): Float =
	32f + (18f - 32f) * progress.coerceIn(0f, 1f)

fun friendUniverseTopPaddingDp(liquidMode: Boolean): Int = if(liquidMode) 128 else 8

fun friendUniverseCaptureHeightDp(searchExpanded: Boolean): Int = if(searchExpanded) 144 else 128

fun friendUniverseMayApplySearch(requestGeneration: Int, currentGeneration: Int): Boolean =
	requestGeneration == currentGeneration

fun friendUniverseCanLoadMore(dataLoading: Boolean, loadingMore: Boolean, hasMore: Boolean, itemCount: Int, lastVisibleItem: Int): Boolean =
	!dataLoading && !loadingMore && hasMore && itemCount>0 && lastVisibleItem>=itemCount-3
