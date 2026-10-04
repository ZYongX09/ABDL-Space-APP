package org.joinmastodon.android.ui.compose.navigation

import android.graphics.Bitmap
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.joinmastodon.android.CompatibilityTestApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 35], application = CompatibilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewBitmapBackdropRecoveryTest {
    @Test fun pausedCaptureClearsStaleImageAndNextDeliveryResumesDrawing() {
        val backdrop = ViewBitmapBackdrop()
        backdrop.updateOriginInWindow(Offset.Zero)
        val first = solid(android.graphics.Color.RED)
        val resumed = solid(android.graphics.Color.GREEN)
        try {
            backdrop.update(first)
            assertEquals(android.graphics.Color.RED, render(backdrop))
            backdrop.update(null)
            Snapshot.sendApplyNotifications()
            assertEquals("Paused capture must not display the old image", android.graphics.Color.TRANSPARENT, render(backdrop))
            backdrop.update(resumed)
            assertEquals("Same consumer resumes with the new capture", android.graphics.Color.GREEN, render(backdrop))
        } finally {
            first.recycle()
            resumed.recycle()
        }
    }

    private fun solid(color: Int): Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun render(backdrop: ViewBitmapBackdrop): Int {
        val output = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        // positionInWindow uses localToWindow; rendering and image state remain real.
        val coordinates = Proxy.newProxyInstance(LayoutCoordinates::class.java.classLoader, arrayOf(LayoutCoordinates::class.java)) { _, method, args ->
            when (method.name.substringBefore('-')) {
                "localToWindow", "localToRoot" -> args!![0]
                "getSize" -> IntSize(8, 8).let { (it.width.toLong() shl 32) or (it.height.toLong() and 0xffffffffL) }
                "isAttached" -> true
                "getParentLayoutCoordinates", "getParentCoordinates" -> null
                else -> throw AssertionError("Unexpected coordinate operation ${method.name}")
            }
        } as LayoutCoordinates
        try {
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(android.graphics.Canvas(output)), Size(8f, 8f)) {
                with(backdrop) { drawBackdrop(Density(1f), coordinates, null, 1) }
            }
            return output.getPixel(4, 4)
        } finally {
            output.recycle()
        }
    }
}
