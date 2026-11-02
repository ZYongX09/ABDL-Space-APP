package org.joinmastodon.android.novel.app

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.R
import org.joinmastodon.android.novel.app.editor.*
import org.joinmastodon.noveleditor.NovelEditorStorage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.util.Locale
import java.util.UUID

/** Production UI/native EditText, existing bounded manual renderer, fictional data and no network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-rUS-w320dp-h640dp-notnight-mdpi", application = CompatibilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NovelEnglishUiTest {
    private val fixture = NovelScreenshotFixture("english", resourceFontScale = 1.8f)
    private val host get() = fixture.host
    @get:Rule val rule: org.junit.rules.TestRule = fixture
    private val models = ViewModelStore()
    private val account = "novel-english-${UUID.randomUUID()}"
    private val app get() = host.activity.application
    private val work = NovelAppTestFixtures.work().copy(wordCount = 9_876_543_210, reads = 987_654_321)
    private val api = TestNovelTransport { method, path, _, _ ->
        check(method == "GET") { "English UI fixture must never upload" }
        when {
            path.startsWith("/works/") && path.endsWith("/comments") -> JSONObject().put("items", JSONArray())
            path.startsWith("/works/") -> NovelAppTestFixtures.workJson(work)
            path.startsWith("/authors/") -> JSONObject().put("author", NovelAppTestFixtures.authorJson()).put("stats", JSONObject()).put("items", JSONArray())
            path == "/creator" -> NovelAppTestFixtures.creatorJson(active = true)
            else -> NovelAppTestFixtures.page(work)
        }
    }

    @After fun cleanup() {
        models.clear()
        app.deleteDatabase(NovelEditorStorage.databaseName(account))
    }

    private fun assertCompleteLabel(text: String) {
        assertEquals("Android Resources must use the actual large-font configuration", 1.8f, host.activity.resources.configuration.fontScale, .001f)
        val nodes = host.nodes(true).filter { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true }
        assertTrue("Production label exists: $text", nodes.isNotEmpty())
        nodes.forEach { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(layouts))
            layouts.forEach { layout ->
                assertEquals(1.8f, layout.layoutInput.density.fontScale, .001f)
                assertFalse("English label overflows: $text", layout.hasVisualOverflow)
                repeat(layout.lineCount) { line ->
                    assertFalse("English label must not be truncated: $text", layout.isLineEllipsized(line))
                    assertTrue(layout.getLineRight(line) <= layout.size.width + .5f)
                }
            }
            assertTrue("English control stays inside 320dp viewport", node.boundsInWindow.left >= 0 && node.boundsInWindow.right <= 320.5f)
        }
    }

    private fun showApp(page: NovelPage) {
        val vm = NovelAppViewModel({ NovelRepository(api) }, MemoryNovelSearchHistory())
        models.put("english-app", vm)
        vm.bindAccount(account)
        vm.navigate(page)
        fixture.show(host, 1.8f) {
            val state by vm.state.collectAsState()
            NovelAppContent(state, vm, false, {}, { _, _ -> }, { _, _, _ -> }, {}, {}, {}, {}, {})
        }
    }

    @Test fun englishStoreAndDetailUseRealResourcesCompactLatinNumbersAndCompleteNarrowActions() {
        showApp(NovelPage.Store)
        assertCompleteLabel("Featured")
        val tabs = host.nodes().single { it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null }
        val scroll = requireNotNull(tabs.config.getOrNull(SemanticsActions.ScrollBy)?.action)
        assertTrue("Large English tabs must expose real horizontal scrolling", scroll.invoke(320f, 0f))
        fixture.settle(host)
        assertCompleteLabel("Reposts")
        assertCompleteLabel("AI writing")
        assertTrue("Original tab position can be restored by scrolling", scroll.invoke(-320f, 0f))
        fixture.settle(host)
        assertCompleteLabel("Featured")
        listOf("Discover", "Shelf", "Create").forEach(::assertCompleteLabel)
        host.onNodeWithText("小说").assertDoesNotExist()
        fixture.capture(host, "english-store-font180-320x640")
    }

    @Test fun englishDetailKeepsUserTextAndLargeCountAndReadingActionWithinTheExistingLayout() {
        showApp(NovelPage.Detail(work.id))
        assertTrue(host.onAllNodesWithText(work.title).isNotEmpty())
        host.onNodeWithContentDescription("Add to shelf").assertIsDisplayed().assertIsEnabled()
        assertCompleteLabel("Start reading")
        host.onNodeWithContentDescription("9,876,543,210 characters").assertExists()
        assertCompleteLabel("9.8B characters")
        fixture.capture(host, "english-detail-font180-320x640")
    }

    @Test fun englishNativeEditorPreservesChineseManuscriptAndShowsCompletePinnedActions() {
        val metadata = NovelMetadata(title = "原稿标题", description = "用户已有正文及作者声明均保留", accepted = true,
            statements = listOf("内容不适宜未成年人阅读"))
        val created = NovelEditorStorage(app, account).use { storage ->
            storage.createWork(metadata.title, metadata.description, "fiction").also {
                storage.putAppMetadata(it.workId(), metadata.json().toString(), null, null)
                storage.checkpoint(it.chapterId(), "原始中文正文\n\nUntranslated user manuscript.", 0, 0, 0, true)
            }
        }
        val controller = NovelEditorController(app, NovelEditorRepository(app, account, api, Dispatchers.Unconfined)) { 0L }
        models.put("english-editor", controller)
        controller.open(created.workId(), null, "edit")
        controller.openChapter(created.chapterId())
        fixture.show(host, 1.8f) {
            val state by controller.state.collectAsState()
            NovelCreationScreen(state, controller, {}, {}, { _, _ -> })
        }
        assertCompleteLabel("Save draft")
        assertCompleteLabel("Submit for review")
        val input = descendants(host.activity.window.decorView).filterIsInstance<EditText>().single { it.contentDescription == "Novel manuscript input" }
        assertTrue(input.isAttachedToWindow)
        assertEquals("Native editor must use real Android font scaling", 1.8f, input.resources.configuration.fontScale, .001f)
        val metrics = input.resources.displayMetrics
        val expectedTextSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 14f, metrics)
        assertEquals("Native manuscript font is actually rendered at 14sp with system scaling", expectedTextSize, input.textSize, .1f)
        assertTrue("Native font must be substantially larger than the 1x 14sp baseline", input.textSize > 14f * metrics.density * 1.5f)
        assertEditableArea(input)
        assertEquals("原始中文正文\n\nUntranslated user manuscript.", input.text.toString())
        assertEquals(metadata.statements, controller.state.value.metadata.statements)
        fixture.capture(host, "english-editor-native-font180-320x640")

        host.runOnUiThread {
            assertTrue("The actual manuscript input accepts focus", input.requestFocus())
            input.setSelection(input.length())
            val connection = requireNotNull(input.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
            assertTrue("The actual InputConnection accepts new manuscript text", connection.commitText("\n\nNative input saved.", 1))
        }
        fixture.settle(host)
        assertEditableArea(input)
        val edited = "原始中文正文\n\nUntranslated user manuscript.\n\nNative input saved."
        assertEquals(edited, input.text.toString())
        assertEquals(edited, controller.state.value.frame?.body())
        assertTrue("Actual input must mark the local manuscript dirty", controller.state.value.dirty)
        host.onNodeWithContentDescription("Save draft").assertIsEnabled().performClick()
        fixture.settle(host)
        assertFalse("The production save action finishes the local checkpoint", controller.state.value.dirty)
        NovelEditorStorage(app, account).use { storage ->
            assertEquals(edited, storage.loadDraft(created.chapterId()).content())
        }
        assertTrue("English UI evidence must never upload", api.requests.all { it.method == "GET" })
        fixture.capture(host, "english-editor-native-input-saved-font180-320x640")
    }

    @Test fun compactEnglishFormattingHasNoChineseMagnitudeUnitsAndDoesNotChangeGlobalLocale() {
        val before = Locale.getDefault()
        assertEquals("999", 999L.novelCompactNumber(Locale.US))
        assertEquals("1K", 1_000L.novelCompactNumber(Locale.US))
        assertEquals("32K", 32_000L.novelCompactNumber(Locale.US))
        assertEquals("1M", 1_000_000L.novelCompactNumber(Locale.US))
        assertEquals("1B", 1_000_000_000L.novelCompactNumber(Locale.US))
        assertEquals("9223372036.8B", Long.MAX_VALUE.novelCompactNumber(Locale.US))
        assertEquals("-9223372036.8B", Long.MIN_VALUE.novelCompactNumber(Locale.US))
        assertEquals(before, Locale.getDefault())
        assertEquals("Original", app.getString(work.typeLabelRes))
        assertEquals("original", work.workType)
        assertEquals("published", work.status)
    }

    private fun assertEditableArea(input: EditText) {
        val metrics = input.resources.displayMetrics
        val minimumTextHeight = maxOf(120f * metrics.density, 3f * input.lineHeight)
        val availableTextHeight = input.height - input.totalPaddingTop - input.totalPaddingBottom
        val availableTextWidth = input.width - input.totalPaddingLeft - input.totalPaddingRight
        assertTrue("Native manuscript needs at least 120dp and three real font lines: size=${input.width}x${input.height}, usableHeight=$availableTextHeight, minimum=$minimumTextHeight, lineHeight=${input.lineHeight}", availableTextHeight >= minimumTextHeight)
        assertTrue("Native manuscript needs a usable text width: $availableTextWidth", availableTextWidth >= 160f * metrics.density)
        val visible = android.graphics.Rect()
        assertTrue("The actual input must have visible raster bounds", input.getGlobalVisibleRect(visible))
        assertTrue("The editable area must not be clipped below a usable height: $visible", visible.height() - input.totalPaddingTop - input.totalPaddingBottom >= minimumTextHeight)
        assertTrue("The actual native text must have a measured layout", input.layout != null && input.layout.lineCount > 0)
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
