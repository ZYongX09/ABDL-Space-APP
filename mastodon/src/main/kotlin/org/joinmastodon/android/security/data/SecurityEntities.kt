/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Sources:
 * https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/prefs/src/main/java/com/twofasapp/prefs/model/PinOptionsEntity.kt
 * https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/prefs/src/main/java/com/twofasapp/prefs/model/LockMethodEntity.kt
 * https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/prefs/src/main/java/com/twofasapp/prefs/model/InvalidPinStatusEntity.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.data

data class PinOptionsEntity(
	val digits: Int,
	val trials: Int,
	val timeout: Long,
)

enum class LockMethodEntity {
	NO_LOCK,
	PIN_SECURED,
	FINGERPRINT_WITH_PIN_SECURED,
}

data class InvalidPinStatusEntity(
	val attempts: Int = 0,
	val lastAttemptSinceBootMs: Long = 0,
	val lockBootMarker: String? = null,
)

data class StoredSecurityData(
	val pinSecured: String,
	val lockStatus: LockMethodEntity,
	val pinOptions: PinOptionsEntity,
	val invalidPinStatus: InvalidPinStatusEntity,
) {
	/** Never include encrypted PIN material in diagnostics either. */
	override fun toString(): String = "StoredSecurityData(" +
		"pinSecured=<redacted>, " +
		"lockStatus=$lockStatus, " +
		"pinOptions=$pinOptions, " +
		"invalidPinStatus=$invalidPinStatus)"
}

internal fun StoredSecurityData.isStructurallyValid(): Boolean {
	val optionsValid = pinOptions.digits in setOf(4, 6) &&
		pinOptions.trials in setOf(3, 5, 10, -1) &&
		pinOptions.timeout in setOf(180_000L, 300_000L, 600_000L)
	val invalidStatusValid = when {
		invalidPinStatus.attempts < 0 -> false
		invalidPinStatus.lastAttemptSinceBootMs < 0L -> false
		invalidPinStatus.attempts == 0 ->
			invalidPinStatus.lastAttemptSinceBootMs == 0L &&
				invalidPinStatus.lockBootMarker == null
		else -> !invalidPinStatus.lockBootMarker.isNullOrBlank() &&
			invalidPinStatus.lockBootMarker.length <= MAX_BOOT_MARKER_LENGTH
	}
	val lockValid = when (lockStatus) {
		LockMethodEntity.NO_LOCK -> pinSecured.isEmpty()
		LockMethodEntity.PIN_SECURED,
		LockMethodEntity.FINGERPRINT_WITH_PIN_SECURED,
		-> pinSecured.isNotBlank() && pinSecured.length <= MAX_ENCRYPTED_PIN_LENGTH
	}
	return optionsValid && invalidStatusValid && lockValid
}

private const val MAX_BOOT_MARKER_LENGTH = 128
private const val MAX_ENCRYPTED_PIN_LENGTH = 512
