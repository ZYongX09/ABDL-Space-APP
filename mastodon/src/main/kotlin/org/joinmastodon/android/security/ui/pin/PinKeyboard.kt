/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/pin/PinKeyboard.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui.pin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.joinmastodon.android.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

private sealed interface PinKey {
	data class Digit(val value: Int) : PinKey
	data object Empty : PinKey
	data object Biometrics : PinKey
	data object Backspace : PinKey
}

private val fixedKeys = listOf(
	PinKey.Digit(1), PinKey.Digit(2), PinKey.Digit(3),
	PinKey.Digit(4), PinKey.Digit(5), PinKey.Digit(6),
	PinKey.Digit(7), PinKey.Digit(8), PinKey.Digit(9),
	PinKey.Biometrics, PinKey.Digit(0), PinKey.Backspace,
)

@Composable
internal fun PinKeyboard(
	isEnabled: Boolean,
	showBiometrics: Boolean,
	onKeyClick: (Int) -> Unit,
	onBiometricsClick: () -> Unit,
	onBackspace: () -> Unit,
	canBackspace: Boolean,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		fixedKeys.chunked(3).forEach { row ->
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceEvenly,
				verticalAlignment = Alignment.CenterVertically,
			) {
				row.forEach { key ->
					PinKeyboardKey(
						key = if (key == PinKey.Biometrics && !showBiometrics) PinKey.Empty else key,
						enabled = isEnabled,
						onDigit = onKeyClick,
						onBiometrics = onBiometricsClick,
						onBackspace = onBackspace,
						canBackspace = canBackspace,
						modifier = Modifier.weight(1f),
					)
				}
			}
		}
	}
}

@Composable
private fun PinKeyboardKey(
	key: PinKey,
	enabled: Boolean,
	onDigit: (Int) -> Unit,
	onBiometrics: () -> Unit,
	onBackspace: () -> Unit,
	canBackspace: Boolean,
	modifier: Modifier,
) {
	val palette = pinPalette()
	val backspaceDescription = stringResource(R.string.security_a11y_backspace)
	val digitDescription = if (key is PinKey.Digit) {
		stringResource(R.string.security_a11y_digit_key, key.value)
	} else {
		""
	}
	val biometricDescription = stringResource(R.string.security_a11y_biometric)
	val clickableModifier = when (key) {
		is PinKey.Digit -> Modifier
			.semantics {
				contentDescription = digitDescription
				role = Role.Button
			}
			.clickable(enabled = enabled) { onDigit(key.value) }
		PinKey.Biometrics -> Modifier
			.semantics {
				contentDescription = biometricDescription
				role = Role.Button
			}
			.clickable(enabled = enabled, onClick = onBiometrics)
		PinKey.Backspace -> Modifier.semantics { contentDescription = backspaceDescription; role = Role.Button }
			.clickable(enabled = enabled && canBackspace, onClick = onBackspace)
		PinKey.Empty -> Modifier
	}
	Box(
		modifier = modifier
			.padding(horizontal = 8.dp, vertical = 2.dp)
			.defaultMinSize(minWidth = 48.dp, minHeight = 72.dp)
			.clip(CircleShape)
			.then(clickableModifier),
		contentAlignment = Alignment.Center,
	) {
		when (key) {
			is PinKey.Digit -> Text(
				text = key.value.toString(),
				fontSize = 29.sp,
				fontWeight = FontWeight.Normal,
				color = palette.foreground,
				modifier = Modifier.alpha(if (enabled) 1f else 0.45f),
			)
			PinKey.Biometrics -> Icon(
				painter = painterResource(R.drawable.ic_security_fingerprint),
				contentDescription = null,
				tint = palette.accent,
				modifier = Modifier
					.size(64.dp)
					.background(palette.tile, CircleShape).padding(18.dp)
					.alpha(if (enabled) 1f else 0.45f),
			)
			PinKey.Backspace -> Icon(
				painter = painterResource(R.drawable.ic_security_backspace),
				contentDescription = null, tint = palette.secondary,
				modifier = Modifier.size(24.dp).alpha(if (enabled && canBackspace) 1f else 0.4f),
			)
			PinKey.Empty -> Unit
		}
	}
}
