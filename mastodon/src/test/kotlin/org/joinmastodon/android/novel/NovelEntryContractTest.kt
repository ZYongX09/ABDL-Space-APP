package org.joinmastodon.android.novel

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
		assertFalse(homeCustom.contains("android:id=\"@+id/novel\""))
		assertTrue(homeOverflow.contains("android:id=\"@+id/novel\""))
	}

	@Test
	fun rebuiltNovelEntryIsVisibleInClassicAndLiquidMoreMenus() {
		assertTrue(homeTabFragment.contains("new HomeToolbarMenuItem(R.id.novel"))
		val item = homeOverflow.substringAfter("@+id/novel").substringBefore("/>")
		assertTrue(item.contains("android:visible=\"true\""))
		assertTrue(item.contains("@drawable/ic_novel_rebuilt"))
		assertFalse(homeCustom.contains("@+id/novel"))
		assertTrue(homeCustom.contains("@+id/overflow"))
		assertTrue(homeTabFragment.contains("menu.findItem(R.id.overflow).setActionView(overflowActionView)"))
	}

	@Test
	fun bothNovelHandlersOpenTheRebuiltAccountScopedActivity() {
		assertTrue(homeTabFragment.contains("if(id==R.id.novel)"))
		assertEquals(2, Regex("NovelAppActivity\\.intent\\(getActivity\\(\\), accountID\\)").findAll(homeTabFragment).count())
		assertFalse(homeTabFragment.contains("NovelEditorActivity.EXTRA_ACCOUNT_ID"))
		assertTrue(homeTabFragment.contains("id == R.id.novel"))
	}

	@Test
	fun nonLiquidOverflowExposesNovelEntry() {
		assertFalse(homeCustom.contains("android:id=\"@+id/novel\""))
		assertTrue(homeOverflow.contains("android:id=\"@+id/novel\""))
		assertTrue(homeTabFragment.contains("id == R.id.novel"))
	}

	@Test
	fun novelActivityIsNotExported() {
		assertTrue(manifest.contains(Regex("""<activity\s+android:name="\.novel\.NovelActivity"\s+android:exported="false"\s*/>""")))
		for (name in listOf("NovelAppActivity", "NovelAccountActionActivity", "editor.NovelCreationActivity", "editor.NovelReaderActivity")) {
			val declaration = manifest.substringAfter("android:name=\"org.joinmastodon.android.novel.app.$name\"").substringBefore("/>")
			assertTrue("$name must be registered privately", declaration.contains("android:exported=\"false\""))
		}
	}
}
