/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/security/SecurityViewModel.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui.security

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.joinmastodon.android.R
import org.joinmastodon.android.security.data.BiometricKeyProvider
import org.joinmastodon.android.security.data.BiometricKeyStoreUnavailableException
import org.joinmastodon.android.security.data.SecurityRepository
import org.joinmastodon.android.security.data.SecurityResult
import org.joinmastodon.android.security.domain.PinTimeout
import org.joinmastodon.android.security.domain.PinTrials
import org.joinmastodon.android.security.ui.BiometricUiController
import org.joinmastodon.android.security.ui.BiometricUiResult
import org.joinmastodon.android.security.ui.UnavailableBiometricUiController

class SecurityViewModel(
	private val securityRepository: SecurityRepository,
	private val biometricController: BiometricUiController = UnavailableBiometricUiController,
	private val biometricKeyProvider: BiometricKeyProvider? = null,
) : ViewModel() {
	private val _uiState = MutableStateFlow(SecurityUiState())
	val uiState: StateFlow<SecurityUiState> = _uiState.asStateFlow()

	private val effectChannel = Channel<SecurityEffect>(Channel.BUFFERED)
	val effects = effectChannel.receiveAsFlow()

	private var refreshJob: Job? = null
	private var policyJob: Job? = null
	private var biometricJob: Job? = null

	init {
		refresh()
	}

	fun refresh() {
		if (refreshJob?.isActive == true) return
		refreshJob = viewModelScope.launch {
			when (val result = safeGetState()) {
				is SecurityResult.Success -> applyState(result.value)
				else -> _uiState.update {
					it.copy(loading = false, errorMessageRes = result.stateErrorMessage())
				}
			}
			refreshJob = null
		}
	}

	fun updatePinTrials(pinTrials: PinTrials) {
		updatePolicy(pinTrials, _uiState.value.pinTimeout)
	}

	fun updatePinTimeout(pinTimeout: PinTimeout) {
		updatePolicy(_uiState.value.pinTrials, pinTimeout)
	}

	fun requestBiometricEnable() {
		if (!_uiState.value.hasPin || biometricJob?.isActive == true) return
		biometricJob = viewModelScope.launch {
			val result = suspendCancellableCoroutine { continuation ->
				biometricController.requestEnable { value ->
					if (continuation.isActive) continuation.resume(value) {}
				}
			}
			when (result) {
				BiometricUiResult.Enabled -> refresh()
				BiometricUiResult.Unavailable -> effectChannel.send(SecurityEffect.BiometricUnavailable)
				BiometricUiResult.Cancelled -> Unit
			}
			biometricJob = null
		}
	}

	/** Called by the host after the platform biometric prompt validated the crypto object. */
	fun onBiometricEnabled() {
		viewModelScope.launch {
			try {
				biometricKeyProvider?.createSecretKey()
			} catch (_: Exception) {
				effectChannel.send(SecurityEffect.BiometricUnavailable)
				return@launch
			}
			when (securityRepository.enableBiometrics()) {
				is SecurityResult.Success -> refresh()
				else -> effectChannel.send(SecurityEffect.BiometricUnavailable)
			}
		}
	}

	fun disableBiometric() {
		if (!_uiState.value.hasPin || biometricJob?.isActive == true) return
		biometricJob = viewModelScope.launch {
			// Never flip the toggle before the repository confirms the demotion.
			when (securityRepository.demoteToPinAfterBiometricInvalidation()) {
				is SecurityResult.Success -> refresh()
				else -> {
					_uiState.update { it.copy(policyUpdating = false) }
					effectChannel.send(SecurityEffect.BiometricUnavailable)
				}
			}
			biometricJob = null
		}
	}

	fun clearError() {
		_uiState.update { it.copy(errorMessageRes = null) }
	}

	private fun updatePolicy(pinTrials: PinTrials, pinTimeout: PinTimeout) {
		if (policyJob?.isActive == true || !_uiState.value.hasPin) return
		_uiState.update { it.copy(policyUpdating = true, errorMessageRes = null) }
		policyJob = viewModelScope.launch {
			val result = try {
				securityRepository.editLockoutPolicy(pinTrials, pinTimeout)
			} catch (error: CancellationException) {
				throw error
			} catch (_: Exception) {
				SecurityResult.StoreError
			}
			when (result) {
				is SecurityResult.Success -> applyState(result.value)
				else -> _uiState.update {
					it.copy(policyUpdating = false, errorMessageRes = result.stateErrorMessage())
				}
			}
			policyJob = null
		}
	}

	private fun applyState(state: org.joinmastodon.android.security.domain.SecurityState) {
		_uiState.value = SecurityUiState(
			loading = false,
			lockMethod = state.lockMethod,
			pinTrials = state.pinOptions.trials,
			pinTimeout = state.pinOptions.timeout,
			pinDigits = state.pinOptions.digits,
			policyUpdating = false,
		)
	}

	private suspend fun safeGetState() = try {
		securityRepository.getSecurityState()
	} catch (error: CancellationException) {
		throw error
	} catch (_: Exception) {
		SecurityResult.StoreError
	}

	override fun onCleared() {
		refreshJob?.cancel()
		policyJob?.cancel()
		biometricJob?.cancel()
		effectChannel.close()
		super.onCleared()
	}
}

@StringRes
private fun SecurityResult<*>.stateErrorMessage(): Int = when (this) {
	SecurityResult.InvalidInput -> R.string.security_error_invalid_input
	SecurityResult.InvalidState -> R.string.security_error_invalid_state
	SecurityResult.Corrupted -> R.string.security_error_store_corrupted
	SecurityResult.StoreError -> R.string.security_error_store_unavailable
	is SecurityResult.Success -> R.string.security_error_store_unavailable
}
