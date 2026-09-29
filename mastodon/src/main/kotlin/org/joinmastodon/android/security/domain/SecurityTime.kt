/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.domain

import android.os.SystemClock

fun interface ElapsedRealtimeProvider {
	fun elapsedRealtimeMs(): Long
}

fun interface BootSessionMarkerProvider {
	fun marker(): String
}

object AndroidElapsedRealtimeProvider : ElapsedRealtimeProvider {
	override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
}
