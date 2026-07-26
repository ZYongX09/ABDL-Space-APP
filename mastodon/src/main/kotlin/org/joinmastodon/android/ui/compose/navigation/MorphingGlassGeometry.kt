package org.joinmastodon.android.ui.compose.navigation

import androidx.compose.ui.util.lerp

internal data class GlassBounds(
	val left: Float,
	val top: Float,
	val width: Float,
	val height: Float,
	val cornerRadius: Float,
)

internal fun interpolateGlassBounds(start: GlassBounds, end: GlassBounds, progress: Float): GlassBounds {
	val t = progress.coerceIn(0f, 1f)
	return GlassBounds(
		left = lerp(start.left, end.left, t),
		top = lerp(start.top, end.top, t),
		width = lerp(start.width, end.width, t),
		height = lerp(start.height, end.height, t),
		cornerRadius = lerp(start.cornerRadius, end.cornerRadius, t),
	)
}

internal fun anchoredMenuBounds(
	anchor: GlassBounds,
	menuWidth: Float,
	menuHeight: Float,
	screenWidth: Float,
	side: MorphingGlassSide,
): GlassBounds {
	val left = when(side) {
		MorphingGlassSide.LEADING -> anchor.left.coerceIn(0f, (screenWidth-menuWidth).coerceAtLeast(0f))
		MorphingGlassSide.TRAILING -> (anchor.left+anchor.width-menuWidth).coerceIn(0f, (screenWidth-menuWidth).coerceAtLeast(0f))
	}
	return GlassBounds(left, anchor.top, menuWidth, menuHeight, 28f)
}

internal fun hitTestMenuRow(y: Float, rows: List<ClosedFloatingPointRange<Float>>): Int? =
	rows.indexOfFirst { y>=it.start && y<it.endInclusive }.takeIf { it>=0 }

internal enum class TrailingToolbarAction { COMPOSE, MORE }

internal fun trailingToolbarAction(x: Float, width: Float): TrailingToolbarAction =
	if(x < width / 2f) TrailingToolbarAction.COMPOSE else TrailingToolbarAction.MORE

internal fun toolbarMenuHeightDp(itemCount: Int, hasBackRow: Boolean): Int =
	((itemCount + if(hasBackRow) 1 else 0) * 48 + 12).coerceIn(60, 420)

internal enum class OutsideGlassGesture { CLOSE_ONLY, FORWARD_TO_CONTENT }

internal fun outsideGlassGesture(distance: Float, touchSlop: Float): OutsideGlassGesture =
	if(distance > touchSlop) OutsideGlassGesture.FORWARD_TO_CONTENT else OutsideGlassGesture.CLOSE_ONLY

internal fun toolbarMenuCommitDelayFrames(): Int = 1

internal fun isInsideLeadingGlass(x: Float, y: Float, top: Float, width: Float, height: Float): Boolean =
	x in 0f..width && y>=top && y<top+height

internal enum class LeadingGlassReleaseAction { OPEN, KEEP_OPEN, COLLAPSE }

internal fun leadingGlassReleaseAction(longPressOpened: Boolean, moved: Boolean): LeadingGlassReleaseAction = when {
	longPressOpened && !moved -> LeadingGlassReleaseAction.COLLAPSE
	longPressOpened -> LeadingGlassReleaseAction.KEEP_OPEN
	else -> LeadingGlassReleaseAction.OPEN
}
