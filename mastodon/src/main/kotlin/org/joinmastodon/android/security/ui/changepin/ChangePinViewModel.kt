/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/changepin/ChangePinViewModel.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui.changepin

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
import org.joinmastodon.android.security.domain.PinDigits
import org.joinmastodon.android.security.domain.PinVerificationResult
import org.joinmastodon.android.security.ui.SecurityDelay
import org.joinmastodon.android.security.ui.pin.PinScreenState
import org.joinmastodon.android.security.ui.pin.SensitivePinBuffer

class ChangePinViewModel(
	private val securityRepository: SecurityRepository,
	private val transitionDelay: SecurityDelay = SecurityDelay.Default,
) : ViewModel() {
	private val currentPin = SensitivePinBuffer()
	private val firstNewPin = SensitivePinBuffer()
	private val inputPin = SensitivePinBuffer()
	private var operationJob: Job? = null

	private val _uiState = MutableStateFlow(ChangePinUiState())
	val uiState: StateFlow<ChangePinUiState> = _uiState.asStateFlow()

	private val effectChannel = Channel<ChangePinEffect>(Channel.BUFFERED)
	val effects = effectChannel.receiveAsFlow()

	init {
		refresh()
	}

	fun beginFlow() {
		operationJob?.cancel()
		operationJob = null
		clearSensitiveBuffers()
		_uiState.value = ChangePinUiState()
		refresh()
	}

	fun refresh() {
		if (operationJob?.isActive == true) return
		operationJob = viewModelScope.launch {
			when (val result = safeGetState()) {
				is SecurityResult.Success -> {
					val digits = result.value.pinOptions.digits
					if (!result.value.isEnabled) {
						showLoadError(R.string.security_error_invalid_state)
						} else if (result.value.invalidPinStatus.shouldBlock) {
							showLoadError(
								R.string.security__too_many_attempts_try_again_after,
								result.value.invalidPinStatus.timeLeftMin.coerceAtLeast(1),
								)
						} else {
						_uiState.value = ChangePinUiState(
							digits = digits,
							newDigits = digits,
							stage = ChangePinStage.EnterCurrent,
							pinScreenState = PinScreenState.Default,
						)
					}
				}
				else -> showLoadError(result.stateErrorMessage())
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
		if (!state.inputEnabled || state.pinScreenState != PinScreenState.Default) return
		val expectedDigits = when (state.stage) {
			ChangePinStage.EnterCurrent -> state.digits.value
			ChangePinStage.EnterNew, ChangePinStage.EnterConfirm -> state.newDigits.value
			else -> return
		}
		if (!inputPin.append(digit)) return
		_uiState.update { it.copy(enteredCount = inputPin.size, errorMessageRes = null) }
		if (inputPin.size == expectedDigits) {
			when (state.stage) {
				ChangePinStage.EnterCurrent -> acceptCurrentPin()
				ChangePinStage.EnterNew -> acceptFirstNewPin()
				ChangePinStage.EnterConfirm -> confirmNewPin()
				else -> Unit
			}
		}
	}

	fun backspace() {
		if (!_uiState.value.inputEnabled || _uiState.value.pinScreenState != PinScreenState.Default) return
		if (inputPin.removeLast()) {
			_uiState.update { it.copy(enteredCount = inputPin.size, errorMessageRes = null) }
		}
	}

	fun pinDigitsChanged(pinDigits: PinDigits) {
		if (_uiState.value.stage != ChangePinStage.EnterNew || operationJob?.isActive == true) return
		inputPin.clear()
		firstNewPin.clear()
		_uiState.update {
			it.copy(newDigits = pinDigits, enteredCount = 0, errorMessageRes = null)
		}
	}

	fun cancel() {
		if (!_uiState.value.canCancel) return
		operationJob?.cancel()
		operationJob = null
		clearSensitiveBuffers()
	}

	private fun acceptCurrentPin() {
		if (operationJob?.isActive == true || _uiState.value.stage != ChangePinStage.EnterCurrent) return
		_uiState.update {
			it.copy(stage = ChangePinStage.TransitionToNew, pinScreenState = PinScreenState.Verifying)
		}
		operationJob = viewModelScope.launch {
			val candidate = inputPin.copy()
			val verification = try {
				securityRepository.verifyPin(candidate.copyOf())
			} catch (error: CancellationException) {
				throw error
			} catch (_: Exception) {
				PinVerificationResult.StoreError(org.joinmastodon.android.security.domain.StoreErrorReason.Unavailable)
			} finally {
				candidate.fill('\u0000')
			}
			when (verification) {
				PinVerificationResult.Success -> {
					copyBuffer(inputPin, currentPin)
					transitionDelay.awaitTransition()
					inputPin.clear()
					_uiState.update {
						it.copy(
							enteredCount = 0,
							stage = ChangePinStage.EnterNew,
							showPinOptions = true,
							messageRes = R.string.security__enter_your_new_pin,
							messageIncludesDigits = true,
							errorMessageRes = null,
							pinScreenState = PinScreenState.Default,
						)
					}
				}
				is PinVerificationResult.Wrong -> resetAfterVerificationError(
					R.string.security__pin_error_incorrect,
				)
				is PinVerificationResult.Blocked -> resetAfterVerificationError(
					R.string.security__too_many_attempts_try_again_after,
					verification.status.timeLeftMin.coerceAtLeast(1),
				)
				PinVerificationResult.InvalidInput -> resetAfterVerificationError(R.string.security_error_invalid_input)
				is PinVerificationResult.StoreError -> resetAfterVerificationError(
					if (verification.reason == org.joinmastodon.android.security.domain.StoreErrorReason.Corrupted) {
						R.string.security_error_store_corrupted
					} else {
						R.string.security_error_store_unavailable
					},
				)
			}
			operationJob = null
		}
	}

	private fun acceptFirstNewPin() {
		if (operationJob?.isActive == true || _uiState.value.stage != ChangePinStage.EnterNew) return
		copyBuffer(inputPin, firstNewPin)
		_uiState.update {
			it.copy(
				stage = ChangePinStage.TransitionToConfirm,
				showPinOptions = false,
				pinScreenState = PinScreenState.Verifying,
			)
		}
		operationJob = viewModelScope.launch {
			try {
				transitionDelay.awaitTransition()
				inputPin.clear()
				_uiState.update {
					it.copy(
						enteredCount = 0,
						stage = ChangePinStage.EnterConfirm,
						messageRes = R.string.security__confirm_new_pin,
						messageIncludesDigits = false,
						errorMessageRes = null,
						pinScreenState = PinScreenState.Default,
					)
				}
			} finally {
				operationJob = null
			}
		}
	}

	private fun confirmNewPin() {
		if (operationJob?.isActive == true || _uiState.value.stage != ChangePinStage.EnterConfirm) return
		if (!inputPin.matches(firstNewPin)) {
			handleMismatch()
			return
		}
		persistChange()
	}

	private fun handleMismatch() {
		_uiState.update {
			it.copy(stage = ChangePinStage.TransitionToConfirm, pinScreenState = PinScreenState.Verifying)
		}
		operationJob = viewModelScope.launch {
			try {
				transitionDelay.awaitTransition()
				inputPin.clear()
				_uiState.update {
					it.copy(
						enteredCount = 0,
						stage = ChangePinStage.EnterConfirm,
						pinScreenState = PinScreenState.Default,
						errorMessageRes = R.string.security_error_no_match,
					)
				}
				effectChannel.send(ChangePinEffect.InvalidPin)
			} finally {
				operationJob = null
			}
		}
	}

	private fun persistChange() {
		_uiState.update {
			it.copy(
				stage = ChangePinStage.Persisting,
				pinScreenState = PinScreenState.Verifying,
				errorMessageRes = null,
			)
		}
		operationJob = viewModelScope.launch {
			val oldPin = currentPin.copy()
			val newPin = inputPin.copy()
			val result = try {
				securityRepository.changePin(oldPin, newPin, _uiState.value.newDigits)
			} catch (error: CancellationException) {
				throw error
			} catch (_: Exception) {
				PinProtectedMutationResult.StoreError
			} finally {
				oldPin.fill('\u0000')
				newPin.fill('\u0000')
			}
			when (result) {
				is PinProtectedMutationResult.Success -> finishOnce()
				is PinProtectedMutationResult.Wrong -> resetAfterCurrentPinError(R.string.security__pin_error_incorrect)
				is PinProtectedMutationResult.Blocked -> resetAfterCurrentPinError(
					R.string.security__too_many_attempts_try_again_after,
					result.status.timeLeftMin.coerceAtLeast(1),
				)
				PinProtectedMutationResult.InvalidInput -> resetAfterCurrentPinError(R.string.security_error_invalid_input)
				PinProtectedMutationResult.Corrupted -> resetAfterCurrentPinError(R.string.security_error_store_corrupted)
				PinProtectedMutationResult.StoreError -> resetAfterCurrentPinError(R.string.security_error_store_unavailable)
			}
			operationJob = null
		}
	}

	private suspend fun finishOnce() {
		clearSensitiveBuffers()
		_uiState.update { it.copy(stage = ChangePinStage.Finished, enteredCount = 0) }
	}

	private suspend fun resetAfterCurrentPinError(
		@StringRes message: Int,
		blockedMinutes: Int = 0,
	) {
		clearSensitiveBuffers()
		_uiState.update {
			it.copy(
				enteredCount = 0,
				stage = ChangePinStage.EnterCurrent,
				showPinOptions = false,
				blockedMinutes = blockedMinutes,
				messageRes = R.string.security__enter_current_pin,
				messageIncludesDigits = false,
				errorMessageRes = message,
				pinScreenState = PinScreenState.Default,
			)
		}
		effectChannel.send(ChangePinEffect.InvalidPin)
	}

	private suspend fun resetAfterVerificationError(
		@StringRes message: Int,
		blockedMinutes: Int = 0,
	) {
		clearSensitiveBuffers()
		_uiState.update {
			it.copy(
				enteredCount = 0,
				stage = ChangePinStage.EnterCurrent,
				showPinOptions = false,
				blockedMinutes = blockedMinutes,
				messageRes = R.string.security__enter_current_pin,
				messageIncludesDigits = false,
				errorMessageRes = message,
				pinScreenState = PinScreenState.Default,
			)
		}
		effectChannel.send(ChangePinEffect.InvalidPin)
	}

	private fun showLoadError(@StringRes message: Int, blockedMinutes: Int = 0) {
		_uiState.update {
			it.copy(
				stage = ChangePinStage.EnterCurrent,
				blockedMinutes = blockedMinutes,
				pinScreenState = PinScreenState.Default,
				errorMessageRes = message,
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

	private fun copyBuffer(source: SensitivePinBuffer, target: SensitivePinBuffer) {
		target.clear()
		val copy = source.copy()
		try {
			copy.forEach { target.append(it.digitToInt()) }
		} finally {
			copy.fill('\u0000')
		}
	}

	private fun clearSensitiveBuffers() {
		currentPin.clear()
		firstNewPin.clear()
		inputPin.clear()
	}

	internal fun sensitiveBufferCountForDiagnostics(): Int =
		currentPin.nonZeroCountForDiagnostics() +
			firstNewPin.nonZeroCountForDiagnostics() +
			inputPin.nonZeroCountForDiagnostics()

	internal fun clearSensitiveForDiagnostics() {
		clearSensitiveBuffers()
	}

	override fun onCleared() {
		operationJob?.cancel()
		clearSensitiveBuffers()
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
