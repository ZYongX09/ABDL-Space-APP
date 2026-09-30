package org.joinmastodon.android.security

import kotlinx.coroutines.runBlocking
import org.joinmastodon.android.security.data.SecurityResult
import org.joinmastodon.android.security.domain.LockMethod
import org.joinmastodon.android.security.ui.FakeSecurityRepository
import org.joinmastodon.android.security.ui.securityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthTrackerTest {
	private class Clock(var time: Long = 1_000L) : ElapsedRealtimeClock {
		override fun now() = time
	}

	@Test fun unknownColdStartIsNotAllowedWithoutIo() = runBlocking {
		val tracker = AuthTracker(FakeSecurityRepository(SecurityResult.Success(securityState(LockMethod.Pin))), Clock())
		assertFalse(tracker.isAccessKnownValid())
		assertEquals(AuthenticationStatus.Expired, tracker.shouldAuthenticate())
		tracker.onAuthenticated()
		assertEquals(AuthenticationStatus.Valid, tracker.shouldAuthenticate())
	}

	@Test fun noLockCanBeConfirmedOnlyBySuccessfulRead() = runBlocking {
		val tracker = AuthTracker(FakeSecurityRepository(), Clock())
		assertFalse(tracker.isAccessKnownValid())
		assertEquals(AuthenticationStatus.Valid, tracker.shouldAuthenticate())
		assertTrue(tracker.isAccessKnownValid())
	}

	@Test fun bothGraceBoundaryAndSecondLockAreEnforced() = runBlocking {
		for (away in listOf(29_000L, 30_000L, 31_000L)) {
			val clock = Clock()
			val tracker = AuthTracker(FakeSecurityRepository(SecurityResult.Success(securityState(LockMethod.Pin))), clock)
			tracker.shouldAuthenticate()
			tracker.onAuthenticated()
			tracker.onMovingToBackground()
			clock.time += away
			tracker.onMovingToForeground()
			assertEquals(if (away <= 30_000L) AuthenticationStatus.Valid else AuthenticationStatus.Expired, tracker.shouldAuthenticate())
			tracker.onAuthenticated()
			tracker.onMovingToBackground()
			clock.time += 31_000L
			tracker.onMovingToForeground()
			assertEquals(AuthenticationStatus.Expired, tracker.shouldAuthenticate())
		}
	}

	@Test fun forcedAuthenticationCannotReuseOldGrace() = runBlocking {
		val clock = Clock()
		val tracker = AuthTracker(FakeSecurityRepository(SecurityResult.Success(securityState(LockMethod.Pin))), clock)
		tracker.onAuthenticated()
		tracker.onMovingToBackground()
		tracker.onAuthenticateScreen()
		clock.time += 1_000L
		tracker.onMovingToForeground()
		assertEquals(AuthenticationStatus.Expired, tracker.shouldAuthenticate())
	}

	@Test fun corruptStoreFailsClosedEvenWhenSessionWasAuthenticated() = runBlocking {
		val repository = FakeSecurityRepository(SecurityResult.Success(securityState(LockMethod.Pin)))
		val tracker = AuthTracker(repository, Clock())
		tracker.onAuthenticated()
		repository.stateResult = SecurityResult.Corrupted
		assertEquals(AuthenticationStatus.Expired, tracker.shouldAuthenticate())
	}

	@Test fun clockRollbackDoesNotGrantGraceAndAppRestartClearsSession() = runBlocking {
		val clock = Clock()
		val tracker = AuthTracker(FakeSecurityRepository(SecurityResult.Success(securityState(LockMethod.Pin))), clock)
		tracker.onAuthenticated()
		tracker.onMovingToBackground()
		clock.time = 0L
		tracker.onMovingToForeground()
		assertEquals(AuthenticationStatus.Expired, tracker.shouldAuthenticate())
		tracker.onAuthenticated()
		tracker.onAppCreate()
		assertFalse(tracker.isAccessKnownValid())
		assertEquals(AuthenticationStatus.Expired, tracker.shouldAuthenticate())
	}
}
