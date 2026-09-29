/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/ui/biometric/BiometricDialog.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui

/**
 * Host contract for the biometric implementation planned for the next security increment.
 * This increment deliberately has no platform prompt implementation and never writes a biometric lock method.
 */
interface BiometricUiController {
	fun requestEnable(onResult: (BiometricUiResult) -> Unit)
}

sealed interface BiometricUiResult {
	data object Enabled : BiometricUiResult
	data object Unavailable : BiometricUiResult
	data object Cancelled : BiometricUiResult
}

internal object UnavailableBiometricUiController : BiometricUiController {
	override fun requestEnable(onResult: (BiometricUiResult) -> Unit) {
		onResult(BiometricUiResult.Unavailable)
	}
}
