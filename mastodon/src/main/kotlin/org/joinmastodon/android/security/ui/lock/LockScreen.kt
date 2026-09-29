/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/lock/LockScreen.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui.lock

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.joinmastodon.android.R
import org.joinmastodon.android.security.data.BiometricKeyProvider
import org.joinmastodon.android.security.ui.biometric.BiometricDialog
import org.joinmastodon.android.security.ui.pin.PinScreen
import org.joinmastodon.android.security.ui.pin.PinScreenState
import org.joinmastodon.android.security.ui.pin.SensitivePinBuffer
import org.joinmastodon.android.security.ui.setuppin.vibrateInvalidPin
import org.joinmastodon.android.ui.compose.component.BackNavigationIcon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar

@Composable
internal fun LockScreen(
	viewModel: LockViewModel,
	biometricKeyProvider: BiometricKeyProvider,
	onSuccess: () -> Unit,
) {
	val uiState by viewModel.uiState.collectAsStateWithLifecycle()
	val context = LocalContext.current
	val lifecycleOwner = LocalLifecycleOwner.current
	val inputPin = remember { SensitivePinBuffer() }
	var showBiometricPrompt by remember { mutableStateOf(true) }

	LaunchedEffect(viewModel) {
		viewModel.effects.collect { effect ->
			when (effect) {
				LockEffect.NotifyInvalidPin -> context.vibrateInvalidPin()
				LockEffect.Finished -> {
					inputPin.clear()
					onSuccess()
				}
			}
		}
	}

	// Refresh the block countdown whenever the lock screen comes back to the foreground,
	// mirroring the upstream ON_RESUME refresh.
	DisposableEffect(lifecycleOwner) {
		val observer = LifecycleEventObserver { _, event ->
			if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
		}
		lifecycleOwner.lifecycle.addObserver(observer)
		onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
	}

	LaunchedEffect(uiState.invalidPinStatus.shouldBlock) {
		if (uiState.invalidPinStatus.shouldBlock) {
			inputPin.clear()
		}
	}

	if (showBiometricPrompt && uiState.lockMethod == org.joinmastodon.android.security.domain.LockMethod.Biometrics &&
		!uiState.invalidPinStatus.shouldBlock && uiState.pinScreenState == PinScreenState.Default) {
		BiometricDialog(
			title = stringResource(R.string.biometric_dialog_auth_title),
			subtitle = stringResource(R.string.biometric_dialog_auth_subtitle),
			negative = stringResource(R.string.biometric_dialog_auth_cancel),
			biometricKeyProvider = biometricKeyProvider,
			onSuccess = { showBiometricPrompt = false; viewModel.onBiometricsVerified() },
			onDismiss = { showBiometricPrompt = false },
			onInvalidated = { showBiometricPrompt = false; viewModel.onBiometricsInvalidated(biometricKeyProvider) },
		)
	}

	Scaffold(
		topBar = {
			SmallTopAppBar(
				title = stringResource(R.string.security__enter_pin),
				navigationIcon = { BackNavigationIcon(onClick = {}, enabled = false) },
			)
		},
	) { padding ->
		PinScreen(
			message = stringResource(R.string.security__enter_current_pin),
			errorMessage = when {
				uiState.invalidPinStatus.shouldBlock -> stringResource(
					R.string.security__too_many_attempts_try_again_after,
					uiState.invalidPinStatus.timeLeftMin.coerceAtLeast(1),
				)
				else -> uiState.errorMessageRes?.let { stringResource(it) }.orEmpty()
			},
			digits = uiState.digits.value,
			enteredCount = inputPin.size,
			state = uiState.pinScreenState,
			isEnabled = !uiState.invalidPinStatus.shouldBlock,
			showLogo = true,
			showBiometrics = uiState.lockMethod == org.joinmastodon.android.security.domain.LockMethod.Biometrics,
			onDigit = { digit ->
				if (inputPin.append(digit) && inputPin.size == uiState.digits.value) {
					viewModel.pinEntered(inputPin.copy())
					inputPin.clear()
				}
			},
			onBackspace = { inputPin.removeLast() },
			onBiometrics = {
				// The prompt is opened by the real BiometricDialog in the lock host; this callback only
				// exists for the keyboard slot and never grants access by itself.
			},
			modifier = Modifier.padding(padding),
		)
	}
}
