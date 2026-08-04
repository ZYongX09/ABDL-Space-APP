package org.joinmastodon.android.novel

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelHomeUiContractTest {
	private val projectDir = File(requireNotNull(System.getProperty("user.dir")))
	private val screen = File(projectDir, "src/main/kotlin/org/joinmastodon/android/novel/NovelHomeScreen.kt").readText()

	@Test
	fun novelHomeUsesMiuixPageChrome() {
		assertTrue(screen.contains("top.yukonga.miuix.kmp.basic.Scaffold"))
		assertTrue(screen.contains("top.yukonga.miuix.kmp.basic.SmallTopAppBar"))
		assertTrue(screen.contains("top.yukonga.miuix.kmp.basic.TabRow"))
	}

	@Test
	fun novelHomeDoesNotUseMaterialPageChrome() {
		assertFalse(screen.contains("androidx.compose.material3.Scaffold"))
		assertFalse(screen.contains("androidx.compose.material3.TopAppBar"))
		assertFalse(screen.contains("androidx.compose.material3.TabRow"))
	}
}
