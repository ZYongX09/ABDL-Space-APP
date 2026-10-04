package org.joinmastodon.android.ui.compose.utils

import android.app.ActivityManager
import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.MastodonApp
import org.joinmastodon.android.ui.compose.navigation.ViewBitmapBackdrop
import org.joinmastodon.android.ui.compose.navigation.liquid.CombinedBackdrop
import org.joinmastodon.android.ui.utils.LiquidGlassCompatibility
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.drawBackdrop

/** Real miuix 0.9.3 nodes, not a substitute single-node Probe factory. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 35], application = CompatibilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class RealBackdropChainCompatibilityTest {
    @get:Rule val compose = createComposeRule()

    private fun reset() {
        LiquidGlassCompatibility::class.java.getDeclaredMethod("resetForTests").apply {
            isAccessible = true
        }.invoke(null)
    }

    @Before fun setUp() {
        reset()
        MastodonApp.context = RuntimeEnvironment.getApplication()
        shadowOf(MastodonApp.context.getSystemService(ActivityManager::class.java)).setIsLowRamDevice(false)
        assertTrue(GraphicsSafety.isSupported())
    }

    @After fun tearDown() { reset() }

    private fun Modifier.elements(): List<Modifier.Element> =
        foldIn(emptyList()) { result, element -> result + element }

    private fun realBackdrop(
        backdrop: Backdrop,
        enabled: Boolean,
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

    @Test fun layeredFactoryKeepsTheEnabledLibraryPrefixInsteadOfFusingConstruction() {
        val backdrop = ViewBitmapBackdrop()
        val layer: GraphicsLayerScope.() -> Unit = { scaleX = 1.1f; scaleY = 1.1f }
        val factories = mutableListOf<Pair<Boolean, List<Modifier.Element>>>()
        val guarded = Modifier.safeBackdropEffect("navigation pill shaders", fallback = {}) { enabled ->
            realBackdrop(backdrop, enabled, layer).also { factories += enabled to it.elements() }
        }

        // On baseline 31fe1ff3 this fails: the second library element produces
        // "Expected one backdrop node", silently disabling NAVIGATION during construction.
        assertTrue("A real two-node drawBackdrop chain is not a graphics failure", GraphicsSafety.isSupported())
        assertEquals(listOf(false, true), factories.map { it.first })
        val off = factories[0].second
        val on = factories[1].second
        assertEquals(2, on.size)
        assertTrue(on.all { it is ModifierNodeElement<*> })
        assertEquals(off.map { it.javaClass }, on.map { it.javaClass })
        assertEquals("DrawBackdropElement", on.last().javaClass.simpleName)
        val wrapped = guarded.elements()
        assertEquals("Untouched layer + draw-safe terminal node", 2, wrapped.size)
        assertSame("Keep the enabled factory's graphicsLayer object", on.first(), wrapped[0])
        assertFalse("Only the terminal effect should be wrapped", wrapped.last().javaClass == on.last().javaClass)
    }

    @Test fun freshCapturedLayerCallbacksAreCompatibleAndTheEnabledOneIsPreserved() {
        val backdrop = ViewBitmapBackdrop()
        val factories = mutableListOf<List<Modifier.Element>>()
        // Deliberately allocate a capturing lambda *inside* each factory invocation,
        // just like pill/indicator in IosLiquidGlassNavigationBar. Do not share it.
        val guarded = Modifier.safeBackdropEffect("navigation indicator shaders", fallback = {}) { enabled ->
            val scale = if (enabled) 1.2f else 0.8f
            realBackdrop(backdrop, enabled, layerBlock = { scaleX = scale; scaleY = scale })
                .also { factories += it.elements() }
        }
        assertTrue("Callback identity is not a modifier-chain structural mismatch", GraphicsSafety.isSupported())
        assertEquals(2, factories.size)
        assertNotSame(factories[0].first(), factories[1].first())
        assertNotEquals("The captured values make the two prefix callbacks unequal", factories[0].first(), factories[1].first())
        assertEquals(factories[0].map { it.javaClass }, factories[1].map { it.javaClass })
        assertSame(factories[1].first(), guarded.elements()[0])
    }

    @Test fun realPillLayerAttachesUpdatesRemovesAndRemountsWithoutFalseFailure() {
        assertRealLifecycle(indicator = false, withLayer = true)
    }

    @Test fun realCombinedBackdropIndicatorAttachesAndUpdatesWithFreshLayerCallbacks() {
        assertRealLifecycle(indicator = true, withLayer = true)
    }

    @Test fun realBackdropWithoutLayerStillUsesTheSingleTerminalNodeContract() {
        val backdrop = ViewBitmapBackdrop()
        val off = realBackdrop(backdrop, false).elements()
        val on = realBackdrop(backdrop, true).elements()
        assertEquals(1, on.size)
        assertEquals("DrawBackdropElement", on.single().javaClass.simpleName)
        assertEquals(off.single().javaClass, on.single().javaClass)
        val guarded = Modifier.safeBackdropEffect("navigation capture backdrop", fallback = {}) { enabled ->
            realBackdrop(backdrop, enabled)
        }
        assertTrue(GraphicsSafety.isSupported())
        assertEquals(1, guarded.elements().size)
        assertRealLifecycle(indicator = false, withLayer = false)
    }

    private fun assertRealLifecycle(indicator: Boolean, withLayer: Boolean) {
        val first = ViewBitmapBackdrop()
        val backdrop: Backdrop = if (indicator) CombinedBackdrop(first, ViewBitmapBackdrop()) else first
        val revision = mutableStateOf(0)
        val visible = mutableStateOf(true)
        val enabledRevisions = mutableListOf<Int>()
        var layerEvaluations = 0
        var fallbacks = 0
        compose.setContent {
            if (visible.value) {
                val value = revision.value
                Box(Modifier.size(48.dp).safeBackdropEffect(
                    if (indicator) "navigation indicator shaders" else "navigation pill shaders",
                    fallback = { fallbacks++; drawRect(Color.Red) },
                ) { enabled ->
                    // Fresh callback on every off/on call and each recomposition.
                    val layer: (GraphicsLayerScope.() -> Unit)? = if (withLayer) {
                        {
                            layerEvaluations++
                            scaleX = 1f + value * 0.05f
                            scaleY = if (indicator) 1f - value * 0.02f else scaleX
                        }
                    } else null
                    realBackdrop(backdrop, enabled, layer, effects = { enabledRevisions += value })
                })
            }
        }
        compose.waitForIdle()
        assertTrue("Construction/real Compose attach must not disable NAVIGATION", GraphicsSafety.isSupported())
        assertTrue("Real enabled terminal update must invoke the library effects callback", 0 in enabledRevisions)
        if (withLayer) assertTrue("The preserved graphicsLayer must participate in layout", layerEvaluations > 0)
        compose.runOnIdle { revision.value = 1 }
        compose.waitForIdle()
        assertTrue("Real terminal update must reach the new callback", 1 in enabledRevisions)
        assertTrue("Recomposition/update is not a shader failure", GraphicsSafety.isSupported())
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle() // Real library onDetach releases its graphics layers.
        val callbacksAfterDetach = enabledRevisions.size
        compose.runOnIdle { revision.value = 2 }
        compose.waitForIdle()
        assertEquals("Removed library node must no longer observe updates", callbacksAfterDetach, enabledRevisions.size)
        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()
        assertTrue(2 in enabledRevisions)
        assertTrue("Detach/remount must leave the session usable", GraphicsSafety.isSupported())
        assertEquals(0, fallbacks)
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        // No View.draw/PixelCopy here: these assertions isolate construction and real
        // attachment/update from software offscreen rendering of RuntimeShaders.
        // Empty effects intentionally avoid AGSL, but the node/layer lifecycle is real.
    }

    @Test fun realSurfaceDrawFailureDisablesBeforeFallbackRestoresCanvasAndAllowsRemoval() {
        val backdrop = ViewBitmapBackdrop()
        val visible = mutableStateOf(true)
        val failSurface = mutableStateOf(false)
        var attempts = 0
        var fallbacks = 0
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            if (visible.value) Box(Modifier.size(48.dp).testTag("effect").safeBackdropEffect(
                "real backdrop surface draw",
                fallback = {
                    assertFalse("Disable before any fallback drawing", GraphicsSafety.isSupported())
                    fallbacks++
                    drawRect(Color.Red.copy(alpha = 0.5f))
                },
            ) { enabled ->
                Modifier.drawBackdrop(
                    enabled = enabled,
                    backdrop = backdrop,
                    shape = { CircleShape },
                    effects = {}, // No software-canvas RuntimeShader ambiguity in this test.
                    layerBlock = { scaleX = 1f; scaleY = 1f },
                    onDrawSurface = {
                        if (failSurface.value) {
                            attempts++
                            drawContext.canvas.save()
                            drawContext.canvas.translate(1000f, 1000f)
                            throw IllegalArgumentException("real library onDrawSurface")
                        }
                        drawRect(Color.Blue)
                    },
                )
            })
        }
        compose.waitForIdle()
        assertTrue("Construct/attach separately from the explicit software draw", GraphicsSafety.isSupported())
        val bounds = compose.onNodeWithTag("effect").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = android.graphics.Canvas(bitmap)
                assertFalse(canvas.isHardwareAccelerated)
                val saves = canvas.saveCount
                failSurface.value = true
                // Invalidate any display list recorded by Compose's initial frame before
                // the explicit software draw; a plain Boolean can leave it cached.
                Snapshot.sendApplyNotifications()
                view.draw(canvas)
                assertFalse(GraphicsSafety.isSupported())
                assertEquals(1, attempts)
                assertTrue(fallbacks > 0)
                assertEquals("Restore leaked effect saves", saves, canvas.saveCount)
                val pixel = bitmap.getPixel(bounds.center.x.toInt(), bounds.center.y.toInt())
                assertEquals("A single half-alpha fallback must not be blended twice", 128, android.graphics.Color.alpha(pixel))
                assertEquals(255, android.graphics.Color.red(pixel))
                assertEquals(0, android.graphics.Color.green(pixel))
                assertEquals(0, android.graphics.Color.blue(pixel))
                view.draw(canvas)
                assertEquals("Disabled session must not re-enter the real draw callback", 1, attempts)
            } finally {
                bitmap.recycle()
            }
            visible.value = false
        }
        compose.waitForIdle() // Real terminal cleanup must be legal even after the draw fuse trips.
        assertFalse(GraphicsSafety.isSupported())
        assertTrue(BackgroundGraphicsSafety.isSupported())
        assertTrue(PageBlurGraphicsSafety.isSupported())
    }
}
