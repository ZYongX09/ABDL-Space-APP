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
