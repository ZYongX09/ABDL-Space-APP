package org.joinmastodon.android.ui.compose.navigation.motion

/**
 * Mutable rectangle used by the motion engine. Mirrors the reference implementation's use of
 * `android.graphics.RectF` while keeping the kernel free of Android types for unit tests.
 */
internal class GlassRect(
	var left: Float = 0f,
	var top: Float = 0f,
	var right: Float = 0f,
	var bottom: Float = 0f,
) {
	val width: Float get() = right - left
	val height: Float get() = bottom - top
	val centerX: Float get() = (left + right) / 2f
	val centerY: Float get() = (top + bottom) / 2f

	fun set(l: Float, t: Float, r: Float, b: Float) {
		left = l
		top = t
		right = r
		bottom = b
	}

	fun set(other: GlassRect) = set(other.left, other.top, other.right, other.bottom)
}
