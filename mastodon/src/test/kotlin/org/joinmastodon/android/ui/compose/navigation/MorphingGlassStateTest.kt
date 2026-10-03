package org.joinmastodon.android.ui.compose.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MorphingGlassStateTest {
	@Test
	fun pressExpandSelectAndCollapseFollowOneStateMachine() {
		var state = MorphingGlassState.idle(MorphingGlassSide.LEADING)
		state = state.reduce(MorphingGlassEvent.Press)
		assertEquals(MorphingGlassPhase.PRESSED, state.phase)
		state = state.reduce(MorphingGlassEvent.BackdropReady)
		assertEquals(MorphingGlassPhase.EXPANDING, state.phase)
		state = state.reduce(MorphingGlassEvent.ExpansionSettled)
		assertEquals(MorphingGlassPhase.EXPANDED, state.phase)
		state = state.reduce(MorphingGlassEvent.HoverItem(2))
		assertEquals(2, state.highlightedIndex)
		state = state.reduce(MorphingGlassEvent.Release)
		assertEquals(MorphingGlassPhase.COLLAPSING, state.phase)
	}

	@Test
	fun releaseOutsideClearsSelectionAndCollapses() {
		val state = MorphingGlassState(
			side = MorphingGlassSide.TRAILING,
			phase = MorphingGlassPhase.SELECTING,
			highlightedIndex = 1,
		).reduce(MorphingGlassEvent.PointerOutside)
		assertEquals(MorphingGlassPhase.COLLAPSING, state.phase)
		assertNull(state.highlightedIndex)
	}

	@Test
	fun oppositeMenuWaitsForCurrentGlassToCollapse() {
		val state = MorphingGlassState(
			side = MorphingGlassSide.LEADING,
			phase = MorphingGlassPhase.EXPANDED,
		).reduce(MorphingGlassEvent.OpenOther(MorphingGlassSide.TRAILING))
		assertEquals(MorphingGlassPhase.COLLAPSING, state.phase)
		assertEquals(MorphingGlassSide.TRAILING, state.pendingSide)
	}
}
