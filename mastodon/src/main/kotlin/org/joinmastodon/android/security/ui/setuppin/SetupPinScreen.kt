/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/setuppin/SetupPinScreen.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui.setuppin

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.joinmastodon.android.R
import org.joinmastodon.android.security.domain.PinDigits
import org.joinmastodon.android.security.ui.pin.PinScreen
import org.joinmastodon.android.ui.compose.component.BackNavigationIcon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
fun SetupPinScreen(
	viewModel: SetupPinViewModel,
	onBack: () -> Unit,
	onFinished: () -> Unit,
) {
	val uiState by viewModel.uiState.collectAsStateWithLifecycle()
	val context = LocalContext.current
	var showPinOptions by remember { mutableStateOf(false) }

	LaunchedEffect(viewModel) {
		viewModel.effects.collect { effect ->
			if (effect is SetupPinEffect.InvalidPin) context.vibrateInvalidPin()
		}
	}
	LaunchedEffect(uiState.stage) {
		if (uiState.stage == SetupPinStage.Finished) onFinished()
	}
	BackHandler {
		if (uiState.canCancel) {
			viewModel.cancel()
			onBack()
		}
	}
	Scaffold(
		topBar = {
			SmallTopAppBar(
				title = stringResource(R.string.security__create_pin),
				navigationIcon = {
						BackNavigationIcon(
							onClick = {
								if (uiState.canCancel) {
									viewModel.cancel()
									onBack()
								}
							},
							enabled = uiState.canCancel,
							contentDescription = stringResource(R.string.back),
						)
				},
			)
		},
	) { padding ->
		PinScreen(
			message = stringResource(
				uiState.messageRes,
				stringResource(uiState.digits.labelRes()),
			),
			errorMessage = uiState.errorMessageRes?.let { stringResource(it) }.orEmpty(),
			digits = uiState.digits.value,
			enteredCount = uiState.enteredCount,
			state = uiState.pinScreenState,
			isEnabled = uiState.inputEnabled,
			showBiometrics = false,
			onDigit = viewModel::digitEntered,
			onBackspace = viewModel::backspace,
			modifier = Modifier.padding(padding),
			footer = {
				if (uiState.showPinOptions) {
					TextButton(
						text = stringResource(R.string.settings__select_pin_length),
						onClick = { showPinOptions = true },
					)
				}
			},
		)
	}
	if (showPinOptions) {
		PinLengthDialog(
			selected = uiState.digits,
			onDismiss = { showPinOptions = false },
			onSelected = {
				viewModel.pinDigitsChanged(it)
				showPinOptions = false
			},
		)
	}
}

@Composable
private fun PinLengthDialog(
	selected: PinDigits,
	onDismiss: () -> Unit,
	onSelected: (PinDigits) -> Unit,
) {
	OverlayDialog(
		show = true,
		title = stringResource(R.string.settings__select_pin_length),
		onDismissRequest = onDismiss,
	) {
		Column {
			PinDigits.entries.forEach { digits ->
				Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
					TextButton(
						text = if (digits == selected) "✓ ${stringResource(digits.labelRes())}" else stringResource(digits.labelRes()),
						onClick = { onSelected(digits) },
						modifier = Modifier.fillMaxWidth(),
					)
				}
			}
		}
	}
}

internal fun PinDigits.labelRes(): Int = when (this) {
	PinDigits.Code4 -> R.string.settings__pin_4_digits
	PinDigits.Code6 -> R.string.settings__pin_6_digits
}

internal fun android.content.Context.vibrateInvalidPin() {
	val vibrator = getSystemService(Vibrator::class.java) ?: return
	val pattern = longArrayOf(0L, 200L, 20L, 30L)
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
		vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
	} else {
		@Suppress("DEPRECATION")
		vibrator.vibrate(pattern, -1)
	}
}
