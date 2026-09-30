/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Tests authored for ABDL Space; behavior is checked against the adapted 2FAS 5.6.0 flow.
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui

import kotlinx.coroutines.CompletableDeferred
import org.joinmastodon.android.security.data.PinProtectedMutationResult
import org.joinmastodon.android.security.data.SecurityRepository
import org.joinmastodon.android.security.data.SecurityResult
import org.joinmastodon.android.security.domain.InvalidPinStatus
import org.joinmastodon.android.security.domain.LockMethod
import org.joinmastodon.android.security.domain.PinDigits
import org.joinmastodon.android.security.domain.PinOptions
import org.joinmastodon.android.security.domain.PinTimeout
import org.joinmastodon.android.security.domain.PinTrials
import org.joinmastodon.android.security.domain.PinVerificationResult
import org.joinmastodon.android.security.domain.SecurityState

internal fun securityState(
	lockMethod: LockMethod = LockMethod.NoLock,
	digits: PinDigits = PinDigits.Code4,
	trials: PinTrials = PinTrials.Trials3,
	timeout: PinTimeout = PinTimeout.Timeout5,
	invalidPinStatus: InvalidPinStatus = InvalidPinStatus.Default,
) = SecurityState(
	lockMethod = lockMethod,
	pinOptions = PinOptions(digits, trials, timeout),
	invalidPinStatus = invalidPinStatus,
)

internal class FakeSecurityRepository(
	var stateResult: SecurityResult<SecurityState> = SecurityResult.Success(securityState()),
) : SecurityRepository {
	var verifyResult: PinVerificationResult = PinVerificationResult.Success
	var setupResult: SecurityResult<SecurityState> = SecurityResult.Success(securityState(LockMethod.Pin))
	var enableResult: SecurityResult<SecurityState> = SecurityResult.Success(securityState(LockMethod.Biometrics))
	var changeResult: PinProtectedMutationResult = PinProtectedMutationResult.Success(securityState(LockMethod.Pin))
	var disableResult: PinProtectedMutationResult = PinProtectedMutationResult.Success(securityState())
	var demoteResult: SecurityResult<SecurityState> =
		SecurityResult.Success(securityState(LockMethod.Pin))
	var policyResult: SecurityResult<SecurityState>? = null

	var setupCalls = 0
	var enableCalls = 0
	var changeCalls = 0
	var disableCalls = 0
	var demoteCalls = 0
	var policyCalls = 0
	var verifyCalls = 0

	var setupPinCopy: CharArray? = null
	var changeCurrentPinCopy: CharArray? = null
	var changeNewPinCopy: CharArray? = null
	var disablePinCopy: CharArray? = null
	var setupDigits: PinDigits? = null
	var changeDigits: PinDigits? = null
	var policyTrials: PinTrials? = null
	var policyTimeout: PinTimeout? = null

	override suspend fun getSecurityState(): SecurityResult<SecurityState> = stateResult

	override suspend fun verifyPin(pin: CharArray): PinVerificationResult {
		verifyCalls += 1
		pin.fill('\u0000')
		return verifyResult
	}

	override suspend fun completeBiometricUnlock(): SecurityResult<SecurityState> = stateResult

	override suspend fun enableBiometrics(): SecurityResult<SecurityState> {
		enableCalls += 1
		if (enableResult is SecurityResult.Success) stateResult = enableResult
		return enableResult
	}

	override suspend fun setupPin(
		pin: CharArray,
		digits: PinDigits,
		lockMethod: LockMethod,
	): SecurityResult<SecurityState> {
		setupCalls += 1
		setupPinCopy = pin.copyOf()
		setupDigits = digits
		pin.fill('\u0000')
		return setupResult
	}

	override suspend fun changePin(
		currentPin: CharArray,
		newPin: CharArray,
		digits: PinDigits,
		lockMethod: LockMethod?,
	): PinProtectedMutationResult {
		changeCalls += 1
		changeCurrentPinCopy = currentPin.copyOf()
		changeNewPinCopy = newPin.copyOf()
		changeDigits = digits
		currentPin.fill('\u0000')
		newPin.fill('\u0000')
		return changeResult
	}

	override suspend fun disablePin(currentPin: CharArray): PinProtectedMutationResult {
		disableCalls += 1
		disablePinCopy = currentPin.copyOf()
		currentPin.fill('\u0000')
		return disableResult
	}

	override suspend fun demoteToPinAfterBiometricInvalidation(): SecurityResult<SecurityState> {
		demoteCalls += 1
		return demoteResult.also { result ->
			if (result is SecurityResult.Success) stateResult = result
		}
	}

	override suspend fun editLockoutPolicy(
		trials: PinTrials,
		timeout: PinTimeout,
	): SecurityResult<SecurityState> {
		policyCalls += 1
		policyTrials = trials
		policyTimeout = timeout
		return policyResult ?: SecurityResult.Success(
			securityState(LockMethod.Pin, trials = trials, timeout = timeout),
		)
	}
}

internal class GateSecurityDelay : SecurityDelay {
	private var gate = CompletableDeferred<Unit>()
	var calls: Int = 0
		private set

	override suspend fun awaitTransition() {
		calls += 1
		gate.await()
	}

	fun release() {
		gate.complete(Unit)
		gate = CompletableDeferred()
	}
}

internal val ImmediateSecurityDelay = SecurityDelay { }

internal fun pinDigits(value: String): List<Int> = value.map { it.digitToInt() }
