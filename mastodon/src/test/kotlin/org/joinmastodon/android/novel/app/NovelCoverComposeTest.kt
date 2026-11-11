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
import org.joinmastodon.android.novel.app.models.Work
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
    private fun image(color: Int, width: Int = 24, height: Int = 32) = BitmapDrawable(host.activity.resources, Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        eraseColor(color)
        // Genuine synthetic image detail for the existing native screenshot anti-placeholder gate;
        // the middle stays the requested color for exact pixel assertions, never a copied golden.
        for (y in 0 until 8) for (x in 0 until width) setPixel(x, y, Color.rgb((x * 10) and 255, y * 30, 150))
    })
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun covers() = descendants(host.attachedComposeView).filterIsInstance<NovelCoverImageView>().filter { it.isAttachedToWindow }
    private fun pixels(view: NovelCoverImageView) = (view.drawable as? BitmapDrawable)?.bitmap
    private val bindingKeyField = NovelCoverBinding::class.java.getDeclaredField("key").apply { isAccessible = true }
    private fun boundKey(view: NovelCoverImageView) = bindingKeyField.get(view.binding) as? NovelCoverKey
    private fun workKey(value: Work) = requireNotNull(novelCoverKey("work:${value.id}", "${value.author.id}:${value.status}", value.coverUrl))
    private fun cover(value: Work) = covers().single { boundKey(it) == workKey(value) }
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
        assertTrue("A cover must have real measured native bounds", view.width > 0 && view.height > 0)
        assertEquals(View.VISIBLE, view.visibility)
        assertEquals("Successful redraws must not restart the native fade", 1f, view.alpha, 0f)
        view.drawable?.let { assertEquals("Drawable opacity must not leak between views", 255, it.alpha) }
        val native = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(native))
            val center = native.getPixel(native.width / 2, native.height / 2)
            assertEquals("The native center must contain this book's pixels", expected, center)
            assertEquals("Check painted alpha, not just the drawable reference", Color.alpha(expected), Color.alpha(center))
        } finally { native.recycle() }
    }
    private fun composeCenter(view: NovelCoverImageView): Pair<Int, Int> {
        val origin = IntArray(2); host.attachedComposeView.getLocationInWindow(origin)
        val location = IntArray(2); view.getLocationInWindow(location)
        return location[0] - origin[0] + view.width / 2 to location[1] - origin[1] + view.height / 2
    }
    private fun assertComposePaints(view: NovelCoverImageView, expected: Int) {
        val root = host.attachedComposeView
        val (x, y) = composeCenter(view)
        assertTrue("Cover center must be inside the real Compose viewport", x in 0 until root.width && y in 0 until root.height)
        // Draw the ACTUAL composed/native tree, including parent alpha/clips, through a translated
        // one-pixel viewport. Repeated frame checks never allocate full-screen screenshots.
        val sample = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(sample).apply { translate(-x.toFloat(), -y.toFloat()) })
            val center = sample.getPixel(0, 0)
            assertEquals("Compose must paint this cover, not its typographic fallback", expected, center)
            assertEquals("The composed success must stay fully opaque", 255, Color.alpha(center))
        } finally { sample.recycle() }
    }
    private fun assertSuccess(view: NovelCoverImageView, value: Work, source: BitmapDrawable, color: Int, composed: Boolean = true) {
        assertTrue(view.isAttachedToWindow)
        assertEquals("Correct pixels must belong to the full current book/projection/URL key", workKey(value), boundKey(view))
        assertFalse("Eviction/disposal must not recycle visible pixels", source.bitmap.isRecycled)
        assertSame("Keep the same successfully decoded bitmap", source.bitmap, pixels(view))
        assertPaints(view, color)
        if (composed) assertComposePaints(view, color)
    }

    @Test fun realWorkRecompositionKeepsSameNativeViewImageAndDoesNotRestartFactoryOrFade() {
        var current by mutableStateOf(work)
        var mounted by mutableStateOf(true)
        show { Column { NovelHeading("封面加载回归", size = 23); if (mounted) NovelBookCover(current, width = 98.dp); NovelHeading(current.title, size = 19) } }
        val view = covers().single(); assertNull(view.drawable); assertEquals(1, loader.calls.size)
        val red = image(Color.RED); loader.succeed(0, red); settle()
        assertSuccess(view, current, red, Color.RED)
        repeat(12) { index ->
            current = current.copy(title = "新标题 $index", reads = index.toLong(), inShelf = index % 2 == 0)
            settle()
            assertSame(view, covers().single())
            repeat(3) { assertSuccess(view, current, red, Color.RED) }
        }
        var previous = view
        repeat(3) {
            mounted = false; settle(); assertTrue(covers().isEmpty()); assertFalse(previous.isAttachedToWindow)
            mounted = true; settle()
            val restored = covers().single()
            assertNotSame("A remount must exercise a new real AndroidView factory", previous, restored)
            assertSuccess(restored, current, red, Color.RED)
            previous = restored
        }
        assertEquals("Redraws/recomposition/warm remounts must never restart this request", 1, loader.calls.size)
        fixture.capture(host, "same-url-recomposition-custom-cover")
    }

    @Test fun decoded500x700PressureKeepsVisibleSuccessOpaqueAndWarmRemountUsesOnlyItsOwnBook() {
        // Exactly three realistic decoded sources (4.2 MB total), not an unbounded catalog or
        // full-screen bitmap loop. The explicit byte budget forces deterministic LRU pressure.
        val red = image(Color.RED, 500, 700)
        val green = image(Color.GREEN, 500, 700)
        val blue = image(Color.BLUE, 500, 700)
        val bytes = red.bitmap.allocationByteCount.toLong()
        val budget = bytes * 2
        val pressured = NovelCoverScope("bounded-render-pressure", { true }, maxBytes = budget, maxEntries = 8)
        val second = NovelAppTestFixtures.work(1).copy(coverUrl = url)
        val third = NovelAppTestFixtures.work(2).copy(coverUrl = url)
        var neighbor by mutableStateOf(second)
        var neighborMounted by mutableStateOf(true)
        var revision by mutableIntStateOf(0)
        try {
            show {
                CompositionLocalProvider(LocalNovelCoverScope provides pressured) {
                    Row {
                        NovelBookCover(work.copy(title = "固定作品 $revision"), width = 98.dp)
                        if (neighborMounted) NovelBookCover(neighbor, width = 76.dp)
                    }
                }
            }
            assertEquals(bytes, green.bitmap.allocationByteCount.toLong()); assertEquals(bytes, blue.bitmap.allocationByteCount.toLong())
            val firstCall = loader.calls.single { it.request.coverKey == workKey(work) }
            val secondCall = loader.calls.single { it.request.coverKey == workKey(second) }
            assertNotEquals("Even an identical URL belongs to distinct book identities", firstCall.request.memoryCacheKey, secondCall.request.memoryCacheKey)
            firstCall.result(red); secondCall.result(green); settle()
            val fixed = cover(work)
            val reused = cover(second)
            assertSuccess(fixed, work, red, Color.RED); assertSuccess(reused, second, green, Color.GREEN)
            assertEquals(2, pressured.retainedCount); assertEquals(budget, pressured.retainedBytes)

            neighbor = third; settle()
            assertSame("The same Compose slot must rebind its native cover to the next book", reused, cover(third))
            assertNull("A new book cannot borrow the previous book's successful pixels", reused.drawable)
            assertPaints(reused, Color.TRANSPARENT)
            val thirdCall = loader.calls.single { it.request.coverKey == workKey(third) }
            thirdCall.result(blue); settle()
            assertEquals(2, pressured.retainedCount); assertEquals(budget, pressured.retainedBytes)
            assertNull("Third 500x700 result must really evict the oldest reusable cache entry", pressured.image(workKey(work)))
            assertSuccess(fixed, work, red, Color.RED); assertSuccess(reused, third, blue, Color.BLUE)
            repeat(8) { index ->
                revision = index + 1; settle()
                assertSame(fixed, cover(work)); assertSame(reused, cover(third))
                repeat(2) { assertSuccess(fixed, work, red, Color.RED); assertSuccess(reused, third, blue, Color.BLUE) }
                assertTrue(pressured.retainedBytes <= budget)
            }
            neighborMounted = false; settle(); assertFalse(reused.isAttachedToWindow)
            assertSuccess(fixed, work, red, Color.RED)
            neighborMounted = true; settle()
            val remounted = cover(third)
            assertNotSame(reused, remounted)
            assertSuccess(remounted, third, blue, Color.BLUE); assertSuccess(fixed, work, red, Color.RED)
            assertEquals("Eviction cannot cause visible redraw reloads or reload the warm MRU remount", 3, loader.calls.size)
            assertTrue(listOf(red, green, blue).none { it.bitmap.isRecycled })
        } finally { pressured.retire() }
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
        covers().filter { pixels(it) === firstPixels }.forEach {
            assertEquals("Reordering must never assign one book's image to a neighbour", workKey(catalog.first()), boundKey(it))
            assertPaints(it, Color.RED)
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
        // The second book deliberately shares the opaque signed URL. Distinct center colors and
        // full keys catch cross-book cache/view reuse that an isNotNull assertion cannot detect.
        val other = NovelAppTestFixtures.work(1).copy(coverUrl = url)
        val books = listOf(work, other)
        val red = image(Color.RED); val blue = image(Color.BLUE)
        val expected = mapOf(workKey(work) to Triple(work, red, Color.RED), workKey(other) to Triple(other, blue, Color.BLUE))
        val base = NovelAppTestFixtures.state().copy(work = NovelLoad(work), works = NovelLoad(Page(books, null)),
            allWorks = NovelLoad(Page(books.reversed(), null)), search = NovelLoad(Page(books, null)), query = "柔软",
            shelf = NovelLoad(Shelf(books.reversed(), work)), ownerWorks = NovelLoad(books.reversed()),
            creator = NovelLoad(NovelAppTestFixtures.creator().copy(works = books)),
            author = NovelLoad(org.joinmastodon.android.novel.app.models.AuthorPage(work.author, NovelAppTestFixtures.stats, books.reversed(), false)))
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
        loader.calls.toList().forEach { call -> call.result(requireNotNull(expected[call.request.coverKey]).second) }
        settle()
        val storeViews = covers().toList()
        fun assertPagePaints() {
            assertTrue("Production cover missing on $page", covers().isNotEmpty())
            var composed = 0
            covers().forEach { view ->
                val (book, source, color) = requireNotNull(expected[boundKey(view)]) { "Unexpected book cover on $page" }
                val (x, y) = composeCenter(view)
                val inside = x in 0 until host.attachedComposeView.width && y in 0 until host.attachedComposeView.height
                assertSuccess(view, book, source, color, composed = inside)
                if (inside) composed++
            }
            assertTrue("At least one actual cover center must be painted inside $page", composed > 0)
        }
        assertPagePaints()
        for (next in listOf(NovelPage.AllWorks, NovelPage.Search, NovelPage.Shelf, NovelPage.Detail(work.id), NovelPage.AuthorProfile(work.author.id), NovelPage.Creator, NovelPage.OwnerWorks, NovelPage.Store)) {
            page = next; settle()
            if (next == NovelPage.Creator) host.scrollTo(hasText(work.title))
            repeat(3) { assertPagePaints() }
        }
        assertTrue("Cross-page coverage must include actual native disposal/remount", storeViews.all { !it.isAttachedToWindow })
        books.forEach { book ->
            assertEquals("All pages reuse only this book's successful pixels, without a new load", 1,
                loader.calls.count { it.request.coverKey == workKey(book) })
        }
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
