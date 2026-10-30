package org.joinmastodon.android.novel.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.IntState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.novel.app.design.NovelTheme
import org.joinmastodon.android.ui.compose.navigation.ViewBitmapBackdrop
import org.joinmastodon.android.ui.views.BackdropCaptureFrameLayout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import androidx.compose.ui.graphics.Color as ComposeColor

/** Real NovelCapturedPage -> ComposeView -> LazyColumn pixels, using the existing bounded host.
 * Only the API capability gate is simulated; the attached native fixture already reports hardware
 * eligibility. The production enabled binding owns its listener throughout layout, scroll and
 * cover updates. The ordinary draw records into a real RenderNode, never a Bitmap Canvas pretending
 * to be hardware. Asserted pixels come from production's native software capture of the actual
 * child subtree, NOT GPU, RuntimeShader, blur or refraction acceptance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w393dp-h852dp-notnight-mdpi", application = CompatibilityTestApplication::class,
    shadows = [NovelBackdropCaptureBindingTest.CompatibilityShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NovelCapturedPageSamplingTest {
    private val fixture = NovelScreenshotFixture("navigation-sampling")
    private val host get() = fixture.host
    @get:Rule val rule: org.junit.rules.TestRule = fixture
    private val backdrop = ViewBitmapBackdrop()
    private lateinit var listState: LazyListState
    private lateinit var productionListener: BackdropCaptureFrameLayout.CaptureListener
    private var replacementCover by mutableStateOf<ImageBitmap?>(null)
    private var expectedBackground = 0

    private fun showPage() {
        listState = LazyListState()
        // Offline source artwork only; never paint or replace a captured/output bitmap.
        val warm = cover(Color.RED, Color.YELLOW)
        val cool = cover(Color.BLUE, Color.CYAN)
        fixture.show(host) {
            val colors = NovelTheme.colors
            val replacement = replacementCover // Exercise latestContent across the ComposeView boundary.
            SideEffect { expectedBackground = colors.background.toArgb() }
            Box(Modifier.fillMaxSize().background(ComposeColor.Magenta)) {
                NovelCapturedPage(backdrop, enabled = true, captureHeight = STRIP_HEIGHT, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(horizontal = 24.dp)) {
                        items(20, key = { it }) { index ->
                            Row(Modifier.fillMaxWidth().height(128.dp), verticalAlignment = Alignment.CenterVertically) {
                                Image(if (index == 7 && replacement != null) replacement else if (index % 2 == 0) warm else cool,
                                    contentDescription = "Cover $index", contentScale = ContentScale.FillBounds,
                                    modifier = Modifier.width(72.dp).height(96.dp))
                                Text("Captured work $index", color = colors.text, modifier = Modifier.padding(start = 16.dp))
                            }
                        }
                    }
                }
                // Deliberately overlaps every sampled pixel vertically, but is not a capture child.
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(140.dp).background(ComposeColor.Magenta))
            }
        }
    }

    private fun eligibleCapture(): BackdropCaptureFrameLayout {
        val capture = descendants(host.attachedComposeView).filterIsInstance<BackdropCaptureFrameLayout>().single()
        assertTrue(capture.isAttachedToWindow)
        assertEquals(host.viewport().first, capture.width)
        assertEquals(host.viewport().second, capture.height)
        assertEquals("The production capture owns only its page ComposeView", 1, capture.childCount)
        val child = capture.getChildAt(0) as ComposeView
        assertTrue(child.isAttachedToWindow && child.hasComposition)
        assertEquals(capture.width, child.width)
        assertEquals(capture.height, child.height)
        // Native Robolectric attachment already reports hardware eligibility for this host.
        // This View property does not describe the pixel-producing capture Canvas: production
        // explicitly uses Canvas(Bitmap), and our ordinary pass uses a real DisplayListCanvas.
        // Do not mutate AttachInfo, install a test listener or re-arm after content updates.
        assertTrue("The attached fixture must pass the production binding's hardware gate", capture.isHardwareAccelerated)
        productionListener = requireNotNull(listener(capture)) { "Enabled production binding must register its listener" }
        assertEquals(0, ReflectionHelpers.getField<Int>(capture, "topCaptureHeight"))
        assertEquals(STRIP_HEIGHT, ReflectionHelpers.getField<Int>(capture, "bottomCaptureHeight"))
        return capture
    }

    private fun listener(capture: BackdropCaptureFrameLayout) =
        ReflectionHelpers.getField<BackdropCaptureFrameLayout.CaptureListener?>(capture, "captureListener")

    private fun generation() = ReflectionHelpers.getField<IntState>(backdrop, "generation\$delegate").intValue

    private fun sample(capture: BackdropCaptureFrameLayout): Bitmap {
        assertSame("Content updates must retain the production listener without re-arming", productionListener, listener(capture))
        val beforeGeneration = generation()
        val nodeClass = Class.forName("android.view.RenderNode")
        val canvasClass = Class.forName("android.view.DisplayListCanvas")
        val node = nodeClass.getMethod("create", String::class.java, View::class.java).invoke(null, "novel-page-sampling-test", capture)
        val canvas = nodeClass.getMethod("start", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(node, capture.width, capture.height) as Canvas
        assertTrue(canvasClass.isInstance(canvas))
        assertTrue(canvas.isHardwareAccelerated)
        try {
            ReflectionHelpers.callInstanceMethod<Void>(capture, "dispatchDraw", ReflectionHelpers.ClassParameter.from(Canvas::class.java, canvas))
        } finally {
            nodeClass.getMethod("end", canvasClass).invoke(node, canvas)
            nodeClass.getMethod("discardDisplayList").invoke(node)
        }
        assertEquals("Production binding must publish exactly one fresh child-subtree strip", beforeGeneration + 1, generation())
        assertSame("Production listener must survive capture", productionListener, listener(capture))
        val actual = requireNotNull(ReflectionHelpers.getField<Bitmap?>(capture, "bottomCaptureBitmap"))
        val image = ReflectionHelpers.getField<State<ImageBitmap?>>(backdrop, "image\$delegate").value
        assertSame("Backdrop receives the actual production strip", actual, requireNotNull(image).asAndroidBitmap())
        assertEquals(capture.width, actual.width)
        assertEquals(STRIP_HEIGHT, actual.height)
        // Capture reuses its buffer. Preserve actual pixels before the next frame overwrites it.
        return requireNotNull(actual.copy(Bitmap.Config.ARGB_8888, false))
    }

    private fun assertOpaquePage(sample: Bitmap) {
        for (y in 0 until sample.height) for (x in 0 until sample.width) {
            val pixel = sample.getPixel(x, y)
            assertEquals("Captured child must fill transparent list gaps at ($x,$y)", 255, Color.alpha(pixel))
            assertNotEquals("Navigation sibling must never enter the page sample", Color.MAGENTA, pixel)
        }
        for (y in listOf(0, sample.height / 2, sample.height - 1)) {
            assertEquals("Left page gutter uses inherited NovelTheme background", expectedBackground, sample.getPixel(4, y))
            assertEquals("Right page gutter uses inherited NovelTheme background", expectedBackground, sample.getPixel(sample.width - 5, y))
        }
    }

    @Test fun capturedComposePageGapsAreOpaqueInLightAndDarkThemesWithoutNavigationSibling() {
        showPage()
        val backgrounds = mutableSetOf<Int>()
        for (dark in listOf(false, true)) {
            fixture.setDark(host, dark)
            fixture.settle(host)
            val capture = eligibleCapture()
            val strip = sample(capture)
            try {
                assertOpaquePage(strip)
                backgrounds += expectedBackground
                val screen = Bitmap.createBitmap(capture.width, capture.height, Bitmap.Config.ARGB_8888)
                try {
                    host.attachedComposeView.draw(Canvas(screen))
                    assertEquals("Sibling is visibly above the sampled page on screen", Color.MAGENTA,
                        screen.getPixel(40, capture.height - STRIP_HEIGHT + 60))
                    assertEquals("Sample is real underlying Compose cover, not the visible sibling", Color.RED, strip.getPixel(40, 60))
                } finally { screen.recycle() }
            } finally { strip.recycle() }
        }
        assertEquals("Both inherited production theme backgrounds were exercised", 2, backgrounds.size)
    }

    @Test fun lazyColumnScrollAndCoverReplacementRefreshRealCapturedPagePixels() {
        showPage()
        val capture = eligibleCapture()
        val before = sample(capture)
        try {
            assertOpaquePage(before)
            assertEquals(Color.RED, before.getPixel(40, 60))
            assertEquals(Color.YELLOW, before.getPixel(40, 112))
            assertEquals(128f, host.runOnUiThread { listState.dispatchRawDelta(128f) }, .01f)
            fixture.settle(host)
            assertEquals("Real LazyColumn advances one row", 1, listState.firstVisibleItemIndex)
            assertEquals(0, listState.firstVisibleItemScrollOffset)
            val scrolled = sample(capture)
            try {
                assertOpaquePage(scrolled)
                assertFalse("Scrolling must change the actual delivered bitmap", before.sameAs(scrolled))
                assertEquals(Color.BLUE, scrolled.getPixel(40, 60))
                assertEquals(Color.CYAN, scrolled.getPixel(40, 112))
                host.runOnUiThread { replacementCover = cover(Color.BLACK, Color.WHITE) }
                fixture.settle(host)
                val updated = sample(capture)
                try {
                    assertOpaquePage(updated)
                    assertFalse("Cover update must replace the previous sampled pixels", scrolled.sameAs(updated))
                    assertEquals(Color.BLACK, updated.getPixel(40, 60))
                    assertEquals(Color.WHITE, updated.getPixel(40, 112))
                    assertSame("Content updates retain the production capture view", capture,
                        descendants(host.attachedComposeView).filterIsInstance<BackdropCaptureFrameLayout>().single())
                    assertEquals(1, listState.firstVisibleItemIndex)
                } finally { updated.recycle() }
            } finally { scrolled.recycle() }
        } finally { before.recycle() }
    }

    private fun cover(top: Int, bottom: Int): ImageBitmap = Bitmap.createBitmap(
        IntArray(12 * 16) { if (it / 12 < 8) top else bottom }, 12, 16, Bitmap.Config.ARGB_8888,
    ).asImageBitmap()

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private companion object { const val STRIP_HEIGHT = 120 }
}
