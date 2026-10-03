/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/di/SecurityModule.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui

import android.content.Context
import org.joinmastodon.android.security.AuthTracker
import org.joinmastodon.android.security.data.AndroidKeystoreBiometricKeyProvider
import org.joinmastodon.android.security.data.BiometricKeyProvider
import org.joinmastodon.android.security.data.SecurityComponents
import org.joinmastodon.android.security.data.SecurityRepository

internal class SecurityGraph private constructor(
	val repository: SecurityRepository,
	val biometricKeyProvider: BiometricKeyProvider,
	val authTracker: AuthTracker,
) {
	companion object {
		fun create(context: Context): SecurityGraph {
			val repository = SecurityComponents.createRepository(context)
			return SecurityGraph(
				repository = repository,
				biometricKeyProvider = AndroidKeystoreBiometricKeyProvider(context.applicationContext.packageName),
				authTracker = AuthTracker(repository),
			)
		}
	}
}

/**
 * Bridges the gatekeeper (in the parent package) to the lock UI without widening the visibility of
 * SecurityGraph itself.
 */
object SecurityGraphFactory {
	internal fun isLockActivity(activity: android.app.Activity): Boolean = activity is org.joinmastodon.android.security.ui.lock.LockActivity

	internal fun lockActivityClass(): Class<*> = org.joinmastodon.android.security.ui.lock.LockActivity::class.java

	internal fun create(context: Context): SecurityGraph = SecurityGraph.create(context)

	internal fun createLockViewModel(context: Context): org.joinmastodon.android.security.ui.lock.LockViewModel {
		val graph = SecurityGraph.create(context)
		return org.joinmastodon.android.security.ui.lock.LockViewModel(graph.repository, graph.authTracker)
	}
}
