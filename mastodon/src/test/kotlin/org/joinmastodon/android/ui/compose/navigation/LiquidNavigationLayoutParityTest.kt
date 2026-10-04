package org.joinmastodon.android.ui.compose.navigation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import org.joinmastodon.android.CompatibilityTestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

/**
 * Real Compose measurement/transforms and real miuix nodes, paired with production wiring
 * contracts. Empty effects intentionally avoid RuntimeShader rendering. The clip test uses
 * a plain draw seam; neither it nor these geometry tests validate GPU lens/highlight output.
 */
@RunWith(RobolectricTestRunner::class)
// Keep the density-2 200dp capture entirely inside the host window (400px, not 320px).
@Config(sdk = [33, 35], application = CompatibilityTestApplication::class, qualifiers = "w600dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class LiquidNavigationLayoutParityTest {
    @get:Rule val compose = createComposeRule()

    /** Explicit software drawing avoids host frame/PixelCopy callbacks and exercises real nodes. */
    private fun drawSoftware(view: View): Bitmap {
        assertTrue("Compose host must be measured before drawing", view.width > 0 && view.height > 0)
        return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
            view.draw(Canvas(it))
        }
    }

    @Test fun pillLayerTransformsBothVisibleIconAndLabel() {
        val progress = mutableFloatStateOf(0f)
        lateinit var icon: LayoutCoordinates
        lateinit var label: LayoutCoordinates
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Row(
                    Modifier.width(200.dp)
                        .drawBackdrop(
                            backdrop = ViewBitmapBackdrop(),
                            shape = { CircleShape },
                            effects = {},
                            layerBlock = {
                                val s = lerp(1f, 1f + 16.dp.toPx() / size.width.coerceAtLeast(1f), progress.floatValue)
                                scaleX = s
                                scaleY = s
                            },
                        )
                        .height(64.dp).padding(4.dp),
                ) {
                    Box(Modifier.size(20.dp).onGloballyPositioned { icon = it }.background(Color.Red))
                    Box(Modifier.size(30.dp, 12.dp).onGloballyPositioned { label = it }.background(Color.Blue))
                }
            }
        }
        compose.waitForIdle()
        for (p in listOf(0f, 0.5f, 1f, 0f)) {
            compose.runOnIdle { progress.floatValue = p }
            compose.waitForIdle()
            compose.runOnIdle {
                val expectedScale = 1f + 16f / 200f * p
                for (coords in listOf(icon, label)) {
                    val origin = coords.localToRoot(Offset.Zero)
                    val right = coords.localToRoot(Offset(coords.size.width.toFloat(), 0f))
                    val bottom = coords.localToRoot(Offset(0f, coords.size.height.toFloat()))
                    assertEquals(coords.size.width * expectedScale, right.x - origin.x, 0.05f)
                    assertEquals(coords.size.height * expectedScale, bottom.y - origin.y, 0.05f)
                }
            }
        }
    }

    @Test fun captureSurfaceMeasuresFullWidthBeforeHorizontalPaddingLtr() = captureGeometry(1f, LayoutDirection.Ltr)

    @Test fun captureSurfaceMeasuresFullWidthBeforeHorizontalPaddingRtlAtDoubleDensity() = captureGeometry(2f, LayoutDirection.Rtl)

    private fun captureGeometry(density: Float, direction: LayoutDirection) {
        var drawnSize = Size.Zero
        lateinit var hostView: View
        lateinit var row: LayoutCoordinates
        val tabs = arrayOfNulls<LayoutCoordinates>(4)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density), LocalLayoutDirection provides direction) {
                hostView = LocalView.current
                val recordedTabs = rememberLayerBackdrop()
                Row(
                    Modifier.width(200.dp)
                        .onGloballyPositioned { row = it }
                        .layerBackdrop(recordedTabs)
                        .drawBackdrop(
                            backdrop = ViewBitmapBackdrop(),
                            shape = { CircleShape },
                            effects = {},
                            onDrawSurface = { drawnSize = size },
                        )
                        .height(56.dp).padding(horizontal = 4.dp),
                ) {
                    repeat(4) { index ->
                        Box(Modifier.weight(1f).fillMaxHeight().onGloballyPositioned {
                            tabs[index] = it
                        })
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            // waitForIdle only guarantees layout here, not a host draw callback.
            drawSoftware(hostView).recycle()
            assertEquals(200f * density, row.size.width.toFloat(), 0.01f)
            assertEquals("Backdrop must not lose 8dp to content padding", row.size.width.toFloat(), drawnSize.width, 0.01f)
            assertEquals(56f * density, drawnSize.height, 0.01f)
            assertEquals((200f - 8f) * density, tabs.sumOf { requireNotNull(it).size.width }.toFloat(), 0.01f)
            assertTrue(tabs.all { requireNotNull(it).size.height == row.size.height })
        }
    }

    @Test fun contentClipLeavesOuterSurfaceDrawUnclipped() {
        lateinit var hostView: View
        lateinit var glass: LayoutCoordinates
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                hostView = LocalView.current
                Box(
                    Modifier.size(80.dp).onGloballyPositioned { glass = it }
                        // Draw seam stands in for the outer backdrop/highlight, not its GPU shader.
                        .drawWithContent { drawRect(Color.Green); drawContent() }
                        .clip(CircleShape),
                ) {
                    Box(Modifier.size(80.dp).background(Color.Red))
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val bitmap = drawSoftware(hostView)
            try {
                // localToRoot is in the LocalView's coordinate space, not window/status-bar space.
                val corner = glass.localToRoot(Offset(2f, 2f))
                val center = glass.localToRoot(Offset(40f, 40f))
                assertEquals(Color.Green.toArgb(), bitmap.getPixel(corner.x.toInt(), corner.y.toInt()))
                assertEquals(Color.Red.toArgb(), bitmap.getPixel(center.x.toInt(), center.y.toInt()))
            } finally {
                bitmap.recycle()
            }
        }
    }
}
