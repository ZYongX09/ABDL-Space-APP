package org.joinmastodon.android.ui.compose.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLiquidToolbarModelTest {
	@Test
	fun liquidModeKeepsOnlyComposeAndOverflowActions() {
		assertEquals(
			listOf(HomeToolbarAction.COMPOSE, HomeToolbarAction.OVERFLOW),
			liquidToolbarActions(),
		)
	}

	@Test
	fun reminderStateOnlyBadgesOverflowAndMatchingMenuRows() {
		val state = homeToolbarReminderState(
			hasUnreadAnnouncements = true,
			hasUpdate = false,
		)
		assertTrue(state.overflowBadged)
		assertTrue(state.announcementsBadged)
		assertFalse(state.settingsBadged)
	}

	@Test
	fun newPostsReplacesTimelineLabelWithoutChangingTimelineSelection() {
		val state = HomeLiquidToolbarState(
			selectedTimeline = 2,
			showNewPosts = true,
			menuPage = HomeToolbarMenuPage.NONE,
		)
		assertEquals(HomeToolbarLeadingMode.NEW_POSTS, state.leadingMode)
		assertEquals(2, state.selectedTimeline)
	}

	@Test
	fun listAndHashtagPagesStayInsideTheAnchoredMenu() {
		assertEquals(HomeToolbarMenuPage.LISTS, HomeToolbarMenuPage.ROOT.openLists())
		assertEquals(HomeToolbarMenuPage.HASHTAGS, HomeToolbarMenuPage.ROOT.openHashtags())
		assertEquals(HomeToolbarMenuPage.ROOT, HomeToolbarMenuPage.LISTS.back())
	}

	@Test
	fun toolbarVisualsStayReadableAndTransparentWithoutFrostedBlur() {
		val spec = homeLiquidToolbarVisualSpec()
		assertEquals(18, spec.titleTextSp)
		assertEquals(17, spec.menuTextSp)
		assertEquals(8, spec.blurRadiusDp)
		assertTrue(spec.surfaceAlpha <= 0.25f)
	}

	@Test
	fun openMenuExpandsCaptureBelowTheToolbar() {
		assertEquals(72, homeToolbarCaptureHeightDp(menuOpen = false))
		assertEquals(520, homeToolbarCaptureHeightDp(menuOpen = true))
	}

	@Test
	fun toolbarMotionUsesAResponsiveUnderdampedSpring() {
		val motion = homeLiquidToolbarMotionSpec()
		assertTrue(motion.dampingRatio < 1f)
		assertTrue(motion.dampingRatio >= 0.7f)
		assertTrue(motion.stiffness >= 350f)
	}

	@Test
	fun liquidTimelineUsesScrollableTopPaddingInsteadOfFixedToolbarSpace() {
		assertEquals(72, homeTimelineTopPaddingDp(liquidMode = true))
		assertEquals(0, homeTimelineTopPaddingDp(liquidMode = false))
	}

	@Test
	fun toolbarReusesBottomGlassBloomOutline() {
		val outline = homeLiquidToolbarOutlineSpec()
		assertEquals(1f, outline.widthDp, 0f)
		assertEquals(2f, outline.innerBlurRadiusDp, 0f)
		assertTrue(outline.dualPeak)
	}

	@Test
	fun collapsedLeadingWidthTracksMeasuredTitleWithinBounds() {
		assertEquals(96f, homeToolbarCollapsedWidthDp(20f), 0f)
		assertEquals(172f, homeToolbarCollapsedWidthDp(100f), 0f)
		assertEquals(260f, homeToolbarCollapsedWidthDp(300f), 0f)
	}

	@Test
	fun backClosesVisibleOrPendingMenuBeforeNavigation() {
		assertTrue(shouldConsumeToolbarBack(menuVisible = true, menuPending = false))
		assertTrue(shouldConsumeToolbarBack(menuVisible = false, menuPending = true))
		assertFalse(shouldConsumeToolbarBack(menuVisible = false, menuPending = false))
	}
}
