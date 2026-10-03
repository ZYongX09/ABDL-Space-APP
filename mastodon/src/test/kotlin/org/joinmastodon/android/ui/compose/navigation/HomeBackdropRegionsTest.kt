package org.joinmastodon.android.ui.compose.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeBackdropRegionsTest {
	@Test
	fun captureRangesClampToTheViewBounds() {
		assertEquals(0..179, topCaptureRange(1640, 180))
		assertEquals(1416..1639, bottomCaptureRange(1640, 224))
		assertEquals(IntRange.EMPTY, topCaptureRange(0, 180))
		assertEquals(IntRange.EMPTY, bottomCaptureRange(1640, 0))
	}
}
