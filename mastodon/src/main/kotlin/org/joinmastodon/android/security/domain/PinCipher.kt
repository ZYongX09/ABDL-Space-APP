/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.domain

interface PinCipher {
	@Throws(PinCipherException::class)
	fun encrypt(pin: CharArray): String

	@Throws(PinCipherException::class)
	fun decrypt(encryptedPin: String): CharArray
}

/** Fixed-message failures prevent provider details or sensitive input from reaching logs/UI. */
sealed class PinCipherException private constructor(message: String) : Exception(message) {
	class Corrupted : PinCipherException("Stored PIN data is corrupted")
	class Unavailable : PinCipherException("PIN encryption is unavailable")
}
