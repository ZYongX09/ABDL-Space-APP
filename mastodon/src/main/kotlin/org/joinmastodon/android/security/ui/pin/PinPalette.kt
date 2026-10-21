package org.joinmastodon.android.security.ui.pin

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class PinPalette(
	val background: Color, val foreground: Color, val secondary: Color,
	val accent: Color, val tile: Color, val emptyDot: Color,
	val error: Color,
)

/** Follow the same Android Material semantic colors bridged by MiuixAppTheme as other pages. */
@Composable
internal fun pinPalette(): PinPalette = with(MiuixTheme.colorScheme) {
	PinPalette(
		background = background,
		foreground = onBackground,
		secondary = onSurfaceVariantSummary,
		accent = primary,
		tile = secondaryContainer,
		emptyDot = outline,
		error = error,
	)
}
