package org.joinmastodon.android.ui.compose.friendcards

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Pure tests: these do not claim that Robolectric's software Canvas exercised a GPU readback. */
@OptIn(ExperimentalCoroutinesApi::class)
class FriendCardContrastTest {
    @Test fun relativeLuminanceRejectsMissingContentAndUsesActualPatchPixels() {
        assertEquals(0f, friendCardPatchLuminance(intArrayOf(0xFF000000.toInt()))!!, .0001f)
        assertEquals(1f, friendCardPatchLuminance(intArrayOf(0xFFFFFFFF.toInt()))!!, .0001f)
        assertEquals(.5f, friendCardPatchLuminance(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()))!!, .0001f)
        assertEquals(.21586f, friendCardPatchLuminance(intArrayOf(0xFF808080.toInt()))!!, .0001f)
        assertNull(friendCardPatchLuminance(intArrayOf()))
        assertNull(friendCardPatchLuminance(intArrayOf(0x00FFFFFF)))
        assertNull(friendCardPatchLuminance(intArrayOf(0x80FFFFFF.toInt())))
    }

    @Test fun controlsChooseTheirOwnHigherContrastInkAndDoNotFlickerNearCrossover() {
        val controller = FriendCardContrastController()
        assertEquals(FriendCardContrastInk.Black, controller.update(FriendCardContrastControl.Close, 1f))
        assertEquals(FriendCardContrastInk.White, controller.update(FriendCardContrastControl.Manage, 0f))
        // Same ambiguous luminance, different histories: each control retains its own state.
        for (value in listOf(.17f, .18f, .19f, .18f, .17f)) {
            assertEquals(FriendCardContrastInk.Black, controller.update(FriendCardContrastControl.Close, value))
            assertEquals(FriendCardContrastInk.White, controller.update(FriendCardContrastControl.Manage, value))
        }
        assertEquals(FriendCardContrastInk.White, controller.update(FriendCardContrastControl.Close, .10f))
        assertEquals(FriendCardContrastInk.Black, controller.update(FriendCardContrastControl.Manage, .30f))
        assertEquals(FriendCardContrastInk.White, controller.update(FriendCardContrastControl.Close, Float.NaN))
        assertEquals(FriendCardContrastInk.Black, controller.update(FriendCardContrastControl.Manage, -1f))
        // Linear luminance crossover is ~.179, NOT a theme-dependent .5 brightness heuristic.
        assertEquals(FriendCardContrastInk.Black,
            FriendCardContrastController().update(FriendCardContrastControl.Close, .20f))
    }

    @Test fun burstInvalidationsAreConflatedThrottledAndIdleHasNoReadbacks() = runTest {
        val capturedAt = mutableListOf<Long>()
        val pump = FriendCardContrastPump({ testScheduler.currentTime }, { capturedAt += testScheduler.currentTime }, { fail("unexpected failure") })
        val consumer = launch { pump.run() }
        runCurrent()
        assertEquals(listOf(0L), capturedAt)
        repeat(10_000) { pump.invalidate() }
        runCurrent()
        advanceTimeBy(99)
        runCurrent()
        assertEquals(listOf(0L), capturedAt)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0L, 100L), capturedAt)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("No periodic capture while idle", 2, capturedAt.size)
        pump.dispose()
        consumer.join()
    }

    @Test fun syntheticScrollingSwapsBrightAndDarkPatchesIndependentlyWithoutColourFeedback() = runTest {
        val controller = FriendCardContrastController()
        var scroll = 0
        var loaded = false
        var frames = 0
        var close = FriendCardContrastInk.Unknown
        var manage = FriendCardContrastInk.Unknown
        // A tiny synthetic content raster with alternate bright/dark stripes, sampled at each
        // control's own x/y. The pump is the production pump; no cover-average/theming stand-in.
        fun patch(x: Int): IntArray = IntArray(16 * 16) { index ->
            val y = index / 16 + scroll
            if (!loaded || (x + y / 16) % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val pump = FriendCardContrastPump({ testScheduler.currentTime }, {
            frames++
            close = controller.update(FriendCardContrastControl.Close, friendCardPatchLuminance(patch(0))!!)
            manage = controller.update(FriendCardContrastControl.Manage, friendCardPatchLuminance(patch(1))!!)
        }, { fail("unexpected failure") })
        val consumer = launch { pump.run() }
        runCurrent()
        assertEquals(FriendCardContrastInk.White, close)
        assertEquals(FriendCardContrastInk.White, manage)
        loaded = true
        pump.invalidate() // Async content/image draw after load, without a scroll gesture.
        advanceTimeBy(100)
        runCurrent()
        assertEquals(FriendCardContrastInk.White, close)
        assertEquals(FriendCardContrastInk.Black, manage)
        scroll = 16
        repeat(100) { pump.invalidate() }
        advanceTimeBy(100)
        runCurrent()
        assertEquals(FriendCardContrastInk.Black, close)
        assertEquals(FriendCardContrastInk.White, manage)
        assertEquals(3, frames)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals("Changing ink does not dirty its source", 3, frames)
        pump.dispose()
        consumer.join()
    }

    @Test fun disposeCancelsTheSingleInFlightCaptureAndNoCallbackSurvivesIt() = runTest {
        var started = 0
        var cancelled = 0
        var accepted = 0
        var failures = 0
        val pump = FriendCardContrastPump({ testScheduler.currentTime }, {
            started++
            try { awaitCancellation() } finally { cancelled++ }
            @Suppress("UNREACHABLE_CODE")
            accepted++
        }, { failures++ })
        val consumer = launch { pump.run() }
        runCurrent()
        repeat(1000) { pump.invalidate() }
        pump.dispose()
        runCurrent()
        consumer.join()
        pump.invalidate()
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(1, started)
        assertEquals(1, cancelled)
        assertEquals(0, accepted)
        assertEquals(0, failures)
    }

    @Test fun synchronousCaptureOverrunFailsTerminallyWithoutCoroutineTimeoutAdvancing() = runTest {
        var now = 0L
        var captures = 0
        var failures = 0
        val pump = FriendCardContrastPump({ now }, {
            captures++
            now += 501L // Model a blocking native call: no suspension or test-scheduler advance.
        }, { failures++ })
        val consumer = launch { pump.run() }
        runCurrent()
        assertEquals("The coroutine timeout never had a chance to fire", 0L, testScheduler.currentTime)
        assertEquals(1, captures)
        assertEquals(1, failures)
        assertTrue("An elapsed-time failure terminates the consumer", consumer.isCompleted)
        repeat(1000) { pump.invalidate() }
        advanceTimeBy(10_000)
        runCurrent()
        pump.run() // A lifecycle restart must not revive a terminally failed pump.
        assertEquals(1, captures)
        assertEquals(1, failures)
        pump.dispose()
    }

    @Test fun readbackFailureAndTimeoutDisableCaptureInsteadOfRetryingForever() = runTest {
        for (timeout in listOf(false, true)) {
            var captures = 0
            var failures = 0
            val pump = FriendCardContrastPump({ testScheduler.currentTime }, {
                captures++
                if (timeout) awaitCancellation() else error("unsupported renderer")
            }, { failures++ })
            val consumer = launch { pump.run() }
            runCurrent()
            advanceTimeBy(501)
            runCurrent()
            repeat(1000) { pump.invalidate() }
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(1, captures)
            assertEquals(1, failures)
            consumer.join()
            pump.dispose()
        }
    }
}
