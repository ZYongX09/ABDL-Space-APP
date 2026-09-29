/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/data/session/src/main/java/com/twofasapp/data/session/mapper/SecurityMapper.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.data

import org.joinmastodon.android.security.domain.InvalidPinStatus
import org.joinmastodon.android.security.domain.LockMethod
import org.joinmastodon.android.security.domain.PinDigits
import org.joinmastodon.android.security.domain.PinOptions
import org.joinmastodon.android.security.domain.PinTimeout
import org.joinmastodon.android.security.domain.PinTrials

internal fun PinOptions.asEntity() = PinOptionsEntity(
	digits = digits.value,
	trials = trials.trials,
	timeout = timeout.timeoutMs,
)

internal fun PinOptionsEntity.asDomain() = PinOptions(
	digits = PinDigits.entries.first { it.value == digits },
	trials = PinTrials.entries.first { it.trials == trials },
	timeout = PinTimeout.entries.first { it.timeoutMs == timeout },
)

internal fun LockMethod.asEntity(): LockMethodEntity = when (this) {
	LockMethod.NoLock -> LockMethodEntity.NO_LOCK
	LockMethod.Pin -> LockMethodEntity.PIN_SECURED
	LockMethod.Biometrics -> LockMethodEntity.FINGERPRINT_WITH_PIN_SECURED
}

internal fun LockMethodEntity.asDomain(): LockMethod = when (this) {
	LockMethodEntity.NO_LOCK -> LockMethod.NoLock
	LockMethodEntity.PIN_SECURED -> LockMethod.Pin
	LockMethodEntity.FINGERPRINT_WITH_PIN_SECURED -> LockMethod.Biometrics
}

internal fun InvalidPinStatus.asEntity() = InvalidPinStatusEntity(
	attempts = attempts,
	lastAttemptSinceBootMs = lastAttemptSinceBootMs,
	lockBootMarker = lockBootMarker,
)
