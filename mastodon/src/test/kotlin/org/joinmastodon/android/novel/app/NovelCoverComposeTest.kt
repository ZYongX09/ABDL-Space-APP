package org.joinmastodon.android.novel.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.events.AccountLoggedOutEvent
import org.joinmastodon.android.novel.app.editor.NovelEditorCover
import org.joinmastodon.android.novel.app.editor.NovelMetadata
import org.joinmastodon.android.novel.app.models.Page
import org.joinmastodon.android.novel.app.models.Shelf
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Actual production covers/screens, attached Compose/native tree and native pixels; no source checks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w393dp-h852dp-notnight-mdpi", application = CompatibilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NovelCoverComposeTest {
    private val fixture = NovelScreenshotFixture("covers")
    @get:Rule val rule: org.junit.rules.TestRule = fixture
    private val host get() = fixture.host
    private val loader = ControlledNovelCoverLoader()
    private val scope = NovelCoverScope("compose-cover-fixture", { true })
    private val models = ViewModelStore()
    private val url = "https://compose-cover.example.test/book.png?signature=one"
    private val work = NovelAppTestFixtures.work().copy(coverUrl = url)
    @After fun cleanup() { models.clear(); scope.retire() }
    private fun image(color: Int) = BitmapDrawable(host.activity.resources, Bitmap.createBitmap(24, 32, Bitmap.Config.ARGB_8888).apply {
        eraseColor(color)
        // Genuine synthetic image detail for the existing native screenshot anti-placeholder gate;
        // the middle stays the requested color for exact pixel assertions, never a copied golden.
        for (y in 0 until 8) for (x in 0 until width) setPixel(x, y, Color.rgb(x * 10, y * 30, 150))
    })
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun covers() = descendants(host.attachedComposeView).filterIsInstance<NovelCoverImageView>().filter { it.isAttachedToWindow }
    private fun pixels(view: NovelCoverImageView) = (view.drawable as? BitmapDrawable)?.bitmap
    private fun show(content: @Composable () -> Unit) = fixture.show(host) {
        CompositionLocalProvider(LocalNovelCoverScope provides scope, LocalNovelCoverLoader provides loader, content = content)
    }
    private fun settle() = host.settle()
    private fun completeVisible(color: Int = Color.RED) {
        val completed = mutableSetOf<ControlledNovelCoverLoader.Call>()
        repeat(4) {
            loader.calls.toList().filter { !it.cancelled && it !in completed }.forEach { call ->
                completed += call; call.result(image(color))
            }
            settle()
        }
    }
    private fun assertPaints(view: NovelCoverImageView, expected: Int) {
        val native = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try { view.draw(Canvas(native)); assertEquals(expected, native.getPixel(native.width / 2, native.height / 2)) }
        finally { native.recycle() }
    }

    @Test fun realWorkRecompositionKeepsSameNativeViewImageAndDoesNotRestartFactoryOrFade() {
        var current by mutableStateOf(work)
        show { Column { NovelHeading("封面加载回归", size = 23); NovelBookCover(current, width = 98.dp); NovelHeading(current.title, size = 19) } }
        val view = covers().single(); assertNull(view.drawable); assertEquals(1, loader.calls.size)
        val red = image(Color.RED); loader.succeed(0, red); settle()
        assertSame(red.bitmap, pixels(view)); assertEquals(1f, view.alpha, 0f); assertPaints(view, Color.RED)
        repeat(12) { index ->
            current = current.copy(title = "新标题 $index", reads = index.toLong(), inShelf = index % 2 == 0)
            settle()
            assertSame(view, covers().single()); assertSame(red.bitmap, pixels(view)); assertEquals(1f, view.alpha, 0f)
        }
        assertEquals(1, loader.calls.size)
        fixture.capture(host, "same-url-recomposition-custom-cover")
    }

    @Test fun signatureUrlSwitchFailureNullUrlAndLateAbaRepliesRenderRealFallbackInsteadOfOldPrivateCover() {
        var current by mutableStateOf(work)
        show { Column { NovelHeading("封面加载回归", size = 23); NovelBookCover(current, width = 98.dp); NovelHeading(current.title, size = 19) } }
        loader.succeed(0, image(Color.RED)); settle(); val view = covers().single(); assertPaints(view, Color.RED)
        current = current.copy(coverUrl = "$url&version=2"); settle()
        assertSame(view, covers().single()); assertNull(view.drawable); assertEquals(2, loader.calls.size)
        loader.succeed(0, image(Color.GREEN)); settle(); assertNull(view.drawable)
        loader.fail(1); settle(); assertNull(view.drawable)
        assertTrue("The production typographic title must remain under the failed image", host.nodes(true).count {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == current.title } == true
        } >= 2)
        current = current.copy(coverUrl = url); settle(); assertNull(view.drawable)
        val attempt = loader.calls.lastIndex
        loader.succeed(1, image(Color.BLUE)); settle(); assertNull(view.drawable)
        loader.succeed(attempt, image(Color.GREEN)); settle(); assertPaints(view, Color.GREEN)
        current = current.copy(coverUrl = null); settle(); assertNull(view.drawable)
        loader.succeed(attempt, image(Color.BLUE)); settle(); assertNull(view.drawable)
        fixture.capture(host, "cover-removed-real-typographic-fallback")
    }

    @Test fun actualStoreLazyScrollDisposesAndRecreatesCoverWithoutPlaceholderOnReturn() {
        val catalog = (0..24).map { index -> NovelAppTestFixtures.work(index).copy(title = "滚动书籍 $index", coverUrl = "$url&book=$index") }
        val vm = NovelAppViewModel({ NovelRepository(TestNovelTransport()) }, MemoryNovelSearchHistory(), { true }).also { models.put("covers", it) }
        var state by mutableStateOf(NovelAppState(works = NovelLoad(Page(catalog, null))))
        show { NovelStoreScreen(state, vm, {}) }
        completeVisible()
        val first = covers().first(); val firstPixels = pixels(first); assertNotNull(firstPixels)
        host.scrollTo(hasText("滚动书籍 18")); settle(); assertFalse("Real lazy item must leave the native window", first.isAttachedToWindow)
        val callsForFirst = loader.calls.count { it.request.coverKey.entity == "work:${catalog.first().id}" }
        for (attempt in 0 until 35) {
            val node = host.nodes().first { it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null }
            val range = requireNotNull(node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange))
            if (range.value() <= 0f) break
            node.config[SemanticsActions.ScrollBy].action!!.invoke(0f, -1000f); settle()
        }
        val restored = covers().first { pixels(it) === firstPixels }
        assertTrue(restored.isAttachedToWindow); assertEquals(1f, restored.alpha, 0f); assertPaints(restored, Color.RED)
        assertEquals(callsForFirst, loader.calls.count { it.request.coverKey.entity == "work:${catalog.first().id}" })
        // Real keyed item reordering must not let the old first book populate its new neighbour.
        state = state.copy(works = NovelLoad(Page(catalog.reversed(), null))); settle()
        val keyField = NovelCoverBinding::class.java.getDeclaredField("key").apply { isAccessible = true }
        covers().filter { pixels(it) === firstPixels }.forEach {
            assertEquals("Reordering must never assign one book's image to a neighbour", "work:${catalog.first().id}", (keyField.get(it.binding) as NovelCoverKey).entity)
        }
        fixture.capture(host, "store-scroll-return-custom-cover")
    }

    @Test fun shelfChunkRegroupAndFilterMoveCoversByBookNotByPosition() {
        val books = (0..8).map { NovelAppTestFixtures.work(it).copy(title = "书架作品 $it", coverUrl = "$url&shelf=$it") }
        val vm = NovelAppViewModel({ NovelRepository(TestNovelTransport()) }, MemoryNovelSearchHistory(), { true }).also { models.put("shelf", it) }
        var state by mutableStateOf(NovelAppState(shelf = NovelLoad(Shelf(books, null))))
        show { NovelShelfScreen(state, vm, {}, { _, _ -> }) }
        // Resolve each visible book with a unique real bitmap, then regroup chunked(3) rows.
        loader.calls.toList().filter { !it.cancelled }.forEachIndexed { index, call -> call.result(image(Color.rgb(20 + index * 20, 30, 160))) }
        settle()
        val initialCalls = loader.calls.size
        state = state.copy(shelf = NovelLoad(Shelf(books.drop(1), null))); settle()
        val retainedBook = "work:${books[1].id}"
        assertEquals("A book moving to a different chunk must reuse only its own success", 1, loader.calls.count { it.request.coverKey.entity == retainedBook })
        assertTrue(covers().any { it.drawable != null }); assertTrue(loader.calls.size <= initialCalls + 3)
        fixture.capture(host, "shelf-regroup-custom-covers")
    }

    @Test fun accountSwitchSameBookSameUrlAndLogoutClearNativeViewAndDoNotBorrowPreviousScope() {
        var owner by mutableStateOf(scope)
        val replacement = NovelCoverScope("other-account", { true })
        show { CompositionLocalProvider(LocalNovelCoverScope provides owner) { NovelBookCover(work, width = 98.dp) } }
        loader.succeed(0, image(Color.RED)); settle(); val view = covers().single(); assertNotNull(view.drawable)
        owner = replacement; settle(); assertSame(view, covers().single()); assertNull(view.drawable)
        loader.succeed(0, image(Color.GREEN)); settle(); assertNull(view.drawable)
        val index = loader.calls.lastIndex; loader.succeed(index, image(Color.BLUE)); settle(); assertPaints(view, Color.BLUE)
        replacement.onLogout(AccountLoggedOutEvent("other-account")); settle(); assertNull(view.drawable)
        loader.succeed(index, image(Color.RED)); settle(); assertNull(view.drawable); assertEquals(0, replacement.retainedCount)
    }

    @Test fun nestedProviderCannotBorrowDifferentAccountScopeAndThemeDisposalRetiresItsOwnScope() {
        var same: NovelCoverScope? = null
        var different: NovelCoverScope? = null
        var visible by mutableStateOf(true)
        // Read locals during composition (SideEffect itself is intentionally not composable).
        show {
            NovelCoverProvider(scope.accountId) {
                val actual = LocalNovelCoverScope.current
                SideEffect { same = actual }
            }
            if (visible) NovelCoverProvider("different-account-fixture") {
                val actual = LocalNovelCoverScope.current
                SideEffect { different = actual }
            }
        }
        assertSame(scope, same); assertNotNull(different); assertNotSame(scope, different)
        visible = false; settle()
        assertFalse(requireNotNull(different).active); assertTrue(scope.active)
    }

    @Test fun allActualAppCoverEntrypointsUseTheSameScopeWithoutChangingFallbackLayouts() {
        val vm = NovelAppViewModel({ NovelRepository(TestNovelTransport()) }, MemoryNovelSearchHistory(), { true }).also { models.put("all-pages", it) }
        val base = NovelAppTestFixtures.state().copy(work = NovelLoad(work), works = NovelLoad(Page(listOf(work), null)),
            allWorks = NovelLoad(Page(listOf(work), null)), search = NovelLoad(Page(listOf(work), null)), query = "柔软",
            shelf = NovelLoad(Shelf(listOf(work), work)), ownerWorks = NovelLoad(listOf(work)),
            creator = NovelLoad(NovelAppTestFixtures.creator().copy(works = listOf(work))),
            author = NovelLoad(org.joinmastodon.android.novel.app.models.AuthorPage(work.author, NovelAppTestFixtures.stats, listOf(work), false)))
        var page by mutableStateOf<NovelPage>(NovelPage.Store)
        show {
            val state = base.copy(pages = listOf(page))
            when (page) {
                NovelPage.Store -> NovelStoreScreen(state, vm, {})
                NovelPage.AllWorks -> NovelAllWorksScreen(state, vm, {})
                NovelPage.Search -> NovelSearchScreen(state, vm, {})
                NovelPage.Shelf -> NovelShelfScreen(state, vm, {}, { _, _ -> })
                NovelPage.Creator -> NovelCreatorScreen(state, vm, {}, { _, _, _ -> }, {}, {})
                NovelPage.OwnerWorks -> NovelOwnerWorksScreen(state, vm, {}, { _, _, _ -> })
                is NovelPage.Detail -> NovelDetailScreen(state, vm, {}, { _, _ -> }, {})
                is NovelPage.AuthorProfile -> NovelAuthorScreen(state, vm, {}, {})
                else -> error("Unexpected cover page")
            }
        }
        completeVisible()
        for (next in listOf(NovelPage.AllWorks, NovelPage.Search, NovelPage.Shelf, NovelPage.Detail(work.id), NovelPage.AuthorProfile(work.author.id), NovelPage.Creator, NovelPage.OwnerWorks)) {
            page = next; settle()
            if (next == NovelPage.Creator) host.scrollTo(hasText(work.title))
            assertTrue("Production cover missing on $next", covers().isNotEmpty())
            covers().forEach { assertNotNull("Same permitted cover must remain warm on $next", it.drawable) }
        }
        assertEquals("All normal work covers share the exact identity, not page-local requests", 1,
            loader.calls.count { it.request.coverKey.entity == "work:${work.id}" })
    }

    @Test fun editorRemoteCoverUsesSameBindingAndPendingLocalSelectionSuppressesItImmediately() {
        var data by mutableStateOf(NovelMetadata(title = "编辑封面", coverUrl = url))
        show { NovelEditorCover(data, Modifier.size(90.dp, 120.dp), entity = "editor:one") }
        assertEquals(1, loader.calls.size); loader.succeed(0, image(Color.RED)); settle()
        val first = covers().single(); assertPaints(first, Color.RED)
        repeat(8) { data = data.copy(title = "编辑封面 $it"); settle() }
        assertEquals(1, loader.calls.size); assertSame(first, covers().single())
        data = data.copy(coverPending = true); settle(); assertNull(first.drawable)
        loader.succeed(0, image(Color.BLUE)); settle(); assertNull(first.drawable)
        data = data.copy(coverPending = false, coverUrl = "$url&new=1"); settle(); assertNull(first.drawable)
        loader.succeed(loader.calls.lastIndex, image(Color.BLUE)); settle(); assertPaints(first, Color.BLUE)
        scope.retire(); settle(); assertNull(first.drawable)
    }
}
