package org.joinmastodon.android.ui.compose.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.joinmastodon.android.R
import org.joinmastodon.android.ui.compose.navigation.animation.stabilizeDragVelocity
import org.junit.Test
import java.io.File

class HomeNavigationTabsTest {
	@Test
	fun mapsEveryTabIdToItsStableIndex() {
		HomeNavigationTabs.ids.forEachIndexed { index, id ->
			assertEquals(index, HomeNavigationTabs.indexOf(id))
		}
	}

	@Test
	fun fallsBackToHomeForUnknownTabId() {
		assertEquals(0, HomeNavigationTabs.indexOf(Int.MIN_VALUE))
	}

	@Test
	fun containsOnlyPrimaryHomeDestinations() {
		assertArrayEquals(
			intArrayOf(
				R.id.tab_home,
				R.id.tab_search,
				R.id.tab_diaper,
				R.id.tab_friend_request,
				R.id.tab_profile,
			),
			HomeNavigationTabs.ids,
		)
		assertEquals(0, HomeNavigationTabs.indexOf(R.id.tab_messages))
	}

	@Test
	fun dragReleaseAlwaysSnapsToAValidTab() {
		assertEquals(1, snapNavigationDragTarget(1.47f, 5))
		assertEquals(2, snapNavigationDragTarget(1.5f, 5))
		assertEquals(0, snapNavigationDragTarget(-0.4f, 5))
		assertEquals(4, snapNavigationDragTarget(4.8f, 5))
	}

	@Test
	fun dragShapeVelocityDoesNotFlipDirectionInOneFrame() {
		assertEquals(4f, stabilizeDragVelocity(8f, -8f), 0f)
		assertEquals(-4f, stabilizeDragVelocity(-8f, 8f), 0f)
		assertEquals(2f, stabilizeDragVelocity(0f, 8f), 0f)
		assertEquals(7.5f, stabilizeDragVelocity(8f, 6f), 0f)
	}

	@Test
	fun bottomNavigationUsesItshoverIconsAndOnlyClicksTriggerMotion() {
		val projectDir = File(requireNotNull(System.getProperty("user.dir")))
		val layout = File(projectDir, "src/main/res/layout/tab_bar.xml").readText()
		val tabBar = File(projectDir, "src/main/java/org/joinmastodon/android/ui/views/TabBar.java").readText()
		val liquidView = File(projectDir, "src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/HomeLiquidNavigationView.kt").readText()
		val liquidBar = File(projectDir, "src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/liquid/IosLiquidGlassNavigationBar.kt").readText()
		val iconView = File(projectDir, "src/main/kotlin/org/joinmastodon/android/ui/views/ItshoverNavigationIconView.kt").readText()

		assertEquals(4, Regex("ItshoverNavigationIconView").findAll(layout).count())
		listOf("home", "magnifier", "star", "globe").forEach { assert(layout.contains("app:iconType=\"$it\"")) }
		assert(layout.contains("@+id/tab_profile_ava"))
		assert(tabBar.substringAfter("private void onChildClick").substringBefore("private boolean onChildLongClick").contains("playAnimation()"))
		assert(!tabBar.substringAfter("public void selectTab").contains("playAnimation()"))
		assertEquals(2, Regex("animateIcon\\(").findAll(liquidBar).count() - 1)
		assert(!liquidBar.substringAfter("LaunchedEffect(selectedIndex)").substringBefore("val interactiveHighlight").contains("animateIcon("))
		assert(liquidView.contains("ICON_HOME"))
		assert(liquidView.contains("ICON_MAGNIFIER"))
		assert(liquidView.contains("ICON_STAR"))
		assert(liquidView.contains("ICON_GLOBE"))
		assert(iconView.contains("private var progress = 1f"))
		assert(iconView.contains("Original SVG paths and motion design"))
	}
}
