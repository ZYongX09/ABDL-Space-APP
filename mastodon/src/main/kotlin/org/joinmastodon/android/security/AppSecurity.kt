/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security

import android.app.Activity
import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import org.joinmastodon.android.security.data.SecurityComponents

object AppSecurity {
	private lateinit var trackerInstance: AuthTracker
	private lateinit var gatekeeper: AppGatekeeper

	@JvmStatic
	fun install(application: Application) {
		if (::gatekeeper.isInitialized) return
		trackerInstance = AuthTracker(SecurityComponents.createRepository(application))
		trackerInstance.onAppCreate()
		gatekeeper = AppGatekeeper(application, trackerInstance)
		gatekeeper.install()
		ProcessLifecycleOwner.get().lifecycle.addObserver(
			LifecycleEventObserver { _, event ->
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

	@JvmStatic
	fun runAfterUnlock(activity: Activity, continuation: Runnable) {
		if (!::gatekeeper.isInitialized) install(activity.application)
		gatekeeper.runAfterUnlock(activity, continuation)
	}

	@JvmStatic
	fun onLockFinished(success: Boolean) {
		if (!::gatekeeper.isInitialized) return
		if (success) gatekeeper.onUnlocked() else gatekeeper.onLockFinishedWithoutSuccess()
	}

	@JvmStatic
	fun isLocked(): Boolean = !::trackerInstance.isInitialized || !trackerInstance.isAccessKnownValid()
}
