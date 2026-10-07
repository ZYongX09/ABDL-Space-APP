package org.joinmastodon.android.ui.compose.utils

import android.graphics.Bitmap
import android.os.Build
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.MastodonApp
import org.joinmastodon.android.ui.compose.component.effect.BgEffectBackground
import org.joinmastodon.android.ui.utils.LiquidGlassCompatibility
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 35], application = CompatibilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class GraphicsSafetyLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Before fun setUp() {
        MastodonApp.context = RuntimeEnvironment.getApplication()
    }

    @Test fun directEnabledNodeAttachesUpdatesRemovesAndRemountsNormally() {
        val visible = mutableStateOf(true)
        val revision = mutableStateOf(0)
        var attaches = 0
        var detaches = 0
        var updates = 0
        compose.setContent {
            if (visible.value) Box(Modifier.size(24.dp).safeBackdropEffect("direct lifecycle", fallback = {
                fail("no fallback")
            }) { enabled ->
                assertTrue(enabled)
                DirectElement(revision.value, attach = { attaches++ }, detach = { detaches++ },
                    update = { updates++ })
            })
        }
        compose.waitForIdle()
        assertEquals(1, attaches)
        compose.runOnIdle { revision.value++ }
        compose.waitForIdle()
        assertTrue(updates > 0)
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        assertEquals(1, detaches)
        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()
        assertEquals(2, attaches)
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        assertEquals(2, detaches)
        assertSupported()
    }

    @Test fun unwrappedAttachUpdateAndDetachFailuresPropagateDirectly() {
        val failure = IllegalStateException("node lifecycle")
        val element = DirectElement(0, attach = { throw failure }, detach = { throw failure },
            update = { throw failure })
        val modifier = Modifier.safeBackdropEffect("direct failure", fallback = { fail("no fallback") }) {
            assertTrue(it)
            element
        }
        assertSame(element, modifier)
        val node = element.create()
        // Direct calls avoid Compose's asynchronous uncaught-exception channel.
        assertSame(failure, assertThrows(IllegalStateException::class.java) { node.onAttach() })
        assertSame(failure, assertThrows(IllegalStateException::class.java) { element.update(node) })
        assertSame(failure, assertThrows(IllegalStateException::class.java) { node.onDetach() })
        assertSupported()
    }

    @Test fun recordingReturnsTheOriginalElementAndDrawsContentExactlyOnce() {
        var records = 0
        var contentDraws = 0
        val element = DirectElement(0, record = { records++ })
        val modifier = Modifier.safeBackdropRecording { element }
        assertSame(element, modifier)
        draw(element.create()) { contentDraws++ }
        assertEquals(1, records)
        assertEquals(1, contentDraws)
        assertSupported()
    }

    @Test fun recordingFailurePropagatesWithoutRedrawingContentOrDisablingEffects() {
        val failure = IllegalArgumentException("recording")
        for (safety in listOf(GraphicsSafety, PageBlurGraphicsSafety)) {
            var contentDraws = 0
            val element = DirectElement(0, record = { throw failure })
            assertSame(element, Modifier.safeBackdropRecording(safety) { element })
            assertSame(failure, assertThrows(IllegalArgumentException::class.java) {
                draw(element.create()) { contentDraws++ }
            })
            assertEquals(1, contentDraws)
            assertSupported()
        }
    }

    @Test fun contentFailureIsTheOriginalExceptionAndDoesNotEnterRecording() {
        val failure = IllegalArgumentException("application content")
        val element = DirectElement(0, record = { fail("recording after failed content") })
        assertSame(element, Modifier.safeBackdropRecording { element })
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) {
            draw(element.create()) { throw failure }
        })
        assertSupported()
    }

    @Test fun rememberedSupportStaysSdkOnlyAcrossFailureAndRecomposition() {
        val revision = mutableStateOf(0)
        val observed = mutableListOf<List<Boolean>>()
        compose.setContent {
            revision.value
            observed += listOf(GraphicsSafety, BackgroundGraphicsSafety, PageBlurGraphicsSafety)
                .map { rememberGraphicsEffectsSupported(it) }
        }
        compose.waitForIdle()
        val failure = UnsatisfiedLinkError("driver")
        assertSame(failure, assertThrows(UnsatisfiedLinkError::class.java) {
            BackgroundGraphicsSafety.guarded("failure", fallback = { fail("no fallback") }) { throw failure }
        })
        compose.runOnIdle { revision.value++ }
        compose.waitForIdle()
        assertTrue(observed.size >= 2)
        assertTrue(observed.all { flags -> flags.all { it == (Build.VERSION.SDK_INT >= 33) } })
        assertSupported()
    }

    @Test @Config(sdk = [26, 32]) fun rememberedSupportIsFalseOnOldSdk() {
        var observed = emptyList<Boolean>()
        compose.setContent {
            observed = listOf(GraphicsSafety, BackgroundGraphicsSafety, PageBlurGraphicsSafety)
                .map { rememberGraphicsEffectsSupported(it) }
        }
        compose.waitForIdle()
        assertEquals(listOf(false, false, false), observed)
    }

    @Test fun softwareScreenshotKeepsBackgroundAndSystemSupported() {
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            BgEffectBackground(dynamicBackground = false, modifier = Modifier.size(24.dp).testTag("effect")) {
                Box(Modifier.size(24.dp).background(Color.Blue))
            }
        }
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("effect").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(android.graphics.Canvas(bitmap))
                assertEquals(Color.Blue.toArgb(), bitmap.getPixel(bounds.center.x.toInt(), bounds.center.y.toInt()))
            } finally { bitmap.recycle() }
        }
        assertSupported()
    }

    private fun assertSupported() {
        assertTrue(LiquidGlassCompatibility.isSystemSupported())
        assertTrue(GraphicsSafety.isSupported())
        assertTrue(BackgroundGraphicsSafety.isSupported())
        assertTrue(PageBlurGraphicsSafety.isSupported())
    }

    private fun draw(node: DirectNode, content: () -> Unit) {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        try {
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr,
                Canvas(android.graphics.Canvas(bitmap)), Size(4f, 4f)) {
                val scope = object : ContentDrawScope, DrawScope by this {
                    override fun drawContent() = content()
                }
                with(node) { scope.draw() }
            }
        } finally { bitmap.recycle() }
    }

    private data class DirectElement(
        val revision: Int,
        val attach: () -> Unit = {},
        val detach: () -> Unit = {},
        val update: () -> Unit = {},
        val record: () -> Unit = {},
    ) : ModifierNodeElement<DirectNode>() {
        override fun create() = DirectNode(attach, detach, record)
        override fun update(node: DirectNode) = update()
        override fun InspectorInfo.inspectableProperties() { name = "directLifecycle" }
    }

    private class DirectNode(
        val attach: () -> Unit,
        val detach: () -> Unit,
        val record: () -> Unit,
    ) : Modifier.Node(), DrawModifierNode {
        override fun onAttach() = attach()
        override fun onDetach() = detach()
        override fun ContentDrawScope.draw() { drawContent(); record() }
    }
}
