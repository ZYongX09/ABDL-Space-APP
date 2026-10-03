package org.joinmastodon.android.ui.compose.navigation

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import top.yukonga.miuix.kmp.blur.Backdrop

internal fun topCaptureRange(viewHeight: Int, requestedHeight: Int): IntRange {
	val height = requestedHeight.coerceIn(0, viewHeight.coerceAtLeast(0))
	return if(height==0) IntRange.EMPTY else 0 until height
}

internal fun bottomCaptureRange(viewHeight: Int, requestedHeight: Int): IntRange {
	val height = requestedHeight.coerceIn(0, viewHeight.coerceAtLeast(0))
	return if(height==0) IntRange.EMPTY else (viewHeight-height) until viewHeight
}

internal class ViewBitmapBackdrop : Backdrop {
	private var image by mutableStateOf<ImageBitmap?>(null)
	private var generation by mutableIntStateOf(0)
	private var originInWindow by mutableStateOf(Offset.Zero)

	override val isCoordinatesDependent = true

	fun update(bitmap: Bitmap) {
		image = bitmap.asImageBitmap()
		generation++
	}

	fun updateOriginInWindow(origin: Offset) {
		originInWindow = origin
	}

	override fun DrawScope.drawBackdrop(
		density: Density,
		coordinates: LayoutCoordinates?,
		layerBlock: (GraphicsLayerScope.() -> Unit)?,
		downscaleFactor: Int,
	) {
		generation
		val currentImage = image ?: return
		val currentCoordinates = coordinates ?: return
		val offset = currentCoordinates.positionInWindow() - originInWindow
		val scaleFactor = downscaleFactor.coerceAtLeast(1)
		withTransform({
			translate(-offset.x / scaleFactor, -offset.y / scaleFactor)
			if (scaleFactor > 1) scale(1f / scaleFactor, 1f / scaleFactor, Offset.Zero)
		}) {
			drawImage(currentImage)
		}
	}
}
