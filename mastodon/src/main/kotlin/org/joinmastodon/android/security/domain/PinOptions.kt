/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/data/session/src/main/java/com/twofasapp/data/session/domain/PinOptions.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.domain

data class PinOptions(
	val digits: PinDigits,
	val trials: PinTrials,
	val timeout: PinTimeout,
) {
	companion object {
		val Default = PinOptions(
			digits = PinDigits.Code4,
			trials = PinTrials.Trials3,
			timeout = PinTimeout.Timeout5,
		)
	}
}
