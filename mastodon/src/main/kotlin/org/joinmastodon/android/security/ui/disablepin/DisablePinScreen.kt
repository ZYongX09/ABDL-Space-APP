/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/disablepin/DisablePinScreen.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui.disablepin

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.joinmastodon.android.R
import org.joinmastodon.android.security.ui.pin.PinScreen
import org.joinmastodon.android.security.ui.setuppin.vibrateInvalidPin
import org.joinmastodon.android.ui.compose.component.BackNavigationIcon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar

@Composable
fun DisablePinScreen(
	viewModel: DisablePinViewModel,
	onBack: () -> Unit,
	onFinished: () -> Unit,
) {
	val uiState by viewModel.uiState.collectAsStateWithLifecycle()
	val context = LocalContext.current
	LaunchedEffect(uiState.blockedMinutes) {
		if (uiState.blockedMinutes > 0) {
			delay(1000L)
			viewModel.refreshLockout()
		}
	}
	LaunchedEffect(viewModel) {
		viewModel.effects.collect { effect ->
			if (effect is DisablePinEffect.InvalidPin) context.vibrateInvalidPin()
		}
	}
	LaunchedEffect(uiState.finished) {
		if (uiState.finished) onFinished()
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
				title = stringResource(R.string.security__disable_pin),
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
			message = stringResource(R.string.security__enter_current_pin),
			errorMessage = uiState.errorMessageRes?.let {
				if (it == R.string.security__too_many_attempts_try_again_after) {
					stringResource(it, uiState.blockedMinutes.coerceAtLeast(1))
				} else {
					stringResource(it)
				}
			}.orEmpty(),
			digits = uiState.digits.value,
			enteredCount = uiState.enteredCount,
			state = uiState.pinScreenState,
			isEnabled = uiState.inputEnabled && uiState.blockedMinutes == 0,
			showBiometrics = false,
			onDigit = viewModel::digitEntered,
			onBackspace = viewModel::backspace,
			modifier = Modifier.padding(padding),
		)
	}
}
