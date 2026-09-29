/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/base/src/main/java/com/twofasapp/base/AuthTracker.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security

import org.joinmastodon.android.security.domain.LockMethod
import org.joinmastodon.android.security.data.SecurityRepository

enum class AuthenticationStatus {
	Valid,
	Expired,
}

/**
 * Process-wide authentication session. Mirrors the upstream 30 second background grace period but
 * uses [SystemClock.elapsedRealtime] instead of the wall clock so changing the system time cannot
 * extend a session. The lock method is re-read from the repository on every check; the in-memory
 * flag only survives within this process.
 */
class AuthTracker(
	private val repository: SecurityRepository,
	private val clock: ElapsedRealtimeClock = SystemElapsedRealtimeClock,
) {
	companion object {
		const val VALIDITY_TIME_MS = 30 * 1000L
	}

	internal var lastBackgroundElapsedMs: Long = Long.MIN_VALUE
	internal var lastForegroundElapsedMs: Long = 0L
	internal var isAuthenticated: Boolean = false
		private set

	fun onAppCreate() {
		reset()
	}

	fun onSplashScreen() {
		reset()
	}

	fun onAuthenticateScreen() {
		reset()
	}

	/** Keeps the session authenticated while the user reconfigures the lock in settings. */
	fun onChangingLockStatus() {
		isAuthenticated = true
	}

	fun onMovingToBackground() {
		if (isAuthenticated) {
			lastBackgroundElapsedMs = clock.now()
			isAuthenticated = false
		}
	}

	fun onMovingToForeground() {
		lastForegroundElapsedMs = clock.now()
		if (isNotValidityTimeElapsed()) {
			isAuthenticated = true
		}
	}

	fun onAuthenticated() {
		isAuthenticated = true
	}

	suspend fun shouldAuthenticate(): AuthenticationStatus = when {
		isNoLock() -> AuthenticationStatus.Valid
		isSessionStillAuthenticated() -> AuthenticationStatus.Valid
		isValidityTimeElapsed() -> AuthenticationStatus.Expired
		else -> AuthenticationStatus.Valid
	}

	private fun isSessionStillAuthenticated() = isAuthenticated

	private suspend fun isNoLock(): Boolean = when (val state = repository.getSecurityState()) {
		is org.joinmastodon.android.security.data.SecurityResult.Success ->
			state.value.lockMethod == LockMethod.NoLock
		else -> false
	}

	private fun isValidityTimeElapsed() =
		lastForegroundElapsedMs - VALIDITY_TIME_MS > lastBackgroundElapsedMs

	private fun isNotValidityTimeElapsed() = !isValidityTimeElapsed()

	private fun reset() {
		lastBackgroundElapsedMs = Long.MIN_VALUE
		lastForegroundElapsedMs = clock.now()
		isAuthenticated = false
	}
}

fun interface ElapsedRealtimeClock {
	fun now(): Long
}

object SystemElapsedRealtimeClock : ElapsedRealtimeClock {
	override fun now(): Long = android.os.SystemClock.elapsedRealtime()
}
