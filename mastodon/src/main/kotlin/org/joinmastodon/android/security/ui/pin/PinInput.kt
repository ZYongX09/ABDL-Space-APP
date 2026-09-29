/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/pin/PinInput.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui.pin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.joinmastodon.android.R
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PinInput(
	digits: Int,
	enteredDigits: Int,
	isVerifying: Boolean,
	enabled: Boolean,
	onBackspace: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val progressDescription = stringResource(
		R.string.security_a11y_pin_progress,
		enteredDigits,
		digits,
	)
	val verifyingDescription = stringResource(R.string.security_a11y_verifying)
	val backspaceDescription = stringResource(R.string.security_a11y_backspace)
	Row(
		modifier = modifier
			.fillMaxWidth()
			.semantics(mergeDescendants = true) {
				contentDescription = progressDescription
			},
		verticalAlignment = Alignment.CenterVertically,
	) {
		Row(
			modifier = Modifier
				.weight(1f)
				.padding(horizontal = 12.dp)
				.semantics { hideFromAccessibility() },
			horizontalArrangement = Arrangement.Center,
			verticalAlignment = Alignment.CenterVertically,
		) {
			repeat(digits) { index ->
				Box(
					modifier = Modifier
						.padding(horizontal = 8.dp)
						.size(12.dp)
						.then(
							if (index < enteredDigits) {
								Modifier.background(MiuixTheme.colorScheme.primary, CircleShape)
							} else {
								Modifier
									.background(MiuixTheme.colorScheme.background, CircleShape)
									.border(2.dp, MiuixTheme.colorScheme.dividerLine, CircleShape)
							},
						),
				)
			}
		}
		Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
			if (isVerifying) {
				CircularProgressIndicator(
					modifier = Modifier
						.size(22.dp)
						.semantics {
								progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
								contentDescription = verifyingDescription
						},
					strokeWidth = 2.dp,
					color = MiuixTheme.colorScheme.primary,
				)
			} else {
				IconButton(
					onClick = onBackspace,
					enabled = enabled && enteredDigits > 0,
					modifier = Modifier.size(48.dp),
				) {
					Icon(
						painter = painterResource(R.drawable.ic_fluent_backspace_24_regular),
						contentDescription = backspaceDescription,
						tint = MiuixTheme.colorScheme.onSurface,
						modifier = Modifier
							.size(24.dp)
							.alpha(if (enabled && enteredDigits > 0) 1f else 0.45f),
					)
				}
			}
		}
	}
	HorizontalDivider(color = MiuixTheme.colorScheme.dividerLine)
}
