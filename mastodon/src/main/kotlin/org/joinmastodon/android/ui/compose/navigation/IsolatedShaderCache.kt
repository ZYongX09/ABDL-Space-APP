package org.joinmastodon.android.ui.compose.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import org.joinmastodon.android.ui.compose.navigation.liquid.rememberGravityRotatedHighlight
import top.yukonga.miuix.kmp.blur.LocalRuntimeShaderCache
import top.yukonga.miuix.kmp.blur.RuntimeShaderCache
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.HighlightStyle

@Composable
internal fun WithIsolatedGlassShaderCache(content: @Composable () -> Unit) {
	val isolated = remember { IsolatedShaderCache.create() }
	CompositionLocalProvider(LocalRuntimeShaderCache provides isolated, content = content)
}

@Composable
internal fun rememberIsolatedGlassHighlight(base: Highlight, extraDegrees: Float): State<Highlight> {
	val rotated = rememberGravityRotatedHighlight(base, extraDegrees)
	val cache = remember { IsolatedShaderCache.create() }
	return remember(rotated, cache) {
		derivedStateOf {
			val current = rotated.value
			current.copy(style = IsolatedHighlightStyle(current.style, cache))
		}
	}
}

/** Paint shader uniforms remain live in a recorded display list; sibling bounds cannot share them. */
private class IsolatedHighlightStyle(
	private val delegate: HighlightStyle,
	private val cache: RuntimeShaderCache,
) : HighlightStyle {
	override val color get() = delegate.color
	override val blendMode get() = delegate.blendMode
	override fun DrawScope.createShader(
		shape: Shape,
		strokeWidthPx: Float,
		highlightAlpha: Float,
		runtimeShaderCache: RuntimeShaderCache,
	): Shader? = with(delegate) { createShader(shape, strokeWidthPx, highlightAlpha, cache) }
}
