/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/data/session/src/main/java/com/twofasapp/data/session/SecurityRepository.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.data

import org.joinmastodon.android.security.domain.InvalidPinStatus
import org.joinmastodon.android.security.domain.LockMethod
import org.joinmastodon.android.security.domain.PinDigits
import org.joinmastodon.android.security.domain.PinTimeout
import org.joinmastodon.android.security.domain.PinTrials
import org.joinmastodon.android.security.domain.PinVerificationResult
import org.joinmastodon.android.security.domain.SecurityState

interface SecurityRepository {
	suspend fun getSecurityState(): SecurityResult<SecurityState>

	suspend fun verifyPin(pin: CharArray): PinVerificationResult

	suspend fun setupPin(
		pin: CharArray,
		digits: PinDigits,
		lockMethod: LockMethod = LockMethod.Pin,
	): SecurityResult<SecurityState>

	suspend fun changePin(
		currentPin: CharArray,
		newPin: CharArray,
		digits: PinDigits,
		lockMethod: LockMethod? = null,
	): PinProtectedMutationResult

	suspend fun disablePin(currentPin: CharArray): PinProtectedMutationResult

	/** Changes only lockout policy. PIN digits can change only with setupPin/changePin. */
	suspend fun editLockoutPolicy(
		trials: PinTrials,
		timeout: PinTimeout,
	): SecurityResult<SecurityState>
}

sealed interface SecurityResult<out T> {
	data class Success<T>(val value: T) : SecurityResult<T>
	data object InvalidState : SecurityResult<Nothing>
	data object InvalidInput : SecurityResult<Nothing>
	data object Corrupted : SecurityResult<Nothing>
	data object StoreError : SecurityResult<Nothing>
}

sealed interface PinProtectedMutationResult {
	data class Success(val state: SecurityState) : PinProtectedMutationResult
	data class Wrong(val status: InvalidPinStatus) : PinProtectedMutationResult
	data class Blocked(val status: InvalidPinStatus) : PinProtectedMutationResult
	data object Corrupted : PinProtectedMutationResult
	data object StoreError : PinProtectedMutationResult
	data object InvalidInput : PinProtectedMutationResult
}
