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

	@Test
	fun trailingPillSplitsComposeAndMoreAtVisualMidpoint() {
		assertEquals(TrailingToolbarAction.COMPOSE, trailingToolbarAction(26f, 108f))
		assertEquals(TrailingToolbarAction.MORE, trailingToolbarAction(82f, 108f))
		assertEquals(TrailingToolbarAction.MORE, trailingToolbarAction(54f, 108f))
	}

	@Test
	fun menuHeightMatchesRenderedRows() {
		assertEquals(60, toolbarMenuHeightDp(itemCount = 1, hasBackRow = false))
		assertEquals(204, toolbarMenuHeightDp(itemCount = 4, hasBackRow = false))
		assertEquals(252, toolbarMenuHeightDp(itemCount = 4, hasBackRow = true))
	}
}
