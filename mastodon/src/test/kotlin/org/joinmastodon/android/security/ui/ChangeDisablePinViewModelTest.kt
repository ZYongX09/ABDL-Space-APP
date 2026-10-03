/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Tests authored for ABDL Space; behavior is checked against the adapted 2FAS 5.6.0 flow.
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.joinmastodon.android.R
import org.joinmastodon.android.security.data.PinProtectedMutationResult
import org.joinmastodon.android.security.data.SecurityResult
import org.joinmastodon.android.security.domain.InvalidPinStatus
import org.joinmastodon.android.security.domain.LockMethod
import org.joinmastodon.android.security.domain.PinDigits
import org.joinmastodon.android.security.domain.PinVerificationResult
import org.joinmastodon.android.security.domain.StoreErrorReason
import org.joinmastodon.android.security.ui.changepin.ChangePinStage
import org.joinmastodon.android.security.ui.changepin.ChangePinViewModel
import org.joinmastodon.android.security.ui.disablepin.DisablePinViewModel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChangeDisablePinViewModelTest {
	@Test
	fun changeKeepsOldPinPrivateAndMutatesOnceAfterNewPinConfirmation() = runTest {
		withMainDispatcher {
			val repository = lockedRepository()
			val viewModel = ChangePinViewModel(repository, ImmediateSecurityDelay)
			runCurrent()
			enter(viewModel, "1234")
			runCurrent()
			assertEquals(ChangePinStage.EnterNew, viewModel.uiState.value.stage)
			assertFalse(viewModel.uiState.value.toString().contains("1234"))
			assertEquals(4, viewModel.sensitiveBufferCountForDiagnostics())

			viewModel.pinDigitsChanged(PinDigits.Code6)
			enter(viewModel, "654321")
			runCurrent()
			assertEquals(ChangePinStage.EnterConfirm, viewModel.uiState.value.stage)
			enter(viewModel, "654321")
			viewModel.digitEntered(1)
			runCurrent()

			assertEquals(ChangePinStage.Finished, viewModel.uiState.value.stage)
			assertEquals(1, repository.verifyCalls)
			assertEquals(1, repository.changeCalls)
			assertArrayEquals("1234".toCharArray(), repository.changeCurrentPinCopy)
			assertArrayEquals("654321".toCharArray(), repository.changeNewPinCopy)
			assertEquals(PinDigits.Code6, repository.changeDigits)
			assertEquals(0, viewModel.sensitiveBufferCountForDiagnostics())
		}
	}

	@Test
	fun changeMismatchDoesNotCallRepositoryAndClearsConfirmation() = runTest {
		withMainDispatcher {
			val repository = lockedRepository()
			val viewModel = ChangePinViewModel(repository, ImmediateSecurityDelay)
			runCurrent()
			enter(viewModel, "1234")
			runCurrent()
			enter(viewModel, "5678")
			runCurrent()
			enter(viewModel, "5679")
			runCurrent()

			assertEquals(ChangePinStage.EnterConfirm, viewModel.uiState.value.stage)
			assertEquals(R.string.security_error_no_match, viewModel.uiState.value.errorMessageRes)
			assertEquals(0, repository.changeCalls)
			assertEquals(8, viewModel.sensitiveBufferCountForDiagnostics())
		}
	}

	@Test
	fun changeRejectsWrongBlockedInvalidAndStoreErrorsBeforeNewPinStage() = runTest {
		val cases = listOf(
			PinVerificationResult.Wrong(InvalidPinStatus(attempts = 1)) to R.string.security__pin_error_incorrect,
			PinVerificationResult.Blocked(blockedStatus()) to R.string.security__too_many_attempts_try_again_after,
			PinVerificationResult.InvalidInput to R.string.security_error_invalid_input,
			PinVerificationResult.StoreError(StoreErrorReason.Corrupted) to R.string.security_error_store_corrupted,
			PinVerificationResult.StoreError(StoreErrorReason.Unavailable) to R.string.security_error_store_unavailable,
		)
		for ((result, expectedMessage) in cases) {
			withMainDispatcher {
				val repository = lockedRepository().apply { verifyResult = result }
				val viewModel = ChangePinViewModel(repository, ImmediateSecurityDelay)
				runCurrent()
				enter(viewModel, "1234")
				runCurrent()
				assertEquals(expectedMessage, viewModel.uiState.value.errorMessageRes)
				assertEquals(ChangePinStage.EnterCurrent, viewModel.uiState.value.stage)
				assertEquals(1, repository.verifyCalls)
				assertEquals(0, repository.changeCalls)
				assertEquals(0, viewModel.sensitiveBufferCountForDiagnostics())
			}
		}
	}

	@Test
	fun changeMapsAtomicMutationErrorsAfterSuccessfulOldPinVerification() = runTest {
		val cases = listOf(
			PinProtectedMutationResult.Wrong(InvalidPinStatus(attempts = 1)) to R.string.security__pin_error_incorrect,
			PinProtectedMutationResult.Blocked(blockedStatus()) to R.string.security__too_many_attempts_try_again_after,
			PinProtectedMutationResult.InvalidInput to R.string.security_error_invalid_input,
			PinProtectedMutationResult.Corrupted to R.string.security_error_store_corrupted,
			PinProtectedMutationResult.StoreError to R.string.security_error_store_unavailable,
		)
		for ((result, expectedMessage) in cases) {
			withMainDispatcher {
				val repository = lockedRepository().apply { changeResult = result }
				val viewModel = ChangePinViewModel(repository, ImmediateSecurityDelay)
				runCurrent()
				enter(viewModel, "1234")
				runCurrent()
				enter(viewModel, "5678")
				runCurrent()
				enter(viewModel, "5678")
				runCurrent()
				assertEquals(expectedMessage, viewModel.uiState.value.errorMessageRes)
				assertEquals(ChangePinStage.EnterCurrent, viewModel.uiState.value.stage)
				assertEquals(1, repository.verifyCalls)
				assertEquals(1, repository.changeCalls)
				assertEquals(0, viewModel.sensitiveBufferCountForDiagnostics())
			}
		}
	}

	@Test
	fun disableMapsSuccessWrongBlockedInvalidAndStoreErrorsAndCallsOnce() = runTest {
		val cases = listOf(
			PinProtectedMutationResult.Success(securityState()) to null,
			PinProtectedMutationResult.Wrong(InvalidPinStatus(attempts = 1)) to R.string.security__pin_error_incorrect,
			PinProtectedMutationResult.Blocked(blockedStatus()) to R.string.security__too_many_attempts_try_again_after,
			PinProtectedMutationResult.InvalidInput to R.string.security_error_invalid_input,
			PinProtectedMutationResult.Corrupted to R.string.security_error_store_corrupted,
			PinProtectedMutationResult.StoreError to R.string.security_error_store_unavailable,
		)
		for ((result, expectedMessage) in cases) {
			withMainDispatcher {
				val repository = lockedRepository().apply { disableResult = result }
				val viewModel = DisablePinViewModel(repository)
				runCurrent()
				enter(viewModel, "1234")
				viewModel.digitEntered(4)
				runCurrent()
				assertEquals(1, repository.disableCalls)
				assertArrayEquals("1234".toCharArray(), repository.disablePinCopy)
				assertEquals(expectedMessage, viewModel.uiState.value.errorMessageRes)
				assertEquals(expectedMessage == null, viewModel.uiState.value.finished)
				assertEquals(0, viewModel.sensitiveBufferCountForDiagnostics())
			}
		}
	}

	@Test
	fun cancelAndDiagnosticClearEraseSensitiveBuffers() = runTest {
		withMainDispatcher {
			val repository = lockedRepository()
			val change = ChangePinViewModel(repository, GateSecurityDelay())
			val disable = DisablePinViewModel(repository)
			runCurrent()
			change.digitEntered(1)
			disable.digitEntered(2)
			assertTrue(change.sensitiveBufferCountForDiagnostics() > 0)
			assertTrue(disable.sensitiveBufferCountForDiagnostics() > 0)

			change.clearSensitiveForDiagnostics()
			disable.clearSensitiveForDiagnostics()
			assertEquals(0, change.sensitiveBufferCountForDiagnostics())
			assertEquals(0, disable.sensitiveBufferCountForDiagnostics())
		}
	}

	private fun lockedRepository() = FakeSecurityRepository(
		SecurityResult.Success(securityState(lockMethod = LockMethod.Pin)),
	)

	private fun blockedStatus() = InvalidPinStatus.blocked(
		attempts = 3,
		lastAttemptSinceBootMs = 1,
		lockBootMarker = "boot",
		timeLeftMs = 120_000,
	)

	private fun enter(viewModel: ChangePinViewModel, pin: String) {
		pinDigits(pin).forEach(viewModel::digitEntered)
	}

	private fun enter(viewModel: DisablePinViewModel, pin: String) {
		pinDigits(pin).forEach(viewModel::digitEntered)
	}

	private suspend fun TestScope.withMainDispatcher(block: suspend () -> Unit) {
		val dispatcher = StandardTestDispatcher(testScheduler)
		Dispatchers.setMain(dispatcher)
		try {
			block()
		} finally {
			Dispatchers.resetMain()
		}
	}
}
