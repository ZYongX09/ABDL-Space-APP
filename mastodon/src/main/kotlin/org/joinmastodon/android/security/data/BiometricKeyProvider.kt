/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/feature/security/src/main/java/com/twofasapp/feature/security/biometric/BiometricKeyProvider.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.data

import javax.crypto.SecretKey

/**
 * Contract for the AndroidKeyStore key that gates biometric unlock. The key is only ever created
 * while a PIN already protects the app and is invalidated whenever biometric enrollment changes.
 */
interface BiometricKeyProvider {
	companion object {
		const val TRANSFORMATION = "AES/GCM/NoPadding"
	}

	/** Loads the existing key. Never creates one; a missing key yields null. */
	fun loadSecretKey(): SecretKey?

	/** Creates a new user-authentication-bound key. Fails if a valid key already exists. */
	fun createSecretKey(): SecretKey

	fun deleteSecretKey()
}
