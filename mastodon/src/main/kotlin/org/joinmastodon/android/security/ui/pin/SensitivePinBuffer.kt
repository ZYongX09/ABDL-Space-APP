/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/pin/PinScreen.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui.pin

internal class SensitivePinBuffer(
	private val capacity: Int = 6,
) {
	private val value = CharArray(capacity)
	var size: Int = 0
		private set

	fun append(digit: Int): Boolean {
		if (digit !in 0..9 || size >= capacity) return false
		value[size++] = ('0'.code + digit).toChar()
		return true
	}

	fun removeLast(): Boolean {
		if (size == 0) return false
		size -= 1
		value[size] = '\u0000'
		return true
	}

	fun copy(): CharArray = value.copyOf(size)

	fun matches(other: SensitivePinBuffer): Boolean {
		if (size != other.size) return false
		var mismatch = 0
		for (index in 0 until size) {
			mismatch = mismatch or (value[index].code xor other.value[index].code)
		}
		return mismatch == 0
	}

	fun clear() {
		value.fill('\u0000')
		size = 0
	}

	internal fun nonZeroCountForDiagnostics(): Int = value.count { it != '\u0000' }
}
