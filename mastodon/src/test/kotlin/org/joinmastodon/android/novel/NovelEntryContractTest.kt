package org.joinmastodon.android.novel

import java.io.File
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
		assertTrue(homeCustom.contains("android:id=\"@+id/novel\""))
		assertTrue(homeOverflow.contains("android:id=\"@+id/novel\""))
	}

	@Test
	fun novelEntryStaysHiddenWhileSourceIsPreserved() {
		// 决策：小说源码与处理器保留，但所有主页/菜单入口隐藏
		assertFalse(homeTabFragment.contains("new HomeToolbarMenuItem(R.id.novel"))
		assertTrue(homeOverflow.substringAfter("@+id/novel").substringBefore("/>").contains("android:visible=\"false\""))
	}

	@Test
	fun novelHandlersRemainWiredForFutureReEnable() {
		assertTrue(homeTabFragment.contains("if(id==R.id.novel)"))
		assertTrue(homeTabFragment.contains("new Intent(getActivity(), NovelEditorActivity.class)"))
		assertTrue(homeTabFragment.contains("NovelEditorActivity.EXTRA_ACCOUNT_ID"))
		assertTrue(homeTabFragment.contains("id == R.id.novel"))
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
