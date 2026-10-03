/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.domain

sealed interface PinVerificationResult {
	data object Success : PinVerificationResult
	data class Wrong(val status: InvalidPinStatus) : PinVerificationResult
	data class Blocked(val status: InvalidPinStatus) : PinVerificationResult
	data object InvalidInput : PinVerificationResult
	data class StoreError(val reason: StoreErrorReason) : PinVerificationResult
}

enum class StoreErrorReason {
	Corrupted,
	Unavailable,
}
