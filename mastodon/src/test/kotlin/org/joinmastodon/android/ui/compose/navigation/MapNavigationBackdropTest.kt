package org.joinmastodon.android.ui.compose.navigation

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.joinmastodon.android.CompatibilityTestApplication
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.blur.Backdrop
import java.lang.reflect.Proxy
import java.util.function.IntConsumer
import java.util.function.IntPredicate

/** Real Canvas pixel tests; only the provider texture readback is substituted, not the backdrop. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 35], application = CompatibilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapNavigationBackdropTest {
    private val activityController = Robolectric.buildActivity(Activity::class.java)
    private lateinit var activity: Activity
    private lateinit var host: FrameLayout
    private lateinit var map: FrameLayout
    private val bitmaps = mutableListOf<Bitmap>()

    @Before fun setUp() {
        activity = activityController.setup().get()
        host = FrameLayout(activity)
        map = FrameLayout(activity)
        activity.setContentView(host)
        host.addView(map, FrameLayout.LayoutParams(80, 160).apply { leftMargin = 11; topMargin = 23 })
        layout()
        assertTrue(map.isAttachedToWindow)
    }

    @After fun tearDown() {
        activityController.pause().stop().destroy()
        bitmaps.forEach { if (!it.isRecycled) it.recycle() }
    }

    @Test fun lateReadySnapshotReplacesEmptyLoadingSourceWithoutNavigationRecreation() {
        val controller = navigation()
        try {
            controller.setMapBackdropActive(true, .2f)
            assertEquals(Color.TRANSPARENT, render(controller.currentBackdrop, map, 20, 120))
            controller.setMapBackdrop(map, solid(Color.GREEN))
            assertEquals(Color.GREEN, render(controller.currentBackdrop, map, 20, 120))
            controller.setBackdropBitmap(solid(Color.RED))
            controller.setBackdropBitmap(null) // Capture budget pauses must not clear the map image.
            assertEquals(Color.GREEN, render(controller.currentBackdrop, map, 20, 120))
        } finally {
            controller.dispose()
        }
    }

    @Test fun leavingMapClearsImageAndNormalStripResumesOnlyAfterNewDelivery() {
        val controller = navigation()
        try {
            controller.setBackdropBitmap(solid(Color.RED))
            controller.setMapBackdropActive(true, .2f)
            controller.setMapBackdrop(map, solid(Color.GREEN))
            val oldMapBackdrop = controller.currentBackdrop
            controller.setMapBackdropActive(false, -1f)
            assertEquals(Color.TRANSPARENT, render(oldMapBackdrop, map, 4, 4))
            assertEquals(Color.TRANSPARENT, render(controller.currentBackdrop, map, 4, 4))
            controller.setMapBackdrop(map, solid(Color.RED)) // A late SDK result after exit is ignored.
            controller.setBackdropBitmap(solid(Color.BLUE))
            assertEquals(Color.BLUE, renderAt(controller.currentBackdrop, 0, 0))
            controller.setMapBackdropActive(true, .2f)
            assertEquals(Color.TRANSPARENT, render(controller.currentBackdrop, map, 4, 4))
        } finally {
            controller.dispose()
        }
    }

    @Test fun sdkSnapshotUsesMapWindowOriginAndScaledProviderSizeNotNavigationOrigin() {
        val backdrop = MapNavigationBackdrop()
        val snapshot = Bitmap.createBitmap(8, 16, Bitmap.Config.ARGB_8888).also(bitmaps::add)
        for (y in 0 until 16) for (x in 0 until 8) {
            snapshot.setPixel(x, y, if (y < 8) Color.RED else if (x < 4) Color.GREEN else Color.BLUE)
        }
        backdrop.update(map, snapshot)
        assertEquals(Color.RED, render(backdrop, map, 20, 20))
        assertEquals(Color.GREEN, render(backdrop, map, 20, 120))
        assertEquals(Color.BLUE, render(backdrop, map, 60, 120))
        assertEquals(Color.GREEN, render(backdrop, map, 20, 120, factor = 2))
        // Moving the provider after snapshot delivery must not leave the old window origin cached.
        map.translationX = 7f
        map.translationY = 31f
        assertEquals(Color.GREEN, render(backdrop, map, 20, 120))
        backdrop.clear()
        assertEquals(Color.TRANSPARENT, render(backdrop, map, 20, 120))
    }

    @Test fun ordinaryMapHostDrawIsNeverMistakenForTexturePixels() {
        var draws = 0
        val drawOnlyView = object : View(activity) {
            override fun draw(canvas: android.graphics.Canvas) {
                draws++
                canvas.drawColor(Color.RED)
            }
        }
        map.addView(drawOnlyView, FrameLayout.LayoutParams(80, 160))
        layout()
        val backdrop = MapNavigationBackdrop()
        backdrop.update(map, solid(Color.GREEN))
        backdrop.captureLiveFrame()
        assertEquals(Color.GREEN, render(backdrop, map, 20, 120))
        assertEquals(0, draws)
        backdrop.clear()
    }

    @Test fun textureFramesInvalidateDrawingAndUnavailableOrFailedReadbackUsesSdkSnapshot() {
        val texture = FakeTexture(activity)
        map.addView(texture, FrameLayout.LayoutParams(60, 120).apply { leftMargin = 10; topMargin = 20 })
        layout()
        val backdrop = MapNavigationBackdrop()
        backdrop.update(map, solid(Color.GREEN))
        backdrop.captureLiveFrame()
        assertEquals(Color.RED, render(backdrop, texture, 20, 80))
        Snapshot.sendApplyNotifications()
        val observer = SnapshotStateObserver { it() }
        var invalidations = 0
        observer.start()
        try {
            observer.observeReads(Any(), { _: Any -> invalidations++ }) {
                render(backdrop, texture, 20, 80)
            }
            texture.color = Color.BLUE
            backdrop.captureLiveFrame()
            Snapshot.sendApplyNotifications()
            assertTrue("Mutating the same readback bitmap must invalidate backdrop drawing", invalidations > 0)
            assertEquals(Color.BLUE, render(backdrop, texture, 20, 80))
        } finally {
            observer.stop()
            observer.clear()
        }
        texture.failReadback = true
        backdrop.captureLiveFrame()
        assertEquals(Color.GREEN, render(backdrop, map, 20, 120))
        texture.failReadback = false
        texture.color = Color.TRANSPARENT
        backdrop.captureLiveFrame()
        assertEquals("Empty first texture frame must retain SDK pixels", Color.GREEN, render(backdrop, map, 20, 120))
        texture.available = false
        backdrop.captureLiveFrame()
        assertEquals(Color.GREEN, render(backdrop, map, 20, 120))
        map.removeView(texture)
        backdrop.captureLiveFrame()
        assertEquals(Color.GREEN, render(backdrop, map, 20, 120))
        backdrop.clear()
    }

    @Test fun textureSubtreeCoordinatesAreIndependentOfSdkSnapshotCoordinates() {
        val texture = FakeTexture(activity)
        texture.twoColors = true
        map.addView(texture, FrameLayout.LayoutParams(60, 120).apply { leftMargin = 10; topMargin = 20 })
        layout()
        val backdrop = MapNavigationBackdrop()
        backdrop.update(map, solid(Color.GREEN))
        backdrop.captureLiveFrame()
        assertEquals(Color.RED, render(backdrop, texture, 10, 10))
        assertEquals(Color.BLUE, render(backdrop, texture, 10, 90))
        assertEquals(Color.BLUE, render(backdrop, texture, 10, 90, factor = 2))
        backdrop.clear()
    }

    @Test fun clearedOrRecycledSnapshotsAreNotDrawnAndDetachedSourceDoesNotLeakOldPixels() {
        val snapshot = solid(Color.GREEN)
        val backdrop = MapNavigationBackdrop()
        backdrop.update(map, snapshot)
        snapshot.recycle()
        assertEquals(Color.TRANSPARENT, render(backdrop, map, 20, 120))
        backdrop.update(map, solid(Color.BLUE))
        val origin = IntArray(2).also(map::getLocationInWindow)
        host.removeView(map)
        assertEquals(Color.TRANSPARENT, renderAt(backdrop, origin[0] + 20, origin[1] + 120))
        backdrop.update(null, null)
        assertEquals(Color.TRANSPARENT, renderAt(backdrop, 0, 0))
        backdrop.clear()
    }

    private fun navigation() = HomeLiquidNavigationController(
        activity, org.joinmastodon.android.R.id.tab_map, null, IntConsumer { }, IntPredicate { false },
    )

    private fun layout() {
        host.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, 240, 400)
    }

    private fun solid(color: Int) = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
        eraseColor(color)
        bitmaps.add(this)
    }

    private fun render(backdrop: Backdrop, view: View, x: Int, y: Int, factor: Int = 1): Int {
        val origin = IntArray(2).also(view::getLocationInWindow)
        return renderAt(backdrop, origin[0] + x, origin[1] + y, factor)
    }

    private fun renderAt(backdrop: Backdrop, windowX: Int, windowY: Int, factor: Int = 1): Int {
        val output = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val coordinates = Proxy.newProxyInstance(LayoutCoordinates::class.java.classLoader, arrayOf(LayoutCoordinates::class.java)) { _, method, _ ->
            when (method.name.substringBefore('-')) {
                "localToWindow", "localToRoot" -> (windowX.toFloat().toRawBits().toLong() shl 32) or (windowY.toFloat().toRawBits().toLong() and 0xffffffffL)
                "getSize" -> IntSize(8, 8).let { (it.width.toLong() shl 32) or (it.height.toLong() and 0xffffffffL) }
                "isAttached" -> true
                "getParentLayoutCoordinates", "getParentCoordinates" -> null
                else -> throw AssertionError("Unexpected coordinates operation ${method.name}")
            }
        } as LayoutCoordinates
        try {
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(android.graphics.Canvas(output)), Size(8f, 8f)) {
                with(backdrop) { drawBackdrop(Density(1f), coordinates, null, factor) }
            }
            return output.getPixel(2, 2)
        } finally {
            output.recycle()
        }
    }

    private class FakeTexture(context: android.content.Context) : TextureView(context) {
        var available = true
        var failReadback = false
        var color = Color.RED
        var twoColors = false
        override fun isAvailable() = available
        override fun getBitmap(bitmap: Bitmap): Bitmap {
            if (failReadback) throw IllegalStateException("SurfaceTexture was destroyed")
            bitmap.eraseColor(color)
            if (twoColors) android.graphics.Canvas(bitmap).drawRect(0f, bitmap.height / 2f, bitmap.width.toFloat(), bitmap.height.toFloat(), android.graphics.Paint().apply { color = Color.BLUE })
            return bitmap
        }
    }
}
