/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/disablepin/DisablePinViewModel.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui.disablepin

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
import org.joinmastodon.android.R
import org.joinmastodon.android.security.data.PinProtectedMutationResult
import org.joinmastodon.android.security.data.SecurityRepository
import org.joinmastodon.android.security.data.SecurityResult
import org.joinmastodon.android.security.ui.pin.PinScreenState
import org.joinmastodon.android.security.ui.pin.SensitivePinBuffer

class DisablePinViewModel(
	private val securityRepository: SecurityRepository,
) : ViewModel() {
	private val inputPin = SensitivePinBuffer()
	private var operationJob: Job? = null

	private val _uiState = MutableStateFlow(DisablePinUiState())
	val uiState: StateFlow<DisablePinUiState> = _uiState.asStateFlow()

	private val effectChannel = Channel<DisablePinEffect>(Channel.BUFFERED)
	val effects = effectChannel.receiveAsFlow()

	init {
		refresh()
	}

	fun beginFlow() {
		operationJob?.cancel()
		operationJob = null
		inputPin.clear()
		_uiState.value = DisablePinUiState()
		refresh()
	}

	fun refresh() {
		if (operationJob?.isActive == true) return
		operationJob = viewModelScope.launch {
			when (val result = safeGetState()) {
				is SecurityResult.Success -> {
					val status = result.value.invalidPinStatus
					_uiState.update {
						it.copy(
							digits = result.value.pinOptions.digits,
							loading = false,
							blockedMinutes = status.timeLeftMin,
							errorMessageRes = when {
								!result.value.isEnabled -> R.string.security_error_invalid_state
								status.shouldBlock -> R.string.security__too_many_attempts_try_again_after
								else -> null
							},
								pinScreenState = PinScreenState.Default,
							)
						}
					}
					else -> showError(result.stateErrorMessage())
			}
			operationJob = null
		}
	}

	fun refreshLockout() {
		viewModelScope.launch {
			when (val result = safeGetState()) {
				is SecurityResult.Success -> {
					val status = result.value.invalidPinStatus
					_uiState.update {
						it.copy(
							blockedMinutes = if (status.shouldBlock) status.timeLeftMin.coerceAtLeast(1) else 0,
							errorMessageRes = if (status.shouldBlock) it.errorMessageRes else null,
						)
					}
				}
				else -> Unit
			}
		}
	}

	fun digitEntered(digit: Int) {
		val state = _uiState.value
		if (!state.inputEnabled || state.pinScreenState != PinScreenState.Default || state.blockedMinutes > 0) return
		if (!inputPin.append(digit)) return
		_uiState.update { it.copy(enteredCount = inputPin.size, errorMessageRes = null) }
		if (inputPin.size == state.digits.value) disablePin()
	}

	fun backspace() {
		if (!_uiState.value.inputEnabled || _uiState.value.blockedMinutes > 0 || _uiState.value.pinScreenState != PinScreenState.Default) return
		if (inputPin.removeLast()) {
			_uiState.update { it.copy(enteredCount = inputPin.size, errorMessageRes = null) }
		}
	}

	fun cancel() {
		if (!_uiState.value.canCancel) return
		operationJob?.cancel()
		operationJob = null
		inputPin.clear()
	}

	private fun disablePin() {
		if (operationJob?.isActive == true || _uiState.value.persisting) return
		_uiState.update { it.copy(persisting = true, pinScreenState = PinScreenState.Verifying) }
		operationJob = viewModelScope.launch {
			val pin = inputPin.copy()
			val result = try {
				securityRepository.disablePin(pin)
			} catch (error: CancellationException) {
				throw error
			} catch (_: Exception) {
				PinProtectedMutationResult.StoreError
			} finally {
				pin.fill('\u0000')
			}
			when (result) {
				is PinProtectedMutationResult.Success -> finishOnce()
				is PinProtectedMutationResult.Wrong -> resetAfterError(
					message = R.string.security__pin_error_incorrect,
					blockedMinutes = 0,
					vibrate = true,
				)
				is PinProtectedMutationResult.Blocked -> resetAfterError(
					message = R.string.security__too_many_attempts_try_again_after,
					blockedMinutes = result.status.timeLeftMin.coerceAtLeast(1),
					vibrate = true,
				)
				PinProtectedMutationResult.InvalidInput -> resetAfterError(R.string.security_error_invalid_input)
				PinProtectedMutationResult.Corrupted -> resetAfterError(R.string.security_error_store_corrupted)
				PinProtectedMutationResult.StoreError -> resetAfterError(R.string.security_error_store_unavailable)
			}
			operationJob = null
		}
	}

	private suspend fun finishOnce() {
		inputPin.clear()
		_uiState.update { it.copy(enteredCount = 0, persisting = false, finished = true) }
	}

	private suspend fun resetAfterError(
		@StringRes message: Int,
		blockedMinutes: Int = 0,
		vibrate: Boolean = false,
	) {
		inputPin.clear()
		_uiState.update {
			it.copy(
				enteredCount = 0,
				persisting = false,
				blockedMinutes = blockedMinutes,
				errorMessageRes = message,
				pinScreenState = PinScreenState.Default,
			)
		}
		if (vibrate) effectChannel.send(DisablePinEffect.InvalidPin)
	}

	private fun showError(@StringRes message: Int) {
		inputPin.clear()
		_uiState.update {
			it.copy(
				enteredCount = 0,
				loading = false,
				persisting = false,
				errorMessageRes = message,
				pinScreenState = PinScreenState.Default,
			)
		}
	}

	private suspend fun safeGetState() = try {
		securityRepository.getSecurityState()
	} catch (error: CancellationException) {
		throw error
	} catch (_: Exception) {
		SecurityResult.StoreError
	}

	internal fun sensitiveBufferCountForDiagnostics(): Int = inputPin.nonZeroCountForDiagnostics()

	internal fun clearSensitiveForDiagnostics() {
		inputPin.clear()
	}

	override fun onCleared() {
		operationJob?.cancel()
		inputPin.clear()
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
