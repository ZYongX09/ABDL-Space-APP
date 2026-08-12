package org.joinmastodon.android.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.graphics.PathParser
import org.joinmastodon.android.R
import kotlin.math.sin

/**
 * Android adaptation of itshover's Apache-2.0 home, magnifier, star and globe icons.
 * Original SVG paths and motion design: https://github.com/itshover/itshover
 */
class ItshoverNavigationIconView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
	private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeCap = Paint.Cap.ROUND
		strokeJoin = Paint.Join.ROUND
	}
	private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
	private val matrix = Matrix()
	private var animator: ValueAnimator? = null
	private var progress = 1f
	private var tint: ColorStateList = ColorStateList.valueOf(Color.BLACK)

	var iconType: Int = ICON_HOME
		set(value) {
			field = value.coerceIn(ICON_HOME, ICON_GLOBE)
			invalidate()
		}

	init {
		context.obtainStyledAttributes(attrs, R.styleable.ItshoverNavigationIconView, defStyleAttr, 0).use { values ->
			iconType = values.getInt(R.styleable.ItshoverNavigationIconView_iconType, ICON_HOME)
			tint = values.getColorStateList(R.styleable.ItshoverNavigationIconView_iconTint) ?: tint
		}
		isClickable = false
		importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
	}

	fun setIconColor(color: Int) {
		tint = ColorStateList.valueOf(color)
		invalidate()
	}

	fun playAnimation() {
		animator?.cancel()
		progress = 0f
		animator = ValueAnimator.ofFloat(0f, 1f).apply {
			duration = when (iconType) {
				ICON_HOME -> 600L
				ICON_MAGNIFIER -> 1000L
				ICON_STAR -> 800L
				else -> 900L
			}
			interpolator = AccelerateDecelerateInterpolator()
			addUpdateListener {
				progress = it.animatedValue as Float
				invalidate()
			}
			start()
		}
	}

	override fun drawableStateChanged() {
		super.drawableStateChanged()
		invalidate()
	}

	override fun onDetachedFromWindow() {
		animator?.cancel()
		animator = null
		progress = 1f
		invalidate()
		super.onDetachedFromWindow()
	}

	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)
		val viewBox = if (iconType == ICON_MAGNIFIER) 32f else if (iconType == ICON_GLOBE) 48f else 24f
		val scale = minOf(width, height) * 0.82f / viewBox
		val left = (width - viewBox * scale) / 2f
		val top = (height - viewBox * scale) / 2f
		val color = tint.getColorForState(drawableState, tint.defaultColor)
		strokePaint.color = color
		strokePaint.strokeWidth = 2f * (viewBox / 24f)
		fillPaint.color = color
		canvas.save()
		canvas.translate(left, top)
		canvas.scale(scale, scale)
		when (iconType) {
			ICON_HOME -> drawHome(canvas)
			ICON_MAGNIFIER -> drawMagnifier(canvas)
			ICON_STAR -> drawStar(canvas)
			ICON_GLOBE -> drawGlobe(canvas)
		}
		canvas.restore()
	}

	private fun drawHome(canvas: Canvas) {
		val roofProgress = segment(progress, 0f, 2f / 3f)
		val houseProgress = segment(progress, 0f, 0.5f)
		val doorProgress = segment(progress, 0.5f, 1f)
		drawPath(canvas, HOME_ROOF, translateY = -2f * (1f - roofProgress), alpha = 0.6f + 0.4f * roofProgress)
		drawPath(canvas, HOME_HOUSE, scaleX = 0.95f + 0.05f * houseProgress, scaleY = 0.95f + 0.05f * houseProgress, pivotX = 12f, pivotY = 16f)
		drawPath(canvas, HOME_DOOR, scaleY = doorProgress, pivotX = 12f, pivotY = 21f)
	}

	private fun drawMagnifier(canvas: Canvas) {
		val x = keyframe(progress, 0f, 1f, 0f, -1f, 0f)
		val y = keyframe(progress, 0f, -1f, -2f, -1f, 0f)
		val rotation = keyframe(progress, 0f, -5f, 5f, -5f, 0f)
		drawPath(canvas, MAGNIFIER_HANDLE, translateX = x, translateY = y, rotation = rotation, pivotX = 13f, pivotY = 13f)
		drawCircle(canvas, 13f, 13f, 10f, x, y, rotation, 13f, 13f)
	}

	private fun drawStar(canvas: Canvas) {
		val start = segment(progress, 0f, 0.62f)
		val reset = segment(progress, 0.62f, 1f)
		val scale = keyframe(start, 1f, 1.1f, 1f)
		val rotation = keyframe(start, 0f, -5f, 5f, 0f) * (1f - reset)
		val fillAlpha = if (progress < 0.5f) segment(progress, 0f, 0.5f) else 1f - segment(progress, 0.62f, 1f)
		drawPath(canvas, STAR, scaleX = 0.8f + 0.2f * start, scaleY = 0.8f + 0.2f * start, pivotX = 12f, pivotY = 12f, fillAlpha = fillAlpha)
		drawPath(canvas, STAR, scaleX = scale, scaleY = scale, rotation = rotation, pivotX = 12f, pivotY = 12f)
	}

	private fun drawGlobe(canvas: Canvas) {
		val rotation = 360f * progress
		drawPath(canvas, GLOBE_CONTINENT_1, rotation = rotation, pivotX = 23f, pivotY = 19f)
		drawPath(canvas, GLOBE_CONTINENT_2, rotation = rotation, pivotX = 23f, pivotY = 19f)
		drawPath(canvas, GLOBE_CONTINENT_3, rotation = rotation, pivotX = 23f, pivotY = 19f)
		drawPath(canvas, GLOBE_OUTLINE, rotation = rotation, pivotX = 23f, pivotY = 19f)
		drawPath(canvas, GLOBE_STEM)
		drawPath(canvas, GLOBE_BASE)
		drawPath(canvas, GLOBE_AXIS)
	}

	private fun drawPath(
		canvas: Canvas,
		path: Path,
		translateX: Float = 0f,
		translateY: Float = 0f,
		scaleX: Float = 1f,
		scaleY: Float = 1f,
		rotation: Float = 0f,
		pivotX: Float = 0f,
		pivotY: Float = 0f,
		alpha: Float = 1f,
		fillAlpha: Float = 0f,
	) {
		matrix.reset()
		matrix.postTranslate(-pivotX, -pivotY)
		matrix.postScale(scaleX, scaleY)
		matrix.postRotate(rotation)
		matrix.postTranslate(pivotX + translateX, pivotY + translateY)
		val transformed = Path(path)
		transformed.transform(matrix)
		if (fillAlpha > 0f) {
			fillPaint.alpha = (255 * fillAlpha.coerceIn(0f, 1f)).toInt()
			canvas.drawPath(transformed, fillPaint)
		}
		strokePaint.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
		canvas.drawPath(transformed, strokePaint)
		strokePaint.alpha = 255
	}

	private fun drawCircle(canvas: Canvas, cx: Float, cy: Float, radius: Float, tx: Float, ty: Float, rotation: Float, px: Float, py: Float) {
		canvas.save()
		canvas.rotate(rotation, px, py)
		canvas.translate(tx, ty)
		canvas.drawCircle(cx, cy, radius, strokePaint)
		canvas.restore()
	}

	private fun segment(value: Float, start: Float, end: Float) = ((value - start) / (end - start)).coerceIn(0f, 1f)

	private fun keyframe(value: Float, vararg values: Float): Float {
		val position = value.coerceIn(0f, 1f) * (values.size - 1)
		val index = position.toInt().coerceAtMost(values.size - 2)
		val fraction = position - index
		return values[index] + (values[index + 1] - values[index]) * fraction
	}

	companion object {
		const val ICON_HOME = 0
		const val ICON_MAGNIFIER = 1
		const val ICON_STAR = 2
		const val ICON_GLOBE = 3

		private fun path(data: String): Path = requireNotNull(PathParser.createPathFromPathData(data))
		private val HOME_ROOF = path("M5 12l-2 0l9 -9l9 9l-2 0")
		private val HOME_HOUSE = path("M5 12v7a2 2 0 0 0 2 2h10a2 2 0 0 0 2 -2v-7")
		private val HOME_DOOR = path("M9 21v-6a2 2 0 0 1 2 -2h2a2 2 0 0 1 2 2v6")
		private val MAGNIFIER_HANDLE = path("m21.393 18.565l7.021 7.021c.781.781.781 2.047 0 2.828h0c-.781.781-2.047.781-2.828 0l-7.021-7.021")
		private val STAR = path("M12 17.75l-6.172 3.245l1.179-6.873l-5-4.867l6.9-1l3.086-6.253l3.086 6.253l6.9 1l-5 4.867l1.179 6.873z")
		private val GLOBE_CONTINENT_1 = path("M36.6225 22.1264C34.6145 19.2959 32.3651 15.7913 28.4377 17.3428C24.4307 18.9257 30.0493 23.15 25.2064 26.9189C22.1135 29.3259 22.8515 31.6477 23.9478 33")
		private val GLOBE_CONTINENT_2 = path("M14 30L15.336 28.0984C16.3999 26.5841 16.557 24.5077 15.7357 22.8151L15.5751 22.4842C14.5131 20.2955 15.1651 17.5604 17.0607 16.253L17.3292 16.0677C18.2109 15.4596 18.808 14.4478 18.9613 13.3023C19.1316 12.0291 18.7338 10.7433 17.8962 9.85981L15.3599 7.24048")
		private val GLOBE_CONTINENT_3 = path("M23.0628 5C22.3771 9.64991 27.3946 14.948 33.7332 10.0381")
		private val GLOBE_OUTLINE = path("M23 33C30.732 33 37 26.732 37 19C37 11.268 30.732 5 23 5C15.268 5 9 11.268 9 19C9 26.732 15.268 33 23 33Z")
		private val GLOBE_STEM = path("M23 43V38")
		private val GLOBE_BASE = path("M16 43H30")
		private val GLOBE_AXIS = path("M38 3.99994L36.435 5.56491C43.855 12.9849 43.855 25.015 36.435 32.435C29.0151 39.8549 16.9849 39.8549 9.56497 32.435L7.99997 34")
	}
}
