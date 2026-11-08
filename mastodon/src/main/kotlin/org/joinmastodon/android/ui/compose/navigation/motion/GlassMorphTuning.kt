package org.joinmastodon.android.ui.compose.navigation.motion

import kotlin.math.max

internal data class GlassShrink(val duration: Float? = null, val damping: Float? = null, val scale: Float? = null)
internal data class GlassAbsorb(val duration: Float? = null, val damping: Float? = null)

/** Open/close spring selection derived from the (optional) per-menu overrides. */
internal class GlassMorphTuning(shrink: GlassShrink? = null, absorb: GlassAbsorb? = null) {
	private val shrinkDuration = max(shrink?.duration ?: DefaultShrinkDuration, MinimumDuration)
	private val shrinkDamping = shrink?.damping?.coerceIn(MinimumDamping, 1f)
	val shrinkScale = (shrink?.scale ?: DefaultShrinkScale).coerceIn(.05f, 1f)
	val collapse = GlassSpringSpec(shrinkDamping ?: 1f, shrinkDuration)
	val eject = GlassSpringSpec(shrinkDamping ?: DefaultEjectDamping, shrinkDuration * EjectRatio)
	val absorb = GlassSpringSpec(
		absorb?.damping?.coerceIn(MinimumDamping, 1f) ?: DefaultAbsorbDamping,
		max(absorb?.duration ?: DefaultAbsorbDuration, MinimumDuration),
	)
	val neckHold = max(shrinkDuration / DefaultShrinkDuration, 1f)

	private companion object {
		const val DefaultShrinkDuration = .21f
		const val DefaultShrinkScale = .25f
		const val DefaultEjectDamping = .75f
		const val DefaultAbsorbDuration = .35f
		const val DefaultAbsorbDamping = .8f
		const val EjectRatio = .32f / .21f
		const val MinimumDuration = .02f
		const val MinimumDamping = .1f
	}
}
