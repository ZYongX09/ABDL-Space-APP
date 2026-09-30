package org.joinmastodon.android.security.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.joinmastodon.android.R
import org.joinmastodon.android.security.data.BiometricKeyProvider
import org.joinmastodon.android.security.data.SecurityResult
import org.joinmastodon.android.security.domain.LockMethod
import org.joinmastodon.android.security.domain.PinTimeout
import org.joinmastodon.android.security.domain.PinTrials
import org.joinmastodon.android.security.ui.security.SecurityViewModel
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@OptIn(ExperimentalCoroutinesApi::class)
class SecurityViewModelTest {
	@Test fun reflectsNoLockAndPinStatesOnRefresh() = runTest { withMain {
		val repository = FakeSecurityRepository()
		val vm = SecurityViewModel(repository, ioDispatcher = StandardTestDispatcher(testScheduler))
		advanceUntilIdle()
		assertFalse(vm.uiState.value.hasPin)
		repository.stateResult = SecurityResult.Success(securityState(LockMethod.Pin, trials = PinTrials.Trials5, timeout = PinTimeout.Timeout10))
		vm.refresh(); advanceUntilIdle()
		assertTrue(vm.uiState.value.hasPin)
		assertEquals(PinTrials.Trials5, vm.uiState.value.pinTrials)
	} }

	@Test fun policySuccessAndFailureKeepActualState() = runTest { withMain {
		val repository = FakeSecurityRepository(SecurityResult.Success(securityState(LockMethod.Pin)))
		val vm = SecurityViewModel(repository, ioDispatcher = StandardTestDispatcher(testScheduler))
		advanceUntilIdle(); vm.updatePinTrials(PinTrials.Trials10); advanceUntilIdle()
		assertEquals(PinTrials.Trials10, vm.uiState.value.pinTrials)
		repository.policyResult = SecurityResult.StoreError
		vm.updatePinTimeout(PinTimeout.Timeout10); advanceUntilIdle()
		assertEquals(PinTimeout.Timeout5, vm.uiState.value.pinTimeout)
		assertEquals(R.string.security_error_store_unavailable, vm.uiState.value.errorMessageRes)
	} }

	@Test fun confirmedKeyIsPersistedOnlyOnceWithoutRecreation() = runTest { withMain {
		val repository = FakeSecurityRepository(SecurityResult.Success(securityState(LockMethod.Pin)))
		val keys = Keys()
		val vm = SecurityViewModel(repository, keys, StandardTestDispatcher(testScheduler))
		advanceUntilIdle(); vm.onBiometricEnabled(); vm.onBiometricEnabled(); advanceUntilIdle()
		assertEquals(1, repository.enableCalls)
		assertEquals(0, keys.creates)
		assertEquals(LockMethod.Biometrics, vm.uiState.value.lockMethod)
	} }

	@Test fun missingKeyAndWriteFailureNeverEnableAndRollbackKey() = runTest { withMain {
		val repository = FakeSecurityRepository(SecurityResult.Success(securityState(LockMethod.Pin)))
		val keys = Keys().apply { key = null }
		val vm = SecurityViewModel(repository, keys, StandardTestDispatcher(testScheduler))
		advanceUntilIdle(); vm.onBiometricEnabled(); advanceUntilIdle()
		assertEquals(0, repository.enableCalls)
		assertEquals(LockMethod.Pin, vm.uiState.value.lockMethod)
		keys.key = SecretKeySpec(ByteArray(32), "AES")
		repository.enableResult = SecurityResult.StoreError
		vm.onBiometricEnabled(); advanceUntilIdle()
		assertEquals(1, repository.enableCalls)
		assertTrue(keys.deletes >= 2)
		assertNull(keys.key)
		assertEquals(LockMethod.Pin, vm.uiState.value.lockMethod)
	} }

	@Test fun disableDemotesAndDeletesKeyOnlyAfterSuccess() = runTest { withMain {
		val repository = FakeSecurityRepository(SecurityResult.Success(securityState(LockMethod.Biometrics)))
		val keys = Keys()
		val vm = SecurityViewModel(repository, keys, StandardTestDispatcher(testScheduler))
		advanceUntilIdle()
		repository.demoteResult = SecurityResult.StoreError
		vm.disableBiometric(); advanceUntilIdle()
		assertEquals(0, keys.deletes)
		repository.demoteResult = SecurityResult.Success(securityState(LockMethod.Pin))
		vm.disableBiometric(); advanceUntilIdle()
		assertEquals(1, keys.deletes)
		assertEquals(LockMethod.Pin, vm.uiState.value.lockMethod)
	} }

	@Test fun noPinCannotEnableBiometrics() = runTest { withMain {
		val repository = FakeSecurityRepository()
		val vm = SecurityViewModel(repository, Keys(), StandardTestDispatcher(testScheduler))
		advanceUntilIdle(); vm.onBiometricEnabled(); advanceUntilIdle()
		assertEquals(0, repository.enableCalls)
	} }

	private class Keys : BiometricKeyProvider {
		var key: SecretKey? = SecretKeySpec(ByteArray(32), "AES")
		var creates = 0
		var deletes = 0
		override fun loadSecretKey() = key
		override fun createSecretKey(): SecretKey { creates++; return key!! }
		override fun deleteSecretKey() { deletes++; key = null }
	}

	private suspend fun TestScope.withMain(block: suspend () -> Unit) {
		Dispatchers.setMain(StandardTestDispatcher(testScheduler))
		try { block() } finally { Dispatchers.resetMain() }
	}
}
