@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package org.joinmastodon.android.novel.app.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.novel.app.NovelScreenshotFixture
import org.joinmastodon.noveleditor.NovelEditorStorage
import org.joinmastodon.reader.domain.BookFormat
import org.joinmastodon.reader.domain.ReaderBook
import org.joinmastodon.reader.domain.ReaderChapter
import org.joinmastodon.reader.domain.ReadingSettings
import org.joinmastodon.reader.ui.PagedReader
import org.joinmastodon.reader.ui.ReaderScreen
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import java.io.File
import java.util.UUID

/** Real attached production Compose + native font metrics. No Espresso/ComposeTestRule idle loop. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w393dp-h852dp-notnight-mdpi", application = CompatibilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NovelReaderPolishUiTest {
    private val fixture = NovelScreenshotFixture("reader")
    private val compose get() = fixture.host
    @get:Rule val rules: org.junit.rules.TestRule = fixture
    private val models = ViewModelStore()
    private val account = "reader-polish-${UUID.randomUUID()}"
    private val book = ReaderBook("reader-polish", "实际阅读测试", format = BookFormat.TXT)
    private val content = (1..140).joinToString("") {
        "【段落$it】中文$it 与 Latin$it words$it 保留$it 全部$it 原文$it、标点$it 和空白$it。e\u0301$it \uD83D\uDC69\uD83C\uDFFD\u200D\uD83D\uDE80$it\n\n"
    } + "尾行空白  \r\n"
    private val chapter get() = ReaderChapter("chapter", book.id, 0, "第一章", content, "chapter")

    @After fun cleanup() {
        compose.runOnUiThread { models.clear() }
        compose.activity.application.deleteDatabase(NovelEditorStorage.databaseName(account))
    }

    private data class ActualPage(val node: SemanticsNode, val layout: TextLayoutResult) {
        val text get() = layout.layoutInput.text.text
    }

    private fun currentPage(source: String = content): ActualPage? = compose.nodes(unmerged = true).mapNotNull { node ->
        val text = node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text ?: return@mapNotNull null
        if (text.isEmpty() || !source.contains(text) || !compose.visible(node)) return@mapNotNull null
        val layouts = mutableListOf<TextLayoutResult>()
        if (node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) != true) return@mapNotNull null
        layouts.singleOrNull()?.let { ActualPage(node, it) }
    }.maxByOrNull { page ->
        val bounds = page.node.boundsInWindow
        (minOf(bounds.right, compose.viewport().first.toFloat()) - maxOf(0f, bounds.left)).coerceAtLeast(0f)
    }

    /** Pagination runs on the real Default dispatcher. Wait a bounded time for it, not fake test IO. */
    private fun awaitPage(source: String = content, accept: (ActualPage) -> Boolean = { true }): ActualPage {
        repeat(300) {
            compose.settle(frames = 2)
            currentPage(source)?.takeIf(accept)?.let { assertFits(it); return it }
            Thread.sleep(10)
        }
        error("Measured page did not settle; windows=${compose.windowDiagnostic()}")
    }

    private fun assertFits(page: ActualPage) {
        val layout = page.layout
        val viewport = layout.layoutInput.constraints
        assertFalse("The actual rendered Text must not clip ${page.text.take(30)}", layout.hasVisualOverflow)
        assertTrue(layout.multiParagraph.height <= viewport.maxHeight)
        for (line in 0 until layout.lineCount) {
            assertTrue(layout.getLineTop(line) >= 0f)
            assertTrue(layout.getLineBottom(line) <= viewport.maxHeight)
            assertTrue(layout.getLineLeft(line) >= 0f)
            assertTrue(layout.getLineRight(line) <= viewport.maxWidth)
        }
    }

    private fun tapBody(page: ActualPage = awaitPage()) {
        val bounds = page.node.boundsInWindow
        val x = ((maxOf(bounds.left, 0f) + minOf(bounds.right, compose.viewport().first.toFloat())) / 2)
        val y = ((maxOf(bounds.top, 0f) + minOf(bounds.bottom, compose.viewport().second.toFloat())) / 2)
        val down = SystemClock.uptimeMillis()
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + 80)) {
            val event = MotionEvent.obtain(down, time, action, x, y, 0)
            try { compose.activity.window.decorView.dispatchTouchEvent(event) } finally { event.recycle() }
            compose.settle(frames = 2)
        }
        compose.settle()
    }

    private fun scrollToPage(index: Int) {
        val pager = compose.nodes().first { it.config.getOrNull(SemanticsActions.ScrollToIndex)?.action != null }
        assertTrue(requireNotNull(pager.config[SemanticsActions.ScrollToIndex].action).invoke(index))
        compose.settle()
    }

    @Test fun renderedPageKeepsItsSourceAnchorAcrossFontScaleLineHeightPaddingAndViewportRoundTrips() {
        var settings by mutableStateOf(ReadingSettings())
        var fontScale by mutableFloatStateOf(1f)
        var width by mutableIntStateOf(337)
        var height by mutableIntStateOf(520)
        var reportedPage = -1
        fixture.show(compose) {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                Box(Modifier.width(width.dp).height(height.dp)) {
                    PagedReader(chapter, settings, 2, { reportedPage = it }, {})
                }
            }
        }
        val first = awaitPage { reportedPage == 2 }
        assertTrue(content.indexOf(first.text) > 0)
        scrollToPage(3)
        val selected = awaitPage { reportedPage == 3 && it.text != first.text }
        val anchor = content.indexOf(selected.text)
        assertTrue(anchor > 0)
        fun containsAnchor(page: ActualPage): Boolean {
            val start = content.indexOf(page.text)
            return start >= 0 && anchor in start until start + page.text.length
        }
        repeat(2) {
            compose.runOnUiThread { fontScale = 1.6f }
            awaitPage { containsAnchor(it) && it.layout.layoutInput.density.fontScale == 1.6f }
            compose.runOnUiThread { settings = settings.copy(lineHeight = 2.05f, horizontalPadding = 36f) }
            awaitPage { containsAnchor(it) && it.layout.layoutInput.style.lineHeight.value > 38f }
            compose.runOnUiThread { width = 270; height = 360 }
            awaitPage { containsAnchor(it) && it.layout.layoutInput.constraints.maxWidth <= 198 }
            compose.runOnUiThread { settings = ReadingSettings(); fontScale = 1f; width = 337; height = 520 }
            assertEquals("Repeated reflow must not gradually walk back through the source", selected.text,
                awaitPage { it.text == selected.text }.text)
        }
    }

    @Test fun savedSourceAnchorRestoresAfterCompositionRecreationWithDifferentFontScaleAndSize() {
        var registry by mutableStateOf(SaveableStateRegistry(null) { true })
        var shown by mutableStateOf(true)
        var scale by mutableFloatStateOf(1f)
        var height by mutableIntStateOf(520)
        var reportedPage = -1
        fixture.show(compose) {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry, LocalDensity provides Density(1f, scale)) {
                if (shown) Box(Modifier.width(337.dp).height(height.dp)) {
                    PagedReader(chapter, ReadingSettings(), 2, { reportedPage = it }, {})
                }
            }
        }
        val before = awaitPage { reportedPage == 2 }
        val anchor = content.indexOf(before.text)
        val saved = registry.performSave()
        compose.runOnUiThread { shown = false }
        compose.settle()
        compose.runOnUiThread { registry = SaveableStateRegistry(saved) { true }; scale = 1.5f; height = 350; shown = true }
        val restored = awaitPage { it.layout.layoutInput.density.fontScale == 1.5f }
        val start = content.indexOf(restored.text)
        assertTrue("Restore a source offset, not the old page number", anchor in start until start + restored.text.length)
    }

    @Test fun productionHostActionsOnlyExistInVisibleToolbarAndDirectoryOpensFromItsActualButton() {
        val app = compose.activity.application
        val work = NovelEditorStorage(app, account).use { storage ->
            storage.createWork(book.title, "测试", "fiction").also { storage.checkpoint(it.chapterId(), content, 0, 0, 0, true) }
        }
        val controller = NovelReaderController(app, NovelEditorRepository(app, account, EditorTestTransport(), Dispatchers.Unconfined), null, work.workId(), null)
        models.put("reader", controller)
        compose.runOnUiThread { controller.start() }
        fixture.show(compose) {
            val state by controller.state.collectAsState()
            ReaderHostScreen(state, controller) {}
        }
        val hidden = awaitPage()
        compose.onNodeWithContentDescription("书签与笔记").assertDoesNotExist()
        compose.onNodeWithContentDescription("章节目录").assertDoesNotExist()
        tapBody(hidden)
        val shown = awaitPage()
        compose.onNodeWithContentDescription("书签与笔记").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithContentDescription("章节目录").assertIsDisplayed().assertHasClickAction()
        assertActionsAboveBody(shown)
        captureNative("reader-toolbar-light-393x852")
        compose.onNodeWithContentDescription("章节目录").performClick()
        compose.assertDialogShowing()
        compose.onNodeWithText("章节目录").assertIsDisplayed()
        compose.onNodeWithText("1 · 第一章").assertIsDisplayed()
    }

    @Test fun glassToolbarSlotAndContentBackdropHaveSeparateBoundsAndDisappearTogetherWithTools() {
        var annotations = 0
        var directory = 0
        var contentBounds = Rect.Zero
        var contentDraws = 0
        fixture.show(compose) {
            val backdrop = rememberLayerBackdrop()
            ReaderScreen(book, listOf(chapter),
                toolbarActions = { ReaderHostToolbarActions(true, backdrop, { annotations++ }, { directory++ }) },
                contentBackdropModifier = Modifier.onGloballyPositioned { contentBounds = it.boundsInRoot() }
                    .drawWithContent { drawContent(); contentDraws++ },
                onBack = {})
        }
        val initial = awaitPage()
        val fullHeight = contentBounds.height
        assertTrue(fullHeight > 0)
        compose.onNodeWithContentDescription("章节目录").assertDoesNotExist()
        tapBody(initial)
        val visible = awaitPage()
        assertTrue(contentBounds.height < fullHeight)
        assertActionsAboveBody(visible, contentBounds)
        compose.onNodeWithContentDescription("书签与笔记").performClick()
        compose.onNodeWithContentDescription("章节目录").performClick()
        assertEquals(1, annotations); assertEquals(1, directory)
        // API28 exercises the real MorphingGlassContainer's native fallback, not hardware refraction.
        captureNative("reader-glass-component-api28-fallback-393x852")
        assertTrue("The content-only backdrop modifier must actually draw", contentDraws > 0)
        tapBody(visible)
        awaitPage()
        compose.onNodeWithContentDescription("书签与笔记").assertDoesNotExist()
        compose.onNodeWithContentDescription("章节目录").assertDoesNotExist()
        assertEquals(fullHeight, contentBounds.height, .5f)
    }

    @Test fun legacyTrailingBackLambdaAndDefaultSlotsStillRenderAndInvokeBack() {
        var backs = 0
        // Deliberately retain the pre-extension call shape to check default-compatible API source.
        fixture.show(compose) { ReaderScreen(book, listOf(chapter)) { backs++ } }
        tapBody()
        awaitPage()
        compose.onNodeWithText("返回").performClick()
        assertEquals(1, backs)
        compose.onNodeWithContentDescription("章节目录").assertDoesNotExist()
    }

    private fun assertActionsAboveBody(page: ActualPage, contentBounds: Rect? = null) {
        val actions = compose.nodes().filter { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == "章节目录" || it == "书签与笔记" } == true
        }
        assertEquals(2, actions.size)
        actions.forEach { node ->
            assertTrue("Host action must stay above the actual body, never float over its text", node.boundsInWindow.bottom <= page.node.boundsInWindow.top)
            contentBounds?.let { assertTrue(node.boundsInRoot.bottom <= it.top) }
            assertTrue("Minimum 48dp reader action target", node.boundsInWindow.width >= 48f && node.boundsInWindow.height >= 48f)
        }
    }

    private fun captureNative(name: String) {
        compose.settle()
        val view = compose.attachedComposeView
        assertTrue(view.isAttachedToWindow && view.width > 0 && view.height > 0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val samples = buildList { for (y in 0 until bitmap.height step 5) for (x in 0 until bitmap.width step 5) add(bitmap.getPixel(x, y)) }
            assertTrue("Require actual native text/icon rasterization, not a blank page", samples.distinct().size > 24)
            val directory = File(System.getProperty("readerRenderDir")
                ?: "/home/ZYongX/.local/share/abdl-novel-polish-20261029/android-ui-renders/reader")
            assertTrue(directory.isDirectory || directory.mkdirs())
            val file = File(directory, "$name.png")
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue(file.length() > 2048)
        } finally { bitmap.recycle() }
    }
}
