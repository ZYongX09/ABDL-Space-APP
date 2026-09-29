/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/di/SecurityModule.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui

import android.content.Context
import org.joinmastodon.android.security.data.AndroidKeystoreBiometricKeyProvider
import org.joinmastodon.android.security.data.BiometricKeyProvider
import org.joinmastodon.android.security.data.SecurityComponents
import org.joinmastodon.android.security.data.SecurityRepository

internal class SecurityGraph private constructor(
	val repository: SecurityRepository,
	val biometricKeyProvider: BiometricKeyProvider,
) {
	companion object {
		fun create(context: Context): SecurityGraph = SecurityGraph(
			repository = SecurityComponents.createRepository(context),
			biometricKeyProvider = AndroidKeystoreBiometricKeyProvider(context.applicationContext.packageName),
		)
	}
}
