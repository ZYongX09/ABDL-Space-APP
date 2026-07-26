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
		assertEquals(0, spec.blurRadiusDp)
		assertTrue(spec.surfaceAlpha <= 0.25f)
	}
}
