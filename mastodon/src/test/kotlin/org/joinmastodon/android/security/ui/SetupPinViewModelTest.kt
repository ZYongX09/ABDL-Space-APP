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
import org.joinmastodon.android.security.data.SecurityResult
import org.joinmastodon.android.security.domain.PinDigits
import org.joinmastodon.android.security.ui.setuppin.SetupPinStage
import org.joinmastodon.android.security.ui.setuppin.SetupPinViewModel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetupPinViewModelTest {
	@Test
	fun supportsFourAndSixDigitsAndSwitchingClearsInput() = runTest {
		withMainDispatcher {
			val repository = FakeSecurityRepository()
			val viewModel = SetupPinViewModel(repository, GateSecurityDelay())
			runCurrent()
			viewModel.digitEntered(1)
			viewModel.digitEntered(2)
			assertEquals(2, viewModel.uiState.value.enteredCount)

			viewModel.pinDigitsChanged(PinDigits.Code6)
			assertEquals(PinDigits.Code6, viewModel.uiState.value.digits)
			assertEquals(0, viewModel.uiState.value.enteredCount)
			assertEquals(0, viewModel.sensitiveBufferCountForDiagnostics())
		}
	}

	@Test
	fun transitionRejectsInputAndDuplicateLastDigit() = runTest {
		withMainDispatcher {
			val repository = FakeSecurityRepository()
			val delay = GateSecurityDelay()
			val viewModel = SetupPinViewModel(repository, delay)
			runCurrent()
			enter(viewModel, "1234")
			runCurrent()

			assertEquals(SetupPinStage.TransitionToConfirm, viewModel.uiState.value.stage)
			viewModel.digitEntered(9)
			viewModel.digitEntered(9)
			assertEquals(4, viewModel.uiState.value.enteredCount)
			assertEquals(0, repository.setupCalls)

			delay.release()
			runCurrent()
			assertEquals(SetupPinStage.EnterConfirm, viewModel.uiState.value.stage)
			assertEquals(0, viewModel.uiState.value.enteredCount)
		}
	}

	@Test
	fun matchingPinsPersistExactlyOnce() = runTest {
		withMainDispatcher {
			val repository = FakeSecurityRepository()
			val viewModel = SetupPinViewModel(repository, ImmediateSecurityDelay)
			runCurrent()
			enter(viewModel, "1234")
			runCurrent()
			assertEquals(SetupPinStage.EnterConfirm, viewModel.uiState.value.stage)
			enter(viewModel, "1234")
			viewModel.digitEntered(4)
			runCurrent()

			assertEquals(SetupPinStage.Finished, viewModel.uiState.value.stage)
			assertEquals(1, repository.setupCalls)
			assertArrayEquals("1234".toCharArray(), repository.setupPinCopy)
			assertEquals(PinDigits.Code4, repository.setupDigits)
			assertEquals(0, viewModel.sensitiveBufferCountForDiagnostics())
		}
	}

	@Test
	fun mismatchClearsConfirmationAndShowsError() = runTest {
		withMainDispatcher {
			val repository = FakeSecurityRepository()
			val viewModel = SetupPinViewModel(repository, ImmediateSecurityDelay)
			runCurrent()
			enter(viewModel, "1234")
			runCurrent()
			enter(viewModel, "1235")
			runCurrent()

			assertEquals(SetupPinStage.EnterConfirm, viewModel.uiState.value.stage)
			assertEquals(R.string.security_error_no_match, viewModel.uiState.value.errorMessageRes)
			assertEquals(0, viewModel.uiState.value.enteredCount)
			assertEquals(0, repository.setupCalls)
			assertEquals(4, viewModel.sensitiveBufferCountForDiagnostics())
		}
	}

	@Test
	fun persistFailureReturnsToConfirmationWithoutPretendingSuccess() = runTest {
		withMainDispatcher {
			val repository = FakeSecurityRepository().apply { setupResult = SecurityResult.StoreError }
			val viewModel = SetupPinViewModel(repository, ImmediateSecurityDelay)
			runCurrent()
			enter(viewModel, "1234")
			runCurrent()
			enter(viewModel, "1234")
			runCurrent()

			assertEquals(SetupPinStage.EnterConfirm, viewModel.uiState.value.stage)
			assertEquals(R.string.security_error_store_unavailable, viewModel.uiState.value.errorMessageRes)
			assertEquals(1, repository.setupCalls)
			assertEquals(4, viewModel.sensitiveBufferCountForDiagnostics())
		}
	}

	@Test
	fun publicStateNeverContainsPinAndSensitiveBuffersCanBeCleared() = runTest {
		withMainDispatcher {
			val repository = FakeSecurityRepository()
			val viewModel = SetupPinViewModel(repository, GateSecurityDelay())
			runCurrent()
			viewModel.digitEntered(8)
			viewModel.digitEntered(7)
			val stateText = viewModel.uiState.value.toString()
			assertFalse(stateText.contains("[8, 7]"))
			assertFalse(stateText.contains("pin="))
			assertNull(viewModel.uiState.value.errorMessageRes)
			assertEquals(2, viewModel.sensitiveBufferCountForDiagnostics())

			viewModel.clearSensitiveForDiagnostics()
			assertEquals(0, viewModel.sensitiveBufferCountForDiagnostics())
		}
	}

	private fun enter(viewModel: SetupPinViewModel, pin: String) {
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
