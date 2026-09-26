package org.joinmastodon.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLiquidToolbarActionsContractTest {
	private val projectDir = File(requireNotNull(System.getProperty("user.dir")))

	@Test
	fun toolbarViewCarriesSearchCapsuleAndIndependentComposeButton() {
		val view = source("src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeLiquidToolbarView.kt")
		val geometry = source("src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/MorphingGlassGeometry.kt")

		assertTrue(view.contains("private val onSearch: Runnable"))
		assertTrue(view.contains("data class HomeToolbarComposeMenuItem"))
		assertTrue(view.contains("fun setComposeMenu(items: List<HomeToolbarComposeMenuItem>)"))
		assertTrue(view.contains("HomeToolbarMenuPage.COMPOSE"))
		assertTrue(view.contains("if(action==TrailingToolbarAction.SEARCH) onSearch.run()"))
		assertTrue(view.contains("COMPOSE_MENU_WIDTH_DP = 200"))
		assertTrue(view.contains("COMPOSE_MENU_END_PADDING_DP = 40"))
		assertTrue(view.contains("ResourceIcon(R.drawable.ic_fluent_edit_24_regular, 24, contentColor)"))
		assertTrue(view.contains("id = R.id.home_search_btn"))
		assertTrue(geometry.contains("internal enum class TrailingToolbarAction { SEARCH, MORE }"))
		assertFalse(geometry.contains("TrailingToolbarAction.COMPOSE"))
		assertFalse(view.contains("COMPOSE_MENU_TOP_GAP_DP"))
		assertFalse(view.contains("GlassCircle"))
	}

	@Test
	fun homeFragmentWiresSearchAndComposeMenuWithoutFriendToolbar() {
		val home = source("src/main/java/org/joinmastodon/android/fragments/HomeFragment.java")
		val homeTab = source("src/main/java/org/joinmastodon/android/fragments/HomeTabFragment.java")

		assertTrue(home.contains("homeTabFragment::openSearch"))
		assertTrue(home.contains("new HomeToolbarComposeMenuItem(R.id.compose_post, getString(R.string.compose_menu_post), getNotes(MiuixIcons.INSTANCE))"))
		assertTrue(home.contains("new HomeToolbarComposeMenuItem(R.id.compose_friend_request, getString(R.string.compose_menu_friend_request), getContactsBook(MiuixIcons.INSTANCE))"))
		assertTrue(home.contains("liquidToolbarController.setComposeMenu(composeItems)"))
		assertFalse(home.contains("FriendUniverseLiquidToolbarController"))
		assertTrue(homeTab.contains("if(id==R.id.compose_post)"))
		assertTrue(homeTab.contains("if(id==R.id.compose_friend_request)"))
		assertTrue(homeTab.contains("public void openSearch()"))
	}

	@Test
	fun menuModelKeepsComposePageAndFinalSpacing() {
		val model = source("src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeLiquidToolbarModel.kt")
		assertTrue(model.contains("COMPOSE;"))
		assertTrue(model.contains("internal fun homeTimelineTopPaddingDp(liquidMode: Boolean): Int = if(liquidMode) 56 else 0"))
		assertTrue(model.contains("internal fun homeToolbarCaptureHeightDp(menuOpen: Boolean): Int = if(menuOpen) 520 else 72"))
	}

	private fun source(path: String) = File(projectDir, path).readText()
}
