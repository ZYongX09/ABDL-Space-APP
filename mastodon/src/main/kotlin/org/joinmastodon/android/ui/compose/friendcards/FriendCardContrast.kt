package org.joinmastodon.android.ui.compose.friendcards

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.pow

internal enum class FriendCardContrastControl { Close, Manage }
internal enum class FriendCardContrastInk { Unknown, White, Black }

/** Relative (linear sRGB) luminance, not the card/theme colour or the cover's average. */
internal fun friendCardPatchLuminance(pixels: IntArray): Float? {
    if (pixels.isEmpty()) return null
    var total = 0.0
    for (pixel in pixels) {
        // The recorded opaque base must have reached the patch. A transparent readback is invalid.
        if ((pixel ushr 24) != 255) return null
        fun linear(shift: Int): Double {
            val value = ((pixel ushr shift) and 255) / 255.0
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        total += .2126 * linear(16) + .7152 * linear(8) + .0722 * linear(0)
    }
    return (total / pixels.size).toFloat()
}

/** Independent Schmitt triggers around the black/white contrast crossover (L ≈ .179). */
internal class FriendCardContrastController {
    private val inks = Array(FriendCardContrastControl.entries.size) { FriendCardContrastInk.Unknown }

    fun update(control: FriendCardContrastControl, luminance: Float): FriendCardContrastInk {
        if (!luminance.isFinite() || luminance !in 0f..1f) return inks[control.ordinal]
        val blackContrast = (luminance + .05f) / .05f
        val whiteContrast = 1.05f / (luminance + .05f)
        val current = inks[control.ordinal]
        val next = when (current) {
            FriendCardContrastInk.Unknown -> if (blackContrast > whiteContrast) FriendCardContrastInk.Black else FriendCardContrastInk.White
            FriendCardContrastInk.White -> if (blackContrast > whiteContrast * 1.25f) FriendCardContrastInk.Black else current
            FriendCardContrastInk.Black -> if (whiteContrast > blackContrast * 1.25f) FriendCardContrastInk.White else current
        }
        inks[control.ordinal] = next
        return next
    }
}

/**
 * One event-driven consumer, at most one pending event and one bounded capture batch. No timer runs
 * while idle. Content invalidations during a readback are coalesced into the next batch, never jobs.
 * Kept independent of Android/Compose so cancellation, throttling and failure are deterministic tests.
 */
internal class FriendCardContrastPump(
    private val nowMillis: () -> Long,
    private val capture: suspend () -> Unit,
    private val onFailure: () -> Unit,
    private val intervalMillis: Long = 100L,
    private val timeoutMillis: Long = 500L,
) {
    private val dirty = Channel<Unit>(Channel.CONFLATED)
    private var consumer: Job? = null
    private var closed = false
    private var failed = false

    fun invalidate() { if (!closed && !failed) dirty.trySend(Unit) }

    suspend fun run() = coroutineScope {
        if (closed || failed) return@coroutineScope
        check(consumer == null)
        consumer = currentCoroutineContext()[Job]
        var lastCapture = nowMillis() - intervalMillis
        invalidate()
        try {
            for (event in dirty) {
                delay((intervalMillis - (nowMillis() - lastCapture)).coerceAtLeast(0L))
                dirty.tryReceive() // Conflated channel: drain the one newer event before sampling.
                currentCoroutineContext().ensureActive()
                lastCapture = nowMillis()
                val completed = try {
                    withTimeoutOrNull(timeoutMillis) { capture(); nowMillis() - lastCapture < timeoutMillis } ?: false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                } catch (_: LinkageError) {
                    false
                }
                if (!completed) {
                    failed = true
                    onFailure()
                    break // Unsupported renderer/readback: no retries until a new composition.
                }
            }
        } finally {
            consumer = null
        }
    }

    fun dispose() {
        closed = true
        dirty.close()
        consumer?.cancel()
    }
}
