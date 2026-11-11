package org.joinmastodon.android.novel.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.IntState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.novel.app.design.NovelTheme
import org.joinmastodon.android.novel.app.editor.NovelEditorCover
import org.joinmastodon.android.novel.app.editor.NovelMetadata
import org.joinmastodon.android.ui.compose.navigation.ViewBitmapBackdrop
import org.joinmastodon.android.ui.views.BackdropCaptureFrameLayout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import kotlin.math.abs
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * The actual NovelBookCover/NovelEditorCover, controlled static loader, mounted LazyColumn and
 * production NovelCapturedPage participate in every frame. Only the API capability gate is
 * shadowed. A real API28 RenderNode records the ordinary pass; production then rasterizes its
 * child subtree into the actual delivered bottom strip. The second observation draws the attached
 * native Compose root after that pass, so a correct strip cannot hide a blank on-screen cover.
 *
 * Both pixel observations are native SOFTWARE raster assertions, not GPU/PixelCopy, RuntimeShader,
 * blur or device acceptance. There are at most twelve checkpoints per scenario, no unbounded idle
 * loops, no network and no replacement of captured/output pixels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28], qualifiers = "zh-rCN-w393dp-h852dp-notnight-mdpi",
    application = CompatibilityTestApplication::class,
    shadows = [NovelBackdropCaptureBindingTest.CompatibilityShadow::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NovelCoverCapturedScrollTest {
    private val fixture = NovelScreenshotFixture("cover-captured-scroll")
    private val host get() = fixture.host
    @get:Rule val rule: org.junit.rules.TestRule = fixture
    private val coverScope = NovelCoverScope(NovelScreenshotFixture.ACCOUNT, { true })
    private val loader = ControlledNovelCoverLoader()
    private val backdrop = ViewBitmapBackdrop()
    private val works = List(12) { index ->
        NovelAppTestFixtures.work(index).copy(coverUrl = "https://covers.example.test/scroll-$index.png?version=static")
    }
    private val artworks = mutableMapOf<String, BitmapDrawable>()
    private val mountedEntities = mutableSetOf<String>()
    private var completedCalls = 0
    private lateinit var listState: LazyListState
    private var captureEnabled by mutableStateOf(true)
    private var operationRevision by mutableIntStateOf(0)
    private var listEpoch by mutableIntStateOf(0)
    private var editorEpoch by mutableIntStateOf(0)
    private var editorData by mutableStateOf(NovelMetadata(title = "Static editor control", coverUrl = EDITOR_URL))
    private var expectedBackground = 0

    @After fun retireTestCoverScope() { coverScope.retire() }

    private fun artwork(top: Int, bottom: Int): BitmapDrawable = BitmapDrawable(host.activity.resources,
        Bitmap.createBitmap(IntArray(36 * 50) { if (it / 36 < 30) top else bottom }, 36, 50, Bitmap.Config.ARGB_8888))

    private fun colors(index: Int) = if (index % 2 == 0) Color.RED to Color.YELLOW else Color.BLUE to Color.CYAN

    private fun showCapturedList() {
        works.forEachIndexed { index, work ->
            val (top, bottom) = colors(index)
            artworks["work:${work.id}"] = artwork(top, bottom)
        }
        listState = LazyListState()
        fixture.show(host) {
            CompositionLocalProvider(LocalNovelCoverScope provides coverScope, LocalNovelCoverLoader provides loader) {
                val background = NovelTheme.colors.background.toArgb()
                SideEffect { expectedBackground = background }
                val revision = operationRevision // Exercise latestContent and unchanged-identity updates.
                Box(Modifier.fillMaxSize().background(ComposeColor.Magenta)) {
                    NovelCapturedPage(backdrop, captureEnabled, STRIP_HEIGHT, Modifier.fillMaxSize()) {
                        key(listEpoch) {
                            LazyColumn(Modifier.fillMaxSize(), state = listState,
                                contentPadding = PaddingValues(horizontal = 24.dp)) {
                                items(works.size, key = { works[it].id }) { index ->
                                    val work = works[index].copy(title = "${works[index].title} / $revision")
                                    DisposableEffect(work.id) {
                                        val entity = "work:${work.id}"
                                        mountedEntities += entity
                                        onDispose { mountedEntities -= entity }
                                    }
                                    Box(Modifier.fillMaxWidth().height(ROW_HEIGHT.dp), contentAlignment = Alignment.Center) {
                                        NovelBookCover(work, width = COVER_WIDTH.dp)
                                    }
                                }
                            }
                        }
                    }
                    // A visible navigation sibling occupies the right gutter, never the page strip.
                    Box(Modifier.align(Alignment.BottomEnd).width(16.dp).height(STRIP_HEIGHT.dp)
                        .background(ComposeColor.Magenta))
                }
            }
        }
        assertTrue("The real mounted cover must request through the controlled loader", loader.calls.isNotEmpty())
        settleImages()
    }

    /** Complete only real loader calls, using tiny source artwork, never capture/output buffers. */
    private fun settleImages() {
        host.settle(frames = 2)
        fun deliverPending() {
            val end = loader.calls.size
            while (completedCalls < end) {
                val index = completedCalls++
                val request = loader.calls[index].request
                assertEquals("Static covers must request software-decodable ARGB pixels", Bitmap.Config.ARGB_8888, request.desiredConfig)
                val entity = request.coverKey.entity
                loader.succeed(index, requireNotNull(artworks[entity]) { "Unexpected cover identity $entity" })
            }
        }
        deliverPending()
        host.settle(frames = 16) // Includes the existing 200ms success transition, if present.
        // Lazy prefetch can mount one adjacent row during those frames. Bound its completion too.
        if (completedCalls < loader.calls.size) {
            deliverPending()
            host.settle(frames = 16)
        }
        assertEquals("Every requested static source must have been delivered in bounded frames", loader.calls.size, completedCalls)
        requestCounts().forEach { (entity, count) ->
            assertEquals("An unchanged successful identity must not request again: $entity", 1, count)
        }
    }

    private fun requestCounts() = loader.calls.groupingBy { it.request.coverKey.entity }.eachCount()

    private fun assertMountedRequestsUnchanged(before: Map<String, Int>, previouslyMounted: Set<String>) {
        val after = requestCounts()
        previouslyMounted.intersect(mountedEntities).forEach { entity ->
            assertEquals("Scrolling/operations must not reload an unchanged mounted cover: $entity", before[entity], after[entity])
        }
    }

    private fun captureView(): BackdropCaptureFrameLayout {
        val capture = descendants(host.attachedComposeView).filterIsInstance<BackdropCaptureFrameLayout>().single()
        assertTrue(capture.isAttachedToWindow)
        assertTrue("Use the fixture's real hardware-eligible attachment, not a fake Canvas", capture.isHardwareAccelerated)
        assertEquals(host.viewport().first, capture.width)
        assertEquals(host.viewport().second, capture.height)
        assertEquals(1, capture.childCount)
        val page = capture.getChildAt(0) as ComposeView
        assertTrue(page.isAttachedToWindow && page.hasComposition)
        assertEquals(capture.width, page.width)
        assertEquals(capture.height, page.height)
        return capture
    }

    private fun listener(capture: BackdropCaptureFrameLayout) =
        ReflectionHelpers.getField<BackdropCaptureFrameLayout.CaptureListener?>(capture, "captureListener")
    private fun image() = ReflectionHelpers.getField<State<ImageBitmap?>>(backdrop, "image\$delegate").value
    private fun generation() = ReflectionHelpers.getField<IntState>(backdrop, "generation\$delegate").intValue

    private fun recordOrdinaryFrame(capture: BackdropCaptureFrameLayout) {
        val nodeClass = Class.forName("android.view.RenderNode")
        val canvasClass = Class.forName("android.view.DisplayListCanvas")
        val node = nodeClass.getMethod("create", String::class.java, View::class.java)
            .invoke(null, "novel-cover-scroll-test", capture)
        val canvas = nodeClass.getMethod("start", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(node, capture.width, capture.height) as Canvas
        assertTrue(canvasClass.isInstance(canvas))
        assertTrue(canvas.isHardwareAccelerated)
        try {
            ReflectionHelpers.callInstanceMethod<Void>(capture, "dispatchDraw",
                ReflectionHelpers.ClassParameter.from(Canvas::class.java, canvas))
        } finally {
            nodeClass.getMethod("end", canvasClass).invoke(node, canvas)
            nodeClass.getMethod("discardDisplayList").invoke(node)
        }
    }

    private fun assertStaticCoversAreComposed(label: String) {
        assertTrue("$label: bitmap successes must have no native NovelCoverImageView/ImageView descendants",
            descendants(host.attachedComposeView).none { it is ImageView })
    }

    private fun scrollTo(offset: Int) {
        val before = listState.firstVisibleItemIndex * ROW_HEIGHT + listState.firstVisibleItemScrollOffset
        assertEquals("Use real LazyColumn scrolling, not view translation", (offset - before).toFloat(),
            host.runOnUiThread { listState.dispatchRawDelta((offset - before).toFloat()) }, .01f)
    }

    private fun expectedAt(pageY: Int): Int {
        val contentY = listState.firstVisibleItemIndex * ROW_HEIGHT + listState.firstVisibleItemScrollOffset + pageY
        val index = contentY / ROW_HEIGHT
        val coverY = contentY % ROW_HEIGHT - COVER_TOP
        return when (coverY) {
            in 0 until COLOR_SPLIT -> colors(index).first
            in COLOR_SPLIT until COVER_HEIGHT -> colors(index).second
            else -> expectedBackground
        }
    }

    private fun awayFromArtworkEdges(pageY: Int): Boolean {
        val coverY = (listState.firstVisibleItemScrollOffset + pageY) % ROW_HEIGHT - COVER_TOP
        return listOf(0, COLOR_SPLIT, COVER_HEIGHT).all { abs(coverY - it) >= 4 }
    }

    private fun assertCapturedFrame(capture: BackdropCaptureFrameLayout, screen: Bitmap, label: String) {
        val registered = listener(capture)
        val beforeGeneration = generation()
        if (captureEnabled) assertNotNull("$label: production binding must be listening", registered)
        else assertNull("$label: plain mode must disable production capture", registered)
        recordOrdinaryFrame(capture)
        assertSame("$label: an ordinary pass must retain the production registration", registered, listener(capture))
        if (captureEnabled) {
            assertEquals("$label: publish one fresh production strip", beforeGeneration + 1, generation())
            assertEquals(0, ReflectionHelpers.getField<Int>(capture, "topCaptureHeight"))
            assertEquals(STRIP_HEIGHT, ReflectionHelpers.getField<Int>(capture, "bottomCaptureHeight"))
            val strip = requireNotNull(ReflectionHelpers.getField<Bitmap?>(capture, "bottomCaptureBitmap"))
            assertSame("$label: inspect the actual bitmap published to the backdrop", strip, requireNotNull(image()).asAndroidBitmap())
            assertEquals(capture.width, strip.width)
            assertEquals(STRIP_HEIGHT, strip.height)
            var artworkSamples = 0
            for (y in 0 until strip.height) {
                for (x in 0 until strip.width) {
                    if (Color.alpha(strip.getPixel(x, y)) != 255) fail("$label: transparent captured page at ($x,$y)")
                }
                assertEquals("$label: inherited left page gutter", expectedBackground, strip.getPixel(4, y))
                assertEquals("$label: navigation sibling must not enter the strip", expectedBackground, strip.getPixel(strip.width - 5, y))
                val pageY = capture.height - strip.height + y
                if (awayFromArtworkEdges(pageY)) {
                    val expected = expectedAt(pageY)
                    for (x in listOf(strip.width / 2 - 12, strip.width / 2, strip.width / 2 + 12)) {
                        assertEquals("$label: actual production strip artwork at ($x,$y)", expected, strip.getPixel(x, y))
                    }
                    if (expected != expectedBackground) artworkSamples++
                }
            }
            assertTrue("$label: every checkpoint must inspect actual artwork within the bottom strip", artworkSamples >= 30)
        } else {
            assertEquals("$label: disabled ordinary draws cannot publish samples", beforeGeneration, generation())
            assertNull(image())
            assertEquals(0, ReflectionHelpers.getField<Int>(capture, "bottomCaptureHeight"))
            assertNull(ReflectionHelpers.getField<Bitmap?>(capture, "captureBitmap"))
            assertNull(ReflectionHelpers.getField<Bitmap?>(capture, "bottomCaptureBitmap"))
        }

        // Observe the visible attached root AFTER the production recording/capture pass ends.
        val publishedGeneration = generation()
        screen.eraseColor(Color.TRANSPARENT)
        host.attachedComposeView.draw(Canvas(screen))
        // Drawing the outer Compose root may first refresh its nested AndroidView display list
        // with a hardware recording, even though the final observation Canvas is software. Allow
        // that single legitimate capture, never recursion/unbounded publications; inspect the
        // actual visible cover pixels below rather than inferring them from a callback count.
        val observationCaptures = generation() - publishedGeneration
        if (captureEnabled) assertTrue("$label: root observation must not recurse into repeated captures", observationCaptures in 0..1)
        else assertEquals("$label: disabled capture stays disabled during root observation", 0, observationCaptures)
        assertSame("$label: root observation retains the production registration", registered, listener(capture))
        assertEquals("$label: visible native-root center must not flash to typographic fallback",
            expectedAt(screen.height / 2), screen.getPixel(screen.width / 2, screen.height / 2))
        var visibleCoverCenters = 0
        listState.layoutInfo.visibleItemsInfo.forEach { item ->
            val top = item.offset + COVER_TOP
            if (top >= 0 && top + COVER_HEIGHT <= screen.height) {
                val x = screen.width / 2
                assertEquals("$label: visible cover ${item.index} center from the real root", colors(item.index).first,
                    screen.getPixel(x, top + COVER_HEIGHT / 2))
                assertEquals("$label: visible cover ${item.index} lower artwork from the real root", colors(item.index).second,
                    screen.getPixel(x, top + COVER_HEIGHT * 3 / 4))
                visibleCoverCenters++
            }
        }
        assertTrue("$label: inspect several fully visible composed covers", visibleCoverCenters >= 4)
        assertEquals("$label: navigation sibling is really visible, just not captured", Color.MAGENTA,
            screen.getPixel(screen.width - 5, screen.height - 20))
        assertStaticCoversAreComposed(label)
    }

    @Test fun staticAndWarmBookCoversKeepRealStripAndVisibleRootPixelsAtEveryScrollCheckpoint() {
        showCapturedList()
        val capture = captureView()
        val registered = requireNotNull(listener(capture))
        val screen = Bitmap.createBitmap(capture.width, capture.height, Bitmap.Config.ARGB_8888)
        try {
            // Small deltas stay inside a mounted cover; larger deltas move both artwork halves
            // through the bottom strip, dispose old rows, then return through the warm cache.
            val offsets = listOf(0, 12, 36, 64, 96, 128, 192, 128, 64, 16, 0)
            var warmRequestCount: Int? = null
            offsets.forEachIndexed { step, offset ->
                val before = requestCounts()
                val previouslyMounted = mountedEntities.toSet()
                if (step == 7) {
                    warmRequestCount = loader.calls.size
                    host.runOnUiThread { listEpoch++ }
                    settleImages()
                    assertEquals("Recreated lazy covers must use the same host's warm successful pixels", warmRequestCount, loader.calls.size)
                    assertCapturedFrame(capture, screen, "warm remount before reverse scroll")
                }
                host.runOnUiThread { if (step == 3 || step == 8) operationRevision++ }
                scrollTo(offset)
                settleImages()
                assertEquals(offset / ROW_HEIGHT, listState.firstVisibleItemIndex)
                assertEquals(offset % ROW_HEIGHT, listState.firstVisibleItemScrollOffset)
                assertMountedRequestsUnchanged(before, previouslyMounted)
                warmRequestCount?.let { assertEquals("Warm reverse scrolling must not start another request", it, loader.calls.size) }
                assertSame("Content operations must retain the actual capture view", capture, captureView())
                assertSame("Scrolling and warm remount must not re-arm the capture listener", registered, listener(capture))
                assertCapturedFrame(capture, screen, "scroll checkpoint $step at $offset")
            }
        } finally { screen.recycle() }
    }

    @Test fun captureEnableDisableAndMetadataOperationsDoNotChangeMountedStaticCoverPixelsOrRequests() {
        showCapturedList()
        val capture = captureView()
        val screen = Bitmap.createBitmap(capture.width, capture.height, Bitmap.Config.ARGB_8888)
        try {
            val offsets = listOf(0, 12, 36, 64, 96, 128, 96, 64, 24, 0)
            val enabled = listOf(true, false, false, true, true, false, true, false, true, true)
            offsets.forEachIndexed { step, offset ->
                val before = requestCounts()
                val previouslyMounted = mountedEntities.toSet()
                host.runOnUiThread { captureEnabled = enabled[step]; operationRevision++ }
                scrollTo(offset)
                settleImages()
                assertMountedRequestsUnchanged(before, previouslyMounted)
                assertSame("Capture controls must not replace or detach the page", capture, captureView())
                assertCapturedFrame(capture, screen, "capture ${enabled[step]} operation $step")
            }
        } finally { screen.recycle() }
    }

    @Test fun directEditorRemoteCoverSurvivesMetadataOperationsAndWarmRemountWithoutCapture() {
        artworks[EDITOR_ENTITY] = artwork(Color.RED, Color.YELLOW)
        fixture.show(host) {
            CompositionLocalProvider(LocalNovelCoverScope provides coverScope, LocalNovelCoverLoader provides loader) {
                val background = NovelTheme.colors.background.toArgb()
                SideEffect { expectedBackground = background }
                Box(Modifier.fillMaxSize()) {
                    key(editorEpoch) {
                        NovelEditorCover(editorData, Modifier.align(Alignment.Center).size(COVER_WIDTH.dp, COVER_HEIGHT.dp),
                            entity = EDITOR_ENTITY)
                    }
                }
            }
        }
        assertEquals(1, loader.calls.size)
        assertEquals("editor", loader.calls.single().request.coverKey.projection)
        settleImages()
        val (width, height) = host.viewport()
        val screen = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            repeat(8) { step ->
                host.runOnUiThread {
                    editorData = editorData.copy(title = "Metadata operation $step", description = "Revision $step", accepted = step % 2 == 0)
                    if (step == 4) editorEpoch++
                }
                settleImages()
                assertEquals("Direct editor operations and warm remount keep the same successful identity", 1, loader.calls.size)
                assertTrue("This is a direct editor control, with no capture wrapper",
                    descendants(host.attachedComposeView).none { it is BackdropCaptureFrameLayout })
                screen.eraseColor(Color.TRANSPARENT)
                host.attachedComposeView.draw(Canvas(screen))
                assertEquals("Editor root center must remain custom artwork at step $step", Color.RED, screen.getPixel(width / 2, height / 2))
                assertEquals(Color.RED, screen.getPixel(width / 2, height / 2 - 25))
                assertEquals(Color.YELLOW, screen.getPixel(width / 2, height / 2 + 25))
                assertEquals(expectedBackground, screen.getPixel(4, height / 2))
                assertStaticCoversAreComposed("direct editor checkpoint $step")
            }
        } finally { screen.recycle() }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private companion object {
        const val STRIP_HEIGHT = 120
        const val ROW_HEIGHT = 128
        const val COVER_WIDTH = 72
        const val COVER_HEIGHT = 100 // NovelBookCover's production .72 aspect ratio at 72dp.
        const val COVER_TOP = (ROW_HEIGHT - COVER_HEIGHT) / 2
        const val COLOR_SPLIT = 60 // Source artwork splits at 30/50, away from cover centers.
        const val EDITOR_ENTITY = "editor:captured-scroll-control"
        const val EDITOR_URL = "https://covers.example.test/editor-control.png?version=static"
    }
}
