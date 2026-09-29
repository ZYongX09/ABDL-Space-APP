/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Source: https://github.com/twofas/2fas-android/blob/119ead28ed8d3d2215afd8f55428c1586401149b/app/src/main/java/com/twofasapp/biometric/BiometricKeyProviderImpl.kt
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * AndroidKeyStore-backed biometric key. Unlike the upstream provider this implementation strictly
 * separates load and create paths: the lock flow may only load an existing key so that a missing
 * or invalidated key can never be silently recreated to bypass re-verification after enrollment
 * changes.
 */
class AndroidKeystoreBiometricKeyProvider(
	packageName: String,
) : BiometricKeyProvider {
	private val keyAlias = "${packageName}.security.biometric.v1"

	init {
		require(packageName.isNotBlank()) { "Package name is required" }
	}

	override fun loadSecretKey(): SecretKey? {
		return try {
			val keyStore = keyStore()
			if (!keyStore.containsAlias(keyAlias)) return null
			keyStore.getKey(keyAlias, null) as? SecretKey ?: throw BiometricKeyStoreCorruptedException()
		} catch (error: BiometricKeyStoreCorruptedException) {
			throw error
		} catch (_: KeyPermanentlyInvalidatedException) {
			throw BiometricKeyInvalidatedException()
		} catch (_: java.security.UnrecoverableKeyException) {
			throw BiometricKeyInvalidatedException()
		} catch (_: Exception) {
			throw BiometricKeyStoreUnavailableException()
		}
	}

	override fun createSecretKey(): SecretKey {
		return try {
			val keyStore = keyStore()
			check(!keyStore.containsAlias(keyAlias)) { "Biometric key already exists" }
			KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
				init(
					KeyGenParameterSpec.Builder(
						keyAlias,
						KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
					)
						.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
						.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
						.setRandomizedEncryptionRequired(true)
						.setUserAuthenticationRequired(true)
						.apply {
							if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
								setInvalidatedByBiometricEnrollment(true)
							}
						}
						.build(),
				)
				generateKey()
			}
		} catch (_: IllegalStateException) {
			throw BiometricKeyAlreadyExistsException()
		} catch (_: Exception) {
			throw BiometricKeyStoreUnavailableException()
		}
	}

	override fun deleteSecretKey() {
		try {
			keyStore().deleteEntry(keyAlias)
		} catch (_: Exception) {
			// Deleting an already absent key is idempotent; other failures are non-fatal.
		}
	}

	private fun keyStore(): KeyStore =
		KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

	companion object {
		private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
	}
}

class BiometricKeyInvalidatedException : Exception()
class BiometricKeyStoreCorruptedException : Exception()
class BiometricKeyStoreUnavailableException : Exception()
class BiometricKeyAlreadyExistsException : Exception()
