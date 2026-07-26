package org.joinmastodon.android.ui.compose.navigation

internal enum class HomeToolbarAction {
	COMPOSE,
	OVERFLOW,
}

internal enum class HomeToolbarLeadingMode {
	TIMELINE,
	NEW_POSTS,
}

internal enum class HomeToolbarMenuPage {
	NONE,
	TIMELINES,
	ROOT,
	LISTS,
	HASHTAGS;

	fun openLists(): HomeToolbarMenuPage = LISTS

	fun openHashtags(): HomeToolbarMenuPage = HASHTAGS

	fun back(): HomeToolbarMenuPage = if(this==LISTS || this==HASHTAGS) ROOT else NONE
}

internal data class HomeToolbarReminderState(
	val overflowBadged: Boolean,
	val announcementsBadged: Boolean,
	val settingsBadged: Boolean,
)

internal data class HomeLiquidToolbarState(
	val selectedTimeline: Int,
	val showNewPosts: Boolean,
	val menuPage: HomeToolbarMenuPage,
) {
	val leadingMode: HomeToolbarLeadingMode
		get() = if(showNewPosts) HomeToolbarLeadingMode.NEW_POSTS else HomeToolbarLeadingMode.TIMELINE
}

internal data class HomeLiquidToolbarVisualSpec(
	val titleTextSp: Int,
	val menuTextSp: Int,
	val blurRadiusDp: Int,
	val surfaceAlpha: Float,
)

internal fun homeLiquidToolbarVisualSpec(): HomeLiquidToolbarVisualSpec = HomeLiquidToolbarVisualSpec(
	titleTextSp = 18,
	menuTextSp = 17,
	blurRadiusDp = 8,
	surfaceAlpha = 0.22f,
)

internal data class HomeLiquidToolbarMotionSpec(
	val dampingRatio: Float,
	val stiffness: Float,
)

internal fun homeLiquidToolbarMotionSpec(): HomeLiquidToolbarMotionSpec = HomeLiquidToolbarMotionSpec(
	dampingRatio = 0.72f,
	stiffness = 520f,
)

internal fun homeToolbarCaptureHeightDp(menuOpen: Boolean): Int = if(menuOpen) 520 else 72

internal fun homeTimelineTopPaddingDp(liquidMode: Boolean): Int = if(liquidMode) 72 else 0

internal fun homeToolbarCollapsedWidthDp(titleWidthDp: Float): Float =
	(titleWidthDp + 72f).coerceIn(96f, 260f)

internal fun shouldConsumeToolbarBack(menuVisible: Boolean, menuPending: Boolean): Boolean = menuVisible || menuPending

internal fun shouldInstallToolbarDragRecognizer(isLeading: Boolean): Boolean = !isLeading

internal fun toolbarGlassZIndex(isExpanded: Boolean): Float = if(isExpanded) 2f else 1f

internal data class HomeLiquidToolbarOutlineSpec(
	val widthDp: Float,
	val innerBlurRadiusDp: Float,
	val dualPeak: Boolean,
)

internal fun homeLiquidToolbarOutlineSpec(): HomeLiquidToolbarOutlineSpec = HomeLiquidToolbarOutlineSpec(
	widthDp = 1f,
	innerBlurRadiusDp = 2f,
	dualPeak = true,
)

internal fun liquidToolbarActions(): List<HomeToolbarAction> = listOf(
	HomeToolbarAction.COMPOSE,
	HomeToolbarAction.OVERFLOW,
)

internal fun homeToolbarReminderState(
	hasUnreadAnnouncements: Boolean,
	hasUpdate: Boolean,
): HomeToolbarReminderState = HomeToolbarReminderState(
	overflowBadged = hasUnreadAnnouncements || hasUpdate,
	announcementsBadged = hasUnreadAnnouncements,
	settingsBadged = hasUpdate,
)
