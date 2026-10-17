package org.joinmastodon.android.ui.compose.navigation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import org.joinmastodon.android.GlobalUserPreferences
import org.joinmastodon.android.R
import org.joinmastodon.android.ui.map.MapGlassPolicy
import org.joinmastodon.android.ui.utils.UiUtils
import org.joinmastodon.android.ui.utils.UiUtils.isDarkTheme
import org.joinmastodon.android.ui.compose.navigation.liquid.iosIndicatorSpecular
import org.joinmastodon.android.ui.compose.navigation.liquid.lens
import org.joinmastodon.android.ui.compose.navigation.liquid.vibrancy
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop

/** Native map samples never include the overlay itself. Content remains fully opaque. */
class MapGlassSurface(context: Context, private val radiusDp: Int, private val largePanel: Boolean, private val fillAlphaOverride: Float = -1f) {
	private val backdrop = ViewBitmapBackdrop()
	private var cornerRadiusDp by mutableStateOf(radiusDp.toFloat())
	private var sampleReady by mutableStateOf(false)
	private var hardware by mutableStateOf(false)
	private var sampleSourceView by mutableStateOf<View?>(null)
	private var mapOrigin by mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
	private var frameTick by mutableIntStateOf(0)
	private val surfaceColor = Color(UiUtils.getThemeColor(context, R.attr.colorM3Surface))
	private val outlineColor = Color(UiUtils.getThemeColor(context, R.attr.colorM3OutlineVariant))
	val view = ComposeView(context).apply {
		setBackgroundColor(AndroidColor.TRANSPARENT)
		isClickable = false
		importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
		setContent { WithIsolatedGlassShaderCache { Surface() } }
	}

	fun update(bitmap: Bitmap?, mapLeft: Int, mapTop: Int) {
		mapOrigin = androidx.compose.ui.geometry.Offset(mapLeft.toFloat(), mapTop.toFloat())
		backdrop.updateOriginInWindow(mapOrigin)
		sampleReady = bitmap != null && !bitmap.isRecycled
		hardware = view.isHardwareAccelerated
		backdrop.update(if (sampleReady) bitmap else null)
	}
	fun setCornerRadius(radiusDp: Float) { cornerRadiusDp = radiusDp.coerceAtLeast(0f) }
	fun setSampleSource(source: View?) { sampleSourceView = source; backdrop.updateSampleSource(source) }
	fun dispose() { sampleReady = false; sampleSourceView = null; backdrop.updateSampleSource(null); backdrop.update(null); view.disposeComposition() }

	@Composable
	private fun Surface() {
		val shape = RoundedCornerShape(cornerRadiusDp.dp)
		val darkTheme = isDarkTheme()
		val liquid = MapGlassPolicy.supportsLiquid(android.os.Build.VERSION.SDK_INT, hardware,
			GlobalUserPreferences.isIosLiquidNavigationEnabled(), sampleReady)
		val fill = surfaceColor.copy(alpha = when {
			fillAlphaOverride >= 0f && liquid -> fillAlphaOverride
			liquid -> MapGlassPolicy.liquidSurfaceAlpha(darkTheme, largePanel)
			else -> MapGlassPolicy.surfaceAlpha(false, largePanel)
		})
		if (liquid && sampleSourceView != null && hardware) {
			// Real-time glass: recompose per frame so drawBackdrop reads the live map texture,
			// exactly like the home glass over scrolling content.
			LaunchedEffect(sampleSourceView) { while (true) { withFrameNanos { }; frameTick++ } }
		}
		@Suppress("UNUSED_EXPRESSION") frameTick
		val material = if (liquid) liquidMaterial(backdrop, shape, fill) else Modifier.background(fill, shape)
		Box(Modifier.fillMaxSize()
			.onGloballyPositioned { backdrop.updateOriginInWindow(mapOrigin) }
			.then(material)
			.border(.75.dp, outlineColor.copy(alpha = if (liquid) .45f else .65f), shape))
	}
}

/** Only reached on API33+ hardware rendering; low API code never constructs a shader. */
@Composable
private fun liquidMaterial(backdrop: Backdrop, shape: RoundedCornerShape, fill: Color): Modifier {
	val highlight = rememberIsolatedGlassHighlight(iosIndicatorSpecular, extraDegrees = -45f)
	return Modifier.drawBackdrop(
		backdrop = backdrop,
		shape = { shape },
		effects = {
			vibrancy()
			blur(MapGlassPolicy.BLUR_DP.dp.toPx(), MapGlassPolicy.BLUR_DP.dp.toPx())
			lens(refractionHeight = MapGlassPolicy.REFRACTION_HEIGHT_DP.dp.toPx(), refractionAmount = MapGlassPolicy.REFRACTION_AMOUNT_DP.dp.toPx())
		},
		highlight = { highlight.value.copy(alpha = MapGlassPolicy.HIGHLIGHT_ALPHA) },
		onDrawSurface = { drawRect(fill) },
	)
}
