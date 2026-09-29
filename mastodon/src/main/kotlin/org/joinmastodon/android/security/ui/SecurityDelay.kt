/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/setuppin/SetupPinViewModel.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui

import kotlinx.coroutines.delay

fun interface SecurityDelay {
	suspend fun awaitTransition()

	companion object {
		val Default = SecurityDelay { delay(200L) }
	}
}
