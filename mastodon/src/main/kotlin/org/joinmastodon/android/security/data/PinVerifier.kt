/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.data

import kotlinx.coroutines.CancellationException
import org.joinmastodon.android.security.domain.PinCipher
import org.joinmastodon.android.security.domain.PinCipherException
import org.joinmastodon.android.security.domain.StoreErrorReason

/** Single comparison path shared by unlock, change-PIN, and disable-PIN flows. */
class PinVerifier(
	private val pinCipher: PinCipher,
) {
	fun verify(pin: CharArray, encryptedPin: String): PinCheckResult {
		val storedPin = try {
			pinCipher.decrypt(encryptedPin)
		} catch (_: PinCipherException.Corrupted) {
			return PinCheckResult.StoreError(StoreErrorReason.Corrupted)
		} catch (_: PinCipherException.Unavailable) {
			return PinCheckResult.StoreError(StoreErrorReason.Unavailable)
		} catch (error: CancellationException) {
			throw error
		} catch (_: Exception) {
			return PinCheckResult.StoreError(StoreErrorReason.Unavailable)
		}

		return try {
			if (constantTimeEquals(pin, storedPin)) PinCheckResult.Match else PinCheckResult.Mismatch
		} finally {
			storedPin.fill('\u0000')
		}
	}

	private fun constantTimeEquals(left: CharArray, right: CharArray): Boolean {
		var difference = left.size xor right.size
		val length = maxOf(left.size, right.size)
		for (index in 0 until length) {
			val leftValue = if (index < left.size) left[index].code else 0
			val rightValue = if (index < right.size) right[index].code else 0
			difference = difference or (leftValue xor rightValue)
		}
		return difference == 0
	}
}

sealed interface PinCheckResult {
	data object Match : PinCheckResult
	data object Mismatch : PinCheckResult
	data class StoreError(val reason: StoreErrorReason) : PinCheckResult
}
