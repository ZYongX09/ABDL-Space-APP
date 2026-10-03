package org.joinmastodon.android.security

import android.content.SharedPreferences
import com.google.gson.Gson
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.joinmastodon.android.security.data.AtomicSecurityStorePoisonState
import org.joinmastodon.android.security.data.EncryptedPreferencesSecurityStore
import org.joinmastodon.android.security.data.InvalidPinStatusEntity
import org.joinmastodon.android.security.data.LockMethodEntity
import org.joinmastodon.android.security.data.PinOptionsEntity
import org.joinmastodon.android.security.data.SecurityStoreResult
import org.joinmastodon.android.security.data.StoredSecurityData
import org.joinmastodon.android.security.domain.PinDigits
import org.joinmastodon.android.security.domain.PinTimeout
import org.joinmastodon.android.security.domain.PinTrials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedPreferencesSecurityStoreTest {
	@Test
	fun failedCommitPoisonsSameAndNewStoreInstancesDespiteUpdatedPreferenceMap() = runBlocking {
		val fakePreferences = FakeSharedPreferences(lockedPreferenceValues())
		fakePreferences.commitBehavior = CommitBehavior.ReturnFalseAfterApplying
		val poisonState = AtomicSecurityStorePoisonState()
		val mutex = Mutex()
		val first = store(fakePreferences, poisonState, mutex)
		val second = store(fakePreferences, poisonState, mutex)

		val result = first.update { current ->
			val existing = (current as SecurityStoreResult.Success).value
			existing.copy(
				pinSecured = "",
				lockStatus = LockMethodEntity.NO_LOCK,
				invalidPinStatus = InvalidPinStatusEntity(),
			)
		}

		assertEquals(SecurityStoreResult.Unavailable, result)
		assertTrue(poisonState.isPoisoned())
		assertEquals(
			LockMethodEntity.NO_LOCK.name,
			fakePreferences.values[EncryptedPreferencesSecurityStore.KEY_LOCK_STATUS],
		)
		assertEquals("", fakePreferences.values[EncryptedPreferencesSecurityStore.KEY_PIN_SECURED])
		assertEquals(SecurityStoreResult.Unavailable, first.read())
		assertEquals(SecurityStoreResult.Unavailable, second.read())

		var transformCalled = false
		assertEquals(
			SecurityStoreResult.Unavailable,
			second.update {
				transformCalled = true
				error("poisoned stores must not run transforms")
			},
		)
		assertFalse(transformCalled)
	}

	@Test
	fun writeExceptionPoisonsSharedStateAndCancellationStillPropagates() {
		val writeFailurePreferences = FakeSharedPreferences(lockedPreferenceValues()).apply {
			commitBehavior = CommitBehavior.Throw(IllegalStateException("disk unavailable"))
		}
		val writeFailurePoison = AtomicSecurityStorePoisonState()
		val writeFailureStore = store(writeFailurePreferences, writeFailurePoison, Mutex())
		runBlocking {
			assertEquals(
				SecurityStoreResult.Unavailable,
				writeFailureStore.update(::unchangedData),
			)
			assertTrue(writeFailurePoison.isPoisoned())
			assertEquals(SecurityStoreResult.Unavailable, writeFailureStore.read())
		}

		val cancellationPreferences = FakeSharedPreferences(lockedPreferenceValues()).apply {
			commitBehavior = CommitBehavior.Throw(CancellationException("cancelled"))
		}
		val cancellationPoison = AtomicSecurityStorePoisonState()
		val cancellationStore = store(cancellationPreferences, cancellationPoison, Mutex())
		assertThrows(CancellationException::class.java) {
			runBlocking { cancellationStore.update(::unchangedData) }
		}
		assertTrue(cancellationPoison.isPoisoned())
	}

	private fun store(
		preferences: FakeSharedPreferences,
		poisonState: AtomicSecurityStorePoisonState,
		mutex: Mutex,
	) = EncryptedPreferencesSecurityStore(
		preferences = preferences.preferences,
		preferencesFileExists = { true },
		gson = Gson(),
		mutex = mutex,
		poisonState = poisonState,
	)

	private fun unchangedData(current: SecurityStoreResult<StoredSecurityData>): StoredSecurityData =
		(current as SecurityStoreResult.Success).value

	private fun lockedPreferenceValues(): MutableMap<String, Any?> {
		val gson = Gson()
		return mutableMapOf(
			EncryptedPreferencesSecurityStore.KEY_PIN_SECURED to "enc:1234",
			EncryptedPreferencesSecurityStore.KEY_LOCK_STATUS to LockMethodEntity.PIN_SECURED.name,
			EncryptedPreferencesSecurityStore.KEY_PIN_OPTIONS to gson.toJson(
				PinOptionsEntity(
					digits = PinDigits.Code4.value,
					trials = PinTrials.Trials3.trials,
					timeout = PinTimeout.Timeout5.timeoutMs,
				),
			),
			EncryptedPreferencesSecurityStore.KEY_INVALID_PIN_STATUS to gson.toJson(
				InvalidPinStatusEntity(),
			),
		)
	}

	private class FakeSharedPreferences(initial: MutableMap<String, Any?>) {
		val values = initial.toMutableMap()
		var commitBehavior: CommitBehavior = CommitBehavior.Succeed

		val preferences: SharedPreferences = Proxy.newProxyInstance(
			SharedPreferences::class.java.classLoader,
			arrayOf(SharedPreferences::class.java),
		) { _, method, arguments ->
			val args = arguments.orEmpty()
			when (method.name) {
				"getAll" -> HashMap(values)
				"getString" -> values[args[0] as String] as? String ?: args[1]
				"contains" -> values.containsKey(args[0] as String)
				"edit" -> newEditor()
				"registerOnSharedPreferenceChangeListener",
				"unregisterOnSharedPreferenceChangeListener",
				-> null
				"toString" -> "FakeSharedPreferences"
				else -> throw UnsupportedOperationException(method.name)
			}
		} as SharedPreferences

		private fun newEditor(): SharedPreferences.Editor {
			val pending = mutableMapOf<String, Any?>()
			return Proxy.newProxyInstance(
				SharedPreferences.Editor::class.java.classLoader,
				arrayOf(SharedPreferences.Editor::class.java),
			) { proxy, method, arguments ->
				val args = arguments.orEmpty()
				when (method.name) {
					"putString" -> {
						pending[args[0] as String] = args[1]
						proxy
					}
					"commit" -> when (val behavior = commitBehavior) {
						CommitBehavior.Succeed -> {
							values.putAll(pending)
							true
						}
						CommitBehavior.ReturnFalseAfterApplying -> {
							values.putAll(pending)
							false
						}
						is CommitBehavior.Throw -> throw behavior.error
					}
					"toString" -> "FakeSharedPreferences.Editor"
					else -> throw UnsupportedOperationException(method.name)
				}
			} as SharedPreferences.Editor
		}
	}

	private sealed interface CommitBehavior {
		data object Succeed : CommitBehavior
		data object ReturnFalseAfterApplying : CommitBehavior
		data class Throw(val error: RuntimeException) : CommitBehavior
	}
}
