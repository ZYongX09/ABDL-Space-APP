package org.joinmastodon.android.ui.compose.navigation

import android.graphics.Bitmap
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import top.yukonga.miuix.kmp.blur.Backdrop
import kotlin.math.round

internal fun topCaptureRange(viewHeight: Int, requestedHeight: Int): IntRange {
	val height = requestedHeight.coerceIn(0, viewHeight.coerceAtLeast(0))
	return if(height==0) IntRange.EMPTY else 0 until height
}

internal fun bottomCaptureRange(viewHeight: Int, requestedHeight: Int): IntRange {
	val height = requestedHeight.coerceIn(0, viewHeight.coerceAtLeast(0))
	return if(height==0) IntRange.EMPTY else (viewHeight-height) until viewHeight
}

/**
 * Bitmap fallback backdrop with an optional live sample source. When a source view is set
 * (the map TextureView), the glass draws that view directly on the hardware canvas every
 * frame — real-time, like the home glass over normal views. Software canvases and detached
 * sources fall back to the last bitmap.
 */
internal class ViewBitmapBackdrop : Backdrop {
	private var image by mutableStateOf<ImageBitmap?>(null)
	private var generation by mutableIntStateOf(0)
	private var originInWindow by mutableStateOf(Offset.Zero)
	private var sampleSource by mutableStateOf<View?>(null)

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
	fun updateSampleSource(source: View?) { sampleSource = source; generation++ }

	override fun DrawScope.drawBackdrop(
		density: Density,
		coordinates: LayoutCoordinates?,
		layerBlock: (GraphicsLayerScope.() -> Unit)?,
		downscaleFactor: Int,
	) {
		generation
		val currentCoordinates = coordinates ?: return
		val source = sampleSource
		val hardware = drawContext.canvas.nativeCanvas.isHardwareAccelerated
		if(source!=null && source.isAttachedToWindow && hardware && source.width>0 && source.height>0){
			val offset=currentCoordinates.positionInWindow()-originInWindow
			drawIntoCanvas { canvas->
				val native=canvas.nativeCanvas
				native.save()
				native.translate(-offset.x,-offset.y)
				try{ source.draw(native) } finally { native.restore() }
			}
			return
		}
		val currentImage=image ?: return
		val offset=currentCoordinates.positionInWindow()-originInWindow
		val factor=downscaleFactor.coerceAtLeast(1)
		val inv=1f/factor
		val x=offset.x*inv
		val y=offset.y*inv
		val roundedX=if(factor>1) round(x*.5f)*2f else x
		val roundedY=if(factor>1) round(y*.5f)*2f else y
		offsetResidualX=(x-roundedX)*factor
		offsetResidualY=(y-roundedY)*factor
		withTransform({
			translate(-roundedX,-roundedY)
			if(factor>1) scale(inv,inv,Offset.Zero)
		}) { drawImage(currentImage) }
	}
}
