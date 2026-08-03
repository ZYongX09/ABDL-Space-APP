package org.joinmastodon.android.novel

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelEntryContractTest {
	private val projectDir = File(requireNotNull(System.getProperty("user.dir")))
	private val homeTabFragment = File(projectDir, "src/main/java/org/joinmastodon/android/fragments/HomeTabFragment.java").readText()
	private val homeCustom = File(projectDir, "src/main/res/menu/home_custom.xml").readText()
	private val homeOverflow = File(projectDir, "src/main/res/menu/home_overflow.xml").readText()
	private val manifest = File(projectDir, "src/main/AndroidManifest.xml").readText()

	@Test
	fun exposesStableNovelMenuId() {
		assertTrue(homeCustom.contains("android:id=\"@+id/novel\""))
		assertTrue(homeOverflow.contains("android:id=\"@+id/novel\""))
	}

	@Test
	fun addsNovelToLiquidHomeRootMenu() {
		assertTrue(homeTabFragment.contains("new HomeToolbarMenuItem(R.id.novel"))
	}

	@Test
	fun liquidNovelSelectionStartsActivityWithCurrentAccount() {
		assertTrue(homeTabFragment.contains("if(id==R.id.novel)"))
		assertTrue(homeTabFragment.contains("new Intent(getActivity(), NovelActivity.class)"))
		assertTrue(homeTabFragment.contains("putExtra(\"account\", accountID)"))
	}

	@Test
	fun nonLiquidOverflowExposesNovelEntry() {
		assertTrue(homeCustom.contains("android:id=\"@+id/novel\""))
		assertTrue(homeOverflow.contains("android:id=\"@+id/novel\""))
		assertTrue(homeTabFragment.contains("id == R.id.novel"))
	}

	@Test
	fun novelActivityIsNotExported() {
		assertTrue(manifest.contains(Regex("""<activity\s+android:name="\.novel\.NovelActivity"\s+android:exported="false"\s*/>""")))
	}
}
