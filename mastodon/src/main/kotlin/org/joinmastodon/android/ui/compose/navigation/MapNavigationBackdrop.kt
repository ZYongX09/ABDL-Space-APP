package org.joinmastodon.android.ui.compose.navigation

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import top.yukonga.miuix.kmp.blur.Backdrop
import kotlin.math.round
import kotlin.math.roundToInt

/** A map-only source. Normal View.draw/strip captures cannot contain a map's texture. */
internal class MapNavigationBackdrop : Backdrop {
    private var source by mutableStateOf<View?>(null)
    private var snapshot by mutableStateOf<Bitmap?>(null)
    private var liveSource: TextureView? = null
    private var liveBitmap: Bitmap? = null
    private var captureBuffer: Bitmap? = null
    private var generation by mutableIntStateOf(0)
    private val location = IntArray(2)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    override val isCoordinatesDependent = true
    override var offsetResidualX = 0f
        private set
    override var offsetResidualY = 0f
        private set

    fun update(mapView: View?, bitmap: Bitmap?) {
        if (source !== mapView) clearLiveFrame()
        source = mapView
        snapshot = bitmap?.takeUnless { it.isRecycled }
        generation++
    }

    /** Called only while the map is visible and the navigation composition is attached. */
    fun captureLiveFrame() {
        val root = source
        val texture = root?.takeIf { it.isAttachedToWindow && it.isShown }?.let(::findTexture)
        if (texture == null || !texture.isAvailable || texture.width <= 0 || texture.height <= 0) {
            clearLiveFrame()
            return
        }
        // Bound readback to 1 MiB. Full-resolution SDK snapshots remain the reliable fallback.
        val scale = minOf(1f, 512f / maxOf(texture.width, texture.height))
        val width = (texture.width * scale).roundToInt().coerceAtLeast(1)
        val height = (texture.height * scale).roundToInt().coerceAtLeast(1)
        var buffer = captureBuffer
        if (buffer == null || buffer.isRecycled || buffer.width != width || buffer.height != height) {
            buffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            captureBuffer = buffer
        }
        val frame = try {
            // An unavailable first frame must not revive pixels from an earlier texture readback.
            buffer.eraseColor(android.graphics.Color.TRANSPARENT)
            // TextureView.draw is deliberately NOT used: it can record an empty texture layer.
            texture.getBitmap(buffer)
        } catch (_: RuntimeException) {
            // A disappearing/recreated SurfaceTexture must not suppress the SDK fallback.
            null
        }
        if (frame == null || frame.isRecycled || !hasTexturePixels(frame)) {
            clearLiveFrame()
            return
        }
        liveSource = texture
        liveBitmap = frame
        generation++ // Bitmap identity may be unchanged, but every successful readback redraws glass.
    }

    fun clear() {
        source = null
        snapshot = null
        clearLiveFrame()
        // Never recycle a bitmap potentially retained by a recorded graphics layer.
        captureBuffer = null
        generation++
    }

    private fun clearLiveFrame() {
        if (liveSource != null || liveBitmap != null) {
            liveSource = null
            liveBitmap = null
            generation++
        }
    }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Int,
    ) {
        generation
        offsetResidualX = 0f
        offsetResidualY = 0f
        val target = coordinates ?: return
        val root = source ?: return
        if (!root.isAttachedToWindow || !root.isShown || root.width <= 0 || root.height <= 0) return
        val texture = liveSource
        val useLive = texture != null && texture.isAttachedToWindow && texture.isShown && texture.isAvailable
            && texture.width > 0 && texture.height > 0 && liveBitmap?.isRecycled == false
        val bitmap = (if (useLive) liveBitmap else snapshot)?.takeUnless { it.isRecycled } ?: return
        val bitmapView = if (useLive) texture!! else root
        // SDK snapshot pixels span the provider view, not the navigation ComposeView. Keep the
        // two origins independent, including status/navigation insets and a nested TextureView.
        bitmapView.getLocationInWindow(location)
        val offset = target.positionInWindow() - Offset(location[0].toFloat(), location[1].toFloat())
        val factor = downscaleFactor.coerceAtLeast(1)
        val inverse = 1f / factor
        val x = offset.x * inverse
        val y = offset.y * inverse
        val roundedX = if (factor > 1) round(x * .5f) * 2f else x
        val roundedY = if (factor > 1) round(y * .5f) * 2f else y
        offsetResidualX = (x - roundedX) * factor
        offsetResidualY = (y - roundedY) * factor
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            try {
                native.translate(-roundedX, -roundedY)
                native.scale(inverse, inverse)
                native.drawBitmap(bitmap, null, RectF(0f, 0f, bitmapView.width.toFloat(), bitmapView.height.toFloat()), paint)
            } finally {
                native.restore()
            }
        }
    }

    private fun hasTexturePixels(bitmap: Bitmap): Boolean {
        // Maps are opaque; a fully transparent readback means the texture has no frame yet.
        // A small grid avoids scanning/copying every pixel at frame rate.
        for (y in 1..3) for (x in 1..3) {
            if (android.graphics.Color.alpha(bitmap.getPixel((bitmap.width - 1) * x / 4, (bitmap.height - 1) * y / 4)) > 0) return true
        }
        return false
    }

    private fun findTexture(view: View): TextureView? {
        if (!view.isShown) return null
        if (view is TextureView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findTexture(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
