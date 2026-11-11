package org.joinmastodon.android.novel.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.R
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

/** Actual production covers/screens and Compose-rendered pixels; no ImageView or source checks.
 * API28 native Skia verifies the composed tree, not hardware display-list behavior on a device.
 */
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
    private val presentations = mutableListOf<NovelCoverPresentation>()
    private val presentationFactory: () -> NovelCoverPresentation = { NovelCoverPresentation().also(presentations::add) }
    private val url = "https://compose-cover.example.test/book.png?signature=one"
    private val work = NovelAppTestFixtures.work().copy(coverUrl = url)
    @After fun cleanup() { models.clear(); scope.retire() }
    private fun image(color: Int, width: Int = 24, height: Int = 32) = BitmapDrawable(host.activity.resources, Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        eraseColor(color)
        // Genuine synthetic image detail for the existing native screenshot anti-placeholder gate;
        // the middle stays the requested color for exact pixel assertions, never a copied golden.
        for (y in 0 until 8) for (x in 0 until width) setPixel(x, y, Color.rgb((x * 10) and 255, y * 30, 150))
    })
    private fun covers() = presentations.filter { it.attached }
    private fun pixels(presentation: NovelCoverPresentation) = (presentation.image as? BitmapDrawable)?.bitmap
    private val bindingKeyField = NovelCoverBinding::class.java.getDeclaredField("key").apply { isAccessible = true }
    private fun boundKey(presentation: NovelCoverPresentation) = bindingKeyField.get(presentation.binding) as? NovelCoverKey
    private fun workKey(value: Work) = requireNotNull(novelCoverKey("work:${value.id}", "${value.author.id}:${value.status}", value.coverUrl))
    private fun cover(value: Work) = covers().single { boundKey(it) == workKey(value) }
    private fun show(content: @Composable () -> Unit) = fixture.show(host) {
        CompositionLocalProvider(LocalNovelCoverScope provides scope, LocalNovelCoverLoader provides loader,
            LocalNovelCoverPresentationFactory provides presentationFactory, content = content)
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
    private fun composeCenter(presentation: NovelCoverPresentation): Pair<Int, Int> {
        assertTrue("A sampled cover must belong to the live composition", presentation.attached)
        val coordinates = requireNotNull(presentation.coordinates) { "Cover must have actual Compose layout coordinates" }
        assertTrue("Cover coordinates must remain attached", coordinates.isAttached)
        assertTrue("A cover must have real measured Compose bounds", coordinates.size.width > 0 && coordinates.size.height > 0)
        val origin = IntArray(2); host.attachedComposeView.getLocationInWindow(origin)
        val location = coordinates.positionInWindow()
        return (location.x - origin[0] + coordinates.size.width / 2f).toInt() to
            (location.y - origin[1] + coordinates.size.height / 2f).toInt()
    }
    private fun composedPixel(presentation: NovelCoverPresentation): Int {
        val root = host.attachedComposeView
        val (x, y) = composeCenter(presentation)
        assertTrue("Cover center must be inside the real Compose viewport", x in 0 until root.width && y in 0 until root.height)
        // Draw the ACTUAL composed tree, including parent alpha/clips, through a translated
        // one-pixel viewport. Repeated frame checks never allocate full-screen screenshots.
        val sample = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return try {
            root.draw(Canvas(sample).apply { translate(-x.toFloat(), -y.toFloat()) })
            sample.getPixel(0, 0)
        } finally { sample.recycle() }
    }
    private fun assertPaints(presentation: NovelCoverPresentation, expected: Int) {
        presentation.image?.let { assertEquals("Drawable opacity must not leak between covers", 255, it.alpha) }
        val center = composedPixel(presentation)
        assertEquals("Compose must paint this cover's actual pixels, without a restarted fade or fallback", expected, center)
        assertEquals("Check composed alpha, not just the image reference", Color.alpha(expected), Color.alpha(center))
    }
    private fun assertEmpty(presentation: NovelCoverPresentation, vararg previousColors: Int) {
        assertNull("An invalidated cover must not retain an earlier image", presentation.image)
        val center = composedPixel(presentation)
        previousColors.forEach { assertNotEquals("The composed fallback must not paint stale private cover pixels", it, center) }
        assertEquals("The production fallback remains opaque", 255, Color.alpha(center))
    }
    private fun assertReleased(presentation: NovelCoverPresentation) {
        assertFalse("Disposed covers must leave the composition", presentation.attached)
        assertNull("Disposed presentations must release their private pixels", presentation.image)
        assertNull("Disposed bindings must release their previous key", boundKey(presentation))
        assertNull("Disposed presentations must release their layout coordinates", presentation.coordinates)
    }
    private fun assertSuccess(presentation: NovelCoverPresentation, value: Work, source: BitmapDrawable, color: Int, composed: Boolean = true) {
        assertTrue(presentation.attached)
        assertEquals("Correct pixels must belong to the full current book/projection/URL key", workKey(value), boundKey(presentation))
        assertFalse("Eviction/disposal must not recycle visible pixels", source.bitmap.isRecycled)
        assertSame("Keep the same successfully decoded bitmap", source.bitmap, pixels(presentation))
        assertEquals("Drawable opacity must not leak between covers", 255, requireNotNull(presentation.image).alpha)
        if (composed) assertPaints(presentation, color)
    }
    private fun assertEditorImage(presentation: NovelCoverPresentation) {
        val description = host.activity.getString(R.string.novel_en_uploaded_cover_preview)
        val node = host.nodes(true).single {
            it.config.getOrNull(SemanticsProperties.Role) == Role.Image &&
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
        }
        assertTrue("The uploaded preview must be an actual attached Compose Image", node.layoutInfo.isAttached && node.layoutInfo.isPlaced)
        val bounds = requireNotNull(presentation.coordinates).boundsInWindow()
        assertEquals("Image semantics must belong to this presentation", bounds.center.x, node.boundsInWindow.center.x, 1f)
        assertEquals("Image semantics must belong to this presentation", bounds.center.y, node.boundsInWindow.center.y, 1f)
        assertEquals(bounds.width, node.boundsInWindow.width, 1f)
        assertEquals(bounds.height, node.boundsInWindow.height, 1f)
    }
    private fun assertNoEditorImage() {
        val description = host.activity.getString(R.string.novel_en_uploaded_cover_preview)
        assertTrue("No stale uploaded Compose Image may survive pending selection or retirement", host.nodes(true).none {
            it.config.getOrNull(SemanticsProperties.Role) == Role.Image &&
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
        })
    }

    @Test fun realWorkRecompositionKeepsSameComposePresentationImageAndDoesNotRestartFactoryOrFade() {
        var current by mutableStateOf(work)
        var mounted by mutableStateOf(true)
        show { Column { NovelHeading("封面加载回归", size = 23); if (mounted) NovelBookCover(current, width = 98.dp); NovelHeading(current.title, size = 19) } }
        val presentation = covers().single(); assertEmpty(presentation, Color.RED); assertEquals(1, loader.calls.size)
        assertEquals("Compose must receive a software-compatible decoded bitmap", Bitmap.Config.ARGB_8888, loader.calls.single().request.desiredConfig)
        val red = image(Color.RED); loader.succeed(0, red); settle()
        assertSuccess(presentation, current, red, Color.RED)
        repeat(12) { index ->
            current = current.copy(title = "新标题 $index", reads = index.toLong(), inShelf = index % 2 == 0)
            settle()
            assertSame(presentation, covers().single())
            assertEquals("Metadata-only recomposition must not restart the presentation factory", 1, presentations.size)
            repeat(3) { assertSuccess(presentation, current, red, Color.RED) }
        }
        var previous = presentation
        repeat(3) {
            mounted = false; settle(); assertTrue(covers().isEmpty()); assertReleased(previous)
            mounted = true; settle()
            val restored = covers().single()
            assertNotSame("A remount must exercise a new composition-owned presentation factory", previous, restored)
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
            val previousNeighbor = cover(second)
            assertSuccess(fixed, work, red, Color.RED); assertSuccess(previousNeighbor, second, green, Color.GREEN)
            assertEquals(2, pressured.retainedCount); assertEquals(budget, pressured.retainedBytes)

            neighbor = third; settle()
            val replacement = cover(third)
            assertNotSame("A new book in the same Compose slot must have its own presentation", previousNeighbor, replacement)
            assertReleased(previousNeighbor)
            assertEmpty(replacement, Color.GREEN)
            val thirdCall = loader.calls.single { it.request.coverKey == workKey(third) }
            thirdCall.result(blue); settle()
            assertEquals(2, pressured.retainedCount); assertEquals(budget, pressured.retainedBytes)
            assertNull("Third 500x700 result must really evict the oldest reusable cache entry", pressured.image(workKey(work)))
            assertSuccess(fixed, work, red, Color.RED); assertSuccess(replacement, third, blue, Color.BLUE)
            repeat(8) { index ->
                revision = index + 1; settle()
                assertSame(fixed, cover(work)); assertSame(replacement, cover(third))
                repeat(2) { assertSuccess(fixed, work, red, Color.RED); assertSuccess(replacement, third, blue, Color.BLUE) }
                assertTrue(pressured.retainedBytes <= budget)
            }
            neighborMounted = false; settle(); assertReleased(replacement)
            assertSuccess(fixed, work, red, Color.RED)
            neighborMounted = true; settle()
            val remounted = cover(third)
            assertNotSame(replacement, remounted)
            assertSuccess(remounted, third, blue, Color.BLUE); assertSuccess(fixed, work, red, Color.RED)
            assertEquals("Eviction cannot cause visible redraw reloads or reload the warm MRU remount", 3, loader.calls.size)
            assertTrue(listOf(red, green, blue).none { it.bitmap.isRecycled })
        } finally { pressured.retire() }
    }

    @Test fun signatureUrlSwitchFailureNullUrlAndLateAbaRepliesRenderRealFallbackInsteadOfOldPrivateCover() {
        var current by mutableStateOf(work)
        show { Column { NovelHeading("封面加载回归", size = 23); NovelBookCover(current, width = 98.dp); NovelHeading(current.title, size = 19) } }
        var presentation = covers().single()
        val fallback = composedPixel(presentation)
        loader.succeed(0, image(Color.RED)); settle(); assertPaints(presentation, Color.RED)
        val original = presentation
        current = current.copy(coverUrl = "$url&version=2"); settle()
        presentation = covers().single(); assertNotSame(original, presentation); assertReleased(original)
        assertEmpty(presentation, Color.RED); assertPaints(presentation, fallback); assertEquals(workKey(current), boundKey(presentation)); assertEquals(2, loader.calls.size)
        loader.succeed(0, image(Color.GREEN)); settle(); assertEmpty(presentation, Color.RED, Color.GREEN)
        loader.fail(1); settle(); assertEmpty(presentation, Color.RED, Color.GREEN); assertPaints(presentation, fallback)
        assertTrue("The production typographic title must remain under the failed image", host.nodes(true).count {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == current.title } == true
        } >= 2)
        val changed = presentation
        current = current.copy(coverUrl = url); settle()
        presentation = covers().single(); assertReleased(changed); assertEmpty(presentation, Color.RED, Color.GREEN)
        assertEquals(workKey(current), boundKey(presentation))
        val attempt = loader.calls.lastIndex
        loader.succeed(1, image(Color.BLUE)); settle(); assertEmpty(presentation, Color.RED, Color.BLUE)
        loader.succeed(attempt, image(Color.GREEN)); settle(); assertPaints(presentation, Color.GREEN)
        val successful = presentation
        current = current.copy(coverUrl = null); settle()
        presentation = covers().single(); assertReleased(successful); assertNull(boundKey(presentation)); assertEmpty(presentation, Color.GREEN); assertPaints(presentation, fallback)
        loader.succeed(attempt, image(Color.BLUE)); settle(); assertEmpty(presentation, Color.GREEN, Color.BLUE); assertPaints(presentation, fallback)
        fixture.capture(host, "cover-removed-real-typographic-fallback")
    }

    @Test fun actualStoreLazyScrollDisposesAndRecreatesCoverWithoutPlaceholderOnReturn() {
        val catalog = (0..24).map { index -> NovelAppTestFixtures.work(index).copy(title = "滚动书籍 $index", coverUrl = "$url&book=$index") }
        val vm = NovelAppViewModel({ NovelRepository(TestNovelTransport()) }, MemoryNovelSearchHistory(), { true }).also { models.put("covers", it) }
        var state by mutableStateOf(NovelAppState(works = NovelLoad(Page(catalog, null))))
        show { NovelStoreScreen(state, vm, {}) }
        completeVisible()
        val first = cover(catalog.first()); val firstPixels = pixels(first); assertNotNull(firstPixels); assertPaints(first, Color.RED)
        host.scrollTo(hasText("滚动书籍 18")); settle(); assertReleased(first)
        val callsForFirst = loader.calls.count { it.request.coverKey.entity == "work:${catalog.first().id}" }
        for (attempt in 0 until 35) {
            val node = host.nodes().first { it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null }
            val range = requireNotNull(node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange))
            if (range.value() <= 0f) break
            node.config[SemanticsActions.ScrollBy].action!!.invoke(0f, -1000f); settle()
        }
        val restored = cover(catalog.first())
        assertNotSame("Returning to a disposed lazy item must create a fresh presentation", first, restored)
        assertSame("Scroll remount must reuse the exact decoded bitmap", firstPixels, pixels(restored)); assertPaints(restored, Color.RED)
        assertEquals(callsForFirst, loader.calls.count { it.request.coverKey.entity == "work:${catalog.first().id}" })
        // Real keyed item reordering must not let the old first book populate its new neighbour.
        state = state.copy(works = NovelLoad(Page(catalog.reversed(), null))); settle()
        covers().filter { pixels(it) === firstPixels }.forEach {
            assertEquals("Reordering must never assign one book's image to a neighbour", workKey(catalog.first()), boundKey(it))
            val (x, y) = composeCenter(it)
            if (x in 0 until host.attachedComposeView.width && y in 0 until host.attachedComposeView.height) assertPaints(it, Color.RED)
        }
        fixture.capture(host, "store-scroll-return-custom-cover")
    }

    @Test fun shelfChunkRegroupAndFilterMoveCoversByBookNotByPosition() {
        val books = (0..8).map { NovelAppTestFixtures.work(it).copy(title = "书架作品 $it", coverUrl = "$url&shelf=$it") }
        val vm = NovelAppViewModel({ NovelRepository(TestNovelTransport()) }, MemoryNovelSearchHistory(), { true }).also { models.put("shelf", it) }
        var state by mutableStateOf(NovelAppState(shelf = NovelLoad(Shelf(books, null))))
        show { NovelShelfScreen(state, vm, {}, { _, _ -> }) }
        // Resolve each visible book with a unique real bitmap, then regroup chunked(3) rows.
        val expected = mutableMapOf<NovelCoverKey, Triple<Work, BitmapDrawable, Int>>()
        loader.calls.toList().filter { !it.cancelled }.forEachIndexed { index, call ->
            val color = Color.rgb(20 + index * 20, 30, 160)
            val source = image(color)
            val book = books.single { workKey(it) == call.request.coverKey }
            expected[call.request.coverKey] = Triple(book, source, color)
            call.result(source)
        }
        settle()
        val initialCalls = loader.calls.size
        state = state.copy(shelf = NovelLoad(Shelf(books.drop(1), null))); settle()
        val retainedBook = books[1]
        assertEquals("A book moving to a different chunk must reuse only its own success", 1, loader.calls.count { it.request.coverKey.entity == "work:${retainedBook.id}" })
        val retained = cover(retainedBook)
        val (book, source, color) = requireNotNull(expected[workKey(retainedBook)])
        assertSuccess(retained, book, source, color)
        covers().filter { it.image != null }.forEach {
            val (value, bitmap, expectedColor) = requireNotNull(expected[boundKey(it)])
            val (x, y) = composeCenter(it)
            val inside = x in 0 until host.attachedComposeView.width && y in 0 until host.attachedComposeView.height
            assertSuccess(it, value, bitmap, expectedColor, composed = inside)
        }
        assertTrue(loader.calls.size <= initialCalls + 3)
        fixture.capture(host, "shelf-regroup-custom-covers")
    }

    @Test fun accountSwitchSameBookSameUrlAndLogoutClearComposeImageAndDoNotBorrowPreviousScope() {
        var owner by mutableStateOf(scope)
        val replacement = NovelCoverScope("other-account", { true })
        try {
            show { CompositionLocalProvider(LocalNovelCoverScope provides owner) { NovelBookCover(work, width = 98.dp) } }
            loader.succeed(0, image(Color.RED)); settle(); val original = covers().single(); assertPaints(original, Color.RED)
            owner = replacement; settle()
            var presentation = covers().single(); assertNotSame(original, presentation); assertReleased(original); assertEmpty(presentation, Color.RED)
            assertEquals(workKey(work), boundKey(presentation))
            loader.succeed(0, image(Color.GREEN)); settle(); assertEmpty(presentation, Color.RED, Color.GREEN)
            val index = loader.calls.lastIndex; loader.succeed(index, image(Color.BLUE)); settle(); assertPaints(presentation, Color.BLUE)
            val successful = presentation
            replacement.onLogout(AccountLoggedOutEvent("other-account")); settle()
            presentation = covers().single(); assertReleased(successful); assertEmpty(presentation, Color.RED, Color.GREEN, Color.BLUE)
            assertNull(boundKey(presentation))
            loader.succeed(index, image(Color.RED)); settle(); assertEmpty(presentation, Color.RED, Color.BLUE); assertEquals(0, replacement.retainedCount)
        } finally { replacement.retire() }
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
        // full keys catch cross-book cache/presentation reuse that an isNotNull assertion cannot detect.
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
        val storePresentations = covers().toList()
        fun assertPagePaints() {
            assertTrue("Production cover missing on $page", covers().isNotEmpty())
            var composed = 0
            covers().forEach { presentation ->
                val (book, source, color) = requireNotNull(expected[boundKey(presentation)]) { "Unexpected book cover on $page" }
                val (x, y) = composeCenter(presentation)
                val inside = x in 0 until host.attachedComposeView.width && y in 0 until host.attachedComposeView.height
                assertSuccess(presentation, book, source, color, composed = inside)
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
        storePresentations.forEach(::assertReleased)
        books.forEach { book ->
            assertEquals("All pages reuse only this book's successful pixels, without a new load", 1,
                loader.calls.count { it.request.coverKey == workKey(book) })
        }
    }

    @Test fun editorRemoteCoverUsesSameBindingAndPendingLocalSelectionSuppressesItImmediately() {
        var data by mutableStateOf(NovelMetadata(title = "编辑封面", coverUrl = url))
        show { NovelEditorCover(data, Modifier.size(90.dp, 120.dp), entity = "editor:one") }
        assertEquals(1, loader.calls.size); loader.succeed(0, image(Color.RED)); settle()
        val first = covers().single(); assertPaints(first, Color.RED); assertEditorImage(first)
        assertEquals(novelCoverKey("editor:one", "editor", url), boundKey(first))
        repeat(8) { data = data.copy(title = "编辑封面 $it"); settle(); assertSame(first, covers().single()); assertPaints(first, Color.RED); assertEditorImage(first) }
        assertEquals(1, loader.calls.size)
        data = data.copy(coverPending = true); settle()
        val pending = covers().single(); assertReleased(first); assertEmpty(pending, Color.RED); assertNull(boundKey(pending)); assertNoEditorImage()
        loader.succeed(0, image(Color.BLUE)); settle(); assertEmpty(pending, Color.RED, Color.BLUE); assertNoEditorImage()
        data = data.copy(coverPending = false, coverUrl = "$url&new=1"); settle()
        val replacement = covers().single(); assertReleased(pending); assertEmpty(replacement, Color.RED, Color.BLUE); assertNoEditorImage()
        assertEquals(novelCoverKey("editor:one", "editor", data.coverUrl), boundKey(replacement))
        loader.succeed(loader.calls.lastIndex, image(Color.BLUE)); settle(); assertPaints(replacement, Color.BLUE); assertEditorImage(replacement)
        scope.retire(); settle()
        val retired = covers().single(); assertReleased(replacement); assertEmpty(retired, Color.RED, Color.BLUE); assertNoEditorImage()
    }
}
