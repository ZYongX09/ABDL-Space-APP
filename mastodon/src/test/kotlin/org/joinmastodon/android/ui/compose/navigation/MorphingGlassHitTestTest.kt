package org.joinmastodon.android.ui.compose.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MorphingGlassHitTestTest {
	@Test
	fun pointerMapsToRowsAndOutsideCancels() {
		val rows = listOf(0f..48f, 48f..96f, 96f..144f)
		assertEquals(1, hitTestMenuRow(70f, rows))
		assertNull(hitTestMenuRow(170f, rows))
	}
}
