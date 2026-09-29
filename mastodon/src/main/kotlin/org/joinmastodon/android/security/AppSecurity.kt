/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Composition root for the app lock, initialized only in the main process. Wires the process-wide
 * [AuthTracker] to foreground/background transitions (30 second grace period, upstream semantics)
 * and installs the [AppGatekeeper] over every first-party activity.
 */
object AppSecurity {
	private lateinit var trackerInstance: AuthTracker
	private lateinit var gatekeeper: AppGatekeeper

	@JvmStatic
	fun install(application: Application) {
		if (::gatekeeper.isInitialized) return
		val repository = org.joinmastodon.android.security.data.SecurityComponents.createRepository(application)
			trackerInstance = AuthTracker(repository)
			trackerInstance.onAppCreate()
			gatekeeper = AppGatekeeper(application, trackerInstance)
		gatekeeper.install()
		ProcessLifecycleOwner.get().lifecycle.addObserver(
			LifecycleEventObserver { source, event ->
				when (event) {
					Lifecycle.Event.ON_START -> trackerInstance.onMovingToForeground()
					Lifecycle.Event.ON_STOP -> trackerInstance.onMovingToBackground()
					else -> Unit
				}
			},
		)
	}

	@JvmStatic
	fun authTracker(): AuthTracker = trackerInstance

	fun authTrackerOrNull(): AuthTracker? = if (::trackerInstance.isInitialized) trackerInstance else null

	/**
	 * Whether a mandatory lock must be shown right now. Synchronous local-prefs read; only used
	 * from the splash cold-start path before any brand content is shown.
	 */
	@JvmStatic
	fun isLocked(): Boolean {
		if (!::gatekeeper.isInitialized) return false
		return kotlinx.coroutines.runBlocking {
				trackerInstance.shouldAuthenticate() == AuthenticationStatus.Expired
		}
	}
}
