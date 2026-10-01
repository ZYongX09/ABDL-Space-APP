package org.joinmastodon.android.security.ui.pin

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.joinmastodon.android.ui.compose.ui.LocalColorMode

internal data class PinPalette(
	val background: Color, val foreground: Color, val secondary: Color,
	val accent: Color, val tile: Color, val emptyDot: Color,
)

@Composable
internal fun pinPalette(): PinPalette = if (LocalColorMode.current == 2) {
	PinPalette(Color(0xFF0D1624), Color(0xFFEBF0FC), Color(0xFF9AA9BF),
		Color(0xFF91BAFF), Color(0xFF1B2E48), Color(0xFF617A9E))
} else {
	PinPalette(Color(0xFFF8FAF9), Color(0xFF172D26), Color(0xFF708078),
		Color(0xFF285C48), Color(0xFFE7EFEB), Color(0xFFD6E0DB))
}
