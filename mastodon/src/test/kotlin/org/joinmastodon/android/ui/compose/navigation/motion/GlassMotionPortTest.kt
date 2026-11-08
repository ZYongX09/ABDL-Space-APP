package org.joinmastodon.android.ui.compose.navigation.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassMotionPortTest {
	@Test
	fun springOvershootsThenSettles() {
		val spring = GlassSpring()
		spring.target(1f, GlassSpringSpec(.5f, .45f))
		var peak = 0f
		repeat(240){ spring.advance(1f / 60f); peak = maxOf(peak, spring.value) }
		// The reference spring snaps once it is within .2 of the destination, so the visible
		// overshoot is small but always above 1 before settling.
		assertTrue("underdamped spring should overshoot, peak=$peak", peak > 1f)
		assertEquals(1f, spring.value, 0.0001f)
		assertTrue(spring.isSettled)
	}

	@Test
	fun jellySwellIsBoundedByFourDp() {
		val jelly = GlassJelly()
		jelly.down(10f, 10f)
		repeat(60){ jelly.advance(1f / 60f) }
		val swell = jelly.swell(height = 48f, density = 1f, amount = 1f)
		assertTrue("swell must grow", swell > 1f)
		assertTrue("swell must stay within 4dp/height, was $swell", swell <= 1f + 4f / 48f + 0.001f)
		assertTrue(jelly.glowValue > 0.99f)
	}

	@Test
	fun jellyMenuDeformStaysWithinDocumentedBounds() {
		val jelly = GlassJelly()
		jelly.down(0f, 0f)
		jelly.move(4000f, 4000f)
		repeat(120){ jelly.advance(1f / 60f) }
		val rect = GlassRect(0f, 0f, 248f, 300f)
		val cx = rect.centerX
		val cy = rect.centerY
		jelly.deformMenu(rect, awayX = 1f, awayY = 1f, density = 1f, amount = 1f)
		val widthDelta = rect.width - 248f
		val heightDelta = rect.height - 300f
		assertTrue("width delta $widthDelta must stay within ±16/±21", widthDelta <= 16f + 0.5f && widthDelta >= -21f - 0.5f)
		assertTrue("height delta $heightDelta must stay within ±16/±21", heightDelta <= 16f + 0.5f && heightDelta >= -21f - 0.5f)
		assertTrue("center shift must stay within ±21", kotlin.math.abs(rect.centerX - cx) <= 21f + 0.5f)
		assertTrue("center shift must stay within ±21", kotlin.math.abs(rect.centerY - cy) <= 21f + 0.5f)
	}

	@Test
	fun jellyControlSwellAndLeanAreBounded() {
		val jelly = GlassJelly()
		jelly.down(48f, 24f)
		// A realistic finger drag, not an unbounded synthetic one: the reference stretch term is
		// dx/longest and is intentionally not clamped.
		jelly.move(108f, 84f)
		repeat(120){ jelly.advance(1f / 60f) }
		val rect = GlassRect(12f, 8f, 108f, 56f)
		jelly.deformControl(rect, density = 1f, amount = 1f)
		assertTrue("trigger must swell", rect.width > 96f && rect.height > 48f)
		assertTrue("swell stays small, width=${rect.width}", rect.width <= 96f * 1.3f)
		assertTrue("swell stays small, height=${rect.height}", rect.height <= 48f * 1.3f)
	}

	@Test
	fun morphOpenRunsTheReferenceTimeline() {
		val motion = GlassMorphMotion()
		val source = GlassRect(12f, 8f, 108f, 56f)
		val target = GlassRect(12f, 8f, 260f, 308f)
		val through = GlassRect(30f, 20f, 52f, 42f)
		motion.initialize(source, target, 28f, 1f, through, true, GlassMorphTuning())

		val diameter = maxOf(248f * 0.5f * through.height / maxOf(248f, 300f), 1f)
		assertEquals(diameter, motion.frame.bounds.width, 0.01f)
		assertEquals(0f, motion.frame.rowsOpacity, 0.001f)
		assertTrue(motion.frame.lensHeight > 0f)

		motion.retarget(true)
		assertTrue("must not be settled immediately", !motion.advanceBy(0.05f))
		assertTrue("lens still on before 84ms", motion.frame.lensHeight > 0f)
		assertTrue("smoothing neck present while morphing", motion.frame.smoothing > 0f)

		motion.advanceBy(0.04f)
		assertTrue("smoothing must still be held before 210ms", motion.frame.smoothing > 0f)

		motion.advanceBy(0.25f)
		assertTrue("smoothing must start releasing after 210ms", motion.frame.smoothing < 20f)

		assertTrue("open must settle", motion.advanceBy(1f))
		assertEquals(0f, motion.frame.smoothing, 0.01f)
		assertEquals(target.left, motion.frame.bounds.left, 1f)
		assertEquals(target.right, motion.frame.bounds.right, 1f)
		assertEquals(target.top, motion.frame.bounds.top, 1f)
		assertEquals(target.bottom, motion.frame.bounds.bottom, 1f)
		assertEquals(1f, motion.frame.contentProgress, 0.02f)
		assertEquals(1f, motion.frame.rowsOpacity, 0.02f)
	}

	@Test
	fun morphClosePinchesAndCollapsesIntoTheTrigger() {
		val motion = GlassMorphMotion()
		val source = GlassRect(12f, 8f, 108f, 56f)
		val target = GlassRect(12f, 8f, 260f, 308f)
		val through = GlassRect(30f, 20f, 52f, 42f)
		motion.initialize(source, target, 28f, 1f, through, true, GlassMorphTuning())
		motion.retarget(true)
		assertTrue(motion.advanceBy(2f))
		val openWidth = motion.frame.bounds.width

		motion.retarget(false)
		motion.advanceBy(0.05f)
		assertTrue("collapse must pinch the width", motion.frame.bounds.width < openWidth)

		assertTrue("close must settle", motion.advanceBy(2f))
		assertTrue("collapsed bounds must sit inside the trigger",
			motion.frame.bounds.left >= source.left - 1f && motion.frame.bounds.right <= source.right + 1f &&
				motion.frame.bounds.top >= source.top - 1f && motion.frame.bounds.bottom <= source.bottom + 1f)
		assertEquals(0f, motion.frame.rowsOpacity, 0.001f)
		assertEquals(1f, motion.frame.sourceOpacity, 0.001f)
	}

	@Test
	fun tuningDefaultsMatchTheReference() {
		val tuning = GlassMorphTuning()
		assertEquals(1f, tuning.neckHold, 0.0001f)
		assertEquals(.25f, tuning.shrinkScale, 0.0001f)
		assertEquals(.75f, tuning.eject.dampingRatio, 0.0001f)
		assertEquals(.32f, tuning.eject.response, 0.0001f)
		assertEquals(.8f, tuning.absorb.dampingRatio, 0.0001f)
		assertEquals(.35f, tuning.absorb.response, 0.0001f)
	}
}
