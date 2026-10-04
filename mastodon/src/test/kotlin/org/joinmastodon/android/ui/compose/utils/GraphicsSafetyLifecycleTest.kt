package org.joinmastodon.android.ui.compose.utils

import android.app.ActivityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import android.graphics.Bitmap
import java.nio.file.Files
import java.nio.file.Paths
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.test.junit4.createComposeRule
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.MastodonApp
import org.joinmastodon.android.ui.compose.component.effect.BgEffectBackground
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.joinmastodon.android.ui.utils.LiquidGlassCompatibility
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = CompatibilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class GraphicsSafetyLifecycleTest {
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
    }

    @After fun tearDown() { reset() }

    private lateinit var renderView: View

    private fun assertRenderedColor(expected: Color) {
        val bounds = compose.onNodeWithTag("effect").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            // Software draw is synchronous; PixelCopy's window callbacks do not run in Robolectric.
            val bitmap = Bitmap.createBitmap(renderView.width, renderView.height, Bitmap.Config.ARGB_8888)
            renderView.draw(android.graphics.Canvas(bitmap))
            assertEquals(expected.toArgb(), bitmap.getPixel(bounds.center.x.toInt(), bounds.center.y.toInt()))
        }
    }

    @Test fun disabledAttachThenEnableFailureReleasesNodeAndDrawsFallback() {
        var detached = false
        var fallbackDrawn = false
        val visible = mutableStateOf(true)
        val effect = ProbeElement(failUpdate = true, detached = { detached = true })
        compose.setContent {
            renderView = LocalView.current
            if (visible.value) Box(Modifier.size(24.dp).testTag("effect").safeBackdropEffect(
                "probe attach",
                fallback = { fallbackDrawn = true; drawRect(Color.Red) },
            ) { enabled -> effect.copy(enabled = enabled) })
        }
        compose.waitForIdle()
        assertFalse(GraphicsSafety.isSupported())
        assertTrue(detached)
        assertRenderedColor(Color.Red)
        assertTrue(fallbackDrawn)
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()
    }

    @Test fun updateFailureReleasesNodeAndUsesExistingBoundaryFallback() {
        val failUpdate = mutableStateOf(false)
        var detached = false
        var fallbackDrawn = false
        compose.setContent {
            renderView = LocalView.current
            val effect = ProbeElement(failUpdate = failUpdate.value, detached = { detached = true })
            Box(Modifier.size(24.dp).testTag("effect").safeBackdropEffect(
                "probe update",
                fallback = { fallbackDrawn = true; drawRect(Color.Red) },
            ) { enabled -> effect.copy(enabled = enabled) })
        }
        compose.waitForIdle()
        assertTrue(GraphicsSafety.isSupported())
        assertFalse(fallbackDrawn)
        compose.runOnIdle { failUpdate.value = true }
        compose.waitForIdle()
        assertFalse(GraphicsSafety.isSupported())
        assertTrue(detached)
        assertRenderedColor(Color.Red)
        assertTrue(fallbackDrawn)
    }

    @Test fun normalRemovalRunsDetachOnce() {
        val visible = mutableStateOf(true)
        var detachCount = 0
        compose.setContent {
            renderView = LocalView.current
            if (visible.value) Box(Modifier.size(24.dp).testTag("effect").safeBackdropEffect(
                "normal", fallback = { fail("no fallback expected") },
            ) { enabled -> ProbeElement(enabled = enabled, detached = { detachCount++ }) })
        }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        assertEquals(1, detachCount)
        assertTrue(GraphicsSafety.isSupported())
    }

    @Test fun recordingFailureDisablesEffectsAndDoesNotDuplicateContent() {
        var attempts = 0
        var contentDraws = 0
        compose.setContent {
            renderView = LocalView.current
            Box(Modifier.size(24.dp).testTag("effect")
                .safeBackdropRecording { RecordingProbeElement { attempts++; throw IllegalArgumentException("recording") } }
                .drawWithContent { contentDraws++; drawRect(Color.Blue) })
        }
        compose.waitForIdle()
        assertRenderedColor(Color.Blue)
        assertFalse(GraphicsSafety.isSupported())
        assertEquals(1, attempts)
        assertTrue(contentDraws > 0)
    }

    @Test fun disabledRecordingBypassesRecorderButDrawsContent() {
        LiquidGlassCompatibility.reportFailure("disabled", IllegalStateException())
        var contentDraws = 0
        compose.setContent {
            renderView = LocalView.current
            Box(Modifier.size(24.dp).testTag("effect")
                .safeBackdropRecording { RecordingProbeElement { fail("must not record") } }
                .drawWithContent { contentDraws++; drawRect(Color.Blue) })
        }
        compose.waitForIdle()
        assertRenderedColor(Color.Blue)
        assertTrue(contentDraws > 0)
    }

    @Test fun contentFailureIsForwardedWithoutDisablingSession() {
        // Exercise the modifier's public node draw contract directly, avoiding Compose's
        // asynchronous uncaught-exception handling in this intentionally throwing case.
        val modifier = Modifier.safeBackdropRecording { RecordingProbeElement {} }
        val element = modifier.foldIn(null as ModifierNodeElement<*>?) { _, value -> value as ModifierNodeElement<*> }
        val node = checkNotNull(element).create()
        node.onAttach()
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(android.graphics.Canvas(bitmap))
        val failure = IllegalArgumentException("application content")
        try {
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(4f, 4f)) {
                val underlying = this
                val scope = object : ContentDrawScope, androidx.compose.ui.graphics.drawscope.DrawScope by underlying {
                    override fun drawContent() { throw failure }
                }
                with(node as DrawModifierNode) { scope.draw() }
            }
            fail("content failure was swallowed")
        } catch (caught: IllegalArgumentException) {
            assertSame(failure, caught)
        }
        assertTrue(GraphicsSafety.isSupported())
        bitmap.recycle()
    }

    @Test fun backgroundFailureKeepsPageBlurRecordingInPlainSurfaceFallback() {
        BackgroundGraphicsSafety.reportFailure("background shader", IllegalArgumentException("test"))
        var records = 0
        var contentDraws = 0
        compose.setContent {
            renderView = LocalView.current
            BgEffectBackground(
                dynamicBackground = false,
                modifier = Modifier.size(24.dp).testTag("effect"),
                bgModifier = Modifier.safeBackdropRecording(PageBlurGraphicsSafety) {
                    RecordingProbeElement { records++ }
                },
            ) {
                Box(Modifier.size(24.dp).drawWithContent { contentDraws++; drawRect(Color.Blue) })
            }
        }
        compose.waitForIdle()
        assertRenderedColor(Color.Blue)
        assertTrue("Plain background must still be recorded for page blur", records > 0)
        assertTrue(contentDraws > 0)
        assertFalse(BackgroundGraphicsSafety.isSupported())
        assertTrue(PageBlurGraphicsSafety.isSupported())
        assertTrue(GraphicsSafety.isSupported())
    }

    @Test fun pageBlurRecordingFailureDoesNotDisableBackgroundOrNavigation() {
        var attempts = 0
        var contentDraws = 0
        compose.setContent {
            renderView = LocalView.current
            Box(Modifier.size(24.dp).testTag("effect")
                .safeBackdropRecording(PageBlurGraphicsSafety) {
                    RecordingProbeElement { attempts++; throw IllegalArgumentException("page recording") }
                }
                .drawWithContent { contentDraws++; drawRect(Color.Blue) })
        }
        compose.waitForIdle()
        assertRenderedColor(Color.Blue)
        assertEquals(1, attempts)
        assertTrue(contentDraws > 0)
        assertFalse(PageBlurGraphicsSafety.isSupported())
        assertTrue(BackgroundGraphicsSafety.isSupported())
        assertTrue(GraphicsSafety.isSupported())
    }

    @Test fun pageBlurEnableFailureReleasesNodeWithoutCrossDomainFailure() {
        var detached = false
        var contentDraws = 0
        compose.setContent {
            renderView = LocalView.current
            Box(Modifier.size(24.dp).testTag("effect")) {
                Box(Modifier.matchParentSize().safeBackdropEffect(
                    "page texture probe",
                    fallback = { drawRect(Color.Red) },
                    safety = PageBlurGraphicsSafety,
                ) { enabled -> ProbeElement(enabled = enabled, failUpdate = true, detached = { detached = true }) })
                Box(Modifier.size(24.dp).drawWithContent { contentDraws++; drawRect(Color.Blue) })
            }
        }
        compose.waitForIdle()
        assertRenderedColor(Color.Blue)
        assertTrue(detached)
        assertTrue(contentDraws > 0)
        assertFalse(PageBlurGraphicsSafety.isSupported())
        assertTrue(BackgroundGraphicsSafety.isSupported())
        assertTrue(GraphicsSafety.isSupported())
    }

    @Test fun pageBlurRecordingIsIndependentOfNavigationFailure() {
        GraphicsSafety.reportFailure("navigation shader", IllegalArgumentException("test"))
        var records = 0
        compose.setContent {
            renderView = LocalView.current
            Box(Modifier.size(24.dp).testTag("effect")
                .safeBackdropRecording(PageBlurGraphicsSafety) { RecordingProbeElement { records++ } }
                .background(Color.Blue))
        }
        compose.waitForIdle()
        assertRenderedColor(Color.Blue)
        assertTrue(records > 0)
        assertTrue(PageBlurGraphicsSafety.isSupported())
        assertTrue(BackgroundGraphicsSafety.isSupported())
        assertFalse(GraphicsSafety.isSupported())
    }

    @Test fun softwareScreenshotDoesNotDisableBackgroundShader() {
        compose.setContent {
            renderView = LocalView.current
            BgEffectBackground(dynamicBackground = false, modifier = Modifier.size(24.dp).testTag("effect")) {
                Box(Modifier.size(24.dp).background(Color.Blue))
            }
        }
        compose.waitForIdle()
        assertRenderedColor(Color.Blue) // View.draw uses a software Canvas.
        assertTrue(BackgroundGraphicsSafety.isSupported())
        assertTrue(PageBlurGraphicsSafety.isSupported())
        assertTrue(GraphicsSafety.isSupported())
    }

    @Test fun aboutPageUsesGuardedRecordingAndEmptyTextureChildren() {
        val about = source("src/main/kotlin/org/joinmastodon/android/ui/compose/AboutPage.kt")
        val page = source("src/main/kotlin/org/joinmastodon/android/ui/compose/utils/PageUtils.kt")
        val background = source("src/main/kotlin/org/joinmastodon/android/ui/compose/component/effect/BgEffectBackground.kt")
        assertEquals(2, Regex("safeBackdropRecording\\(PageBlurGraphicsSafety\\)").findAll(about).count())
        assertFalse(about.contains("Modifier.textureBlur("))
        assertTrue(about.contains("contentBlendMode = ComposeBlendMode.DstIn"))
        assertTrue(about.contains("fallbackColor = Color.Transparent"))
        assertEquals(2, Regex("\\n\\s*AboutCard\\(").findAll(about).count())
        val texture = page.substring(page.indexOf("internal fun PageTextureBlur("), page.indexOf("fun BlurredBar("))
        assertTrue(texture.contains("Spacer(Modifier.matchParentSize().safeBackdropEffect("))
        assertTrue(texture.contains("safety = PageBlurGraphicsSafety"))
        assertTrue(texture.contains("enabled = enabled"))
        assertTrue(texture.contains("colors = colors"))
        assertTrue(texture.contains("blurRadius = blurRadius"))
        assertTrue(texture.contains("noiseCoefficient = noiseCoefficient"))
        assertTrue(texture.contains("maskPaint"))
        assertTrue(texture.contains("CompositingStrategy.Offscreen"))
        val effectFactory = texture.substring(texture.indexOf(") { enabled ->"), texture.indexOf("Box(Modifier.drawWithContent"))
        assertFalse(effectFactory.contains("MiuixTheme"))
        assertFalse(effectFactory.contains("content()"))
        val plainFallback = background.substring(background.indexOf("if (painter == null"), background.indexOf("    Box(\n"))
        assertTrue(plainFallback.contains("Spacer(Modifier.fillMaxSize().then(bgModifier).background(surface))"))
        assertTrue(plainFallback.contains("content()"))
    }

    private fun source(path: String): String = listOf(Paths.get(path), Paths.get("mastodon/$path"))
        .first { Files.exists(it) }.let(Files::readString)

    @Test fun noBlurBarLeavesCallerBackgroundUntouched() {
        // Source contract for the false branch: an opaque background here breaks About's
        // intentionally transparent expanded header, even though it looks safe in isolation.
        val candidates = listOf(
            Paths.get("src/main/kotlin/org/joinmastodon/android/ui/compose/utils/PageUtils.kt"),
            Paths.get("mastodon/src/main/kotlin/org/joinmastodon/android/ui/compose/utils/PageUtils.kt"),
        )
        val source = Files.readString(candidates.first { Files.exists(it) })
        val bar = source.substring(source.indexOf("fun BlurredBar("))
        assertTrue(bar.contains("} else Modifier,"))
        assertFalse(bar.contains("else Modifier.background"))
    }

    private data class RecordingProbeElement(val record: () -> Unit) : ModifierNodeElement<RecordingProbeNode>() {
        override fun create() = RecordingProbeNode(record)
        override fun update(node: RecordingProbeNode) { node.record = record }
        override fun InspectorInfo.inspectableProperties() { name = "recordingProbe" }
    }

    private class RecordingProbeNode(var record: () -> Unit) : Modifier.Node(), DrawModifierNode {
        override fun ContentDrawScope.draw() { drawContent(); record() }
    }

    private data class ProbeElement(
        val enabled: Boolean = false,
        val failUpdate: Boolean = false,
        val detached: () -> Unit,
    ) : ModifierNodeElement<ProbeNode>() {
        override fun create() = ProbeNode(enabled, detached)
        override fun update(node: ProbeNode) {
            node.enabled = enabled
            if (enabled && failUpdate) throw IllegalArgumentException("effect update")
        }
        override fun InspectorInfo.inspectableProperties() { name = "graphicsSafetyProbe" }
    }

    private class ProbeNode(
        var enabled: Boolean,
        val detached: () -> Unit,
    ) : Modifier.Node(), DrawModifierNode {
        override fun onAttach() {
            check(!enabled) { "Must attach disabled before enabling graphics" }
        }
        override fun onDetach() = detached()
        override fun ContentDrawScope.draw() {
            drawContent()
        }
    }
}
