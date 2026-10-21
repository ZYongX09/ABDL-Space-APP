package org.joinmastodon.android.security.ui.pin

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real Android windows and Activity lifecycle, without a repository, credentials, or an unlock. */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 27, 28, 29, 30, 31, 32], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PinWindowAppearanceTest {
	@Test fun startStopResumeAndDestroyRestoreOnlyThePropertiesOwnedByPin() {
		val activity = Robolectric.buildActivity(ComponentActivity::class.java).create()
		val window = activity.get().window
		configurePreviousOwner(window)
		val original = snapshot(window)
		val owner = PinWindowOwner(window, activity.get().lifecycle, Color.WHITE)
		assertEquals("A created but invisible route must not take ownership", original, snapshot(window))
		activity.start().resume().visible()
		assertPinWindow(window, true)
		activity.pause() // Includes the biometric prompt's pause/resume boundary.
		assertPinWindow(window, true)
		activity.resume()
		assertPinWindow(window, true)
		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
		window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LOW_PROFILE
		activity.pause().stop()
		assertEquals(original, snapshot(window))
		assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
		assertTrue(window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LOW_PROFILE != 0)
		activity.restart().start().resume()
		assertPinWindow(window, true)
		owner.close() // Route pop while the host remains resumed.
		assertEquals(original, snapshot(window))
		owner.close()
		activity.pause().stop().destroy()
		assertEquals(original, snapshot(window))
	}

	@Test fun themeReplacementUsesTheActualBackgroundAndDoesNotLeakToAnotherWindow() {
		val host = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
		val other = Robolectric.buildActivity(Activity::class.java).setup()
		val window = host.get().window
		configurePreviousOwner(window)
		val original = snapshot(window)
		val unrelated = snapshot(other.get().window)
		val light = PinWindowOwner(window, host.get().lifecycle, Color.WHITE)
		assertPinWindow(window, true)
		light.close()
		assertEquals("Theme replacement must first release the previous owner exactly", original, snapshot(window))
		val dark = PinWindowOwner(window, host.get().lifecycle, Color.BLACK)
		assertPinWindow(window, false)
		assertEquals(unrelated, snapshot(other.get().window))
		dark.close()
		assertEquals(original, snapshot(window))
		host.pause().stop().destroy()
		other.pause().stop().destroy()
	}

	@Test fun recreatedSecureTestWindowReacquiresAndReleasesWithoutChangingDecorFitsPolicy() {
		val activity = Robolectric.buildActivity(EmptySecurePinWindowActivity::class.java).setup().visible()
		val first = activity.get()
		assertPinWindow(first.window, true)
		activity.recreate()
		assertNotSame(first, activity.get())
		assertPinWindow(activity.get().window, true)
		activity.pause().stop().destroy()
		assertTrue(first.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
		assertEquals(0, activity.get().window.decorView.systemUiVisibility and LAYOUT)
	}

	@Test fun insetsDispatchKeepsTheHostListenerAndTheOriginalInsets() {
		val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
		val root = FrameLayout(activity.get())
		activity.get().setContentView(root)
		var calls = 0
		root.setOnApplyWindowInsetsListener { _, insets -> calls++; insets }
		val incoming = if (Build.VERSION.SDK_INT >= 29) {
			WindowInsets.Builder().setSystemWindowInsets(android.graphics.Insets.of(9, 24, 11, 48)).build()
		} else {
			WindowInsets::class.java.getConstructor(android.graphics.Rect::class.java)
				.newInstance(android.graphics.Rect(9, 24, 11, 48))
		}
		val owner = PinWindowOwner(activity.get().window, activity.get().lifecycle, Color.WHITE)
		val delivered = root.dispatchApplyWindowInsets(incoming)
		assertEquals(1, calls)
		assertEquals(24, delivered.systemWindowInsetTop)
		assertEquals(48, delivered.systemWindowInsetBottom)
		owner.close()
		root.dispatchApplyWindowInsets(incoming)
		assertEquals(2, calls)
		activity.pause().stop().destroy()
	}

	private fun configurePreviousOwner(window: Window) {
		window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
		window.statusBarColor = Color.MAGENTA
		window.navigationBarColor = Color.CYAN
		window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or
			WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
		window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
		if (Build.VERSION.SDK_INT >= 28) {
			window.navigationBarDividerColor = Color.YELLOW
			window.attributes = window.attributes.apply {
				layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
			}
		}
		if (Build.VERSION.SDK_INT >= 29) {
			window.isStatusBarContrastEnforced = true
			window.isNavigationBarContrastEnforced = true
		}
	}

	private fun assertPinWindow(window: Window, darkIcons: Boolean) {
		assertEquals(LAYOUT, window.decorView.systemUiVisibility and LAYOUT)
		assertEquals(0, window.decorView.systemUiVisibility and
			(View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY))
		assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN)
		assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
		assertEquals(Color.TRANSPARENT, window.statusBarColor)
		assertEquals(Color.TRANSPARENT, window.navigationBarColor)
		val controller = WindowCompat.getInsetsController(window, window.decorView)
		assertEquals(darkIcons, controller.isAppearanceLightStatusBars)
		assertEquals(darkIcons, controller.isAppearanceLightNavigationBars)
		assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
			window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
		if (Build.VERSION.SDK_INT >= 28) {
			assertEquals(Color.TRANSPARENT, window.navigationBarDividerColor)
			assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
				window.attributes.layoutInDisplayCutoutMode)
		}
		if (Build.VERSION.SDK_INT >= 29) {
			assertFalse(window.isStatusBarContrastEnforced)
			assertFalse(window.isNavigationBarContrastEnforced)
		}
	}

	private fun snapshot(window: Window): List<Any> {
		val controller = WindowCompat.getInsetsController(window, window.decorView)
		return listOf(window.attributes.flags and (WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
			WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
			WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION or WindowManager.LayoutParams.FLAG_SECURE),
			window.decorView.systemUiVisibility and (LAYOUT or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
				View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR), window.attributes.softInputMode,
			window.statusBarColor, window.navigationBarColor,
			controller.isAppearanceLightStatusBars, controller.isAppearanceLightNavigationBars,
			if (Build.VERSION.SDK_INT >= 28) window.attributes.layoutInDisplayCutoutMode else 0,
			if (Build.VERSION.SDK_INT >= 28) window.navigationBarDividerColor else 0,
			Build.VERSION.SDK_INT >= 29 && window.isStatusBarContrastEnforced,
			Build.VERSION.SDK_INT >= 29 && window.isNavigationBarContrastEnforced)
	}

	private companion object {
		const val LAYOUT = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
			View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
	}
}

/** A fake empty secure window, not a way to enter or bypass the application's real lock. */
class EmptySecurePinWindowActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
		PinWindowOwner(window, lifecycle, Color.WHITE)
		setContentView(FrameLayout(this))
	}
}
