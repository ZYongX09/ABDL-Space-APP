/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui

internal class BiometricSession {
	private var generation = 0L
	private var active = false

	@Synchronized fun begin(): Long {
		generation++
		active = true
		return generation
	}

	@Synchronized fun isActive(request: Long): Boolean = active && generation == request

	@Synchronized fun complete(request: Long): Boolean {
		if (!isActive(request)) return false
		active = false
		return true
	}

	@Synchronized fun cancel() {
		active = false
		generation++
	}
}
