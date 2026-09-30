/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/lock/LockScreen.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui.lock

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.joinmastodon.android.R
import org.joinmastodon.android.security.data.BiometricKeyProvider
import org.joinmastodon.android.security.domain.LockMethod
import org.joinmastodon.android.security.ui.biometric.BiometricDialog
import org.joinmastodon.android.security.ui.pin.PinScreen
import org.joinmastodon.android.security.ui.pin.PinScreenState
import org.joinmastodon.android.security.ui.setuppin.vibrateInvalidPin
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar

@Composable
internal fun LockScreen(
	viewModel: LockViewModel,
	biometricKeyProvider: BiometricKeyProvider,
	onSuccess: () -> Unit,
) {
	val state by viewModel.uiState.collectAsStateWithLifecycle()
	val context = LocalContext.current
	val lifecycle = LocalLifecycleOwner.current.lifecycle
	var showBiometrics by remember { mutableStateOf(true) }
	var requestId by remember { mutableIntStateOf(0) }
	LaunchedEffect(viewModel) {
		viewModel.effects.collect { context.vibrateInvalidPin() }
	}
	LaunchedEffect(state.finished) {
		if (state.finished) onSuccess()
	}
	LaunchedEffect(lifecycle, state.invalidPinStatus.shouldBlock) {
		lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
			viewModel.refresh()
			if (state.invalidPinStatus.shouldBlock) {
				while (true) { delay(1_000L); viewModel.refresh() }
			}
		}
	}
	if (showBiometrics && state.ready && !state.finished && state.lockMethod == LockMethod.Biometrics &&
		!state.invalidPinStatus.shouldBlock && state.pinScreenState == PinScreenState.Default) {
		BiometricDialog(
			title = stringResource(R.string.biometric_dialog_auth_title),
			subtitle = stringResource(R.string.biometric_dialog_auth_subtitle),
			negative = stringResource(R.string.biometric_dialog_auth_cancel),
			biometricKeyProvider = biometricKeyProvider,
			requestId = requestId,
			onSuccess = { showBiometrics = false; viewModel.onBiometricsVerified() },
			onDismiss = { showBiometrics = false },
			onInvalidated = { showBiometrics = false; viewModel.onBiometricsInvalidated(biometricKeyProvider) },
		)
	}
	Scaffold(topBar = { SmallTopAppBar(title = stringResource(R.string.security__enter_pin)) }) { padding ->
		PinScreen(
			message = stringResource(R.string.security__enter_pin),
			errorMessage = when {
				state.invalidPinStatus.shouldBlock -> stringResource(R.string.security__too_many_attempts_try_again_after,
					state.invalidPinStatus.timeLeftMin.coerceAtLeast(1))
				else -> state.errorMessageRes?.let { stringResource(it) }.orEmpty()
			},
			digits = state.digits.value,
			enteredCount = state.enteredCount,
			state = state.pinScreenState,
			isEnabled = state.ready && !state.finished && !state.invalidPinStatus.shouldBlock,
			showLogo = true,
			showBiometrics = state.lockMethod == LockMethod.Biometrics,
			onDigit = viewModel::digitEntered,
			onBackspace = viewModel::backspace,
			onBiometrics = { if (!showBiometrics) { requestId++; showBiometrics = true } },
			modifier = Modifier.padding(padding),
		)
	}
}
