package org.joinmastodon.android.ui.compose.navigation

import java.io.File
import org.joinmastodon.android.R
import org.joinmastodon.android.ui.compose.navigation.animation.stabilizeDragVelocity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeNavigationTabsTest {
	@Test
	fun mapsEveryTabIdToItsStableIndex() {
		HomeNavigationTabs.ids.forEachIndexed { index, id ->
			assertEquals(index, HomeNavigationTabs.indexOf(id))
		}
	}

	@Test
	fun migratesLegacyTabsAndUnknownIds() {
		assertEquals(1, HomeNavigationTabs.indexOf(R.id.tab_search))
		assertEquals(0, HomeNavigationTabs.indexOf(R.id.tab_friend_request))
		assertEquals(0, HomeNavigationTabs.indexOf(Int.MIN_VALUE))
	}

	@Test
	fun containsOnlyPrimaryHomeDestinations() {
		assertArrayEquals(
			intArrayOf(
				R.id.tab_home,
				R.id.tab_messages,
				R.id.tab_map,
				R.id.tab_diaper,
				R.id.tab_profile,
			),
			HomeNavigationTabs.ids,
		)
	}

	@Test
	fun mapIsTheCenterOfFiveDistinctDestinations() {
		assertEquals(5, HomeNavigationTabs.ids.size)
		assertEquals(5, HomeNavigationTabs.ids.toSet().size)
		assertEquals(2, HomeNavigationTabs.indexOf(R.id.tab_map))
		assertEquals(R.id.tab_map, HomeNavigationTabs.ids[HomeNavigationTabs.ids.size / 2])
	}

	@Test
	fun classicAndLiquidNavigationKeepTheSameFiveDestinationOrder() {
		val projectDir = File(requireNotNull(System.getProperty("user.dir")))
		val layout = File(projectDir, "src/main/res/layout/tab_bar.xml").readText()
		val liquid = File(projectDir, "src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeLiquidNavigationView.kt").readText()
		val ids = File(projectDir, "src/main/res/values/ids.xml").readText()
		val layoutTabs = Regex("android:id=\"@(?:\\+)?id/(tab_home|tab_messages|tab_map|tab_diaper|tab_profile)\"")
			.findAll(layout).map { it.groupValues[1] }.toList()
		assertEquals(listOf("tab_home", "tab_messages", "tab_map", "tab_diaper", "tab_profile"), layoutTabs)
		assertTrue(ids.contains("name=\"tab_map\" type=\"id\""))
		val items = liquid.substringAfter("val items = listOf(").substringBefore("val iconTypes")
		assertEquals(5, Regex("NavigationItem\\(").findAll(items).count())
		assertTrue(items.indexOf("R.string.messages") < items.indexOf("R.string.map_presence_title"))
		assertTrue(items.indexOf("R.string.map_presence_title") < items.indexOf("R.string.diaper"))
		assertTrue(liquid.contains("painterResource(R.drawable.ic_fluent_map_24_regular)"))
	}

	@Test
	fun embeddedMapStateRestoresOldBundlesAndActivatesOnlyAfterSelection() {
		val projectDir = File(requireNotNull(System.getProperty("user.dir")))
		val home = File(projectDir, "src/main/java/org/joinmastodon/android/fragments/HomeFragment.java").readText()
		val creation = home.substringAfter("private FriendMapFragment createEmbeddedFriendMapFragment(){").substringBefore("public void onDestroy()")
		listOf("__is_tab", "noAutoLoad", "hidden").forEach {
			assertTrue(creation.contains("args.putBoolean(\"$it\", true);"))
		}
		assertTrue(creation.contains("fragment.setTabVisible(false);"))
		val restoration = home.substringAfter("public void onViewStateRestored(").substringBefore("public void onHiddenChanged(")
		assertTrue(restoration.contains("restoreChildFragment(savedInstanceState, \"friendMapFragment\")"))
		assertTrue(restoration.contains("if(friendMapFragment==null)\n\t\t\tfriendMapFragment=createEmbeddedFriendMapFragment();"))
		assertTrue(restoration.contains("if(!friendMapFragment.isAdded())"))
		assertTrue(restoration.contains(".hide(friendMapFragment)"))
		assertTrue(restoration.contains(".runOnCommit(this::updateMapTabVisibility)"))
		assertTrue(home.contains("putFragment(outState, \"friendMapFragment\", friendMapFragment)"))
		val visibility = home.substringAfter("private void updateMapTabVisibility(){").substringBefore("private void updateCaptureHeights()")
		assertTrue(visibility.contains("parentActive && currentTab==R.id.tab_map"))
		assertTrue(visibility.contains("friendMapFragment.isAdded() && !friendMapFragment.isHidden()"))
		assertTrue(home.contains("setNavigationInsets(topSystemInset, navigationHost==null ? 0 : navigationHost.getHeight())"))
		val loading = home.substringAfter("private void maybeTriggerLoading(").substringBefore("private boolean onTabLongClick(")
		assertTrue(loading.indexOf("instanceof FriendMapFragment") < loading.indexOf("instanceof LoaderFragment"))
		assertFalse(loading.contains("friendMapFragment.loadData()"))
		val back = home.substringAfter("public boolean onBackPressed(){").substringBefore("private void selectTabInNavigation(")
		assertTrue(back.contains("parentActive && currentTab==R.id.tab_map"))
		assertTrue(back.indexOf("friendMapFragment.onBackPressed()") < back.indexOf("liquidToolbarController.onBackPressed()"))
	}

	@Test
	fun dragReleaseAlwaysSnapsToAValidTab() {
		val tabsCount = HomeNavigationTabs.ids.size
		assertEquals(1, snapNavigationDragTarget(1.47f, tabsCount))
		assertEquals(2, snapNavigationDragTarget(1.5f, tabsCount))
		assertEquals(0, snapNavigationDragTarget(-0.4f, tabsCount))
		assertEquals(4, snapNavigationDragTarget(4.8f, tabsCount))
	}

	@Test
	fun dragShapeVelocityDoesNotFlipDirectionInOneFrame() {
		assertEquals(4f, stabilizeDragVelocity(8f, -8f), 0f)
		assertEquals(-4f, stabilizeDragVelocity(-8f, 8f), 0f)
		assertEquals(2f, stabilizeDragVelocity(0f, 8f), 0f)
		assertEquals(7.5f, stabilizeDragVelocity(8f, 6f), 0f)
	}

	@Test
	fun standardAndLiquidNavigationUseMessagesWithoutFriendTab() {
		val projectDir = File(requireNotNull(System.getProperty("user.dir")))
		val layout = File(projectDir, "src/main/res/layout/tab_bar.xml").readText()
		val tabBar = File(projectDir, "src/main/java/org/joinmastodon/android/ui/views/TabBar.java").readText()
		val home = File(projectDir, "src/main/java/org/joinmastodon/android/fragments/HomeFragment.java").readText()
		val conversations = File(projectDir, "src/main/java/org/joinmastodon/android/chat/ui/ConversationsFragment.java").readText()
		val liquidView = File(projectDir, "src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeLiquidNavigationView.kt").readText()
		val liquidBar = File(projectDir, "src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/liquid/IosLiquidGlassNavigationBar.kt").readText()
		val iconView = File(projectDir, "src/main/kotlin/org/joinmastodon/android/ui/views/ItshoverNavigationIconView.kt").readText()

		assertEquals(2, Regex("ItshoverNavigationIconView").findAll(layout).count())
		listOf("home", "star").forEach { assertTrue(layout.contains("app:iconType=\"$it\"")) }
		assertTrue(layout.contains("android:id=\"@id/tab_messages\""))
		assertTrue(layout.contains("@drawable/ic_tab_messages"))
		assertFalse(layout.contains("@+id/tab_search"))
		assertFalse(layout.contains("@+id/tab_friend_request"))
		assertTrue(layout.contains("@+id/tab_profile_ava"))
		assertTrue(tabBar.substringAfter("private void onChildClick").substringBefore("private boolean onChildLongClick").contains("playAnimation()"))
		assertFalse(tabBar.substringAfter("public void selectTab").contains("playAnimation()"))
		assertEquals(2, Regex("animateIcon\\(").findAll(liquidBar).count() - 1)
		assertFalse(liquidBar.substringAfter("LaunchedEffect(selectedIndex)").substringBefore("val interactiveHighlight").contains("animateIcon("))
		assertTrue(liquidView.contains("ICON_HOME"))
		assertTrue(liquidView.contains("ICON_STAR"))
		assertFalse(liquidView.contains("ICON_MAGNIFIER"))
		assertFalse(liquidView.contains("ICON_GLOBE"))
		assertTrue(liquidView.contains("painterResource(R.drawable.ic_tab_messages)"))
		assertTrue(home.contains("return R.id.tab_messages"))
		assertTrue(home.contains("return R.id.tab_home"))
		assertTrue(home.contains("return conversationsFragment"))
		assertTrue(conversations.contains("getBoolean(\"noAutoLoad\", false)"))
		assertTrue(conversations.contains("public void setTabBarBottomInset"))
		assertTrue(conversations.contains("public void loadData()"))
		assertTrue(iconView.contains("private var progress = 1f"))
		assertTrue(iconView.contains("Original SVG paths and motion design"))
	}
}
