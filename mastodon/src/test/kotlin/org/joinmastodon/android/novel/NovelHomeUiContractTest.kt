package org.joinmastodon.android.novel

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelHomeUiContractTest {
	private val projectDir = File(requireNotNull(System.getProperty("user.dir")))
	private val screen = File(projectDir, "src/main/kotlin/org/joinmastodon/android/novel/NovelHomeScreen.kt").readText()
	private val library = File(projectDir, "src/main/kotlin/org/joinmastodon/android/novel/NovelLibraryScreen.kt").readText()
	private val activity = File(projectDir, "src/main/kotlin/org/joinmastodon/android/novel/NovelActivity.kt").readText()

	@Test
	fun novelHomeUsesMiuixPageChrome() {
		assertTrue(screen.contains("top.yukonga.miuix.kmp.basic.Scaffold"))
		assertTrue(screen.contains("top.yukonga.miuix.kmp.basic.SmallTopAppBar"))
		assertTrue(screen.contains("top.yukonga.miuix.kmp.basic.TabRow"))
	}

	@Test
	fun bookshelfIsWiredToAccountScopedLibrary() {
		assertTrue(screen.contains("fun NovelHomeScreen(accountId: String, libraryViewModel: NovelLibraryViewModel"))
		assertTrue(screen.contains("NovelLibraryScreen"))
		assertTrue(activity.contains("NovelHomeScreen(accountId = accountID"))
		assertTrue(library.contains("上传 TXT/EPUB"))
		assertTrue(library.contains("粘贴文本"))
		assertTrue(library.contains("onDownload"))
		assertTrue(library.contains("onDelete"))
		assertTrue(library.contains("top.yukonga.miuix.kmp.basic.Card"))
	}

	@Test
	fun novelHomeDoesNotUseMaterialPageChrome() {
		assertFalse(screen.contains("androidx.compose.material3.Scaffold"))
		assertFalse(screen.contains("androidx.compose.material3.TopAppBar"))
		assertFalse(screen.contains("androidx.compose.material3.TabRow"))
	}
}
