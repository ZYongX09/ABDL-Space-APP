/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/lock/LockViewModel.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.joinmastodon.android.R
import org.joinmastodon.android.security.AuthTracker
import org.joinmastodon.android.security.data.BiometricKeyProvider
import org.joinmastodon.android.security.data.SecurityRepository
import org.joinmastodon.android.security.data.SecurityResult
import org.joinmastodon.android.security.domain.LockMethod
import org.joinmastodon.android.security.domain.PinVerificationResult
import org.joinmastodon.android.security.domain.StoreErrorReason
import org.joinmastodon.android.security.ui.SecurityDelay
import org.joinmastodon.android.security.ui.pin.PinScreenState
import org.joinmastodon.android.security.ui.pin.SensitivePinBuffer

class LockViewModel(
	private val securityRepository: SecurityRepository,
	private val authTracker: AuthTracker,
	private val transitionDelay: SecurityDelay = SecurityDelay.Default,
	private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
	private val input = SensitivePinBuffer()
	private val state = MutableStateFlow(LockUiState())
	val uiState = state.asStateFlow()
	private val effectChannel = Channel<LockEffect>(Channel.BUFFERED)
	val effects = effectChannel.receiveAsFlow()
	private var verifying = false
	private var refreshing = false

	init { refresh() }

	fun refresh() {
		if (verifying || refreshing || state.value.finished) return
		refreshing = true
		viewModelScope.launch {
			try {
				when (val result = withContext(ioDispatcher) { securityRepository.getSecurityState() }) {
					is SecurityResult.Success -> {
						val value = result.value
						if (!value.isEnabled) finishOnce()
						else state.update {
							it.copy(lockMethod = value.lockMethod, digits = value.pinOptions.digits,
								invalidPinStatus = value.invalidPinStatus, pinScreenState = PinScreenState.Default,
								ready = true)
						}
					}
					else -> showStoreError(result)
				}
			} catch (error: CancellationException) {
				throw error
			} catch (_: Exception) {
				showStoreError(SecurityResult.StoreError)
			} finally { refreshing = false }
		}
	}

	fun digitEntered(digit: Int) {
		if (!canEnter() || input.size >= state.value.digits.value || !input.append(digit)) return
		state.update { it.copy(enteredCount = input.size, errorMessageRes = null) }
		if (input.size == state.value.digits.value) pinEntered(input.copy())
	}

	fun backspace() {
		if (!canEnter()) return
		if (input.removeLast()) state.update { it.copy(enteredCount = input.size) }
	}

	private fun canEnter() = state.value.ready && !verifying && !state.value.finished &&
		!state.value.invalidPinStatus.shouldBlock && state.value.pinScreenState == PinScreenState.Default

	fun pinEntered(pin: CharArray) {
		if (!canEnter()) { pin.fill('\u0000'); return }
		verifying = true
		state.update { it.copy(pinScreenState = PinScreenState.Verifying) }
		viewModelScope.launch {
			try {
				val result = withContext(ioDispatcher) { securityRepository.verifyPin(pin) }
				input.clear()
				when (result) {
					PinVerificationResult.Success -> finishOnce()
					is PinVerificationResult.Wrong -> {
						transitionDelay.awaitTransition()
						state.update { it.copy(enteredCount = 0, pinScreenState = PinScreenState.Default,
							invalidPinStatus = result.status, errorMessageRes = R.string.security__pin_error_incorrect) }
						effectChannel.send(LockEffect.NotifyInvalidPin)
					}
					is PinVerificationResult.Blocked -> {
						transitionDelay.awaitTransition()
						state.update { it.copy(enteredCount = 0, pinScreenState = PinScreenState.Default,
							invalidPinStatus = result.status, errorMessageRes = null) }
						effectChannel.send(LockEffect.NotifyInvalidPin)
					}
					PinVerificationResult.InvalidInput -> showStoreError(SecurityResult.InvalidInput)
					is PinVerificationResult.StoreError -> showStoreError(
						if (result.reason == StoreErrorReason.Corrupted) SecurityResult.Corrupted else SecurityResult.StoreError)
				}
			} catch (error: CancellationException) {
				throw error
			} catch (_: Exception) {
				showStoreError(SecurityResult.StoreError)
			} finally { pin.fill('\u0000'); verifying = false }
		}
	}

	fun onBiometricsVerified() {
		if (verifying || state.value.finished || !state.value.ready) return
		verifying = true
		viewModelScope.launch {
			try {
				when (val result = withContext(ioDispatcher) { securityRepository.completeBiometricUnlock() }) {
					is SecurityResult.Success -> finishOnce()
					else -> showStoreError(result)
				}
			} finally { verifying = false }
		}
	}

	fun onBiometricsInvalidated(keyProvider: BiometricKeyProvider) {
		viewModelScope.launch {
			val result = withContext(ioDispatcher) {
				keyProvider.deleteSecretKey()
				securityRepository.demoteToPinAfterBiometricInvalidation()
			}
			if (result is SecurityResult.Success) state.update {
				it.copy(lockMethod = LockMethod.Pin, errorMessageRes = R.string.fingerprint__biometric_invalidated)
			}
			else showStoreError(result)
		}
	}

	private fun finishOnce() {
		if (state.value.finished) return
		input.clear()
		authTracker.onAuthenticated()
		state.update { it.copy(finished = true, enteredCount = 0, errorMessageRes = null) }
	}

	private fun showStoreError(result: SecurityResult<*>) {
		input.clear()
		state.update { it.copy(enteredCount = 0, ready = false, pinScreenState = PinScreenState.Default,
			errorMessageRes = if (result == SecurityResult.Corrupted) R.string.security_error_store_corrupted else R.string.security_error_store_unavailable) }
	}

	override fun onCleared() {
		input.clear()
		effectChannel.close()
		super.onCleared()
	}
}

sealed interface LockEffect { data object NotifyInvalidPin : LockEffect }
