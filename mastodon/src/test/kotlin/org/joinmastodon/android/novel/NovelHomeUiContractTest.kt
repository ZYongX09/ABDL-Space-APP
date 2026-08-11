package org.joinmastodon.android.novel

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelHomeUiContractTest {
	private val projectDir = File(requireNotNull(System.getProperty("user.dir")))
	private val screen = File(projectDir, "src/main/kotlin/org/joinmastodon/android/novel/NovelHomeScreen.kt").readText()
	private val library = File(projectDir, "src/main/kotlin/org/joinmastodon/android/novel/NovelLibraryScreen.kt").readText()
	private val viewModel = File(projectDir, "src/main/kotlin/org/joinmastodon/android/novel/NovelLibraryViewModel.kt").readText()
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
		assertTrue(library.contains("上传 TXT"))
		assertTrue(library.contains("上传 EPUB"))
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

	@Test fun libraryUsesMiuixDialogsAndRendersDismissibleErrors() {
		assertFalse(library.contains("androidx.compose.material3.AlertDialog"))
		assertFalse(library.contains("androidx.compose.ui.window.Dialog"))
		assertFalse(library.contains("OutlinedTextField"))
		assertTrue(library.contains("top.yukonga.miuix.kmp.overlay.OverlayDialog"))
		assertTrue(library.contains("top.yukonga.miuix.kmp.basic.TextField"))
		assertTrue(library.contains("top.yukonga.miuix.kmp.basic.TextButton"))
		assertTrue(library.contains("state.error"))
		assertTrue(library.contains("onDismissError"))
	}

	@Test fun miuixDialogsPackageTheirNavigationEventRuntime() {
		val build = File(projectDir, "build.gradle").readText()
		val rootBuild = File(projectDir.parentFile, "build.gradle").readText()
		assertTrue(build.contains("androidx.navigationevent:navigationevent:1.0.2"))
		assertTrue(build.contains("androidx.navigationevent:navigationevent-compose:1.0.2"))
		assertTrue(rootBuild.contains("com.android.tools.build:gradle:8.9.1"))
		assertTrue(activity.contains("NavigationEventDispatcherOwner"))
		assertTrue(activity.contains("LocalNavigationEventDispatcherOwner provides this"))
	}

	@Test fun readerActionsReachViewModelAndProductionSyncFacade() {
		assertTrue(screen.contains("onBookmark ="))
		assertTrue(screen.contains("libraryViewModel.addBookmark"))
		assertTrue(screen.contains("onNote ="))
		assertTrue(screen.contains("libraryViewModel.addNote"))
		assertTrue(viewModel.contains("fun addBookmark("))
		assertTrue(viewModel.contains("syncWrites.saveBookmark"))
		assertTrue(viewModel.contains("fun addNote("))
		assertTrue(viewModel.contains("syncWrites.saveAnnotation"))
	}

	@Test fun safFailureIsReportedThroughVisibleViewModelError() {
		assertTrue(library.contains("handleSelectedDocument"))
		assertTrue(screen.contains("libraryViewModel::reportError"))
		assertTrue(viewModel.contains("fun reportError(message: String)"))
	}

	@Test fun libraryUsesCompatibleSingleDocumentPicker() {
		assertTrue(library.contains("ActivityResultContracts.GetContent()"))
		assertTrue(library.contains("picker.launch(\"text/plain\")"))
		assertTrue(library.contains("picker.launch(\"application/epub+zip\")"))
		assertFalse(library.contains("picker.launch(\"*/*\")"))
		assertFalse(library.contains("ActivityResultContracts.OpenDocument()"))
	}
}
