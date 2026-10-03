/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.joinmastodon.android.security.domain.LockMethod
import org.joinmastodon.android.security.domain.PinDigits
import org.joinmastodon.android.security.domain.PinTimeout
import org.joinmastodon.android.security.domain.PinTrials

internal class IoSecurityRepository(
	private val delegate: SecurityRepository,
	private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SecurityRepository {
	override suspend fun getSecurityState() = withContext(dispatcher) { delegate.getSecurityState() }
	override suspend fun verifyPin(pin: CharArray) = try {
		withContext(dispatcher) { delegate.verifyPin(pin) }
	} finally { pin.fill('\u0000') }
	override suspend fun setupPin(pin: CharArray, digits: PinDigits, lockMethod: LockMethod) = try {
		withContext(dispatcher) { delegate.setupPin(pin, digits, lockMethod) }
	} finally { pin.fill('\u0000') }
	override suspend fun changePin(currentPin: CharArray, newPin: CharArray, digits: PinDigits, lockMethod: LockMethod?) = try {
		withContext(dispatcher) { delegate.changePin(currentPin, newPin, digits, lockMethod) }
	} finally { currentPin.fill('\u0000'); newPin.fill('\u0000') }
	override suspend fun disablePin(currentPin: CharArray) = try {
		withContext(dispatcher) { delegate.disablePin(currentPin) }
	} finally { currentPin.fill('\u0000') }
	override suspend fun enableBiometrics() = withContext(dispatcher) { delegate.enableBiometrics() }
	override suspend fun completeBiometricUnlock() = withContext(dispatcher) { delegate.completeBiometricUnlock() }
	override suspend fun demoteToPinAfterBiometricInvalidation() = withContext(dispatcher) { delegate.demoteToPinAfterBiometricInvalidation() }
	override suspend fun editLockoutPolicy(trials: PinTrials, timeout: PinTimeout) = withContext(dispatcher) { delegate.editLockoutPolicy(trials, timeout) }
}
