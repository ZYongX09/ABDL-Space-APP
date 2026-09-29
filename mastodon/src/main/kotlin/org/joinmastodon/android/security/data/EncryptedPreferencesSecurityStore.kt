/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Preference keys and serialized models adapted from 2FAS Android 5.6.0:
 * https://github.com/twofas/2fas-android/tree/119ead28ed8d3d2215afd8f55428c1586401149b/prefs
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonParseException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Dedicated security preferences store.
 *
 * The PIN value is already protected by AndroidKeyStore AES/GCM. Keeping the four logical values
 * in one ordinary, app-private SharedPreferences file lets every state transition use one atomic
 * commit and avoids an additional encrypted-preferences compatibility layer. Backup rules
 * exclude this file because its non-exportable key cannot be restored.
 *
 * SharedPreferences updates its in-process map before reporting a failed disk commit. Therefore a
 * failed commit or write exception poisons the store for the rest of this process. Every store
 * instance sharing the process poison state then fails closed instead of observing an unpersisted
 * NO_LOCK value from that map.
 */
class EncryptedPreferencesSecurityStore internal constructor(
	private val preferences: SharedPreferences,
	private val preferencesFileExists: () -> Boolean,
	private val gson: Gson,
	private val mutex: Mutex,
	private val poisonState: SecurityStorePoisonState,
) : SecurityStore {
	constructor(
		context: Context,
		gson: Gson = Gson(),
	) : this(
		preferences = context.applicationContext.getSharedPreferences(
			PREFERENCES_FILE,
			Context.MODE_PRIVATE,
		),
		preferencesFileExists = {
			context.applicationContext.applicationInfo.dataDir
				?.let { java.io.File(it, "shared_prefs/$PREFERENCES_FILE.xml").exists() }
				?: false
		},
		gson = gson,
		mutex = PROCESS_MUTEX,
		poisonState = PROCESS_POISON_STATE,
	)

	override suspend fun read(): SecurityStoreResult<StoredSecurityData> = mutex.withLock {
		if (poisonState.isPoisoned()) SecurityStoreResult.Unavailable else readUnlocked()
	}

	override suspend fun update(
		transform: (SecurityStoreResult<StoredSecurityData>) -> StoredSecurityData,
	): SecurityStoreResult<StoredSecurityData> = mutex.withLock {
		if (poisonState.isPoisoned()) return@withLock SecurityStoreResult.Unavailable

		// Intentionally let transform failures reach the repository so corruption and temporary
		// unavailability retain their distinct fail-closed results.
		val updated = transform(readUnlocked())
		if (!updated.isStructurallyValid()) return@withLock SecurityStoreResult.Corrupted

		try {
			val committed = preferences.edit()
				.putString(KEY_PIN_SECURED, updated.pinSecured)
				.putString(KEY_LOCK_STATUS, updated.lockStatus.name)
				.putString(KEY_PIN_OPTIONS, gson.toJson(updated.pinOptions))
				.putString(KEY_INVALID_PIN_STATUS, gson.toJson(updated.invalidPinStatus))
				.commit()
			if (committed) {
				SecurityStoreResult.Success(updated)
			} else {
				poisonState.poison()
				SecurityStoreResult.Unavailable
			}
		} catch (error: CancellationException) {
			poisonState.poison()
			throw error
		} catch (_: Exception) {
			poisonState.poison()
			SecurityStoreResult.Unavailable
		}
	}

	private fun readUnlocked(): SecurityStoreResult<StoredSecurityData> {
		if (poisonState.isPoisoned()) return SecurityStoreResult.Unavailable
		return try {
			val persisted = preferences.all
			when {
				persisted.isEmpty() && !preferencesFileExists() -> SecurityStoreResult.Missing
				persisted.isEmpty() -> SecurityStoreResult.Corrupted
				persisted.keys != REQUIRED_KEYS -> SecurityStoreResult.Corrupted
				else -> {
					val data = StoredSecurityData(
						pinSecured = requiredString(KEY_PIN_SECURED),
						lockStatus = enumValueOf(requiredString(KEY_LOCK_STATUS)),
						pinOptions = gson.fromJson(
							requiredString(KEY_PIN_OPTIONS),
							PinOptionsEntity::class.java,
						),
						invalidPinStatus = gson.fromJson(
							requiredString(KEY_INVALID_PIN_STATUS),
							InvalidPinStatusEntity::class.java,
						),
					)
					if (data.isStructurallyValid()) SecurityStoreResult.Success(data)
					else SecurityStoreResult.Corrupted
				}
			}
		} catch (error: CancellationException) {
			throw error
		} catch (_: ClassCastException) {
			SecurityStoreResult.Corrupted
		} catch (_: IllegalArgumentException) {
			SecurityStoreResult.Corrupted
		} catch (_: JsonParseException) {
			SecurityStoreResult.Corrupted
		} catch (_: NullPointerException) {
			SecurityStoreResult.Corrupted
		} catch (_: Exception) {
			SecurityStoreResult.Unavailable
		}
	}

	private fun requiredString(key: String): String =
		preferences.getString(key, null) ?: throw IllegalArgumentException("Missing security value")

	companion object {
		private val PROCESS_MUTEX = Mutex()
		private val PROCESS_POISON_STATE: SecurityStorePoisonState = AtomicSecurityStorePoisonState()

		const val PREFERENCES_FILE = "abdl_space_security"
		const val KEY_PIN_SECURED = "pinSecured"
		const val KEY_LOCK_STATUS = "lockStatus"
		const val KEY_PIN_OPTIONS = "pinOptions"
		const val KEY_INVALID_PIN_STATUS = "invalidPinStatus"

		private val REQUIRED_KEYS = setOf(
			KEY_PIN_SECURED,
			KEY_LOCK_STATUS,
			KEY_PIN_OPTIONS,
			KEY_INVALID_PIN_STATUS,
		)
	}
}

internal interface SecurityStorePoisonState {
	fun isPoisoned(): Boolean
	fun poison()
}

internal class AtomicSecurityStorePoisonState : SecurityStorePoisonState {
	private val poisoned = AtomicBoolean(false)

	override fun isPoisoned(): Boolean = poisoned.get()

	override fun poison() {
		poisoned.set(true)
	}
}
