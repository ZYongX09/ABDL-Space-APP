/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/setuppin/SetupPinViewModel.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui.setuppin

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
import org.joinmastodon.android.security.data.SecurityRepository
import org.joinmastodon.android.security.data.SecurityResult
import org.joinmastodon.android.security.domain.PinDigits
import org.joinmastodon.android.security.ui.SecurityDelay
import org.joinmastodon.android.security.ui.pin.PinScreenState
import org.joinmastodon.android.security.ui.pin.SensitivePinBuffer

class SetupPinViewModel(
	private val securityRepository: SecurityRepository,
	private val transitionDelay: SecurityDelay = SecurityDelay.Default,
) : ViewModel() {
	private val firstPin = SensitivePinBuffer()
	private val currentPin = SensitivePinBuffer()
	private var operationJob: Job? = null
	private var loadJob: Job? = null

	private val _uiState = MutableStateFlow(SetupPinUiState())
	val uiState: StateFlow<SetupPinUiState> = _uiState.asStateFlow()

	private val effectChannel = Channel<SetupPinEffect>(Channel.BUFFERED)
	val effects = effectChannel.receiveAsFlow()

	init {
		loadDefaults()
	}

	fun beginFlow() {
		operationJob?.cancel()
		loadJob?.cancel()
		operationJob = null
		loadJob = null
		clearSensitiveBuffers()
		_uiState.value = SetupPinUiState()
		loadDefaults()
	}

	fun refresh() {
		if (_uiState.value.stage == SetupPinStage.EnterFirst && currentPin.size == 0) {
			loadDefaults()
		}
	}

	private fun loadDefaults() {
		if (loadJob?.isActive == true || operationJob?.isActive == true) return
		loadJob = viewModelScope.launch {
			when (val result = safeGetState()) {
				is SecurityResult.Success -> if (!result.value.isEnabled) {
					_uiState.update { it.copy(digits = result.value.pinOptions.digits, errorMessageRes = null) }
				} else {
					_uiState.update { it.copy(errorMessageRes = R.string.security_error_invalid_state) }
				}
				else -> _uiState.update { it.copy(errorMessageRes = result.setupErrorMessage()) }
			}
			loadJob = null
		}
	}

	fun digitEntered(digit: Int) {
		val state = _uiState.value
		if (!state.inputEnabled || state.pinScreenState != PinScreenState.Default) return
		if (!currentPin.append(digit)) return
		_uiState.update { it.copy(enteredCount = currentPin.size, errorMessageRes = null) }
		if (currentPin.size == state.digits.value) {
			when (state.stage) {
				SetupPinStage.EnterFirst -> transitionToConfirmation()
				SetupPinStage.EnterConfirm -> confirmPin()
				else -> Unit
			}
		}
	}

	fun backspace() {
		if (!_uiState.value.inputEnabled || _uiState.value.pinScreenState != PinScreenState.Default) return
		if (currentPin.removeLast()) {
			_uiState.update { it.copy(enteredCount = currentPin.size, errorMessageRes = null) }
		}
	}

	fun pinDigitsChanged(pinDigits: PinDigits) {
		if (_uiState.value.stage != SetupPinStage.EnterFirst || operationJob?.isActive == true) return
		clearSensitiveBuffers()
		_uiState.value = SetupPinUiState(digits = pinDigits)
	}

	fun cancel() {
		if (!_uiState.value.canCancel) return
		operationJob?.cancel()
		operationJob = null
		clearSensitiveBuffers()
	}

	private fun transitionToConfirmation() {
		if (operationJob?.isActive == true || _uiState.value.stage != SetupPinStage.EnterFirst) return
		copyCurrentIntoFirst()
		_uiState.update {
			it.copy(
				stage = SetupPinStage.TransitionToConfirm,
				showPinOptions = false,
				pinScreenState = PinScreenState.Verifying,
			)
		}
		operationJob = viewModelScope.launch {
			try {
				transitionDelay.awaitTransition()
				currentPin.clear()
				_uiState.update {
					it.copy(
						enteredCount = 0,
						stage = SetupPinStage.EnterConfirm,
						messageRes = R.string.security__confirm_new_pin,
						errorMessageRes = null,
						pinScreenState = PinScreenState.Default,
					)
				}
			} finally {
				operationJob = null
			}
		}
	}

	private fun confirmPin() {
		if (operationJob?.isActive == true || _uiState.value.stage != SetupPinStage.EnterConfirm) return
		if (currentPin.matches(firstPin)) {
			persistPin()
		} else {
			handleMismatch()
		}
	}

	private fun handleMismatch() {
		_uiState.update {
			it.copy(
				stage = SetupPinStage.TransitionToConfirm,
				pinScreenState = PinScreenState.Verifying,
			)
		}
		operationJob = viewModelScope.launch {
			try {
				transitionDelay.awaitTransition()
				currentPin.clear()
				_uiState.update {
					it.copy(
						enteredCount = 0,
						stage = SetupPinStage.EnterConfirm,
						pinScreenState = PinScreenState.Default,
						errorMessageRes = R.string.security_error_no_match,
					)
				}
				effectChannel.send(SetupPinEffect.InvalidPin)
			} finally {
				operationJob = null
			}
		}
	}

	private fun persistPin() {
		_uiState.update {
			it.copy(
				stage = SetupPinStage.Persisting,
				pinScreenState = PinScreenState.Verifying,
				errorMessageRes = null,
			)
		}
		operationJob = viewModelScope.launch {
			val pin = currentPin.copy()
			val result = try {
				securityRepository.setupPin(pin, _uiState.value.digits)
			} catch (error: CancellationException) {
				throw error
			} catch (_: Exception) {
				SecurityResult.StoreError
			} finally {
				pin.fill('\u0000')
			}
			when (result) {
				is SecurityResult.Success -> finishOnce()
				else -> showPersistenceError(result.setupErrorMessage())
			}
			operationJob = null
		}
	}

	private suspend fun finishOnce() {
		clearSensitiveBuffers()
		_uiState.update {
			it.copy(stage = SetupPinStage.Finished, enteredCount = 0)
		}
	}

	private fun showPersistenceError(@StringRes message: Int) {
		currentPin.clear()
		_uiState.update {
			it.copy(
				enteredCount = 0,
				stage = SetupPinStage.EnterConfirm,
				pinScreenState = PinScreenState.Default,
				errorMessageRes = message,
			)
		}
	}

	private fun copyCurrentIntoFirst() {
		firstPin.clear()
		val copy = currentPin.copy()
		try {
			copy.forEach { firstPin.append(it.digitToInt()) }
		} finally {
			copy.fill('\u0000')
		}
	}

	private suspend fun safeGetState() = try {
		securityRepository.getSecurityState()
	} catch (error: CancellationException) {
		throw error
	} catch (_: Exception) {
		SecurityResult.StoreError
	}

	private fun clearSensitiveBuffers() {
		firstPin.clear()
		currentPin.clear()
	}

	internal fun sensitiveBufferCountForDiagnostics(): Int =
		firstPin.nonZeroCountForDiagnostics() + currentPin.nonZeroCountForDiagnostics()

	internal fun clearSensitiveForDiagnostics() {
		clearSensitiveBuffers()
	}

	override fun onCleared() {
		operationJob?.cancel()
		loadJob?.cancel()
		clearSensitiveBuffers()
		effectChannel.close()
		super.onCleared()
	}
}

@StringRes
private fun SecurityResult<*>.setupErrorMessage(): Int = when (this) {
	SecurityResult.InvalidInput -> R.string.security_error_invalid_input
	SecurityResult.InvalidState -> R.string.security_error_invalid_state
	SecurityResult.Corrupted -> R.string.security_error_store_corrupted
	SecurityResult.StoreError -> R.string.security_error_store_unavailable
	is SecurityResult.Success -> R.string.security_error_store_unavailable
}
