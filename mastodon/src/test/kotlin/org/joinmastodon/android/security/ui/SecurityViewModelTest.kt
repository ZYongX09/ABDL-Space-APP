/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) Two Factor Authentication Service, Inc.
 * Tests authored for ABDL Space; behavior is checked against the adapted 2FAS 5.6.0 flow.
 * Modified for ABDL Space, 2026.
 */
package org.joinmastodon.android.security.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.joinmastodon.android.R
import org.joinmastodon.android.security.data.SecurityResult
import org.joinmastodon.android.security.domain.LockMethod
import org.joinmastodon.android.security.domain.PinTimeout
import org.joinmastodon.android.security.domain.PinTrials
import org.joinmastodon.android.security.ui.security.SecurityEffect
import org.joinmastodon.android.security.ui.security.SecurityViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SecurityViewModelTest {
	@Test
	fun reflectsNoLockAndPinStatesOnRefresh() = runTest {
		withMainDispatcher {
			val repository = FakeSecurityRepository()
			val viewModel = SecurityViewModel(repository)
			runCurrent()
			assertFalse(viewModel.uiState.value.hasPin)
			assertEquals(LockMethod.NoLock, viewModel.uiState.value.lockMethod)

			repository.stateResult = SecurityResult.Success(
				securityState(
					lockMethod = LockMethod.Pin,
					trials = PinTrials.Trials5,
					timeout = PinTimeout.Timeout10,
				),
			)
			viewModel.refresh()
			runCurrent()
			assertTrue(viewModel.uiState.value.hasPin)
			assertEquals(PinTrials.Trials5, viewModel.uiState.value.pinTrials)
			assertEquals(PinTimeout.Timeout10, viewModel.uiState.value.pinTimeout)
		}
	}

	@Test
	fun policyUpdatesPersistAndFailuresDoNotPretendSuccess() = runTest {
		withMainDispatcher {
			val repository = FakeSecurityRepository(
				SecurityResult.Success(securityState(lockMethod = LockMethod.Pin)),
			)
			val viewModel = SecurityViewModel(repository)
			runCurrent()
			viewModel.updatePinTrials(PinTrials.Trials10)
			runCurrent()
			assertEquals(1, repository.policyCalls)
			assertEquals(PinTrials.Trials10, viewModel.uiState.value.pinTrials)

			repository.policyResult = SecurityResult.StoreError
			viewModel.updatePinTimeout(PinTimeout.Timeout10)
			runCurrent()
			assertEquals(2, repository.policyCalls)
			assertEquals(PinTimeout.Timeout5, viewModel.uiState.value.pinTimeout)
			assertEquals(R.string.security_error_store_unavailable, viewModel.uiState.value.errorMessageRes)
		}
	}

	@Test
	fun biometricEnableFlowUsesControllerAndIgnoresRepositoryWrites() = runTest {
		withMainDispatcher {
			val repository = FakeSecurityRepository(
				SecurityResult.Success(securityState(lockMethod = LockMethod.Pin)),
			)
			val controller = FakeBiometricController(BiometricUiResult.Enabled)
			val viewModel = SecurityViewModel(repository, controller)
			runCurrent()
			viewModel.requestBiometricEnable()
			runCurrent()

			assertEquals(1, controller.calls)
			assertEquals(0, repository.policyCalls)
			assertEquals(0, repository.setupCalls)
			assertEquals(0, repository.changeCalls)
			assertEquals(0, repository.disableCalls)
			assertEquals(LockMethod.Pin, viewModel.uiState.value.lockMethod)

			viewModel.onBiometricEnabled()
			advanceUntilIdle()
			assertEquals(1, repository.enableCalls)
			assertEquals(LockMethod.Biometrics, viewModel.uiState.value.lockMethod)
		}
	}

	@Test
	fun biometricUnavailableResultEmitsEffectWithoutStateChange() = runTest {
		withMainDispatcher {
			val repository = FakeSecurityRepository(
				SecurityResult.Success(securityState(lockMethod = LockMethod.Pin)),
			)
			val controller = FakeBiometricController(BiometricUiResult.Unavailable)
			val viewModel = SecurityViewModel(repository, controller)
			runCurrent()
			val effect = async { viewModel.effects.first() }
			viewModel.requestBiometricEnable()
			runCurrent()

			assertEquals(SecurityEffect.BiometricUnavailable, effect.await())
			assertEquals(LockMethod.Pin, viewModel.uiState.value.lockMethod)
		}
	}

	@Test
	fun disableBiometricDemotesViaRepository() = runTest {
		withMainDispatcher {
			val repository = FakeSecurityRepository(
				SecurityResult.Success(securityState(lockMethod = LockMethod.Biometrics)),
			)
			val viewModel = SecurityViewModel(repository, FakeBiometricController(BiometricUiResult.Enabled))
			runCurrent()
			viewModel.disableBiometric()
			advanceUntilIdle()

			assertEquals(1, repository.demoteCalls)
			assertEquals(LockMethod.Pin, viewModel.uiState.value.lockMethod)
		}
	}

	@Test
	fun noPinBiometricRequestIsIgnored() = runTest {
		withMainDispatcher {
			val repository = FakeSecurityRepository()
			val viewModel = SecurityViewModel(repository)
			runCurrent()
			viewModel.requestBiometricEnable()
			runCurrent()
			assertEquals(LockMethod.NoLock, viewModel.uiState.value.lockMethod)
			assertEquals(0, repository.policyCalls)
		}
	}

	private class FakeBiometricController(
		private val result: BiometricUiResult,
	) : BiometricUiController {
		var calls = 0
			private set

		override fun requestEnable(onResult: (BiometricUiResult) -> Unit) {
			calls += 1
			onResult(result)
		}
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
