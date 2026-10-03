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
				R.id.tab_diaper,
				R.id.tab_profile,
			),
			HomeNavigationTabs.ids,
		)
	}

	@Test
	fun dragReleaseAlwaysSnapsToAValidTab() {
		assertEquals(1, snapNavigationDragTarget(1.47f, 4))
		assertEquals(2, snapNavigationDragTarget(1.5f, 4))
		assertEquals(0, snapNavigationDragTarget(-0.4f, 4))
		assertEquals(3, snapNavigationDragTarget(4.8f, 4))
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
