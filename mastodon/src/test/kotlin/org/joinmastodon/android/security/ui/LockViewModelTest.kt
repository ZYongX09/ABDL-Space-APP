package org.joinmastodon.android.security.ui

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.joinmastodon.android.security.AuthTracker
import org.joinmastodon.android.security.data.SecurityResult
import org.joinmastodon.android.security.domain.LockMethod
import org.joinmastodon.android.security.domain.PinVerificationResult
import org.joinmastodon.android.security.domain.InvalidPinStatus
import org.joinmastodon.android.security.ui.lock.LockViewModel
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LockViewModelTest {
	@Test fun inputCountChangesBackspaceWorksAndSuccessCompletesOnce() = runTest {
		withMain {
			val repository = FakeSecurityRepository(SecurityResult.Success(securityState(LockMethod.Pin)))
			val tracker = AuthTracker(repository, { 1_000L })
			val vm = LockViewModel(repository, tracker, ImmediateSecurityDelay, StandardTestDispatcher(testScheduler))
			advanceUntilIdle()
			vm.digitEntered(1); vm.digitEntered(2)
			assertEquals(2, vm.uiState.value.enteredCount)
			vm.backspace()
			assertEquals(1, vm.uiState.value.enteredCount)
			listOf(2, 3, 4).forEach(vm::digitEntered)
			vm.digitEntered(9)
			advanceUntilIdle()
			assertTrue(vm.uiState.value.finished)
			assertEquals(1, repository.verifyCalls)
			assertEquals(0, vm.uiState.value.enteredCount)
			vm.digitEntered(9)
			advanceUntilIdle()
			assertEquals(1, repository.verifyCalls)
			assertFalse(vm.uiState.value.toString().contains("1234"))
		}
	}

	@Test fun blockedInputCannotInvokeVerificationAndExpiryRefreshReenablesIt() = runTest {
		withMain {
			val blocked = InvalidPinStatus.blocked(3, 1_000L, "boot", 60_000L)
			val repository = FakeSecurityRepository(SecurityResult.Success(securityState(LockMethod.Pin, invalidPinStatus = blocked)))
			val vm = LockViewModel(repository, AuthTracker(repository, { 1_000L }), ImmediateSecurityDelay, StandardTestDispatcher(testScheduler))
			advanceUntilIdle()
			listOf(1, 2, 3, 4).forEach(vm::digitEntered)
			assertEquals(0, repository.verifyCalls)
			repository.stateResult = SecurityResult.Success(securityState(LockMethod.Pin))
			vm.refresh(); advanceUntilIdle()
			vm.digitEntered(1)
			assertEquals(1, vm.uiState.value.enteredCount)
		}
	}

	@Test fun biometricStoreFailureDoesNotFinishAndRepeatedSuccessIsIgnored() = runTest {
		withMain {
			val repository = FakeSecurityRepository(SecurityResult.Success(securityState(LockMethod.Biometrics)))
			val vm = LockViewModel(repository, AuthTracker(repository, { 1_000L }), ImmediateSecurityDelay, StandardTestDispatcher(testScheduler))
			advanceUntilIdle()
			repository.stateResult = SecurityResult.StoreError
			vm.onBiometricsVerified(); advanceUntilIdle()
			assertFalse(vm.uiState.value.finished)
			repository.stateResult = SecurityResult.Success(securityState(LockMethod.Biometrics))
			vm.refresh(); advanceUntilIdle()
			vm.onBiometricsVerified(); vm.onBiometricsVerified(); advanceUntilIdle()
			assertTrue(vm.uiState.value.finished)
		}
	}

	@Test fun wrongPinClearsCountAndCorruptionDisablesInput() = runTest {
		withMain {
			val repository = FakeSecurityRepository(SecurityResult.Success(securityState(LockMethod.Pin)))
			repository.verifyResult = PinVerificationResult.Wrong(InvalidPinStatus(attempts = 1))
			val vm = LockViewModel(repository, AuthTracker(repository, { 1_000L }), ImmediateSecurityDelay, StandardTestDispatcher(testScheduler))
			advanceUntilIdle()
			listOf(1, 2, 3, 4).forEach(vm::digitEntered)
			advanceUntilIdle()
			assertEquals(0, vm.uiState.value.enteredCount)
			assertFalse(vm.uiState.value.finished)
			repository.stateResult = SecurityResult.Corrupted
			vm.refresh(); advanceUntilIdle()
			vm.digitEntered(1)
			assertEquals(0, vm.uiState.value.enteredCount)
			assertFalse(vm.uiState.value.ready)
		}
	}

	private suspend fun TestScope.withMain(block: suspend () -> Unit) {
		Dispatchers.setMain(StandardTestDispatcher(testScheduler))
		try { block() } finally { Dispatchers.resetMain() }
	}
}
