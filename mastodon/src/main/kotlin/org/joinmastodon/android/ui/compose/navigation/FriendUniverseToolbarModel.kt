package org.joinmastodon.android.ui.compose.navigation

private const val COLLAPSE_RANGE_DP = 72f

fun friendUniverseCollapseProgress(scrollY: Int, density: Float): Float =
	(scrollY / (COLLAPSE_RANGE_DP * density.coerceAtLeast(1f))).coerceIn(0f, 1f)

fun friendUniverseTitleSizeSp(progress: Float): Float =
	32f + (18f - 32f) * progress.coerceIn(0f, 1f)

fun friendUniverseTitleGlassWidthDp(progress: Float): Float =
	196f + (132f - 196f) * progress.coerceIn(0f, 1f)

fun friendUniverseTitleGlassHeightDp(progress: Float): Float =
	58f + (48f - 58f) * progress.coerceIn(0f, 1f)

fun friendUniverseTopPaddingDp(liquidMode: Boolean): Int = if(liquidMode) 112 else 8

fun friendUniverseCaptureHeightDp(searchExpanded: Boolean): Int = if(searchExpanded) 128 else 120

fun friendUniverseMayApplySearch(requestGeneration: Int, currentGeneration: Int): Boolean =
	requestGeneration == currentGeneration

fun friendUniverseDataLoadingAfterResponse(requestGeneration: Int, currentGeneration: Int, dataLoading: Boolean): Boolean =
	if(requestGeneration == currentGeneration) false else dataLoading

fun friendUniverseCanLoadMore(dataLoading: Boolean, loadingMore: Boolean, hasMore: Boolean, itemCount: Int, lastVisibleItem: Int): Boolean =
	!dataLoading && !loadingMore && hasMore && itemCount>0 && lastVisibleItem>=itemCount-3
