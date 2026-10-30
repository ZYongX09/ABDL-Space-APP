package org.joinmastodon.android.novel.app.editor

import android.app.Application
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.novel.app.NovelAppTestFixtures
import org.joinmastodon.android.novel.app.NovelScreenshotFixture
import org.joinmastodon.android.novel.importer.NovelImportCoordinator
import org.joinmastodon.noveleditor.EditorModels
import org.joinmastodon.noveleditor.NovelEditorStorage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.UUID

/** The production creation screen and native manuscript widget, backed by actual isolated SQLite.
 * Imports go through the real parser/review/controller. Only the owner GET transport is substituted.
 * There are no real accounts, uploads, manual UI drawings, copied references or approved goldens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w393dp-h852dp-notnight-mdpi", application = CompatibilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NovelEditorScreenshotTest {
    private val fixture = NovelScreenshotFixture("editor")
    private val compose get() = fixture.host
    @get:Rule val rules: org.junit.rules.TestRule = fixture
    private val models = ViewModelStore()
    private val account = "novel-editor-screenshot-${UUID.randomUUID()}"
    private val api = EditorTestTransport()
    private lateinit var app: Application
    private lateinit var controller: NovelEditorController
    private var work: EditorModels.CreatedWork? = null
    private val chapters = mutableListOf<String>()
    private var importFile: File? = null
    private var left = 0
    private var deleted = 0
    private var preview: Pair<String, String?>? = null
    private val metadata = NovelMetadata(title = "柔软日常", description = "测试用虚构故事：成年朋友在日常中学习沟通与尊重边界。", tags = listOf("成年角色", "社群上传"), statements = listOf("内容不适宜未成年人阅读", "虚构内容，请勿当真"), accepted = true)

    @Before fun prepareController() {
        compose.runOnUiThread {
            app = compose.activity.application
            // Run isolated SQLite IO synchronously, not an unbounded background pool or fake repository.
            controller = NovelEditorController(app, NovelEditorRepository(app, account, api, Dispatchers.Unconfined)) { 0L }
            models.put("screenshot-editor", controller)
            api.reply = { request ->
                check(request.method == "GET" && request.path == "/owner/works/fixture-work-0") { "Unexpected screenshot network operation: $request" }
                NovelAppTestFixtures.workJson().put("client_work_id", requireNotNull(work).workId()).put("chapters", JSONArray(chapters.mapIndexed { index, id ->
                    JSONObject().put("id", id).put("title", chapterTitles[index]).put("sort_order", index).put("generation", 1)
                        .put("word_count", body.length).put("status", statuses[index])
                }))
            }
        }
    }

    @After fun releaseControllerAndIsolatedStorage() {
        compose.runOnUiThread { models.clear() }
        importFile?.delete()
        app.deleteDatabase(NovelEditorStorage.databaseName(account))
        File(app.filesDir, "novel-app/${NovelImportCoordinator.accountHash(account)}").deleteRecursively()
    }

    private fun show(dark: Boolean = false) {
        fixture.setDark(compose, dark)
        fixture.show(compose) {
            val state by controller.state.collectAsState()
            NovelCreationScreen(state, controller, onBack = { controller.leaveEditor { left++ } }, onDelete = { deleted++ }, onPreview = { w, c -> preview = w to c })
        }
    }
    private fun shot(id: Int, state: String, dialog: Boolean = false) = fixture.capture(compose, "novel-ui$id-$state-light-393x852", id, dialog)
    private fun click(text: String) { compose.onNodeWithText(text).performClick(); fixture.settle(compose) }
    private fun assertNoUpload() = assertTrue("Screenshots must not submit/upload any document", api.requests.all { it.method == "GET" })

    private fun openWork(editor: Boolean = false) {
        compose.runOnUiThread {
            val created = NovelEditorStorage(app, account).use { storage ->
                val created = storage.createWork(metadata.title, metadata.description, "fiction")
                storage.putAppMetadata(created.workId(), metadata.json().toString(), "fixture-work-0", "fixture-release-0")
                chapterTitles.forEachIndexed { index, title ->
                    val id = if (index == 0) created.chapterId() else storage.addChapter(created.workId(), created.volumeId(), title).id()
                    storage.renameChapter(id, title)
                    storage.checkpoint(id, body, 0, 0, 0, true)
                    storage.putRemoteChapter(id, statuses[index], if (statuses[index] == "pending") "fixture-submission" else null, if (statuses[index] == "rejected") "测试审核意见：请补充授权说明" else null, 1)
                    chapters += id
                }
                created
            }
            work = created
            controller.open(null, "fixture-work-0", "edit")
            assertEquals("manage", controller.state.value.page)
            if (editor) controller.openChapter(created.chapterId())
            assertNull(controller.state.value.error)
        }
    }

    @Test fun createMetadataRendersTheActualFormAndTitleFieldUpdatesTheRealController() {
        compose.runOnUiThread { controller.open(null, null, "create"); controller.metadata(metadata) }
        show()
        compose.onNodeWithText("新建作品").assertIsDisplayed()
        compose.onNodeWithText("作品名称 *").assertIsDisplayed()
        compose.onNodeWithText("写第一章  →").assertIsEnabled()
        shot(14, "create")
        compose.onNode(hasSetTextAction() and hasText(metadata.title)).performTextReplacement("测试用新故事")
        assertEquals("测试用新故事", controller.state.value.metadata.title)
        compose.onNodeWithText("AI辅助").performScrollTo().performClick()
        assertEquals("ai", controller.state.value.metadata.type)
        compose.onNodeWithText("AI 辅助说明 / 来源").assertExists()
        compose.onNodeWithText("授权转载").performScrollTo().performClick()
        compose.runOnUiThread { controller.metadata(controller.state.value.metadata.copy(originalAuthor = "待填写原作者")) }
        fixture.settle(compose)
        compose.onNode(hasSetTextAction() and hasText("待填写原作者")).performScrollTo().performTextReplacement("示例原作者")
        assertEquals("示例原作者", controller.state.value.metadata.originalAuthor)
        fixture.capture(compose, "metadata-repost-original-author")
        assertNoUpload()
    }

    @Test fun manuscriptRendersTheRealAttachedEditTextAndSaveDoesNotUpload() {
        openWork(editor = true); show()
        compose.onNodeWithText("小说编辑").assertIsDisplayed()
        compose.onNodeWithText("正文编辑").assertDoesNotExist()
        compose.onNodeWithText("提交章节审核").assertIsEnabled()
        compose.onNodeWithContentDescription("新段落并缩进").assertHasClickAction()
        compose.runOnUiThread {
            val native = requireNotNull(findManuscript(compose.activity.window.decorView))
            assertTrue("The actual AndroidView must be attached", native.isAttachedToWindow)
            val input = native.getChildAt(1) as EditText
            assertEquals(body, input.text.toString())
            assertTrue("Native text layout must really exist", input.layout != null && input.layout.lineCount > 2)
            assertTrue("Native widget must have a measured visible height", input.width > 0 && input.height > 100)
        }
        shot(13, "manuscript")
        compose.onNodeWithContentDescription("新段落并缩进").performClick(); fixture.settle(compose)
        assertTrue(controller.state.value.dirty)
        click("保存草稿")
        assertFalse(controller.state.value.dirty)
        NovelEditorStorage(app, account).use { assertEquals(controller.state.value.frame!!.body(), it.loadDraft(work!!.chapterId()).content()) }
        compose.onNodeWithText("预览", substring = true).performClick(); fixture.settle(compose)
        assertEquals(work!!.workId() to work!!.chapterId(), preview)
        assertNoUpload()
    }

    @Test fun managementRendersRealChapterStatusesAndItsMoreSheetIsOpenedByTheProductionButton() {
        openWork(); show()
        compose.onNodeWithText("作品管理").assertIsDisplayed()
        compose.onNodeWithText("章节管理").assertIsDisplayed()
        compose.onNodeWithText("新建章节", substring = true).assertIsEnabled()
        compose.onNodeWithText(chapterTitles.first()).assertIsDisplayed()
        shot(12, "manage")
        compose.onNodeWithContentDescription("更多操作").performClick(); fixture.settle(compose)
        compose.assertDialogShowing()
        compose.onNodeWithText("作品操作").assertIsDisplayed()
        compose.onNodeWithText("编辑作品资料").assertIsEnabled()
        compose.onNodeWithText("下架作品").assertHasClickAction()
        shot(5, "work-actions", dialog = true)
        click("编辑作品资料")
        assertEquals("metadata", controller.state.value.page)
        compose.onNodeWithText("作品资料").assertIsDisplayed()
        assertNoUpload()
    }

    @Test fun importPickerPageRendersWithParseDisabledUntilARealDocumentIsSelected() {
        compose.runOnUiThread { controller.open(null, null, "import") }
        show()
        compose.onNodeWithText("上传文稿").assertIsDisplayed()
        compose.onNodeWithText("选择本地文件", substring = true).assertIsEnabled()
        compose.onNodeWithText("尚未选择文件").assertIsDisplayed()
        compose.onNodeWithText("解析并预览  →").assertIsNotEnabled()
        shot(10, "import")
        val recognition = compose.onNode(isToggleable())
        recognition.assertIsOn().performClick().assertIsOff()
        assertTrue("Choosing recognition options must not create a work", NovelEditorStorage(app, account).use { it.listWorks(false).isEmpty() })
        assertNoUpload()
    }

    @Test fun parsedPreviewAdjustmentAndSplitCaptureActualProductionPagesAndDialogWithoutChangingStoredWorks() {
        compose.runOnUiThread {
            controller.open(null, null, "import")
            importFile = File(app.cacheDir, "novel-screenshot-${UUID.randomUUID()}.txt").apply { writeText(importText) }
            controller.parse(Uri.fromFile(importFile!!), true)
            assertEquals("importPreview", controller.state.value.page)
            assertEquals(3, controller.state.value.importResult!!.chapters().size)
            assertNull(controller.state.value.error)
        }
        show()
        compose.onNodeWithText("解析预览").assertIsDisplayed()
        compose.onNodeWithText("章节识别").assertIsDisplayed()
        compose.onNodeWithText("确认导入为草稿").assertIsEnabled()
        shot(9, "import-preview")
        click("调整章节")
        assertEquals("adjust", controller.state.value.page)
        compose.onNodeWithText("调整章节").assertIsDisplayed()
        compose.onAllNodes(isToggleable())[0].performClick(); fixture.settle(compose)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("拆分章节"))
        compose.onNodeWithText("拆分章节").assertIsEnabled()
        shot(8, "adjust")
        click("拆分章节")
        compose.assertDialogShowing()
        compose.onNodeWithText("点击段落之间的空隙设置拆分点。总字数不变，原正文不会丢失。").assertIsDisplayed()
        compose.onNodeWithText("确认拆分").assertIsEnabled()
        val before = controller.state.value.importResult!!.chapters().map { it.content() }
        shot(7, "split", dialog = true)
        click("确认拆分")
        assertEquals(4, controller.state.value.importResult!!.chapters().size)
        assertEquals(before.joinToString(""), controller.state.value.importResult!!.chapters().joinToString("") { it.content() })
        click("完成调整")
        assertEquals("importPreview", controller.state.value.page)
        assertTrue("Preview/reorder/split must not persist a work before explicit import confirmation", NovelEditorStorage(app, account).use { it.listWorks(false).isEmpty() })
        assertNoUpload()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h640dp-night-mdpi")
    fun narrowDarkManuscriptKeepsTheActualTextWidgetAndPinnedSaveSubmitActionsVisible() {
        openWork(editor = true); show(dark = true)
        compose.onNodeWithText("小说编辑").assertIsDisplayed()
        compose.onNodeWithText("正文编辑").assertDoesNotExist()
        compose.onNodeWithText("保存草稿").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("提交章节审核").assertIsDisplayed().assertIsEnabled()
        compose.runOnUiThread {
            val native = requireNotNull(findManuscript(compose.activity.window.decorView))
            assertTrue(native.isAttachedToWindow && native.width > 0 && native.height > 0)
            assertEquals(body, (native.getChildAt(1) as EditText).text.toString())
        }
        fixture.capture(compose, "manuscript-dark-320x640", 13)
        assertNoUpload()
    }

    @Test fun nativeBodyFocusCompactsWithoutReplacingEditTextOrComposingAndDoneRestoresActions() {
        openWork(editor = true); show()
        val native = requireNotNull(findManuscript(compose.activity.window.decorView))
        val input = native.getChildAt(1) as EditText
        val beforeHeight = input.height
        fixture.capture(compose, "editing-before-focus")
        compose.runOnUiThread {
            assertTrue(input.requestFocus())
            val connection = requireNotNull(input.onCreateInputConnection(EditorInfo()))
            assertTrue(connection.setComposingText("正在组合", 1))
        }
        val editable = input.text
        fixture.settle(compose)
        assertSame(native, findManuscript(compose.activity.window.decorView))
        assertSame(input, requireNotNull(findManuscript(compose.activity.window.decorView)).getChildAt(1))
        assertSame(editable, input.text)
        assertTrue(input.hasFocus()); assertTrue(BaseInputConnection.getComposingSpanStart(input.text) >= 0)
        assertTrue("Compact mode must increase the real native writing area", input.height > beforeHeight)
        compose.onNodeWithText("提交章节审核").assertDoesNotExist()
        compose.onNodeWithText("完成").assertIsDisplayed().assertIsEnabled()
        fixture.capture(compose, "editor-focus-compact")
        val focusedHeight = input.height
        controller.saveExplicit(); fixture.settle(compose)
        assertSame(editable, input.text); assertTrue(BaseInputConnection.getComposingSpanStart(input.text) >= 0)
        compose.onNodeWithText("已保存到本机，不会自动上传").assertDoesNotExist()
        compose.onNodeWithText("已存本机").assertDoesNotExist()
        assertEquals("Saving must not insert a banner above the manuscript", focusedHeight, input.height)
        NovelEditorStorage(app, account).use { assertEquals(input.text.toString(), it.loadDraft(work!!.chapterId()).content()) }
        click("完成")
        assertSame(native, findManuscript(compose.activity.window.decorView))
        assertFalse(input.hasFocus())
        compose.onNodeWithText("提交章节审核").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("已保存到本机，不会自动上传").assertDoesNotExist()
        compose.onNodeWithText("已保存到本机").assertDoesNotExist()
        compose.onNodeWithText(" 预览").assertIsDisplayed().assertIsEnabled()
        fixture.capture(compose, "editor-focus-done")
        assertNoUpload()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h640dp-notnight-mdpi")
    fun threeDigitChapterNumbersHaveOneUnclippedMeasuredLineAtScaledFonts() {
        compose.runOnUiThread {
            val created = NovelEditorStorage(app, account).use { storage ->
                storage.createWork("百章目录", "真实数字测量", "fiction").also { created ->
                    storage.putAppMetadata(created.workId(), metadata.json().toString(), null, null)
                    repeat(102) { storage.addChapter(created.workId(), created.volumeId(), "章节${it + 2}") }
                }
            }
            work = created; controller.open(created.workId(), null, "edit")
        }
        fixture.show(compose) {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                val state by controller.state.collectAsState()
                NovelCreationScreen(state, controller, {}, {}, { _, _ -> })
            }
        }
        compose.onNodeWithText("倒序").performClick(); fixture.settle(compose)
        for (number in listOf(103, 102, 100)) {
            compose.onNodeWithText(number.toString()).performScrollTo()
            // The merged clickable row can be visible while its centered number is still clipped.
            // Center the actual unmerged Text using its unclipped position, not the row's bounds.
            for (attempt in 0 until 8) {
                val target = compose.nodes(unmerged = true).single { it.config.getOrNull(SemanticsProperties.TestTag) == "chapter-number-$number" }
                val scroller = generateSequence(target) { it.parent }.first { it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null }
                val viewport = scroller.boundsInWindow
                val top = target.positionInWindow.y
                val bottom = top + target.size.height
                if (top >= viewport.top + 8f && bottom <= viewport.bottom - 8f && target.boundsInWindow.height >= target.size.height) break
                val distance = (top + bottom - viewport.top - viewport.bottom) / 2f
                if (!requireNotNull(scroller.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(0f, distance)) break
                fixture.settle(compose)
            }
            fixture.settle(compose)
            fixture.capture(compose, "chapter-number-$number-scaled")
            val node = compose.nodes(unmerged = true).single { it.config.getOrNull(SemanticsProperties.TestTag) == "chapter-number-$number" }
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(layouts))
            val layout = layouts.single()
            assertEquals(number.toString(), layout.layoutInput.text.text)
            assertEquals(1.5f, layout.layoutInput.density.fontScale, 0f)
            assertEquals(1, layout.lineCount); assertFalse(layout.layoutInput.softWrap); assertEquals(1, layout.layoutInput.maxLines)
            assertFalse("Canonical layout overflows for $number: size=${layout.size}, bounds=${node.boundsInWindow}", layout.hasVisualOverflow)
            assertFalse(layout.isLineEllipsized(0))
            assertEquals(number.toString().length, layout.getLineEnd(0, visibleEnd = true))
            val bounds = node.boundsInWindow
            assertTrue("Measured number $number must have visible height: bounds=$bounds, position=${node.positionInWindow}, size=${node.size}, layout=${layout.size}", bounds.height >= layout.size.height)
            assertTrue("Actual measured column must contain all three digits", bounds.width >= layout.getLineRight(0) - layout.getLineLeft(0))
            assertTrue("Number column must remain wholly inside the narrow viewport", bounds.left >= 0f && bounds.right <= compose.viewport().first)
            for (offset in number.toString().indices) {
                val glyph = layout.getBoundingBox(offset)
                assertTrue("Digit $offset of $number must fit horizontally: $glyph in $bounds", glyph.left >= 0f && glyph.right <= bounds.width)
                assertTrue("Digit $offset of $number must fit vertically: $glyph in $bounds", glyph.top >= 0f && glyph.bottom <= bounds.height)
            }
        }
        assertNoUpload()
    }

    @Test fun localCoverNativeCanvasPreviewSwitchRemoveAndRestoreNeverDrawsARecycledOrStaleBitmap() {
        val first = File(app.cacheDir, "preview-${UUID.randomUUID()}.png")
        val second = File(app.cacheDir, "preview-${UUID.randomUUID()}.png")
        fun create(file: File, color: Int) {
            val image = Bitmap.createBitmap(30, 40, Bitmap.Config.ARGB_8888)
            try { image.eraseColor(color); file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { image.recycle() }
        }
        create(first, Color.RED); create(second, Color.BLUE)
        var data by mutableStateOf(metadata.copy(localCover = first.path, coverUrl = "https://media.example.test/old.png", coverPending = true))
        try {
            fixture.show(compose) { NovelEditorCover(data, Modifier.size(90.dp, 120.dp)) }
            fun center(): Int {
                val (width, height) = compose.viewport()
                val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                return try { compose.attachedComposeView.draw(Canvas(image)); image.getPixel(45, 60) }
                finally { image.recycle() }
            }
            fun awaitPixel(expected: Int) {
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                while (System.nanoTime() < deadline) {
                    fixture.settle(compose)
                    if (center() == expected) return
                    Thread.yield()
                }
                assertEquals("Actual decoded cover must render on the native Canvas", expected, center())
            }
            awaitPixel(Color.RED)
            compose.onNodeWithContentDescription("本地封面预览").assertIsDisplayed()
            repeat(3) {
                compose.runOnUiThread { data = data.copy(localCover = second.path) }
                awaitPixel(Color.BLUE)
                compose.runOnUiThread { data = data.copy(localCover = first.path) }
                awaitPixel(Color.RED)
                compose.runOnUiThread { data = data.copy(localCover = null, coverUrl = null, coverPending = false) }
                fixture.settle(compose)
                compose.onNodeWithContentDescription("本地封面预览").assertDoesNotExist()
                center() // A retired Compose draw frame must never contain a recycled bitmap.
                compose.runOnUiThread { data = data.copy(localCover = first.path, coverPending = true) }
                awaitPixel(Color.RED)
            }
            compose.runOnUiThread { controller.open(null, null, "create"); controller.metadata(data) }
            show()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline && compose.nodes().none { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("本地封面预览") == true }) {
                fixture.settle(compose); Thread.yield()
            }
            compose.onNodeWithContentDescription("本地封面预览").assertIsDisplayed()
            fixture.capture(compose, "local-cover-selected")
            assertNoUpload()
        } finally { first.delete(); second.delete() }
    }

    private fun findManuscript(view: View): NovelManuscriptView? {
        if (view is NovelManuscriptView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findManuscript(view.getChildAt(i))?.let { return it }
        return null
    }

    companion object {
        private val chapterTitles = listOf("属于自己的角落", "新的开始", "给生活一点空间", "一封没有寄出的信", "晴天的约定", "周末的计划")
        private val statuses = listOf("published", "published", "pending", "rejected", "draft", "draft")
        private const val body = "这是只存在于测试夹具中的虚构文稿。\n\n午后的阳光落在窗沿，成年朋友们约好慢慢聊聊最近的生活。\n\n他们尊重彼此的边界，也愿意给不同的选择留一点空间。\n\n桌上是一封尚未寄出的信，和一杯温度刚好的茶。\n\n故事还没有结束，新的日常正在缓缓开始。"
        private val importText = "第一章 属于自己的角落\n$body\n第二章 新的开始\n测试第二章正文：每个决定都先经过沟通。\n\n这里保留真实段落供拆分。\n第三章 晴天的约定\n测试第三章正文：故事继续。"
    }
}
