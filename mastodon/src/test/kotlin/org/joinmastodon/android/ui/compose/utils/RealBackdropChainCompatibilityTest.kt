package org.joinmastodon.android.ui.compose.utils

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.ui.compose.navigation.ViewBitmapBackdrop
import org.joinmastodon.android.ui.compose.navigation.liquid.CombinedBackdrop
import org.joinmastodon.android.ui.utils.LiquidGlassCompatibility
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

/** Real miuix 0.9.3 elements and Compose lifecycles, with no replacement/delegating nodes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 35], application = CompatibilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class RealBackdropChainCompatibilityTest {
    @get:Rule val compose = createComposeRule()

    private fun Modifier.elements(): List<Modifier.Element> =
        foldIn(emptyList()) { result, element -> result + element }

    private fun backdrop(combined: Boolean): Backdrop = if (combined) {
        CombinedBackdrop(ViewBitmapBackdrop(), ViewBitmapBackdrop())
    } else ViewBitmapBackdrop()

    private fun realBackdrop(
        backdrop: Backdrop,
        enabled: Boolean = true,
        layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
        effects: BackdropEffectScope.() -> Unit = {},
    ): Modifier = Modifier.drawBackdrop(
        enabled = enabled,
        backdrop = backdrop,
        shape = { CircleShape },
        effects = effects,
        layerBlock = layerBlock,
        onDrawSurface = { drawRect(Color.Blue) },
    )

    @Test fun singleNodeFactoryReturnsTheExactEnabledLibraryModifier() {
        assertOriginalChain(combined = false, withLayer = false)
    }

    @Test fun twoNodeFactoryReturnsBothExactEnabledLibraryElements() {
        assertOriginalChain(combined = false, withLayer = true)
    }

    @Test fun combinedBackdropSingleNodeIsNotWrapped() {
        assertOriginalChain(combined = true, withLayer = false)
    }

    @Test fun combinedBackdropTwoNodeChainIsNotWrapped() {
        assertOriginalChain(combined = true, withLayer = true)
    }

    private fun assertOriginalChain(combined: Boolean, withLayer: Boolean) {
        val library = realBackdrop(backdrop(combined), layerBlock = if (withLayer) {
            { scaleX = 1.1f; scaleY = 1.1f }
        } else null)
        val calls = mutableListOf<Boolean>()
        val result = Modifier.safeBackdropEffect("real chain", fallback = { fail("no fallback") }) {
            calls += it
            library
        }
        assertEquals(listOf(true), calls)
        assertSame(library, result)
        val elements = result.elements()
        assertEquals(if (withLayer) 2 else 1, elements.size)
        assertTrue(elements.all { it is ModifierNodeElement<*> })
        assertEquals("DrawBackdropElement", elements.last().javaClass.simpleName)
        library.elements().zip(elements).forEach { (expected, actual) -> assertSame(expected, actual) }
        assertSupported()
    }

    @Test fun freshCapturedLayerCallbackIsConstructedOnlyOnceAndPreserved() {
        val source = backdrop(false)
        var calls = 0
        lateinit var library: Modifier
        val result = Modifier.safeBackdropEffect("capturing layer", fallback = { fail("no fallback") }) { enabled ->
            calls++
            val scale = if (enabled) 1.2f else 0.8f
            realBackdrop(source, enabled, layerBlock = { scaleX = scale; scaleY = scale })
                .also { library = it }
        }
        assertEquals(1, calls)
        assertSame(library, result)
        assertSame(library.elements().first(), result.elements().first())
        assertSupported()
    }

    @Test fun callerPrefixAndRealTwoNodeChainRetainElementIdentityAndOrder() {
        val prefix = object : Modifier.Element {}
        val library = realBackdrop(backdrop(true), layerBlock = { alpha = 0.75f })
        val result = prefix.safeBackdropEffect("prefixed chain", fallback = { fail("no fallback") }) { library }
        val elements = result.elements()
        assertEquals(3, elements.size)
        assertSame(prefix, elements.first())
        library.elements().zip(elements.drop(1)).forEach { (expected, actual) -> assertSame(expected, actual) }
        assertSupported()
    }

    @Test fun graphicsEffectReturnsTheRealTwoNodeChainWithoutADrawBoundary() {
        val library = realBackdrop(backdrop(false), layerBlock = { scaleX = 1.1f })
        assertSame(library, Modifier.safeGraphicsEffect("real chain", fallback = { fail("no fallback") }) { library })
        assertEquals(2, library.elements().size)
        assertSupported()
    }

    @Test fun recordingHelperReturnsARealLibraryChainWithoutStructuralValidation() {
        val library = realBackdrop(backdrop(true), layerBlock = { scaleY = 1.1f })
        assertSame(library, Modifier.safeBackdropRecording { library })
        assertEquals(2, library.elements().size)
        assertSupported()
    }

    @Test fun realSingleNodeAttachesUpdatesDetachesAndRemounts() {
        assertRealLifecycle(combined = false, withLayer = false)
    }

    @Test fun realPillDoubleChainAttachesUpdatesDetachesAndRemounts() {
        assertRealLifecycle(combined = false, withLayer = true)
    }

    @Test fun realCombinedSingleNodeAttachesUpdatesDetachesAndRemounts() {
        assertRealLifecycle(combined = true, withLayer = false)
    }

    @Test fun realCombinedIndicatorDoubleChainUpdatesWithFreshLayerCallbacks() {
        assertRealLifecycle(combined = true, withLayer = true)
    }

    private fun assertRealLifecycle(combined: Boolean, withLayer: Boolean) {
        val source = backdrop(combined)
        val revision = mutableStateOf(0)
        val visible = mutableStateOf(true)
        val effectRevisions = mutableListOf<Int>()
        val factoryEnabled = mutableListOf<Boolean>()
        var layerEvaluations = 0
        var fallbacks = 0
        compose.setContent {
            if (visible.value) {
                val value = revision.value
                Box(Modifier.size(48.dp).safeBackdropEffect("real lifecycle", fallback = {
                    fallbacks++
                }) { enabled ->
                    factoryEnabled += enabled
                    val layer: (GraphicsLayerScope.() -> Unit)? = if (withLayer) {
                        {
                            layerEvaluations++
                            scaleX = 1f + value * 0.05f
                            scaleY = if (combined) 1f - value * 0.02f else scaleX
                        }
                    } else null
                    realBackdrop(source, enabled, layer, effects = { effectRevisions += value })
                })
            }
        }
        compose.waitForIdle()
        assertTrue("Normal direct enabled onAttach invokes real effects", 0 in effectRevisions)
        if (withLayer) assertTrue(layerEvaluations > 0)
        compose.runOnIdle { revision.value = 1 }
        compose.waitForIdle()
        assertTrue(1 in effectRevisions)
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        val callbacksAfterDetach = effectRevisions.size
        compose.runOnIdle { revision.value = 2 }
        compose.waitForIdle()
        assertEquals(callbacksAfterDetach, effectRevisions.size)
        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()
        assertTrue(2 in effectRevisions)
        assertTrue(factoryEnabled.isNotEmpty())
        assertTrue("No synthetic disabled attach", factoryEnabled.all { it })
        assertEquals(0, fallbacks)
        assertSupported()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        // Empty effects isolate real layer/node lifecycle from software RuntimeShader rendering.
    }

    @Test fun realBackdropFactoryFailurePropagatesAndALaterChainStillWorks() {
        assertFactoryFailure("backdrop")
    }

    @Test fun realGraphicsFactoryFailurePropagatesAndALaterChainStillWorks() {
        assertFactoryFailure("graphics")
    }

    @Test fun realRecordingFactoryFailurePropagatesAndALaterChainStillWorks() {
        assertFactoryFailure("recording")
    }

    private fun assertFactoryFailure(kind: String) {
        val failure = IllegalArgumentException("real library factory")
        var calls = 0
        val factory = {
            calls++
            val library = realBackdrop(backdrop(true), layerBlock = { scaleX = 1.2f })
            assertEquals(2, library.elements().size)
            throw failure
        }
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) {
            when (kind) {
                "backdrop" -> Modifier.safeBackdropEffect("real construction", fallback = { fail("no fallback") }) {
                    assertTrue(it)
                    factory()
                }
                "graphics" -> Modifier.safeGraphicsEffect("real construction", fallback = { fail("no fallback") }, factory = factory)
                else -> Modifier.safeBackdropRecording(factory = factory)
            }
        })
        assertEquals(1, calls)
        assertSupported()
        val later = realBackdrop(backdrop(false))
        assertSame(later, Modifier.safeBackdropEffect("later", fallback = { fail("no fallback") }) { later })
    }

    @Test fun realLayerBackdropRecordingPreservesElementsAndNormalLifecycle() {
        val visible = mutableStateOf(true)
        val revision = mutableStateOf(0)
        val observations = mutableListOf<Int>()
        compose.setContent {
            val source = rememberLayerBackdrop { drawContent() }
            if (visible.value) {
                val library = androidx.compose.runtime.remember(source) { Modifier.layerBackdrop(source) }
                val modifier = Modifier.safeBackdropRecording(PageBlurGraphicsSafety) { library }
                assertSame(library, modifier)
                assertEquals("LayerBackdropElement", modifier.elements().single().javaClass.simpleName)
                observations += revision.value
                Box(Modifier.size((24 + revision.value).dp).then(modifier))
            }
        }
        compose.waitForIdle()
        assertTrue(0 in observations)
        compose.runOnIdle { revision.value = 1 }
        compose.waitForIdle()
        assertTrue(1 in observations)
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        val afterDetach = observations.size
        compose.runOnIdle { revision.value = 2 }
        compose.waitForIdle()
        assertEquals(afterDetach, observations.size)
        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()
        assertTrue(2 in observations)
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        assertSupported()
    }

    private fun assertSupported() {
        assertTrue(LiquidGlassCompatibility.isSystemSupported())
        assertTrue(GraphicsSafety.isSupported())
        assertTrue(BackgroundGraphicsSafety.isSupported())
        assertTrue(PageBlurGraphicsSafety.isSupported())
    }
}
