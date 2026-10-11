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
import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import kotlin.math.round

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
	override var offsetResidualX = 0f
		private set
	override var offsetResidualY = 0f
		private set

	fun update(bitmap: Bitmap?) {
		image = bitmap?.asImageBitmap()
		generation++
	}

	fun updateOriginInWindow(origin: Offset) { originInWindow = origin }

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
		val factor = downscaleFactor.coerceAtLeast(1)
		val inv = 1f / factor
		val x = offset.x * inv
		val y = offset.y * inv
		val roundedX = if(factor>1) round(x * .5f) * 2f else x
		val roundedY = if(factor>1) round(y * .5f) * 2f else y
		offsetResidualX = (x - roundedX) * factor
		offsetResidualY = (y - roundedY) * factor
		val consumerSize = (density as? BackdropEffectScope)?.size ?: size
		withTransform({
			if(layerBlock!=null) with(BitmapBackdropTransform()) { inverse(density, consumerSize, layerBlock) }
			translate(-roundedX, -roundedY)
			if(factor>1) scale(inv, inv, Offset.Zero)
		}) { drawImage(currentImage) }
	}
}
