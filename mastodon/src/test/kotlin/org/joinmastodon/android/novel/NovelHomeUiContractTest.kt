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
	private val authoring = File(projectDir, "src/main/kotlin/org/joinmastodon/android/novel/author/AuthoringScreen.kt").readText()
	private val authoringViewModel = File(projectDir, "src/main/kotlin/org/joinmastodon/android/novel/author/AuthoringViewModel.kt").readText()
	private val chapterEditorActivity = File(projectDir, "src/main/kotlin/org/joinmastodon/android/novel/author/NovelChapterEditorActivity.kt").readText()
	private val structureActivity = File(projectDir, "src/main/kotlin/org/joinmastodon/android/novel/author/NovelWorkStructureActivity.kt").readText()
	private val lineNumberEditor = File(projectDir, "src/main/kotlin/org/joinmastodon/android/novel/author/NovelLineNumberEditor.kt").readText()
	private val authoringApi = File(projectDir, "src/main/java/org/joinmastodon/android/api/novels/NovelAuthoringApi.java").readText()

	@Test
	fun novelHomeUsesMiuixPageChrome() {
		assertTrue(screen.contains("top.yukonga.miuix.kmp.basic.Scaffold"))
		assertTrue(screen.contains("top.yukonga.miuix.kmp.basic.SmallTopAppBar"))
		assertTrue(screen.contains("NovelTabBar"))
	}

	@Test
	fun bookshelfIsWiredToAccountScopedLibrary() {
		assertTrue(screen.contains("fun NovelHomeScreen(accountId: String, libraryViewModel: NovelLibraryViewModel"))
		assertTrue(screen.contains("NovelLibraryScreen"))
		assertTrue(activity.contains("NovelHomeScreen(accountId = accountID"))
		assertTrue(library.contains("导入小说"))
		assertTrue(library.contains("也可从文件管理器“打开方式”或“分享”到 ABDL Space"))
		assertTrue(library.contains("可离线阅读"))
		assertTrue(library.contains("onDownload"))
		assertTrue(library.contains("onDelete"))
		assertTrue(library.contains("top.yukonga.miuix.kmp.basic.Card"))
		assertTrue(activity.contains("Intent.ACTION_VIEW"))
		assertTrue(activity.contains("Intent.ACTION_SEND"))
		assertTrue(activity.contains("if (externalDocument != null) AccountSessionManager.getInstance().lastActiveAccountID"))
		assertTrue(activity.contains("if (savedInstanceState == null) externalDocument(intent) else null"))
		assertTrue(screen.contains("mutableIntStateOf(if (externalDocument == null) 0 else 1)"))
	}

	@Test fun novelLibraryIsTextFirstAndHasNoCoverSystem() {
		assertFalse(library.contains("coverUri"))
		assertFalse(library.contains("封面"))
		assertTrue(library.contains("章节"))
		assertTrue(library.contains("下载到本机"))
		assertFalse(screen.contains("作者功能正在建设"))
		assertTrue(screen.contains("AuthoringScreen"))
		assertTrue(library.contains("BookStateTile"))
		assertTrue(library.contains("StatusBadge"))
		assertTrue(library.contains("PrimaryBookAction"))
		assertTrue(library.contains("clickable(enabled = enabled"))
	}

	@Test fun creationTabUsesRealEligibilityAndDraftWorks() {
		assertTrue(screen.contains("authoringViewModel: AuthoringViewModel"))
		assertTrue(activity.contains("AuthoringViewModel(application, accountID)"))
		assertTrue(authoringViewModel.contains("loadEligibility"))
		assertTrue(authoringViewModel.contains("loadWorks"))
		assertTrue(authoringViewModel.contains("creating = true"))
		assertTrue(authoring.contains("创作中心"))
		assertTrue(authoring.contains("注册已满 72 小时"))
		assertTrue(authoring.contains("至少发布 1 条当前存在的帖子"))
		assertTrue(authoring.contains("新建作品"))
		assertTrue(authoring.contains("当前只创建私有草稿，不会立即公开"))
		assertTrue(authoring.contains("OverlayDialog"))
		assertFalse(authoring.contains("封面"))
		assertFalse(authoring.contains("提交审核"))
		assertTrue(authoringApi.contains("/api/v1/novels/authoring"))
		assertTrue(authoringApi.contains("cache(null)"))
		assertTrue(authoringApi.contains("Idempotency-Key"))
		assertTrue(authoringApi.contains("/structure"))
		assertTrue(authoringApi.contains("/volumes/"))
		assertTrue(authoringViewModel.contains("openWork("))
		assertTrue(authoringViewModel.contains("createVolume("))
		assertTrue(authoringViewModel.contains("createChapter("))
		assertTrue(authoringViewModel.contains("renameVolume("))
		assertTrue(authoringViewModel.contains("renameChapter("))
		assertTrue(authoringViewModel.contains("deleteVolume("))
		assertTrue(authoringViewModel.contains("deleteChapter("))
		assertTrue(authoring.contains("作品目录"))
		assertTrue(authoring.contains("新建分卷"))
		assertTrue(authoring.contains("新建章节"))
		assertTrue(authoring.contains("点击章节进入独立编辑页"))
		assertTrue(authoring.contains("BackHandler"))
		assertTrue(authoring.contains("NovelChapterEditorScreen"))
		assertTrue(screen.contains("NovelWorkStructureActivity.intent"))
		assertTrue(structureActivity.contains("class NovelWorkStructureActivity : ComponentActivity"))
		assertTrue(structureActivity.contains("viewModel.openWork(workId)"))
		assertTrue(structureActivity.contains("NovelChapterEditorActivity.intent"))
		assertTrue(chapterEditorActivity.contains("class NovelChapterEditorActivity : ComponentActivity"))
		assertTrue(chapterEditorActivity.contains("viewModel.openChapter(workId, chapter)"))
		assertTrue(chapterEditorActivity.contains("NovelChapterEditorScreen(state, viewModel, ::finish)"))
		assertTrue(authoringViewModel.contains("chapterMutex(inputKey).withLock"))
		assertFalse(authoring.contains("if (state.editingChapter != null)"))
		assertTrue(authoring.contains("AndroidView"))
		assertTrue(authoring.contains("PrimaryAction(\"新建作品\""))
		assertTrue(authoring.contains("MiuixIcons.FolderFill"))
		assertTrue(authoring.contains("已同步"))
		assertFalse(authoring.contains("label = \"章节正文\""))
		assertTrue(authoring.contains("NovelLineNumberEditor"))
		assertTrue(lineNumberEditor.contains("class NovelLineNumberEditor"))
		assertTrue(lineNumberEditor.contains("addView(divider"))
		assertTrue(lineNumberEditor.contains("getLineBaseline"))
		assertTrue(lineNumberEditor.contains("for (line in firstLine..lastLine)"))
		assertFalse(lineNumberEditor.contains("rebuildLineStarts"))
		assertFalse(authoring.contains("repeat(lineCount)"))
		assertTrue(authoring.contains("保留本地副本"))
		assertTrue(authoring.contains("采用云端"))
		assertTrue(authoring.contains("复制文本"))
		assertTrue(authoringViewModel.contains("saveChapterContent("))
		assertTrue(authoringViewModel.contains("AuthorDraftSyncWorker.enqueue"))
		assertTrue(authoringApi.contains("base_version"))
		assertTrue(authoringApi.contains("DraftConflictException"))
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

	@Test fun novelInteractionsUseSheetsLoadingBackAndAnimatedTabs() {
		assertTrue(library.contains("OverlayBottomSheet"))
		assertTrue(library.contains("BasicComponent"))
		assertTrue(library.contains("CircularProgressIndicator"))
		assertTrue(library.contains("同步请求已提交"))
		assertTrue(viewModel.contains("openingBookId"))
		assertTrue(screen.contains("BackHandler(enabled = libraryState.reader != null)"))
		assertTrue(screen.contains("ReaderPalette.NIGHT"))
		assertTrue(screen.contains("animateDpAsState"))
		assertTrue(screen.contains("NovelTabIndicatorOffset"))
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
