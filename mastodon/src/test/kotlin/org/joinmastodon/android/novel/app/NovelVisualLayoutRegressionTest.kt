package org.joinmastodon.android.novel.app

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelStore
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.novel.app.models.*
import org.joinmastodon.android.ui.views.BackdropCaptureFrameLayout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Attached production Compose/native views, real VM/repository, offline transport only.
 * Native Skia screenshots are evidence for geometry/fallback, not hardware glass verification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w393dp-h852dp-notnight-mdpi", application = CompatibilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NovelVisualLayoutRegressionTest {
    private val fixture = NovelScreenshotFixture("app")
    private val host get() = fixture.host
    @get:Rule val rule: org.junit.rules.TestRule = fixture
    private val models = ViewModelStore()
    private lateinit var vm: NovelAppViewModel
    private val repost = NovelAppTestFixtures.work(type = "repost").copy(originalAuthor = "原作者白棉", wordCount = 9_876_543_210)
    private val hugeStats = NovelAppTestFixtures.stats.copy(words = 9_876_543_210, reads = 987_654_321, collections = 123_456_789)
    private val catalog = (0..8).map { index -> if (index == 0) repost else NovelAppTestFixtures.work(index) }
    private var originalFontScale = 1f
    private var expectedFontScale = 1f
    private val transport = TestNovelTransport { method, path, body, _ ->
        when {
            method == "POST" && path.endsWith("/follow") -> JSONObject().put("following", body!!.getBoolean("following"))
            path == "/creator" -> NovelAppTestFixtures.creatorJson(active = true).put("works", JSONArray().put(NovelAppTestFixtures.workJson(repost))).put("stats", statsJson())
            path == "/stats" -> statsJson()
            path == "/owner/works" -> NovelAppTestFixtures.page(repost)
            path == "/shelf" -> NovelAppTestFixtures.page(repost.copy(inShelf = true)).put("continue_work", JSONObject.NULL)
            path == "/authors/42" -> JSONObject().put("author", NovelAppTestFixtures.authorJson()).put("stats", statsJson()).put("following", false).put("items", JSONArray().put(NovelAppTestFixtures.workJson(repost)))
            path.endsWith("/comments") -> JSONObject().put("items", JSONArray()).put("next_cursor", JSONObject.NULL)
            path.startsWith("/works/") -> NovelAppTestFixtures.workJson(repost)
            else -> NovelAppTestFixtures.page(*catalog.toTypedArray())
        }
    }
    private fun statsJson() = hugeStats.let { s -> JSONObject().put("works", s.works).put("followers", s.followers).put("words", s.words).put("reads", s.reads).put("collections", s.collections).put("comments", s.comments) }

    @Before fun setUp() = host.runOnUiThread {
        originalFontScale = host.activity.resources.configuration.fontScale
        vm = NovelAppViewModel({ NovelRepository(transport) }, MemoryNovelSearchHistory(), { it == NovelScreenshotFixture.ACCOUNT })
        models.put("visual-regression", vm)
        vm.bindAccount(NovelScreenshotFixture.ACCOUNT, NovelAppTestFixtures.author.copy(id = 99))
    }
    @After fun tearDown() = host.runOnUiThread {
        models.clear()
        RuntimeEnvironment.setFontScale(originalFontScale)
    }
    private fun show(page: NovelPage = NovelPage.Store, fontScale: Float = 1f) {
        expectedFontScale = fontScale
        host.runOnUiThread { RuntimeEnvironment.setFontScale(fontScale); vm.navigate(page) }
        fixture.show(host, fontScale) {
            val state by vm.state.collectAsState()
            NovelAppContent(state, vm, liquid = false, onBack = {}, onRead = { _, _ -> }, onCreate = { _, _, _ -> },
                onVerify = {}, onPost = {}, onShareWork = {}, onShareAuthor = {}, onMessage = {})
        }
    }
    private fun tag(value: String): SemanticsNode = host.nodes(true).single { it.config.getOrNull(SemanticsProperties.TestTag) == value }
    private fun text(value: String) = host.nodes(true).filter { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == value } == true }
    private fun boundsInside(outer: Rect, inner: Rect) {
        assertTrue("Nonempty actual bounds: $inner", inner.width > 0 && inner.height > 0)
        assertTrue("Bounds $inner must fit $outer", inner.left >= outer.left - 1 && inner.right <= outer.right + 1 && inner.top >= outer.top - 1 && inner.bottom <= outer.bottom + 1)
    }
    private fun assertTextFits(value: String) {
        val nodes = text(value)
        assertTrue("Actual production text must exist: $value", nodes.isNotEmpty())
        nodes.forEach { node ->
            val result = mutableListOf<TextLayoutResult>()
            assertTrue("Production text layout result required: $value", requireNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(result))
            assertTrue(result.isNotEmpty())
            result.forEach { layout ->
                assertEquals("Actual production text must use requested font scale: $value", expectedFontScale, layout.layoutInput.density.fontScale, .001f)
                val diagnostic = "text=$value size=${layout.size} paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height} constraints=${layout.layoutInput.constraints} style=${layout.layoutInput.style}"
                assertFalse("Text overflows: $diagnostic", layout.hasVisualOverflow)
                repeat(layout.lineCount) { line ->
                    assertFalse("Text ellipsized: $diagnostic", layout.isLineEllipsized(line))
                    assertTrue("Actual line left clipped: $diagnostic", layout.getLineLeft(line) >= -.5f)
                    assertTrue("Actual line right clipped: $diagnostic", layout.getLineRight(line) <= layout.size.width + .5f)
                    assertTrue("Actual line top clipped: $diagnostic", layout.getLineTop(line) >= -.5f)
                    assertTrue("Actual line bottom clipped: $diagnostic", layout.getLineBottom(line) <= layout.size.height + .5f)
                }
            }
        }
    }
    private fun assertPlainNavigationBounds() {
        val viewport = Rect(0f, 0f, host.viewport().first.toFloat(), host.viewport().second.toFloat())
        val overlay = tag("novel-navigation").boundsInWindow
        val pill = tag("novel-navigation-pill").boundsInWindow
        boundsInside(viewport, overlay); boundsInside(overlay, pill)
        assertTrue("Plain pill is compact, not full-bleed", pill.width <= 320f && pill.left >= 19 && pill.right <= viewport.right - 19)
        assertTrue("Ordinary navigation has no 96dp block", pill.height in 55f..90f)
        val tabs = host.nodes().filter { it.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Tab }
        assertEquals("Exactly three actionable production tabs", 3, tabs.size)
        tabs.forEach { tab ->
            boundsInside(pill, tab.boundsInWindow)
            assertTrue("Minimum touch target", tab.boundsInWindow.height >= 48)
            assertNotNull(tab.config.getOrNull(SemanticsActions.OnClick)?.action)
        }
        listOf("书城", "书架", "创作").forEach(::assertTextFits)
    }

    @Test fun plainPillHasThreeBoundedTabsAndNativeContentRemainsItsSibling() {
        show()
        assertPlainNavigationBounds()
        val views = descendants(host.attachedComposeView)
        val capture = views.filterIsInstance<BackdropCaptureFrameLayout>().single()
        assertTrue(capture.isAttachedToWindow)
        assertEquals(host.viewport().first, capture.width)
        assertEquals(host.viewport().second, capture.height)
        assertEquals("Capture owns only the page ComposeView", 1, capture.childCount)
        assertEquals(1, descendants(capture).filterIsInstance<androidx.compose.ui.platform.ComposeView>().size)
        fixture.capture(host, "store-plain-navigation-light-393x852", 21)
        host.onNodeWithText("创作").performClick(); fixture.settle(host)
        assertEquals(NovelPage.Creator, vm.state.value.page)
        assertPlainNavigationBounds()
    }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h640dp-notnight-mdpi")
    fun narrowFont180StoreAndCreatorKeepTabsAndCompactExactMetrics() {
        show(fontScale = 1.8f)
        host.onNodeWithText("18+ ABDL 社群 · 用户上传小说").assertDoesNotExist()
        assertPlainNavigationBounds()
        fixture.capture(host, "store-plain-navigation-font180-light-320x640", 21)
        host.onNodeWithText("创作").performClick(); fixture.settle(host)
        assertPlainNavigationBounds()
        host.onNodeWithContentDescription("${hugeStats.words.novelNumber()} 累计创作字数").assertExists()
        assertTextFits(hugeStats.words.novelCompactNumber())
        boundsInside(Rect(0f, 0f, 320f, 640f), tag("novel-value-累计创作字数").boundsInWindow)
        fixture.capture(host, "creator-large-count-font180-light-320x640", 15)
    }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h640dp-notnight-mdpi")
    fun repostBylineAndCoverUseOriginalAuthorWhileFollowTargetsUploader() {
        show(NovelPage.Detail(repost.id), fontScale = 1.8f)
        host.onNodeWithText("原作者白棉 著").assertIsDisplayed()
        assertTrue("Fallback cover carries original author", text("原作者白棉").isNotEmpty())
        host.onNodeWithText("${repost.author.name} 著").assertDoesNotExist()
        host.onNodeWithContentDescription("${repost.wordCount.novelNumber()} 字").assertExists()
        assertTextFits(repost.wordCount.novelCompactNumber() + " 字")
        host.onNodeWithText("开始阅读").assertIsDisplayed().assertIsEnabled()
        host.onNodeWithContentDescription("加入书架").assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        val actions = tag("novel-detail-actions").boundsInWindow
        val shelfAction = tag("novel-detail-shelf-action").boundsInWindow
        val readAction = tag("novel-detail-read-action").boundsInWindow
        boundsInside(Rect(0f, 0f, 320f, 640f), actions)
        boundsInside(actions, shelfAction); boundsInside(actions, readAction)
        assertEquals(48f, shelfAction.width, .5f); assertEquals(48f, shelfAction.height, .5f)
        assertTrue("CTA must not grow into a vertical word stack", actions.height <= 100f && readAction.height <= 72f)
        assertTrue("Reading occupies remaining width", readAction.width >= 200f && shelfAction.right < readAction.left)
        assertTextFits("开始阅读")
        val readLayout = mutableListOf<TextLayoutResult>()
        requireNotNull(text("开始阅读").single().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(readLayout)
        assertEquals("Complete reading label stays on one line", 1, readLayout.single().lineCount)
        assertEquals("开始阅读".length, readLayout.single().getLineEnd(0, visibleEnd = true))
        fixture.capture(host, "detail-repost-large-count-font180-light-320x640", 11)
        host.onNodeWithText("上传者 · ${repost.author.name}").performScrollTo().performClick(); fixture.settle(host)
        assertEquals(NovelPage.AuthorProfile(repost.author.id), vm.state.value.page)
        host.onNodeWithText("上传者账号 · @${repost.author.username}").assertIsDisplayed()
        host.onNodeWithText("+ 关注").performClick(); fixture.settle(host)
        assertTrue(transport.requests.any { it.method == "POST" && it.path == "/authors/${repost.author.id}/follow" })
        assertTrue(vm.state.value.author.value!!.following)
    }

    @Test fun nativeInsetsAreAppliedOnceAndScrollEndReservesOnlyMeasuredNavigation() {
        show()
        val before = tag("novel-navigation").boundsInWindow
        // This fixture normally hides both system bars for reference screenshots. On API28 Core
        // correctly returns zero visible insets when those flags remain set; enable real bars first.
        host.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        host.activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        fixture.settle(host)
        // API28 ViewCompat.toWindowInsetsCompat(native, attachedView) clamps navigation to the
        // Robolectric root's zero stable bottom and carries its old hidden-bar mask. Build explicit
        // legacy stable+visible type data, then give it to the real registered Compose listener at
        // the compat boundary. Do not change production inset state or fake layout coordinates.
        val delivered = WindowInsetsCompat.Builder()
            .setSystemWindowInsets(Insets.of(0, 24, 0, 24))
            .setStableInsets(Insets.of(0, 24, 0, 24))
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 24))
            .setVisible(WindowInsetsCompat.Type.systemBars(), true).build()
        val owner = descendants(host.attachedComposeView).single { it is androidx.compose.ui.node.RootForTest && it.parent === host.attachedComposeView }
        assertEquals(24, delivered.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
        assertEquals(24, delivered.getInsets(WindowInsetsCompat.Type.statusBars()).top)
        val adapter = requireNotNull(owner.getTag(androidx.core.R.id.tag_on_apply_window_listener))
        val listener = adapter.javaClass.declaredFields.first { androidx.core.view.OnApplyWindowInsetsListener::class.java.isAssignableFrom(it.type) }
            .apply { isAccessible = true }.get(adapter) as androidx.core.view.OnApplyWindowInsetsListener
        listener.onApplyWindowInsets(owner, delivered)
        fixture.settle(host)
        val after = tag("novel-navigation").boundsInWindow
        assertEquals("Single bottom inset, not once per nested ComposeView", before.height + 24f, after.height, 1f)
        val capture = descendants(host.attachedComposeView).filterIsInstance<BackdropCaptureFrameLayout>().single()
        val location = IntArray(2); capture.getLocationInWindow(location)
        assertEquals("Safe top inset belongs only to outer host", 24, location[1])
        // A target intersecting the viewport can still sit under the sibling navigation. Scroll
        // the actual LazyColumn to its semantic maximum before evaluating end-padding geometry.
        var reachedEnd = false
        for (attempt in 0 until 28) {
            val scroller = host.nodes().single { it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null && it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
            val range = requireNotNull(scroller.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange))
            if (range.maxValue().isFinite() && range.value() >= range.maxValue()) { reachedEnd = true; break }
            assertTrue("Real list must accept scroll to end", requireNotNull(scroller.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(0f, host.viewport().second.toFloat()))
            fixture.settle(host)
        }
        val finalRange = requireNotNull(host.nodes().single { it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null && it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange))
        reachedEnd = reachedEnd || (finalRange.maxValue().isFinite() && finalRange.value() >= finalRange.maxValue())
        assertTrue("Must reach actual LazyColumn end before checking padding: ${finalRange.value()}/${finalRange.maxValue()}", reachedEnd)
        val link = text("全部图书  ›").single().boundsInWindow
        assertTrue("Last content remains above the measured overlay", link.bottom <= after.top + 1)
        assertTrue("No fixed 96dp redundant gap", after.top - link.bottom < 80)
        fixture.capture(host, "store-single-insets-scroll-end-light-393x852", 21)
    }

    @Test fun compactFormatterHandlesThresholdsAndLongEdgesWithoutFloatRounding() {
        assertEquals("9999".toLong().novelNumber(), 9999L.novelCompactNumber())
        assertEquals("1万", 10_000L.novelCompactNumber())
        assertEquals("3.2万", 32_000L.novelCompactNumber())
        assertEquals("1亿", 100_000_000L.novelCompactNumber())
        assertEquals("1万亿", 1_000_000_000_000L.novelCompactNumber())
        assertEquals("9223372万亿", Long.MAX_VALUE.novelCompactNumber())
        assertEquals("-9223372万亿", Long.MIN_VALUE.novelCompactNumber())
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
