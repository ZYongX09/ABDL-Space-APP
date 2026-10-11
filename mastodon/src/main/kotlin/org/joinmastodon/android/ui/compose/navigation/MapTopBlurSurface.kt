package org.joinmastodon.android.ui.compose.navigation

import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import org.joinmastodon.android.R
import org.joinmastodon.android.ui.utils.UiUtils
import org.joinmastodon.android.ui.compose.navigation.liquid.vibrancy
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported

/**
 * Top safe-area strip above the map: a progressive blur approximated with stacked strips whose
 * blur strength and tint ramp upward (the miuix example app's "Progressive" top bar look; the
 * library's ProgressiveBlur itself needs Compose 1.12/AGP 9.1). Invisible outside API33+
 * hardware rendering, where the shader path cannot run.
 */
class MapTopBlurSurface(context: Context) {
	private val backdrop = ViewBitmapBackdrop()
	private var hardware by mutableStateOf(false)
	private var sampleSourceView by mutableStateOf<View?>(null)
	private var frameTick by mutableIntStateOf(0)
	private val surfaceColor = Color(UiUtils.getThemeColor(context, R.attr.colorM3Surface))
	val view = ComposeView(context).apply {
		setBackgroundColor(AndroidColor.TRANSPARENT)
		isClickable = false
		importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
		setContent { WithIsolatedGlassShaderCache { Surface() } }
	}

	fun setSampleSource(source: View?) {
		sampleSourceView = source
		backdrop.updateSampleSource(source)
	}

	fun dispose() {
		sampleSourceView = null
		backdrop.updateSampleSource(null)
		backdrop.update(null)
		view.disposeComposition()
	}

	@Composable
	private fun Surface() {
		hardware = view.isHardwareAccelerated
		val active = hardware && sampleSourceView != null && isRuntimeShaderSupported()
		if (active) {
			// Real-time: recompose per frame so the strips follow map drags.
			LaunchedEffect(sampleSourceView) { while (true) { withFrameNanos { }; frameTick++ } }
		}
		@Suppress("UNUSED_EXPRESSION") frameTick
		if (!active) return
		// Bottom of the strip first: blur and tint grow toward the status bar.
		Column(Modifier.fillMaxSize()) {
			for (i in StripProfiles.indices) {
				val profile = StripProfiles[i]
				Box(Modifier
					.fillMaxWidth()
					.weight(1f)
					.drawBackdrop(
						backdrop = backdrop,
						shape = { RectangleShape },
						effects = {
							vibrancy()
							blur(profile.blurDp.dp.toPx(), profile.blurDp.dp.toPx())
						},
						onDrawSurface = { drawRect(surfaceColor.copy(alpha = profile.tintAlpha)) },
					))
			}
		}
	}

	private data class StripProfile(val blurDp: Int, val tintAlpha: Float)
	private companion object {
		val StripProfiles = arrayOf(
			StripProfile(5, .10f),
			StripProfile(11, .16f),
			StripProfile(18, .22f),
			StripProfile(26, .28f),
		)
	}
}
