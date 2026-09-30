/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/base/src/main/java/com/twofasapp/base/AuthTracker.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security

import org.joinmastodon.android.security.data.SecurityRepository
import org.joinmastodon.android.security.data.SecurityResult
import org.joinmastodon.android.security.domain.LockMethod

enum class AuthenticationStatus { Valid, Expired }

class AuthTracker(
	private val repository: SecurityRepository,
	private val clock: ElapsedRealtimeClock = SystemElapsedRealtimeClock,
) {
	private var backgroundTime: Long? = null
	private var authenticated = false
	private var lockMethod: LockMethod? = null
	private var generation = 0L

	@Synchronized
	fun onAppCreate() {
		lockMethod = null
		reset()
	}

	@Synchronized
	fun onSplashScreen() = reset()

	@Synchronized
	fun onAuthenticateScreen() = reset()

	@Synchronized
	fun onChangingLockStatus() {
		lockMethod = null
		backgroundTime = null
		authenticated = true
		generation++
	}

	@Synchronized
	fun onMovingToBackground() {
		backgroundTime = if (authenticated) clock.now() else null
		authenticated = false
		generation++
	}

	@Synchronized
	fun onMovingToForeground() {
		val elapsed = backgroundTime?.let { clock.now() - it }
		if (elapsed != null && elapsed in 0..VALIDITY_TIME_MS) authenticated = true
		backgroundTime = null
		generation++
	}

	@Synchronized
	fun onAuthenticated() {
		authenticated = true
		backgroundTime = null
		generation++
	}

	@Synchronized
	fun sessionGeneration(): Long = generation

	@Synchronized
	fun isAccessKnownValid(): Boolean = lockMethod == LockMethod.NoLock || authenticated

	@Synchronized
	fun isProtectionEnabled(): Boolean = lockMethod != LockMethod.NoLock

	suspend fun shouldAuthenticate(): AuthenticationStatus {
		val state = repository.getSecurityState()
		return synchronized(this) {
			when (state) {
				is SecurityResult.Success -> {
					lockMethod = state.value.lockMethod
					if (lockMethod == LockMethod.NoLock || authenticated) AuthenticationStatus.Valid
					else AuthenticationStatus.Expired
				}
				else -> {
					lockMethod = null
					AuthenticationStatus.Expired
				}
			}
		}
	}

	private fun reset() {
		backgroundTime = null
		authenticated = false
		generation++
	}

	companion object { const val VALIDITY_TIME_MS = 30_000L }
}

fun interface ElapsedRealtimeClock { fun now(): Long }

object SystemElapsedRealtimeClock : ElapsedRealtimeClock {
	override fun now(): Long = android.os.SystemClock.elapsedRealtime()
}
