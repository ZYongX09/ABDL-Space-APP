/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/security/SecurityViewModel.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.joinmastodon.android.R
import org.joinmastodon.android.security.data.BiometricKeyProvider
import org.joinmastodon.android.security.data.SecurityRepository
import org.joinmastodon.android.security.data.SecurityResult
import org.joinmastodon.android.security.domain.LockMethod
import org.joinmastodon.android.security.domain.PinTimeout
import org.joinmastodon.android.security.domain.PinTrials
import org.joinmastodon.android.security.domain.SecurityState

class SecurityViewModel(
	private val securityRepository: SecurityRepository,
	private val biometricKeyProvider: BiometricKeyProvider? = null,
	private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
	private val state = MutableStateFlow(SecurityUiState())
	val uiState = state.asStateFlow()
	private val effectChannel = Channel<SecurityEffect>(Channel.BUFFERED)
	val effects = effectChannel.receiveAsFlow()
	private var refreshJob: Job? = null
	private var mutationJob: Job? = null
	private var generation = 0L

	init { refresh() }

	fun refresh() {
		if (mutationJob?.isActive == true) return
		refreshJob?.cancel()
		val request = ++generation
		refreshJob = viewModelScope.launch {
			val result = safeRead()
			if (request != generation) return@launch
			when (result) {
				is SecurityResult.Success -> applyState(result.value)
				else -> state.update { it.copy(loading = false, errorMessageRes = result.errorMessage()) }
			}
		}
	}

	fun updatePinTrials(trials: PinTrials) = updatePolicy(trials, state.value.pinTimeout)
	fun updatePinTimeout(timeout: PinTimeout) = updatePolicy(state.value.pinTrials, timeout)

	/** The setup CryptoObject has succeeded; only persist the already-authenticated key. */
	fun onBiometricEnabled() {
		if (!state.value.hasPin || mutationJob?.isActive == true) return
		beginMutation()
		mutationJob = viewModelScope.launch {
			val result = try {
				withContext(ioDispatcher) {
					if (biometricKeyProvider?.loadSecretKey() == null) SecurityResult.StoreError
					else securityRepository.enableBiometrics()
				}
			} catch (error: CancellationException) {
				throw error
			} catch (_: Exception) {
				SecurityResult.StoreError
			}
			if (result !is SecurityResult.Success) {
				withContext(ioDispatcher) { runCatching { biometricKeyProvider?.deleteSecretKey() } }
			}
			finishMutation(result)
		}
	}

	fun disableBiometric() {
		if (state.value.lockMethod != LockMethod.Biometrics || mutationJob?.isActive == true) return
		beginMutation()
		mutationJob = viewModelScope.launch {
			val result = try {
				withContext(ioDispatcher) {
					securityRepository.demoteToPinAfterBiometricInvalidation().also {
						if (it is SecurityResult.Success) runCatching { biometricKeyProvider?.deleteSecretKey() }
					}
				}
			} catch (error: CancellationException) {
				throw error
			} catch (_: Exception) {
				SecurityResult.StoreError
			}
			finishMutation(result)
		}
	}

	fun clearError() = state.update { it.copy(errorMessageRes = null) }

	private fun beginMutation() {
		generation++
		refreshJob?.cancel()
		state.update { it.copy(policyUpdating = true, errorMessageRes = null) }
	}

	private fun updatePolicy(trials: PinTrials, timeout: PinTimeout) {
		if (!state.value.hasPin || mutationJob?.isActive == true) return
		beginMutation()
		mutationJob = viewModelScope.launch {
			val result = try {
				withContext(ioDispatcher) { securityRepository.editLockoutPolicy(trials, timeout) }
			} catch (error: CancellationException) {
				throw error
			} catch (_: Exception) {
				SecurityResult.StoreError
			}
			finishMutation(result)
		}
	}

	private fun finishMutation(result: SecurityResult<SecurityState>) {
		when (result) {
			is SecurityResult.Success -> applyState(result.value)
			else -> state.update { it.copy(policyUpdating = false, errorMessageRes = result.errorMessage()) }
		}
	}

	private fun applyState(value: SecurityState) {
		state.value = SecurityUiState(loading = false, lockMethod = value.lockMethod,
			pinTrials = value.pinOptions.trials, pinTimeout = value.pinOptions.timeout, pinDigits = value.pinOptions.digits)
	}

	private suspend fun safeRead(): SecurityResult<SecurityState> = try {
		withContext(ioDispatcher) { securityRepository.getSecurityState() }
	} catch (error: CancellationException) {
		throw error
	} catch (_: Exception) {
		SecurityResult.StoreError
	}

	override fun onCleared() {
		effectChannel.close()
		super.onCleared()
	}
}

private fun SecurityResult<*>.errorMessage(): Int = when (this) {
	SecurityResult.InvalidInput -> R.string.security_error_invalid_input
	SecurityResult.InvalidState -> R.string.security_error_invalid_state
	SecurityResult.Corrupted -> R.string.security_error_store_corrupted
	else -> R.string.security_error_store_unavailable
}
