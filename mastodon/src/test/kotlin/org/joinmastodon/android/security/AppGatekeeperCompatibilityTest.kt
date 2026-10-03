package org.joinmastodon.android.security

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.joinmastodon.android.security.data.SecurityResult
import org.joinmastodon.android.security.domain.LockMethod
import org.joinmastodon.android.security.ui.FakeSecurityRepository
import org.joinmastodon.android.security.ui.securityState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28, 30, 31, 33, 35], application = Application::class)
class AppGatekeeperCompatibilityTest {
	private val dispatcher = UnconfinedTestDispatcher()
	@Before fun setup() = Dispatchers.setMain(dispatcher)
	@After fun teardown() = Dispatchers.resetMain()

	private fun fixture(): Triple<Activity, AuthTracker, AppGatekeeper> {
		val activity = Robolectric.buildActivity(Activity::class.java).create().get()
		activity.setContentView(TextView(activity).apply { text = "private" })
		val tracker = AuthTracker(FakeSecurityRepository(SecurityResult.Success(securityState(LockMethod.Pin))), ElapsedRealtimeClock { 1_000L })
		val gate = AppGatekeeper(RuntimeEnvironment.getApplication(), tracker, dispatcher, dispatcher, {})
		gate.onActivityCreated(activity, null)
		return Triple(activity, tracker, gate)
	}

	@Test fun callbacksWaitForUnlockAndResumeAndExecuteOnlyOnce() {
		val (activity, tracker, gate) = fixture()
		var calls = 0
		gate.runAfterUnlock(activity, Runnable { calls++ })
		gate.onActivityResumed(activity)
		assertEquals(0, calls)
		assertNotNull(activity.window.decorView.findViewWithTag<View>(AppGatekeeper.SHIELD_TAG))
		gate.onActivityPaused(activity)
		gate.onUnlocked()
		assertEquals(0, calls)
		gate.onActivityResumed(activity)
		assertEquals(1, calls)
		gate.onActivityResumed(activity)
		assertEquals(1, calls)
		assertNull(activity.window.decorView.findViewWithTag<View>(AppGatekeeper.SHIELD_TAG))
		assertTrue(tracker.isAccessKnownValid())
	}

	@Test fun repeatedLockSessionsCanLaunchAgain() = runBlocking {
		val (activity, tracker, _) = fixture()
		var launches = 0
		val gate = AppGatekeeper(RuntimeEnvironment.getApplication(), tracker, dispatcher, dispatcher, { launches++ })
		gate.onActivityCreated(activity, null)
		gate.onActivityResumed(activity)
		gate.onActivityResumed(activity)
		assertEquals(1, launches)
		gate.onLockFinishedWithoutSuccess()
		gate.onActivityResumed(activity)
		assertEquals(2, launches)
		gate.onUnlocked()
		tracker.onAuthenticateScreen()
		gate.onActivityResumed(activity)
		assertEquals(3, launches)
	}

	@Test fun cancelledAndDestroyedOwnersCannotRunDeferredWork() {
		val (activity, _, gate) = fixture()
		var calls = 0
		gate.runAfterUnlock(activity, Runnable { calls++ })
		gate.onActivityResumed(activity)
		gate.onLockFinishedWithoutSuccess()
		gate.onUnlocked()
		gate.onActivityResumed(activity)
		assertEquals(0, calls)
		gate.runAfterUnlock(activity, Runnable { calls++ })
		gate.onActivityPaused(activity)
		gate.onActivityDestroyed(activity)
		gate.onUnlocked()
		assertEquals(1, calls)
	}

	@Test fun shieldHidesUnderlyingAccessibilityAndRestoresIt() {
		val (activity, _, gate) = fixture()
		val content = activity.window.decorView as ViewGroup
		val protectedRoot = content.getChildAt(0)
		assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, protectedRoot.importantForAccessibility)
		gate.onActivityResumed(activity)
		gate.onUnlocked()
		assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, protectedRoot.importantForAccessibility)
	}
}
