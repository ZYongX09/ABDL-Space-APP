package org.joinmastodon.android.ui.compose.navigation

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.squircle.SquircleDefaults
import top.yukonga.miuix.kmp.squircle.addSquircleRect

/**
 * Directional squircle clip-reveal that grows the visible band from the top edge (popup shown
 * below its anchor) as [fractionProgress] moves 0 → 1. Mirrors miuix's `popupClipReveal` for the
 * `showBelow` case so the liquid-glass menus share the same reveal feel as `OverlayListPopup`.
 *
 * The clip path is reshaped into a squircle (continuous-corner rounded rect) of width ×
 * (height × progress); the four corners stay aligned with the surrounding rounded surface
 * during the reveal. When [squircleEnabled] is `false`, [addSquircleRect] falls back to a
 * plain rounded rectangle of the same dimensions on its own.
 *
 * Callers should forward [top.yukonga.miuix.kmp.squircle.isSquircleEnabled] from a @Composable
 * context (it cannot be read inside [drawWithCache]).
 */
internal fun Modifier.menuClipRevealFromTop(
	fractionProgress: () -> Float,
	cornerRadius: Dp,
	squircleEnabled: Boolean,
): Modifier = drawWithCache {
	val path = Path()
	val cornerPx = cornerRadius.toPx()
	onDrawWithContent {
		val progress = fractionProgress().coerceIn(0f, 1f)
		if (progress <= 0f) return@onDrawWithContent
		val width = size.width
		val height = size.height
		val visibleHeight = height * progress
		if (visibleHeight <= 0f) return@onDrawWithContent

		path.rewind()
		path.addSquircleRect(
			width = width,
			height = visibleHeight,
			cornerRadius = cornerPx,
			extension = SquircleDefaults.Extension,
			squircleEnabled = squircleEnabled,
		)
		clipPath(path) {
			this@onDrawWithContent.drawContent()
		}
	}
}

