package org.joinmastodon.android.ui.compose.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MorphingGlassHitTestTest {
	@Test
	fun pointerMapsToRowsAndOutsideCancels() {
		val rows = listOf(0f..48f, 48f..96f, 96f..144f)
		assertEquals(1, hitTestMenuRow(70f, rows))
		assertNull(hitTestMenuRow(170f, rows))
	}

	@Test
	fun trailingPillSplitsSearchAndMoreAtVisualMidpoint() {
		assertEquals(TrailingToolbarAction.SEARCH, trailingToolbarAction(26f, 108f))
		assertEquals(TrailingToolbarAction.MORE, trailingToolbarAction(82f, 108f))
		assertEquals(TrailingToolbarAction.MORE, trailingToolbarAction(54f, 108f))
	}

	@Test
	fun menuHeightMatchesRenderedRows() {
		assertEquals(60, toolbarMenuHeightDp(itemCount = 1, hasBackRow = false))
		assertEquals(204, toolbarMenuHeightDp(itemCount = 4, hasBackRow = false))
		assertEquals(252, toolbarMenuHeightDp(itemCount = 4, hasBackRow = true))
	}

	@Test
	fun toolbarDragSelectionMatchesRenderedPaddingAndRowBoundaries() {
		for(density in listOf(1f, 2.75f, 3f)) {
			val width = 200 * density
			val height = ToolbarMenuGeometry.heightDp(2) * density
			fun row(yDp: Float, x: Float = width / 2) = ToolbarMenuGeometry.rowAt(x, yDp * density, width, height, density, 2)
			assertEquals(116, ToolbarMenuGeometry.heightDp(2))
			assertNull(row(-1f))
			assertNull(row(13.9f))
			assertEquals(0, row(14f))
			assertEquals(0, row(61.9f))
			assertEquals(1, row(62f))
			assertEquals(1, row(109.9f))
			assertNull(row(110f))
			assertNull(row(38f, -1f))
			assertNull(row(38f, width))
			assertNull(ToolbarMenuGeometry.rowAt(width / 2, 430 * density, width, 420 * density, density, 20))
		}
	}

	@Test
	fun outsideGestureOnlyForwardsAfterTouchSlop() {
		assertEquals(OutsideGlassGesture.CLOSE_ONLY, outsideGlassGesture(0f, 8f))
		assertEquals(OutsideGlassGesture.CLOSE_ONLY, outsideGlassGesture(7.9f, 8f))
		assertEquals(OutsideGlassGesture.FORWARD_TO_CONTENT, outsideGlassGesture(8.1f, 8f))
	}

	@Test
	fun menuRequestCommitsOnNextFrameWithoutWaitingForBackdrop() {
		assertEquals(1, toolbarMenuCommitDelayFrames())
	}

	@Test
	fun leadingGlassHitRegionCoversFullVisualHeight() {
		assertTrue(isInsideLeadingGlass(x = 20f, y = 40f, top = 32f, width = 180f, height = 48f))
		assertTrue(isInsideLeadingGlass(x = 20f, y = 79.9f, top = 32f, width = 180f, height = 48f))
		assertFalse(isInsideLeadingGlass(x = 20f, y = 80.1f, top = 32f, width = 180f, height = 48f))
	}

	@Test
	fun longPressReleaseBehaviorDependsOnMovement() {
		assertEquals(LeadingGlassReleaseAction.COLLAPSE, leadingGlassReleaseAction(longPressOpened = true, moved = false, upwardFling = false))
		assertEquals(LeadingGlassReleaseAction.KEEP_OPEN, leadingGlassReleaseAction(longPressOpened = true, moved = true, upwardFling = false))
		assertEquals(LeadingGlassReleaseAction.COLLAPSE, leadingGlassReleaseAction(longPressOpened = true, moved = true, upwardFling = true))
		assertEquals(LeadingGlassReleaseAction.OPEN, leadingGlassReleaseAction(longPressOpened = false, moved = false, upwardFling = false))
	}

	@Test
	fun tapAndLongPressOpenAtDifferentGesturePhases() {
		assertEquals(LeadingGlassOpenTrigger.ON_RELEASE, leadingGlassOpenTrigger(longPressReached = false))
		assertEquals(LeadingGlassOpenTrigger.ON_LONG_PRESS, leadingGlassOpenTrigger(longPressReached = true))
	}

	@Test
	fun upwardFlingUsesNegativeSystemVelocityThreshold() {
		assertTrue(isUpwardToolbarFling(velocityY = -1201f, minimumFlingVelocity = 1200f))
		assertFalse(isUpwardToolbarFling(velocityY = -1199f, minimumFlingVelocity = 1200f))
		assertFalse(isUpwardToolbarFling(velocityY = 1800f, minimumFlingVelocity = 1200f))
	}

	@Test
	fun trailingMenuUsesTheSameUpwardFlingRule() {
		assertTrue(shouldCollapseTrailingMenu(velocityY = -1400f, minimumFlingVelocity = 1200f))
		assertFalse(shouldCollapseTrailingMenu(velocityY = -900f, minimumFlingVelocity = 1200f))
		assertFalse(shouldCollapseTrailingMenu(velocityY = 1400f, minimumFlingVelocity = 1200f))
	}
}
