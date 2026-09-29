/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/changepin/ChangePinScreen.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui.changepin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.joinmastodon.android.R
import org.joinmastodon.android.security.domain.PinDigits
import org.joinmastodon.android.security.ui.pin.PinScreen
import org.joinmastodon.android.security.ui.setuppin.labelRes
import org.joinmastodon.android.security.ui.setuppin.vibrateInvalidPin
import org.joinmastodon.android.ui.compose.component.BackNavigationIcon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
fun ChangePinScreen(
	viewModel: ChangePinViewModel,
	onBack: () -> Unit,
	onFinished: () -> Unit,
) {
	val uiState by viewModel.uiState.collectAsStateWithLifecycle()
	val context = LocalContext.current
	var showPinOptions by remember { mutableStateOf(false) }
	LaunchedEffect(uiState.blockedMinutes) {
		if (uiState.blockedMinutes > 0) {
			delay(1000L)
			viewModel.refreshLockout()
		}
	}
	LaunchedEffect(viewModel) {
		viewModel.effects.collect { effect ->
			if (effect is ChangePinEffect.InvalidPin) context.vibrateInvalidPin()
		}
	}
	LaunchedEffect(uiState.stage) {
		if (uiState.stage == ChangePinStage.Finished) onFinished()
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
				title = stringResource(R.string.security__change_pin),
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
		val message = if (uiState.messageIncludesDigits) {
			stringResource(uiState.messageRes, stringResource(uiState.newDigits.labelRes()))
		} else {
			stringResource(uiState.messageRes)
		}
		PinScreen(
			message = message,
			errorMessage = uiState.errorMessageRes?.let {
				if (it == R.string.security__too_many_attempts_try_again_after) {
					stringResource(it, uiState.blockedMinutes.coerceAtLeast(1))
				} else {
					stringResource(it)
				}
			}.orEmpty(),
			digits = when (uiState.stage) {
				ChangePinStage.EnterCurrent, ChangePinStage.TransitionToNew, ChangePinStage.Loading -> uiState.digits.value
				else -> uiState.newDigits.value
			},
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
		ChangePinLengthDialog(
			selected = uiState.newDigits,
			onDismiss = { showPinOptions = false },
			onSelected = {
				viewModel.pinDigitsChanged(it)
				showPinOptions = false
			},
		)
	}
}

@Composable
private fun ChangePinLengthDialog(
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
				TextButton(
					text = if (digits == selected) "✓ ${stringResource(digits.labelRes())}" else stringResource(digits.labelRes()),
					onClick = { onSelected(digits) },
					modifier = Modifier.fillMaxWidth(),
				)
			}
		}
	}
}
