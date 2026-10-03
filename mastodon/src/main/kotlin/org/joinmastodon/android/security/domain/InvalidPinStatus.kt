/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/data/session/src/main/java/com/twofasapp/data/session/domain/InvalidPinStatus.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.domain

import kotlin.math.ceil

data class InvalidPinStatus(
	val attempts: Int = 0,
	val lastAttemptSinceBootMs: Long = 0,
	val lockBootMarker: String? = null,
	val shouldBlock: Boolean = false,
	val timeLeftMs: Long = 0,
	val timeLeftMin: Int = 0,
) {
	companion object {
		val Default = InvalidPinStatus()

		fun blocked(
			attempts: Int,
			lastAttemptSinceBootMs: Long,
			lockBootMarker: String,
			timeLeftMs: Long,
		): InvalidPinStatus {
			val safeTimeLeft = timeLeftMs.coerceAtLeast(0L)
			return InvalidPinStatus(
				attempts = attempts,
				lastAttemptSinceBootMs = lastAttemptSinceBootMs,
				lockBootMarker = lockBootMarker,
				shouldBlock = safeTimeLeft > 0L,
				timeLeftMs = safeTimeLeft,
				timeLeftMin = ceil(safeTimeLeft / 60_000.0).toInt(),
			)
		}
	}
}
