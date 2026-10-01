/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/pin/PinInput.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui.pin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.joinmastodon.android.R

@Composable
internal fun PinInput(digits: Int, enteredDigits: Int, modifier: Modifier = Modifier) {
	val palette = pinPalette()
	val description = stringResource(R.string.security_a11y_pin_progress, enteredDigits, digits)
	Row(
		modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description },
		horizontalArrangement = Arrangement.spacedBy(19.dp, Alignment.CenterHorizontally),
		verticalAlignment = Alignment.CenterVertically,
	) {
		repeat(digits) { index ->
			Box(Modifier.size(12.dp).background(
				if (index < enteredDigits) palette.accent else palette.emptyDot, CircleShape))
		}
	}
}
