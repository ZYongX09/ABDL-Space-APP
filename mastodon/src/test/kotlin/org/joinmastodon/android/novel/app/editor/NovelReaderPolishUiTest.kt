@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package org.joinmastodon.android.novel.app.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Alignment
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
import org.joinmastodon.android.novel.app.design.NovelIcons
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
        val bounds = node.boundsInWindow
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

    private fun pagerNode(): SemanticsNode = compose.nodes().single {
        it.config.getOrNull(SemanticsActions.ScrollToIndex)?.action != null
    }

    private fun assertNoPaginationSpinner() {
        assertFalse("Toolbar gestures must not recreate pagination or flash its spinner", compose.nodes().any {
            it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null
        })
    }

    private fun dispatchTouch(down: Long, time: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, time, action, x, y, 0)
        try { assertTrue(compose.activity.window.decorView.dispatchTouchEvent(event)) } finally { event.recycle() }
        compose.settle(frames = 1)
        assertNoPaginationSpinner()
    }

    private fun tapBody(page: ActualPage = awaitPage()) {
        val bounds = page.node.boundsInWindow
        val x = (maxOf(bounds.left, 0f) + minOf(bounds.right, compose.viewport().first.toFloat())) / 2
        // Below the top overlay and above the bottom-end host actions, even on a short final page.
        val pager = pagerNode().boundsInWindow
        val y = maxOf((maxOf(bounds.top, 0f) + minOf(bounds.bottom, compose.viewport().second.toFloat())) / 2,
            pager.top + pager.height * .55f).coerceAtMost(pager.bottom - 80f)
        val down = SystemClock.uptimeMillis()
        dispatchTouch(down, down, MotionEvent.ACTION_DOWN, x, y)
        compose.settle(frames = 4)
        dispatchTouch(down, down + 80, MotionEvent.ACTION_UP, x, y)
        compose.settle()
        assertNoPaginationSpinner()
    }

    private fun swipeBody(forward: Boolean) {
        val bounds = pagerNode().boundsInWindow
        val start = bounds.left + bounds.width * if (forward) .85f else .15f
        val end = bounds.left + bounds.width * if (forward) .15f else .85f
        val y = bounds.top + bounds.height * .6f
        val down = SystemClock.uptimeMillis()
        dispatchTouch(down, down, MotionEvent.ACTION_DOWN, start, y)
        repeat(12) { step ->
            val fraction = (step + 1) / 12f
            dispatchTouch(down, down + (step + 1) * 16L, MotionEvent.ACTION_MOVE, start + (end - start) * fraction, y)
        }
        dispatchTouch(down, down + 208, MotionEvent.ACTION_UP, end, y)
        compose.settle(frames = 100)
        assertNoPaginationSpinner()
    }

    private fun scrollToPage(index: Int) {
        assertTrue(requireNotNull(pagerNode().config[SemanticsActions.ScrollToIndex].action).invoke(index))
        compose.settle()
    }

    private fun assertSameReadingSurface(before: ActualPage, after: ActualPage, pagerId: Int, pagerBounds: Rect) {
        assertEquals("Toolbar visibility must not change the source range", before.text, after.text)
        assertEquals("Toolbar visibility must not change the text's pixel viewport", before.layout.layoutInput.constraints, after.layout.layoutInput.constraints)
        assertEquals("The same page Text stays composed", before.node.id, after.node.id)
        assertEquals("Text keeps its original placement", before.bounds, after.bounds)
        assertEquals("Do not recreate the Pager when tools toggle", pagerId, pagerNode().id)
        assertEquals("Pager keeps the original full-width bounds", pagerBounds, pagerNode().boundsInWindow)
        assertNoPaginationSpinner()
    }

    private fun assertFullWidthPager(page: ActualPage, expectedBounds: Rect) {
        assertEquals("Pager must span the original safe reading surface, not its padded text area", expectedBounds, pagerNode().boundsInWindow)
        assertEquals(expectedBounds.left + 28f, page.node.boundsInWindow.left, .5f)
        assertEquals(expectedBounds.top + 18f, page.node.boundsInWindow.top, .5f)
        assertEquals(expectedBounds.width - 56f, page.layout.layoutInput.constraints.maxWidth.toFloat(), .5f)
        assertEquals(expectedBounds.height - 36f, page.layout.layoutInput.constraints.maxHeight.toFloat(), .5f)
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
        val legacyMaterialGeometry = renderOriginalMaterialActionsGeometry()
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
        val pager = pagerNode()
        val pagerBounds = pager.boundsInWindow
        assertFullWidthPager(hidden, Rect(0f, 0f, compose.viewport().first.toFloat(), compose.viewport().second.toFloat()))
        compose.onNodeWithContentDescription("书签与笔记").assertDoesNotExist()
        compose.onNodeWithContentDescription("章节目录").assertDoesNotExist()
        tapBody(hidden)
        val shown = awaitPage()
        compose.onNodeWithContentDescription("书签与笔记").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithContentDescription("章节目录").assertIsDisplayed().assertHasClickAction()
        assertSameReadingSurface(hidden, shown, pager.id, pagerBounds)
        assertActionsAtOriginalBottomEnd(legacyMaterialGeometry)
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
                toolbarActions = {
                    ReaderHostToolbarActions(true, backdrop, { annotations++ }, { directory++ },
                        Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(8.dp))
                },
                contentBackdropModifier = Modifier.onGloballyPositioned { contentBounds = it.boundsInRoot() }
                    .drawWithContent { drawContent(); contentDraws++ },
                onBack = {})
        }
        val initial = awaitPage()
        val initialContentBounds = contentBounds
        val pager = pagerNode()
        val pagerBounds = pager.boundsInWindow
        assertTrue(initialContentBounds.height > 0)
        assertFullWidthPager(initial, initialContentBounds)
        compose.onNodeWithContentDescription("章节目录").assertDoesNotExist()
        tapBody(initial)
        val visible = awaitPage()
        assertEquals("Overlay tools must not shrink the text/backdrop viewport", initialContentBounds, contentBounds)
        assertSameReadingSurface(initial, visible, pager.id, pagerBounds)
        assertActionsAtOriginalBottomEnd()
        compose.onNodeWithContentDescription("书签与笔记").performClick()
        compose.onNodeWithContentDescription("章节目录").performClick()
        assertEquals(1, annotations); assertEquals(1, directory)
        // API28 exercises the real MorphingGlassContainer's native fallback, not hardware refraction.
        captureNative("reader-glass-component-api28-fallback-393x852")
        assertTrue("The content-only backdrop modifier must actually draw", contentDraws > 0)
        tapBody(visible)
        val hiddenAgain = awaitPage()
        compose.onNodeWithContentDescription("书签与笔记").assertDoesNotExist()
        compose.onNodeWithContentDescription("章节目录").assertDoesNotExist()
        assertEquals(initialContentBounds, contentBounds)
        assertSameReadingSurface(initial, hiddenAgain, pager.id, pagerBounds)
    }

    @Test fun fullWidthPagerTouchSwipesKeepConsecutiveSourcePagesAcrossToolbarToggles() {
        val reports = mutableListOf<Int>()
        fixture.show(compose) {
            ReaderScreen(book, listOf(chapter), onPositionChanged = { reports += it.pageIndex }, onBack = {})
        }
        val first = awaitPage()
        val pager = pagerNode()
        val pagerBounds = pager.boundsInWindow
        assertFullWidthPager(first, Rect(0f, 0f, compose.viewport().first.toFloat(), compose.viewport().second.toFloat()))
        assertEquals(0, content.indexOf(first.text))
        assertEquals(0, reports.last())
        tapBody(first)
        assertSameReadingSurface(first, awaitPage(), pager.id, pagerBounds)
        compose.onNodeWithText("设置").assertIsDisplayed()
        swipeBody(forward = true)
        val second = awaitPage { it.text != first.text && reports.lastOrNull() == 1 }
        assertEquals("A real swipe advances to the next contiguous source range", first.text.length, content.indexOf(second.text))
        compose.onNodeWithText("设置").assertIsDisplayed()
        tapBody(second)
        val hidden = awaitPage()
        assertSameReadingSurface(second, hidden, pager.id, pagerBounds)
        compose.onNodeWithText("设置").assertDoesNotExist()
        swipeBody(forward = true)
        val third = awaitPage { it.text != second.text && reports.lastOrNull() == 2 }
        assertEquals(content.indexOf(second.text) + second.text.length, content.indexOf(third.text))
        assertTrue("Rendered pages must concatenate an exact prefix of the unmodified Unicode source",
            content.startsWith(first.text + second.text + third.text))
        swipeBody(forward = false)
        assertEquals(second.text, awaitPage { it.text == second.text && reports.lastOrNull() == 1 }.text)
        swipeBody(forward = false)
        assertEquals(first.text, awaitPage { it.text == first.text && reports.lastOrNull() == 0 }.text)
        assertEquals(listOf(0, 1, 2, 1, 0), reports)
        assertEquals(pager.id, pagerNode().id)
        assertEquals(pagerBounds, pagerNode().boundsInWindow)
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

    private data class ActionGeometry(val visual: Rect, val touch: Rect)

    private fun actionNodes() = compose.nodes().filter { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == "章节目录" || it == "书签与笔记" } == true
    }.sortedBy { it.boundsInRoot.left }

    private fun actionGeometry() = actionNodes().map { ActionGeometry(it.boundsInRoot, it.touchBoundsInRoot) }

    /** Render the exact 96ea7b45 Row/default Material buttons in the same attached native fixture.
     * A default button draws 40dp inside a 48dp touch slot: visual end/bottom are 12dp, not 8dp.
     * Compare both rectangles exactly; do not shift production placement to satisfy visual semantics.
     */
    private fun renderOriginalMaterialActionsGeometry(): List<ActionGeometry> {
        fixture.show(compose) {
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalIconButton(onClick = {}) { Icon(NovelIcons.Bookmark, "书签与笔记") }
                    FilledTonalIconButton(onClick = {}) { Icon(NovelIcons.List, "章节目录") }
                }
            }
        }
        val (width, height) = compose.viewport()
        val expectedTouch = listOf(
            Rect(width - 108f, height - 56f, width - 60f, height - 8f),
            Rect(width - 56f, height - 56f, width - 8f, height - 8f),
        )
        val expected = expectedTouch.map { touch -> ActionGeometry(
            Rect(touch.left + 4f, touch.top + 4f, touch.right - 4f, touch.bottom - 4f), touch,
        ) }
        return actionGeometry().also {
            assertEquals("Original Material defaults must render 40dp visuals centered in the old 48dp slots", expected, it)
        }
    }

    private fun assertActionsAtOriginalBottomEnd(legacyMaterialGeometry: List<ActionGeometry>? = null) {
        val actions = actionNodes()
        assertEquals(2, actions.size)
        val (width, height) = compose.viewport()
        val geometry = actionGeometry()
        assertEquals(listOf("书签与笔记"), actions[0].config[SemanticsProperties.ContentDescription])
        assertEquals(listOf("章节目录"), actions[1].config[SemanticsProperties.ContentDescription])
        if (legacyMaterialGeometry != null) {
            assertEquals("Production Material visuals AND touch slots must match the actual old Row render exactly", legacyMaterialGeometry, geometry)
        } else {
            // Glass has a 48dp visual container, unlike the default Material button's 40dp visual.
            assertEquals(width - 8f, geometry[1].visual.right, 0f)
            assertEquals(height - 8f, geometry[1].visual.bottom, 0f)
            assertEquals(4f, geometry[1].visual.left - geometry[0].visual.right, 0f)
        }
        assertEquals("Keep the old 8dp touch-slot end padding", width - 8f, geometry[1].touch.right, 0f)
        assertEquals("Keep the old 8dp touch-slot bottom padding", height - 8f, geometry[1].touch.bottom, 0f)
        assertEquals("Keep the old 4dp spacing between touch slots", 4f, geometry[1].touch.left - geometry[0].touch.right, 0f)
        geometry.forEach {
            assertEquals("Exactly 48dp reader touch target width", 48f, it.touch.width, 0f)
            assertEquals("Exactly 48dp reader touch target height", 48f, it.touch.height, 0f)
        }
        val title = compose.nodes(unmerged = true).single { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == book.title
        }
        assertEquals("Host actions must not crowd the original centered title row", width / 2f, title.boundsInWindow.center.x, .5f)
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
                ?: "/home/ZYongX/.local/share/abdl-novel-reader-upload-20261030/android-ui-renders/reader")
            assertTrue(directory.isDirectory || directory.mkdirs())
            val file = File(directory, "$name.png")
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue(file.length() > 2048)
        } finally { bitmap.recycle() }
    }
}
