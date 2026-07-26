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
	blurRadiusDp = 4,
	surfaceAlpha = 0.22f,
)

internal data class HomeLiquidToolbarMotionSpec(
	val dampingRatio: Float,
	val stiffness: Float,
)

internal fun homeLiquidToolbarMotionSpec(): HomeLiquidToolbarMotionSpec = HomeLiquidToolbarMotionSpec(
	dampingRatio = 0.78f,
	stiffness = 500f,
)

internal fun homeToolbarCaptureHeightDp(menuOpen: Boolean): Int = if(menuOpen) 520 else 72

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
