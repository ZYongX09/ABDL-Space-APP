package org.joinmastodon.android.security

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.joinmastodon.android.security.data.InvalidPinStatusEntity
import org.joinmastodon.android.security.data.LockMethodEntity
import org.joinmastodon.android.security.data.PinOptionsEntity
import org.joinmastodon.android.security.data.PinProtectedMutationResult
import org.joinmastodon.android.security.data.SecurityRepositoryImpl
import org.joinmastodon.android.security.data.SecurityResult
import org.joinmastodon.android.security.data.SecurityStore
import org.joinmastodon.android.security.data.SecurityStoreResult
import org.joinmastodon.android.security.data.StoredSecurityData
import org.joinmastodon.android.security.domain.BootSessionMarkerProvider
import org.joinmastodon.android.security.domain.ElapsedRealtimeProvider
import org.joinmastodon.android.security.domain.InvalidPinStatus
import org.joinmastodon.android.security.domain.LockMethod
import org.joinmastodon.android.security.domain.PinCipher
import org.joinmastodon.android.security.domain.PinCipherException
import org.joinmastodon.android.security.domain.PinDigits
import org.joinmastodon.android.security.domain.PinOptions
import org.joinmastodon.android.security.domain.PinTimeout
import org.joinmastodon.android.security.domain.PinTrials
import org.joinmastodon.android.security.domain.PinVerificationResult
import org.joinmastodon.android.security.domain.StoreErrorReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityRepositoryTest {
	@Test
	fun domainDefaultsAndOptionsAreComplete() {
		assertEquals(PinDigits.Code4, PinOptions.Default.digits)
		assertEquals(PinTrials.Trials3, PinOptions.Default.trials)
		assertEquals(PinTimeout.Timeout5, PinOptions.Default.timeout)
		assertEquals(listOf(4, 6), PinDigits.entries.map { it.value })
		assertEquals(listOf(3, 5, 10, -1), PinTrials.entries.map { it.trials })
		assertEquals(listOf(180_000L, 300_000L, 600_000L), PinTimeout.entries.map { it.timeoutMs })
	}

	@Test
	fun missingStoreIsNoLockAndSetupSuccessWrongThresholdThenSuccess() = runBlocking {
		val fixture = Fixture()
		val initial = fixture.repository.getSecurityState().success()
		assertEquals(LockMethod.NoLock, initial.lockMethod)
		assertEquals(PinOptions.Default, initial.pinOptions)

		fixture.repository.setupPin("1234".toChars(), PinDigits.Code4).success()
		assertTrue(fixture.repository.verifyPin("0000".toChars()) is PinVerificationResult.Wrong)
		assertTrue(fixture.repository.verifyPin("0000".toChars()) is PinVerificationResult.Wrong)
		val blocked = fixture.repository.verifyPin("0000".toChars()) as PinVerificationResult.Blocked
		assertEquals(3, blocked.status.attempts)
		assertEquals(300_000L, blocked.status.timeLeftMs)
		assertTrue(fixture.repository.verifyPin("1234".toChars()) is PinVerificationResult.Blocked)

		fixture.clock.now += 300_000L
		assertEquals(PinVerificationResult.Success, fixture.repository.verifyPin("1234".toChars()))
		assertEquals(0, fixture.store.value()!!.invalidPinStatus.attempts)
	}

	@Test
	fun everyTrialAndTimeoutOptionBlocksAtConfiguredThreshold() = runBlocking {
		for (trials in listOf(PinTrials.Trials3, PinTrials.Trials5, PinTrials.Trials10)) {
			for (timeout in PinTimeout.entries) {
				val fixture = Fixture()
				fixture.repository.setupPin("1234".toChars(), PinDigits.Code4).success()
				fixture.repository.editLockoutPolicy(trials, timeout).success()
				repeat(trials.trials - 1) {
					assertTrue(fixture.repository.verifyPin("9999".toChars()) is PinVerificationResult.Wrong)
				}
				val blocked = fixture.repository.verifyPin("9999".toChars()) as PinVerificationResult.Blocked
				assertEquals(timeout.timeoutMs, blocked.status.timeLeftMs)
			}
		}
	}

	@Test
	fun noLimitNeverBlocksAndSuccessResetsFailures() = runBlocking {
		val fixture = Fixture()
		fixture.repository.setupPin("1234".toChars(), PinDigits.Code4).success()
		fixture.repository.editLockoutPolicy(PinTrials.NoLimit, PinTimeout.Timeout3).success()
		repeat(25) {
			assertTrue(fixture.repository.verifyPin("9999".toChars()) is PinVerificationResult.Wrong)
		}
		assertEquals(PinVerificationResult.Success, fixture.repository.verifyPin("1234".toChars()))
		assertEquals(0, fixture.store.value()!!.invalidPinStatus.attempts)
	}

	@Test
	fun rebootWhileLockedStartsOneCompleteLockPeriodThenExpires() = runBlocking {
		val fixture = Fixture()
		fixture.repository.setupPin("1234".toChars(), PinDigits.Code4).success()
		repeat(3) { fixture.repository.verifyPin("9999".toChars()) }
		fixture.clock.now += 100_000L
		fixture.boot.marker = "boot-2"

		val restarted = fixture.repository.getSecurityState().success().invalidPinStatus
		assertTrue(restarted.shouldBlock)
		assertEquals(300_000L, restarted.timeLeftMs)
		fixture.clock.now += 299_999L
		assertTrue(fixture.repository.getSecurityState().success().invalidPinStatus.shouldBlock)
		fixture.clock.now += 1L
		assertEquals(InvalidPinStatus.Default, fixture.repository.getSecurityState().success().invalidPinStatus)
		assertEquals(0, fixture.store.value()!!.invalidPinStatus.attempts)
	}

	@Test
	fun concurrentFailuresDoNotLoseAttempts() = runBlocking {
		val fixture = Fixture()
		fixture.repository.setupPin("1234".toChars(), PinDigits.Code4).success()
		fixture.repository.editLockoutPolicy(PinTrials.NoLimit, PinTimeout.Timeout5).success()
		(1..40).map {
			async(Dispatchers.Default) { fixture.repository.verifyPin("0000".toChars()) }
		}.awaitAll()
		assertEquals(40, fixture.store.value()!!.invalidPinStatus.attempts)
	}

	@Test
	fun setupChangeAndDisableEachUseOneAtomicStoreCommit() = runBlocking {
		val fixture = Fixture()
		fixture.repository.setupPin("1234".toChars(), PinDigits.Code4, LockMethod.Pin).success()
		assertEquals(1, fixture.store.commits.get())
		assertEquals("enc:1234", fixture.store.value()!!.pinSecured)
		fixture.repository.verifyPin("9999".toChars())

		fixture.repository.changePin(
			currentPin = "1234".toChars(),
			newPin = "654321".toChars(),
			digits = PinDigits.Code6,
			lockMethod = LockMethod.Biometrics,
		).success()
		assertEquals(3, fixture.store.commits.get())
		with(fixture.store.value()!!) {
			assertEquals("enc:654321", pinSecured)
			assertEquals(6, pinOptions.digits)
			assertEquals(LockMethodEntity.FINGERPRINT_WITH_PIN_SECURED, lockStatus)
			assertEquals(0, invalidPinStatus.attempts)
		}

		fixture.repository.disablePin("654321".toChars()).success()
		assertEquals(4, fixture.store.commits.get())
		with(fixture.store.value()!!) {
			assertEquals("", pinSecured)
			assertEquals(LockMethodEntity.NO_LOCK, lockStatus)
			assertEquals(0, invalidPinStatus.attempts)
		}
	}

	@Test
	fun corruptionAndUnavailableStorageFailClosed() = runBlocking {
		val corrupt = Fixture(initial = SecurityStoreResult.Corrupted)
		assertEquals(SecurityResult.Corrupted, corrupt.repository.getSecurityState())
		assertEquals(
			PinVerificationResult.StoreError(StoreErrorReason.Corrupted),
			corrupt.repository.verifyPin("1234".toChars()),
		)

		val unavailable = Fixture(initial = SecurityStoreResult.Unavailable)
		assertEquals(SecurityResult.StoreError, unavailable.repository.getSecurityState())
		assertEquals(
			PinVerificationResult.StoreError(StoreErrorReason.Unavailable),
			unavailable.repository.verifyPin("1234".toChars()),
		)
	}

	@Test
	fun diagnosticsAndExceptionsNeverContainPin() = runBlocking {
		val sensitive = "7319"
		val fixture = Fixture()
		fixture.repository.setupPin(sensitive.toChars(), PinDigits.Code4).success()
		assertFalse(fixture.store.value().toString().contains(sensitive))
		assertFalse(PinCipherException.Corrupted().toString().contains(sensitive))
		assertFalse(PinCipherException.Unavailable().toString().contains(sensitive))
		assertNotEquals(sensitive, fixture.repository.verifyPin("0000".toChars()).toString())
	}

	@Test
	fun setupAcceptsOnlyMissingOrValidNoLockAndNeverOverwritesExistingLock() = runBlocking {
		val locked = Fixture()
		locked.repository.setupPin("1234".toChars(), PinDigits.Code4).success()
		val persisted = locked.store.value()
		assertEquals(
			SecurityResult.InvalidState,
			locked.repository.setupPin("9999".toChars(), PinDigits.Code4),
		)
		assertEquals(persisted, locked.store.value())
		assertEquals(1, locked.store.commits.get())
		assertEquals(1, locked.cipher.encryptCalls.get())

		val validNoLock = Fixture(initial = SecurityStoreResult.Success(defaultStoredData()))
		validNoLock.repository.setupPin("5678".toChars(), PinDigits.Code4).success()
		assertEquals("enc:5678", validNoLock.store.value()!!.pinSecured)

		val damagedNoLock = Fixture(
			initial = SecurityStoreResult.Success(defaultStoredData().copy(pinSecured = "orphaned")),
		)
		assertEquals(
			SecurityResult.Corrupted,
			damagedNoLock.repository.setupPin("5678".toChars(), PinDigits.Code4),
		)
		assertEquals(0, damagedNoLock.store.commits.get())
		assertEquals(0, damagedNoLock.cipher.encryptCalls.get())

		val corrupt = Fixture(initial = SecurityStoreResult.Corrupted)
		assertEquals(SecurityResult.Corrupted, corrupt.repository.setupPin("5678".toChars(), PinDigits.Code4))
		assertEquals(0, corrupt.store.commits.get())
		assertEquals(0, corrupt.cipher.encryptCalls.get())
	}

	@Test
	fun changeAndDisableAuthenticateCurrentPinCountFailuresAndRespectBlocking() = runBlocking {
		val fixture = Fixture()
		fixture.repository.setupPin("1234".toChars(), PinDigits.Code4).success()

		assertTrue(
			fixture.repository.changePin("0000".toChars(), "654321".toChars(), PinDigits.Code6) is
				PinProtectedMutationResult.Wrong,
		)
		assertTrue(
			fixture.repository.changePin("0000".toChars(), "654321".toChars(), PinDigits.Code6) is
				PinProtectedMutationResult.Wrong,
		)
		val changeBlocked = fixture.repository.changePin(
			"0000".toChars(),
			"654321".toChars(),
			PinDigits.Code6,
		) as PinProtectedMutationResult.Blocked
		assertEquals(3, changeBlocked.status.attempts)
		assertEquals("enc:1234", fixture.store.value()!!.pinSecured)
		assertEquals(4, fixture.store.value()!!.pinOptions.digits)
		assertEquals(1, fixture.cipher.encryptCalls.get())

		fixture.clock.now += PinTimeout.Timeout5.timeoutMs
		fixture.repository.changePin(
			currentPin = "1234".toChars(),
			newPin = "654321".toChars(),
			digits = PinDigits.Code6,
			lockMethod = LockMethod.Biometrics,
		).success()
		assertEquals(2, fixture.cipher.encryptCalls.get())
		assertEquals(0, fixture.store.value()!!.invalidPinStatus.attempts)

		assertTrue(fixture.repository.disablePin("000000".toChars()) is PinProtectedMutationResult.Wrong)
		assertTrue(fixture.repository.disablePin("000000".toChars()) is PinProtectedMutationResult.Wrong)
		val disableBlocked = fixture.repository.disablePin("000000".toChars()) as PinProtectedMutationResult.Blocked
		assertEquals(3, disableBlocked.status.attempts)
		assertEquals("enc:654321", fixture.store.value()!!.pinSecured)
		assertEquals(LockMethodEntity.FINGERPRINT_WITH_PIN_SECURED, fixture.store.value()!!.lockStatus)

		fixture.clock.now += PinTimeout.Timeout5.timeoutMs
		fixture.repository.disablePin("654321".toChars()).success()
		assertEquals(LockMethodEntity.NO_LOCK, fixture.store.value()!!.lockStatus)
		assertEquals("", fixture.store.value()!!.pinSecured)
	}

	@Test
	fun changeAndDisableCannotOverwriteCorruptStoreOrMissingKeystoreKey() = runBlocking {
		val missingStore = Fixture()
		assertEquals(
			PinProtectedMutationResult.Corrupted,
			missingStore.repository.changePin("1234".toChars(), "5678".toChars(), PinDigits.Code4),
		)
		assertEquals(
			PinProtectedMutationResult.Corrupted,
			missingStore.repository.disablePin("1234".toChars()),
		)
		assertEquals(0, missingStore.store.commits.get())

		val corruptStore = Fixture(initial = SecurityStoreResult.Corrupted)
		assertEquals(
			PinProtectedMutationResult.Corrupted,
			corruptStore.repository.changePin("1234".toChars(), "5678".toChars(), PinDigits.Code4),
		)
		assertEquals(
			PinProtectedMutationResult.Corrupted,
			corruptStore.repository.disablePin("1234".toChars()),
		)

		val missingKey = Fixture()
		missingKey.repository.setupPin("1234".toChars(), PinDigits.Code4).success()
		val persisted = missingKey.store.value()
		missingKey.cipher.decryptFailure = PinCipherException.Corrupted()
		assertEquals(
			PinProtectedMutationResult.Corrupted,
			missingKey.repository.changePin("1234".toChars(), "5678".toChars(), PinDigits.Code4),
		)
		assertEquals(persisted, missingKey.store.value())
		assertEquals(1, missingKey.cipher.encryptCalls.get())
		assertEquals(
			PinProtectedMutationResult.Corrupted,
			missingKey.repository.disablePin("1234".toChars()),
		)
		assertEquals(persisted, missingKey.store.value())
		assertEquals(1, missingKey.store.commits.get())
	}

	@Test
	fun competingRepositoryChangesAuthenticateTheLatestStoredCiphertext() = runBlocking {
		val store = InMemorySecurityStore(SecurityStoreResult.Missing)
		val cipher = FakePinCipher()
		val clock = FakeClock()
		val boot = FakeBootMarker()
		val first = SecurityRepositoryImpl(store, cipher, clock, boot)
		val second = SecurityRepositoryImpl(store, cipher, clock, boot)
		first.setupPin("1234".toChars(), PinDigits.Code4).success()

		val results = listOf(
			async(Dispatchers.Default) {
				first.changePin("1234".toChars(), "1111".toChars(), PinDigits.Code4)
			},
			async(Dispatchers.Default) {
				second.changePin("1234".toChars(), "2222".toChars(), PinDigits.Code4)
			},
		).awaitAll()

		assertEquals(1, results.count { it is PinProtectedMutationResult.Success })
		assertEquals(1, results.count { it is PinProtectedMutationResult.Wrong })
		assertEquals(2, cipher.encryptCalls.get())
		assertEquals(1, store.value()!!.invalidPinStatus.attempts)
		assertTrue(store.value()!!.pinSecured in setOf("enc:1111", "enc:2222"))
	}

	@Test
	fun lockoutPolicyCannotChangeDigitsAndNonAsciiOrWrongLengthPinsAreInvalidInput() = runBlocking {
		val policy = Fixture()
		policy.repository.setupPin("123456".toChars(), PinDigits.Code6).success()
		assertTrue(policy.repository.verifyPin("000000".toChars()) is PinVerificationResult.Wrong)
		policy.repository.editLockoutPolicy(PinTrials.Trials10, PinTimeout.Timeout10).success()
		assertEquals(6, policy.store.value()!!.pinOptions.digits)
		assertEquals(PinTrials.Trials10.trials, policy.store.value()!!.pinOptions.trials)
		assertEquals(1, policy.store.value()!!.invalidPinStatus.attempts)

		val fixture = Fixture()
		assertEquals(
			SecurityResult.InvalidInput,
			fixture.repository.setupPin("١٢٣٤".toChars(), PinDigits.Code4),
		)
		assertEquals(
			SecurityResult.InvalidInput,
			fixture.repository.setupPin("12345".toChars(), PinDigits.Code4),
		)
		assertEquals(0, fixture.store.commits.get())
		assertEquals(0, fixture.cipher.encryptCalls.get())

		fixture.repository.setupPin("1234".toChars(), PinDigits.Code4).success()
		val commits = fixture.store.commits.get()
		assertEquals(PinVerificationResult.InvalidInput, fixture.repository.verifyPin("١٢٣٤".toChars()))
		assertEquals(PinVerificationResult.InvalidInput, fixture.repository.verifyPin("123".toChars()))
		assertEquals(
			PinProtectedMutationResult.InvalidInput,
			fixture.repository.changePin("1234".toChars(), "５６７８".toChars(), PinDigits.Code4),
		)
		assertEquals(
			PinProtectedMutationResult.InvalidInput,
			fixture.repository.changePin("1234".toChars(), "56789".toChars(), PinDigits.Code4),
		)
		assertEquals(
			PinProtectedMutationResult.InvalidInput,
			fixture.repository.disablePin("١٢٣٤".toChars()),
		)
		assertEquals(commits, fixture.store.commits.get())
		assertEquals(0, fixture.store.value()!!.invalidPinStatus.attempts)
	}

	@Test
	fun repositoryCancellationIsNeverConvertedToStoreError() = runBlocking {
		val cipher = object : PinCipher {
			override fun encrypt(pin: CharArray): String = "enc:${String(pin)}"
			override fun decrypt(encryptedPin: String): CharArray = throw CancellationException("cancelled")
		}
		val fixture = Fixture(cipher = cipher)
		fixture.repository.setupPin("1234".toChars(), PinDigits.Code4).success()

		assertThrows(CancellationException::class.java) {
			runBlocking { fixture.repository.verifyPin("1234".toChars()) }
		}
		assertThrows(CancellationException::class.java) {
			runBlocking {
				fixture.repository.changePin("1234".toChars(), "5678".toChars(), PinDigits.Code4)
			}
		}
		assertEquals(1, fixture.store.commits.get())
	}

	private class Fixture(
		initial: SecurityStoreResult<StoredSecurityData> = SecurityStoreResult.Missing,
		val cipher: FakePinCipher = FakePinCipher(),
		private val customCipher: PinCipher = cipher,
		bootProvider: BootSessionMarkerProvider? = null,
	) {
		constructor(
			initial: SecurityStoreResult<StoredSecurityData> = SecurityStoreResult.Missing,
			cipher: PinCipher,
		) : this(initial, FakePinCipher(), cipher, null)

		val store = InMemorySecurityStore(initial)
		val clock = FakeClock()
		val boot = FakeBootMarker()
		val repository = SecurityRepositoryImpl(store, customCipher, clock, bootProvider ?: boot)
	}

	private class FakeClock(var now: Long = 10_000L) : ElapsedRealtimeProvider {
		override fun elapsedRealtimeMs() = now
	}

	private class FakeBootMarker(var marker: String = "boot-1") : BootSessionMarkerProvider {
		override fun marker() = marker
	}

	private class FakePinCipher : PinCipher {
		val encryptCalls = AtomicInteger()
		var encryptFailure: PinCipherException? = null
		var decryptFailure: PinCipherException? = null

		override fun encrypt(pin: CharArray): String {
			encryptCalls.incrementAndGet()
			encryptFailure?.let { throw it }
			return "enc:${String(pin)}"
		}

		override fun decrypt(encryptedPin: String): CharArray {
			decryptFailure?.let { throw it }
			if (!encryptedPin.startsWith("enc:")) throw PinCipherException.Corrupted()
			return encryptedPin.removePrefix("enc:").toCharArray()
		}
	}

	private class InMemorySecurityStore(
		initial: SecurityStoreResult<StoredSecurityData>,
	) : SecurityStore {
		private var current = initial
		private val mutex = Mutex()
		val commits = AtomicInteger()

		override suspend fun read() = mutex.withLock { current }

		override suspend fun update(
			transform: (SecurityStoreResult<StoredSecurityData>) -> StoredSecurityData,
		): SecurityStoreResult<StoredSecurityData> = mutex.withLock {
			val updated = transform(current)
			commits.incrementAndGet()
			SecurityStoreResult.Success(updated).also { current = it }
		}

		fun value() = (current as? SecurityStoreResult.Success)?.value
	}

	private fun String.toChars() = toCharArray()

	private fun <T> SecurityResult<T>.success(): T =
		(this as SecurityResult.Success<T>).value

	private fun PinProtectedMutationResult.success() =
		(this as PinProtectedMutationResult.Success).state

	private companion object {
		fun defaultStoredData() = StoredSecurityData(
			pinSecured = "",
			lockStatus = LockMethodEntity.NO_LOCK,
			pinOptions = PinOptionsEntity(
				digits = PinDigits.Code4.value,
				trials = PinTrials.Trials3.trials,
				timeout = PinTimeout.Timeout5.timeoutMs,
			),
			invalidPinStatus = InvalidPinStatusEntity(),
		)
	}
}
