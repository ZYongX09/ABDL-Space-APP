/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.joinmastodon.android.security.domain.PinCipher
import org.joinmastodon.android.security.domain.PinCipherException

/**
 * Encrypts PINs with a non-exportable AndroidKeyStore AES key and a fresh GCM IV per write.
 *
 * The serialized value is versioned and contains only the IV plus authenticated ciphertext.
 * A missing key for existing ciphertext and every authentication/format failure are corruption,
 * never a signal to regenerate a key or disable the lock.
 */
class AndroidKeystorePinCipher(
	packageName: String,
) : PinCipher {
	private val keyAlias = "${packageName}.security.pin.v1"

	init {
		require(packageName.isNotBlank()) { "Package name is required" }
	}
	override fun encrypt(pin: CharArray): String {
		val plaintext = encodeUtf8(pin)
		return try {
			val cipher = Cipher.getInstance(TRANSFORMATION)
			cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
			val iv = cipher.iv ?: throw PinCipherException.Unavailable()
			if (iv.size != IV_LENGTH_BYTES) throw PinCipherException.Unavailable()
			val ciphertext = cipher.doFinal(plaintext)
			serialize(iv, ciphertext)
		} catch (error: PinCipherException) {
			throw error
		} catch (_: GeneralSecurityException) {
			throw PinCipherException.Unavailable()
		} catch (_: ProviderException) {
			throw PinCipherException.Unavailable()
		} catch (_: IllegalArgumentException) {
			throw PinCipherException.Unavailable()
		} finally {
			plaintext.fill(0)
		}
	}

	override fun decrypt(encryptedPin: String): CharArray {
		val payload = parse(encryptedPin)
		val plaintext = try {
			val cipher = Cipher.getInstance(TRANSFORMATION)
			cipher.init(
				Cipher.DECRYPT_MODE,
				getExistingKey() ?: throw PinCipherException.Corrupted(),
				GCMParameterSpec(TAG_LENGTH_BITS, payload.iv),
			)
			cipher.doFinal(payload.ciphertext)
		} catch (error: PinCipherException) {
			throw error
		} catch (_: AEADBadTagException) {
			throw PinCipherException.Corrupted()
		} catch (_: KeyPermanentlyInvalidatedException) {
			throw PinCipherException.Corrupted()
		} catch (_: GeneralSecurityException) {
			throw PinCipherException.Corrupted()
		} catch (_: ProviderException) {
			throw PinCipherException.Unavailable()
		} catch (_: IllegalArgumentException) {
			throw PinCipherException.Corrupted()
		}

		return try {
			decodeUtf8(plaintext)
		} catch (_: CharacterCodingException) {
			throw PinCipherException.Corrupted()
		} finally {
			plaintext.fill(0)
		}
	}

	private fun getOrCreateKey(): SecretKey = ProcessWideKeyCreator(
		lock = PROCESS_KEY_CREATION_LOCK,
		findExisting = ::getExistingKey,
		create = ::generateKey,
	).getOrCreate()

	private fun generateKey(): SecretKey {
		return try {
			KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
				init(
					KeyGenParameterSpec.Builder(
						keyAlias,
						KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
					)
						.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
						.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
						.setRandomizedEncryptionRequired(true)
						.setKeySize(KEY_SIZE_BITS)
						.build(),
				)
				generateKey()
			}
		} catch (_: GeneralSecurityException) {
			throw PinCipherException.Unavailable()
		} catch (_: ProviderException) {
			throw PinCipherException.Unavailable()
		}
	}

	private fun getExistingKey(): SecretKey? {
		return try {
			val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
			if (!keyStore.containsAlias(keyAlias)) return null
			keyStore.getKey(keyAlias, null) as? SecretKey ?: throw PinCipherException.Corrupted()
		} catch (error: PinCipherException) {
			throw error
		} catch (_: GeneralSecurityException) {
			throw PinCipherException.Unavailable()
		} catch (_: ProviderException) {
			throw PinCipherException.Unavailable()
		} catch (_: java.io.IOException) {
			throw PinCipherException.Unavailable()
		}
	}

	private fun serialize(iv: ByteArray, ciphertext: ByteArray): String {
		val ivEncoded = Base64.encodeToString(iv, Base64.NO_WRAP)
		val ciphertextEncoded = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
		return "$FORMAT_VERSION:$ivEncoded:$ciphertextEncoded"
	}

	private fun parse(serialized: String): EncryptedPayload {
		if (serialized.length !in MIN_SERIALIZED_LENGTH..MAX_SERIALIZED_LENGTH) {
			throw PinCipherException.Corrupted()
		}
		val parts = serialized.split(':', limit = 3)
		if (parts.size != 3 || parts[0] != FORMAT_VERSION) throw PinCipherException.Corrupted()
		val iv = decodeBase64(parts[1])
		val ciphertext = decodeBase64(parts[2])
		if (iv.size != IV_LENGTH_BYTES || ciphertext.size < TAG_LENGTH_BYTES) {
			throw PinCipherException.Corrupted()
		}
		return EncryptedPayload(iv, ciphertext)
	}

	private fun decodeBase64(value: String): ByteArray {
		if (value.isEmpty()) throw PinCipherException.Corrupted()
		return try {
			Base64.decode(value, Base64.NO_WRAP)
		} catch (_: IllegalArgumentException) {
			throw PinCipherException.Corrupted()
		}
	}

	private fun encodeUtf8(value: CharArray): ByteArray {
		val encoded = StandardCharsets.UTF_8.newEncoder()
			.onMalformedInput(CodingErrorAction.REPORT)
			.onUnmappableCharacter(CodingErrorAction.REPORT)
			.encode(CharBuffer.wrap(value))
		return ByteArray(encoded.remaining()).also(encoded::get)
	}

	private fun decodeUtf8(value: ByteArray): CharArray {
		val decoded = StandardCharsets.UTF_8.newDecoder()
			.onMalformedInput(CodingErrorAction.REPORT)
			.onUnmappableCharacter(CodingErrorAction.REPORT)
			.decode(ByteBuffer.wrap(value))
		return CharArray(decoded.remaining()).also(decoded::get)
	}

	private data class EncryptedPayload(
		val iv: ByteArray,
		val ciphertext: ByteArray,
	)

	private companion object {
		val PROCESS_KEY_CREATION_LOCK = Any()

		const val KEYSTORE_PROVIDER = "AndroidKeyStore"
		const val TRANSFORMATION = "AES/GCM/NoPadding"
		const val TAG_LENGTH_BITS = 128
		const val TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8
		const val IV_LENGTH_BYTES = 12
		const val KEY_SIZE_BITS = 256
		const val FORMAT_VERSION = "v1"
		const val MIN_SERIALIZED_LENGTH = 8
		const val MAX_SERIALIZED_LENGTH = 512
	}
}

/**
 * Serializes first-key creation across all cipher instances and double-checks after acquiring the
 * process lock so only one caller generates a key for a newly observed alias.
 */
internal class ProcessWideKeyCreator<T : Any>(
	private val lock: Any,
	private val findExisting: () -> T?,
	private val create: () -> T,
) {
	fun getOrCreate(): T {
		findExisting()?.let { return it }
		return synchronized(lock) {
			findExisting() ?: create()
		}
	}
}
