/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/data/session/src/main/java/com/twofasapp/data/session/domain/PinTrials.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.domain

enum class PinTrials(
	val trials: Int,
	val label: String,
) {
	Trials3(3, "3"),
	Trials5(5, "5"),
	Trials10(10, "10"),
	NoLimit(-1, "No limit"),
}
