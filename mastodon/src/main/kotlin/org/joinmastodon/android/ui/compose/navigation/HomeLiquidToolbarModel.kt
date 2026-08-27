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
	blurRadiusDp = 18,
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

/**
 * Miuix-identical menu enter/exit animation spec.
 *
 * Mirrors `ListPopupDefaults` + `CascadingListPopupLayout` from miuix-ui so the liquid-glass
 * menus share the same spring scale + directional clip-reveal + alpha + dim feel as
 * `OverlayListPopup`. The container itself still morphs via [homeLiquidToolbarMotionSpec]
 * (kept light), while the inner content rides these tracks for the elastic reveal.
 *
 * Springs are expressed in folme style (damping + response seconds) — `folmeSpring(response)`
 * converts to `stiffness = (2π/response)²`, so a response of 0.45s ≈ stiffness 195.
 * Lower `fractionSpringDamping` produces more visible overshoot (0.6 ≈ 10% bounce).
 */
internal data class HomeLiquidToolbarMenuAnimationSpec(
	val fractionSpringDamping: Float,
	val fractionSpringResponseSec: Float,
	val fractionVisibilityThreshold: Float,
	val containerSpringDamping: Float,
	val containerSpringResponseSec: Float,
	val containerMorphScaleFrom: Float,
	val containerMorphScaleDamping: Float,
	val containerMorphScaleResponseSec: Float,
	val alphaEnterDurationMs: Int,
	val alphaExitDurationMs: Int,
	val dimEnterDurationMs: Int,
	val dimExitDurationMs: Int,
	val enterScaleFrom: Float,
	val primaryShrunkScale: Float,
	val cornerRadiusDp: Float,
)

internal fun homeLiquidToolbarMenuAnimationSpec(): HomeLiquidToolbarMenuAnimationSpec = HomeLiquidToolbarMenuAnimationSpec(
	fractionSpringDamping = 0.62f,
	fractionSpringResponseSec = 0.42f,
	fractionVisibilityThreshold = 0.0001f,
	containerSpringDamping = 0.58f,
	containerSpringResponseSec = 0.45f,
	containerMorphScaleFrom = 0.88f,
	containerMorphScaleDamping = 0.55f,
	containerMorphScaleResponseSec = 0.42f,
	alphaEnterDurationMs = 220,
	alphaExitDurationMs = 150,
	dimEnterDurationMs = 300,
	dimExitDurationMs = 150,
	enterScaleFrom = 0.15f,
	primaryShrunkScale = 0.95f,
	cornerRadiusDp = 28f,
)

/**
 * Container-morph spring used for the liquid-glass outline deformation. Snaps quickly close to the
 * final size without overshoot so the inner miuix-style content reveal dominates the perceived motion.
 */
internal data class HomeLiquidToolbarContainerMorphSpec(
	val dampingRatio: Float,
	val stiffness: Float,
)

internal fun homeLiquidToolbarContainerMorphSpec(): HomeLiquidToolbarContainerMorphSpec = HomeLiquidToolbarContainerMorphSpec(
	dampingRatio = 0.95f,
	stiffness = 1500f,
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
