package org.joinmastodon.android.security

import android.provider.Settings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.joinmastodon.android.security.data.AndroidBootSessionMarkerProvider
import org.joinmastodon.android.security.data.BootCountReader
import org.joinmastodon.android.security.data.BootSessionMarkerUnavailableException
import org.joinmastodon.android.security.data.InvalidPinStatusEntity
import org.joinmastodon.android.security.data.LockMethodEntity
import org.joinmastodon.android.security.data.PinOptionsEntity
import org.joinmastodon.android.security.data.ProcessWideKeyCreator
import org.joinmastodon.android.security.data.SecurityComponents
import org.joinmastodon.android.security.data.SecurityRepositoryImpl
import org.joinmastodon.android.security.data.SecurityResult
import org.joinmastodon.android.security.data.SecurityStore
import org.joinmastodon.android.security.data.SecurityStoreResult
import org.joinmastodon.android.security.data.StoredSecurityData
import org.joinmastodon.android.security.domain.PinCipher
import org.joinmastodon.android.security.domain.PinDigits
import org.joinmastodon.android.security.domain.PinTimeout
import org.joinmastodon.android.security.domain.PinTrials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])

class SecurityInfrastructureTest {
	@Test
	fun androidBootCountProviderTreatsMissingSettingAsUnavailable() {
		val context = RuntimeEnvironment.getApplication()
		Settings.Global.putString(context.contentResolver, Settings.Global.BOOT_COUNT, null)
		assertThrows(BootSessionMarkerUnavailableException::class.java) {
			AndroidBootSessionMarkerProvider(context).marker()
		}
	}

	@Test
	fun bootCountMarkerRejectsNegativeAndExceptionalValues() {
		assertEquals("7", AndroidBootSessionMarkerProvider(BootCountReader { 7 }).marker())
		assertThrows(BootSessionMarkerUnavailableException::class.java) {
			AndroidBootSessionMarkerProvider(BootCountReader { -1 }).marker()
		}
		assertThrows(BootSessionMarkerUnavailableException::class.java) {
			AndroidBootSessionMarkerProvider(
				BootCountReader { throw BootSessionMarkerUnavailableException() },
			).marker()
		}
		assertThrows(BootSessionMarkerUnavailableException::class.java) {
			AndroidBootSessionMarkerProvider(
				BootCountReader { throw IllegalStateException("provider unavailable") },
			).marker()
		}
	}

	@Test
	fun unavailableBootMarkerMakesLockedRepositoryFailClosed() = runBlocking {
		val repository = SecurityRepositoryImpl(
			store = FixedStore(lockedData(attempts = PinTrials.Trials3.trials)),
			pinCipher = NoOpPinCipher,
			elapsedRealtimeProvider = { 10_000L },
			bootSessionMarkerProvider = AndroidBootSessionMarkerProvider(
				BootCountReader { throw BootSessionMarkerUnavailableException() },
			),
		)

		assertEquals(SecurityResult.StoreError, repository.getSecurityState())
	}

	@Test
	fun processWideKeyCreatorDoubleChecksUnderSharedLockAcrossInstances() {
		val lock = Any()
		val existing = AtomicReference<Any?>(null)
		val createCalls = AtomicInteger()
		val firstLookupCount = AtomicInteger()
		val bothFirstLookups = CountDownLatch(2)
		val releaseFirstLookups = CountDownLatch(1)
		val workers = Executors.newFixedThreadPool(2)

		fun creator() = ProcessWideKeyCreator(
			lock = lock,
			findExisting = {
				val lookup = firstLookupCount.incrementAndGet()
				if (lookup <= 2) {
					val observedBeforeCreation = existing.get()
					bothFirstLookups.countDown()
					releaseFirstLookups.await(5, TimeUnit.SECONDS)
					observedBeforeCreation
				} else {
					existing.get()
				}
			},
			create = {
				createCalls.incrementAndGet()
				Any().also(existing::set)
			},
		)

		try {
			val first = workers.submit<Any> { creator().getOrCreate() }
			val second = workers.submit<Any> { creator().getOrCreate() }
			assertTrue(bothFirstLookups.await(5, TimeUnit.SECONDS))
			releaseFirstLookups.countDown()
			assertSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
			assertEquals(1, createCalls.get())
			assertTrue(firstLookupCount.get() >= 4)
		} finally {
			releaseFirstLookups.countDown()
			workers.shutdownNow()
		}
	}

	@Test
	fun securityComponentsRecognizesOnlyExactMainProcessName() {
		assertTrue(SecurityComponents.isMainProcess("top.abdl_space.app", "top.abdl_space.app"))
		assertFalse(SecurityComponents.isMainProcess("top.abdl_space.app", "top.abdl_space.app:pushcore"))
		assertFalse(SecurityComponents.isMainProcess("top.abdl_space.app", null))
	}

	@Test
	fun securityComponentsReturnsOneInterfaceSingletonAndFailsFastOffMainProcess() {
		val context = RuntimeEnvironment.getApplication()
		ShadowApplication.setProcessName(context.packageName)
		val first = SecurityComponents.createRepository(context)
		val second = SecurityComponents.createRepository(context)
		assertSame(first, second)

		ShadowApplication.setProcessName("${context.packageName}:pushcore")
		assertThrows(IllegalStateException::class.java) {
			SecurityComponents.createRepository(context)
		}
		ShadowApplication.setProcessName(context.packageName)
	}

	private class FixedStore(initial: StoredSecurityData) : SecurityStore {
		private var value = initial

		override suspend fun read(): SecurityStoreResult<StoredSecurityData> =
			SecurityStoreResult.Success(value)

		override suspend fun update(
			transform: (SecurityStoreResult<StoredSecurityData>) -> StoredSecurityData,
		): SecurityStoreResult<StoredSecurityData> {
			value = transform(SecurityStoreResult.Success(value))
			return SecurityStoreResult.Success(value)
		}
	}

	private object NoOpPinCipher : PinCipher {
		override fun encrypt(pin: CharArray): String = "enc"
		override fun decrypt(encryptedPin: String): CharArray = "1234".toCharArray()
	}

	private fun lockedData(attempts: Int) = StoredSecurityData(
		pinSecured = "enc:1234",
		lockStatus = LockMethodEntity.PIN_SECURED,
		pinOptions = PinOptionsEntity(
			digits = PinDigits.Code4.value,
			trials = PinTrials.Trials3.trials,
			timeout = PinTimeout.Timeout5.timeoutMs,
		),
		invalidPinStatus = InvalidPinStatusEntity(
			attempts = attempts,
			lastAttemptSinceBootMs = 5_000L,
			lockBootMarker = "boot-previous",
		),
	)
}
