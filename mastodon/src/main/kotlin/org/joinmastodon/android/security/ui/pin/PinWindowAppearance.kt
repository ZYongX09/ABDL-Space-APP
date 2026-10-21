package org.joinmastodon.android.security.ui.pin

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Only PIN routes own these window properties; settings and other activities keep their own policy. */
@Composable
internal fun PinWindowAppearance(background: ComposeColor) {
	val window = LocalView.current.context.activityOrNull()?.window
	val lifecycle = LocalLifecycleOwner.current.lifecycle
	val color = background.toArgb()
	DisposableEffect(window, lifecycle, color) {
		val owner = window?.let { PinWindowOwner(it, lifecycle, color) }
		onDispose { owner?.close() }
	}
}

private fun Context.activityOrNull(): Activity? = when (this) {
	is Activity -> this
	is ContextWrapper -> if (baseContext !== this) baseContext.activityOrNull() else null
	else -> null
}

/**
 * Reversible, route-local edge-to-edge for API 26+. In particular, do not call the Activity-wide
 * enableEdgeToEdge/setDecorFitsSystemWindows: the latter has no matching getter on API 30-32.
 * The legacy layout flags still let this window draw behind both bars on those releases, and can
 * be restored exactly. No insets listener is installed or replaced (Compose owns its own listener).
 * FLAG_SECURE, overlay protection, authentication, and back handling are deliberately not owned here.
 */
@Suppress("DEPRECATION")
internal class PinWindowOwner(
	private val window: Window,
	private val lifecycle: Lifecycle,
	private val background: Int,
) : AutoCloseable {
	private data class Previous(
		val flags: Int,
		val systemUi: Int,
		val softInputMode: Int,
		val statusColor: Int,
		val navigationColor: Int,
		val navigationDivider: Int,
		val cutoutMode: Int,
		val statusContrast: Boolean,
		val navigationContrast: Boolean,
		val lightStatus: Boolean,
		val lightNavigation: Boolean,
	)

	private var previous: Previous? = null
	private var closed = false
	private val observer = LifecycleEventObserver { _, event ->
		when (event) {
			Lifecycle.Event.ON_START -> acquire()
			Lifecycle.Event.ON_STOP -> release()
			Lifecycle.Event.ON_DESTROY -> close()
			else -> Unit // A biometric prompt's pause/resume must not change the window policy.
		}
	}

	init {
		if (lifecycle.currentState != Lifecycle.State.DESTROYED) lifecycle.addObserver(observer)
	}

	private fun acquire() {
		if (closed || previous != null) return
		val decor = window.decorView
		val controller = WindowCompat.getInsetsController(window, decor)
		previous = Previous(
			flags = window.attributes.flags,
			systemUi = decor.systemUiVisibility,
			softInputMode = window.attributes.softInputMode,
			statusColor = window.statusBarColor,
			navigationColor = window.navigationBarColor,
			navigationDivider = if (Build.VERSION.SDK_INT >= 28) window.navigationBarDividerColor else 0,
			cutoutMode = if (Build.VERSION.SDK_INT >= 28) window.attributes.layoutInDisplayCutoutMode else 0,
			statusContrast = Build.VERSION.SDK_INT >= 29 && window.isStatusBarContrastEnforced,
			navigationContrast = Build.VERSION.SDK_INT >= 29 && window.isNavigationBarContrastEnforced,
			lightStatus = controller.isAppearanceLightStatusBars,
			lightNavigation = controller.isAppearanceLightNavigationBars,
		)
		window.setFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS, WINDOW_MASK)
		// Pick icon contrast from the actual theme surface, not from a separate dark-mode palette.
		val darkIcons = ColorUtils.calculateLuminance(background) > 0.5
		window.statusBarColor = Color.TRANSPARENT
		window.navigationBarColor = Color.TRANSPARENT
		if (Build.VERSION.SDK_INT >= 28) {
			window.navigationBarDividerColor = Color.TRANSPARENT
			window.attributes = window.attributes.apply {
				layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
			}
		}
		if (Build.VERSION.SDK_INT >= 29) {
			window.isStatusBarContrastEnforced = false
			window.isNavigationBarContrastEnforced = false
		}
		window.setSoftInputMode((window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or
			WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
		setLightAppearance(darkIcons, darkIcons)
		decor.systemUiVisibility = (decor.systemUiVisibility and UI_MASK.inv()) or LAYOUT_FLAGS or
			(if (darkIcons) LIGHT_FLAGS else 0)
		ViewCompat.requestApplyInsets(decor)
	}

	private fun release() {
		val saved = previous ?: return
		previous = null
		val decor = window.decorView
		window.statusBarColor = saved.statusColor
		window.navigationBarColor = saved.navigationColor
		if (Build.VERSION.SDK_INT >= 28) {
			window.navigationBarDividerColor = saved.navigationDivider
			window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = saved.cutoutMode }
		}
		if (Build.VERSION.SDK_INT >= 29) {
			window.isStatusBarContrastEnforced = saved.statusContrast
			window.isNavigationBarContrastEnforced = saved.navigationContrast
		}
		window.setSoftInputMode((window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or
			(saved.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST))
		setLightAppearance(saved.lightStatus, saved.lightNavigation)
		// Compat's API 26-29 light setters clear TRANSLUCENT_* and add DRAWS_*.
		// On API 30+ its setters also rewrite legacy light bits, which need not match native
		// appearance (e.g. before the first traversal). Restore both representations exactly,
		// and only after every operation with flag/UI side effects. Never restore FLAG_SECURE.
		window.setFlags(saved.flags, WINDOW_MASK)
		decor.systemUiVisibility = (decor.systemUiVisibility and UI_MASK.inv()) or (saved.systemUi and UI_MASK)
		ViewCompat.requestApplyInsets(decor)
	}

	private fun setLightAppearance(status: Boolean, navigation: Boolean) {
		if (Build.VERSION.SDK_INT >= 30) {
			// Native masked appearance writes leave legacy UI bits and unrelated appearance alone.
			val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
				WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
			val appearance = (if (status) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0) or
				(if (navigation) WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0)
			window.insetsController?.setSystemBarsAppearance(appearance, mask)
		} else {
			val controller = WindowCompat.getInsetsController(window, window.decorView)
			controller.isAppearanceLightStatusBars = status
			controller.isAppearanceLightNavigationBars = navigation
		}
	}

	override fun close() {
		if (closed) return
		closed = true
		lifecycle.removeObserver(observer)
		release()
	}

	private companion object {
		const val LAYOUT_FLAGS = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
			View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
		const val LIGHT_FLAGS = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
		const val UI_MASK = LAYOUT_FLAGS or LIGHT_FLAGS or
			View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE or
			View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
		const val WINDOW_MASK = WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
			WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or
			WindowManager.LayoutParams.FLAG_FULLSCREEN
	}
}
