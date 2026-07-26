package org.joinmastodon.android.ui.compose.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class MorphingGlassGeometryTest {
	@Test
	fun geometryStartsAtPillAndEndsAtAnchoredMenu() {
		val closed = GlassBounds(12f, 40f, 180f, 48f, 24f)
		val open = GlassBounds(12f, 40f, 248f, 356f, 28f)
		assertEquals(closed, interpolateGlassBounds(closed, open, 0f))
		assertEquals(open, interpolateGlassBounds(closed, open, 1f))
	}

	@Test
	fun trailingMenuRemainsInsideScreen() {
		val bounds = anchoredMenuBounds(
			anchor = GlassBounds(300f, 40f, 108f, 48f, 24f),
			menuWidth = 248f,
			menuHeight = 356f,
			screenWidth = 420f,
			side = MorphingGlassSide.TRAILING,
		)
		assertEquals(160f, bounds.left, 0f)
		assertEquals(408f, bounds.left + bounds.width, 0f)
	}
}
