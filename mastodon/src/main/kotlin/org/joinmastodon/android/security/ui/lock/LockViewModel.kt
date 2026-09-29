/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/lock/LockViewModel.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui.lock

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.joinmastodon.android.R
import org.joinmastodon.android.security.AuthTracker
import org.joinmastodon.android.security.data.BiometricKeyProvider
import org.joinmastodon.android.security.domain.InvalidPinStatus
import org.joinmastodon.android.security.domain.LockMethod
import org.joinmastodon.android.security.domain.PinVerificationResult
import org.joinmastodon.android.security.data.SecurityRepository
import org.joinmastodon.android.security.data.SecurityResult
import org.joinmastodon.android.security.domain.StoreErrorReason
import org.joinmastodon.android.security.ui.SecurityDelay
import org.joinmastodon.android.security.ui.pin.PinScreenState

class LockViewModel(
	private val securityRepository: SecurityRepository,
	private val authTracker: AuthTracker,
	private val transitionDelay: SecurityDelay = SecurityDelay.Default,
) : ViewModel() {
	private val _uiState = MutableStateFlow(LockUiState())
	val uiState: StateFlow<LockUiState> = _uiState.asStateFlow()

	private val effectChannel = Channel<LockEffect>(Channel.BUFFERED)
	val effects = effectChannel.receiveAsFlow()

	private var verifying = false

	init {
		refresh()
	}

	fun refresh() {
		if (verifying) return
		viewModelScope.launch {
			when (val result = securityRepository.getSecurityState()) {
				is SecurityResult.Success -> {
					val state = result.value
					if (!state.isEnabled) {
						// The lock disappeared under us (PIN cleared elsewhere): treat as unlocked.
						authTracker.onAuthenticated()
						effectChannel.send(LockEffect.Finished)
						return@launch
					}
					_uiState.value = LockUiState(
						lockMethod = state.lockMethod,
						digits = state.pinOptions.digits,
						invalidPinStatus = state.invalidPinStatus,
						pinScreenState = PinScreenState.Default,
					)
				}
				else -> _uiState.update {
					it.copy(pinScreenState = PinScreenState.Default, errorMessageRes = result.lockErrorMessage())
				}
			}
		}
	}

	fun pinEntered(pin: CharArray) {
		if (verifying) return
		verifying = true
		_uiState.update { it.copy(pinScreenState = PinScreenState.Verifying) }
		viewModelScope.launch {
			try {
				when (val result = securityRepository.verifyPin(pin)) {
					PinVerificationResult.Success -> finishOnce()
					is PinVerificationResult.Wrong -> {
						transitionDelay.awaitTransition()
						_uiState.update {
							it.copy(
								pinScreenState = PinScreenState.Default,
								invalidPinStatus = result.status,
								errorMessageRes = R.string.security__pin_error_incorrect,
							)
						}
						effectChannel.send(LockEffect.NotifyInvalidPin)
					}
					is PinVerificationResult.Blocked -> {
						transitionDelay.awaitTransition()
						_uiState.update {
							it.copy(
								pinScreenState = PinScreenState.Default,
								invalidPinStatus = result.status,
								errorMessageRes = null,
							)
						}
						effectChannel.send(LockEffect.NotifyInvalidPin)
					}
					PinVerificationResult.InvalidInput -> resetAfterError(R.string.security_error_invalid_input)
					is PinVerificationResult.StoreError -> resetAfterError(result.reason.lockErrorMessage())
				}
			} catch (error: CancellationException) {
				throw error
			} finally {
				pin.fill('\u0000')
				verifying = false
			}
		}
	}

	/** Biometric unlock also clears the PIN failure counter, mirroring upstream behavior. */
	suspend fun biometricsVerified() {
		if (verifying) return
		verifying = true
		try {
			finishOnce()
		} finally {
			verifying = false
		}
	}

	fun onBiometricsVerified() {
		viewModelScope.launch { biometricsVerified() }
	}

	fun onBiometricsInvalidated(keyProvider: BiometricKeyProvider) {
		viewModelScope.launch { disableBiometric(keyProvider) }
	}

	/** Biometric enrollment changed: drop the invalidated key and fall back to the PIN. */
	suspend fun disableBiometric(keyProvider: BiometricKeyProvider) {
		keyProvider.deleteSecretKey()
		when (securityRepository.demoteToPinAfterBiometricInvalidation()) {
			is SecurityResult.Success -> _uiState.update {
				it.copy(
					lockMethod = LockMethod.Pin,
					errorMessageRes = R.string.fingerprint__biometric_invalidated,
				)
			}
			else -> effectChannel.send(LockEffect.NotifyInvalidPin)
		}
	}

	private suspend fun finishOnce() {
		authTracker.onAuthenticated()
		_uiState.update { it.copy(pinScreenState = PinScreenState.Default, errorMessageRes = null) }
		effectChannel.send(LockEffect.Finished)
	}

	private suspend fun resetAfterError(@StringRes message: Int) {
		transitionDelay.awaitTransition()
		_uiState.update {
			it.copy(
				pinScreenState = PinScreenState.Default,
				errorMessageRes = message,
			)
		}
	}

	override fun onCleared() {
		effectChannel.close()
		super.onCleared()
	}
}

@StringRes
private fun SecurityResult<*>.lockErrorMessage(): Int = when (this) {
	SecurityResult.InvalidInput -> R.string.security_error_invalid_input
	SecurityResult.InvalidState -> R.string.security_error_invalid_state
	SecurityResult.Corrupted -> R.string.security_error_store_corrupted
	SecurityResult.StoreError -> R.string.security_error_store_unavailable
	is SecurityResult.Success -> R.string.security_error_store_unavailable
}

@StringRes
private fun StoreErrorReason.lockErrorMessage(): Int = when (this) {
	StoreErrorReason.Corrupted -> R.string.security_error_store_corrupted
	StoreErrorReason.Unavailable -> R.string.security_error_store_unavailable
}

sealed interface LockEffect {
	data object NotifyInvalidPin : LockEffect
	data object Finished : LockEffect
}
