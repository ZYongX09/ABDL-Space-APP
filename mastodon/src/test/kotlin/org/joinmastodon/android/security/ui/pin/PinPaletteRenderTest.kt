package org.joinmastodon.android.security.ui.pin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.GlobalUserPreferences
import org.joinmastodon.android.MastodonApp
import org.joinmastodon.android.R
import org.joinmastodon.android.api.session.AccountLocalPreferences
import org.joinmastodon.android.ui.compose.MiuixAppTheme
import org.joinmastodon.android.ui.utils.UiUtils
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import top.yukonga.miuix.kmp.basic.TextButton
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Native rendering of the shared, EMPTY PIN UI. Never launches the app lock or enters a PIN. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 32], qualifiers = "w393dp-h852dp-mdpi", application = CompatibilityTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PinPaletteRenderTest {
	@get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
	private val previousTheme = GlobalUserPreferences.theme
	private val previousColor = GlobalUserPreferences.color
	private var contentView: View? = null

	@Before fun setup() {
		MastodonApp.context = RuntimeEnvironment.getApplication()
		// The isolated test Application does not run GlobalUserPreferences.load().
		GlobalUserPreferences.color = AccountLocalPreferences.ColorPreference.PURPLE
		compose.activityRule.scenario.onActivity {
			it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
		}
	}

	@After fun cleanup() {
		GlobalUserPreferences.theme = previousTheme
		GlobalUserPreferences.color = previousColor
	}

	@Test fun emptyPinUsesRealMaterialColorsInBothThemesAndPaintsBehindSafeAreas() {
		val dark = mutableStateOf(false)
		var palette: PinPalette? = null
		setTheme(false)
		compose.setContent {
			key(dark.value) {
				MiuixAppTheme {
					contentView = LocalView.current
					palette = pinPalette()
					PinScaffold {
						PinScreen(message = "PIN", errorMessage = "Test error", digits = 6, enteredCount = 0,
							state = PinScreenState.Default, isEnabled = true, showBiometrics = true,
							onDigit = { error("Empty rendering test must never enter a PIN") }, onBackspace = {},
							contentInsets = WindowInsets(left = 16, top = 24, right = 16, bottom = 48),
							footer = { TextButton(text = "Options", onClick = {}) })
					}
				}
			}
		}
		val backgrounds = mutableListOf<Int>()
		for (isDark in listOf(false, true)) {
			compose.runOnIdle { setTheme(isDark); dark.value = isDark }
			compose.waitForIdle()
			val colors = requireNotNull(palette)
			fun theme(attribute: Int) = UiUtils.getThemeColor(compose.activity, attribute)
			assertEquals(theme(R.attr.colorM3Background), colors.background.toArgb())
			assertEquals(theme(R.attr.colorM3OnBackground), colors.foreground.toArgb())
			assertEquals(theme(R.attr.colorM3OnSurfaceVariant), colors.secondary.toArgb())
			assertEquals(theme(R.attr.colorM3Primary), colors.accent.toArgb())
			assertEquals(theme(R.attr.colorM3SecondaryContainer), colors.tile.toArgb())
			assertEquals(theme(R.attr.colorM3Outline), colors.emptyDot.toArgb())
			assertEquals(theme(R.attr.colorM3Error), colors.error.toArgb())
			backgrounds.add(colors.background.toArgb())
			compose.onNodeWithText("PIN").performScrollTo().assertIsDisplayed()
			val mode = if (isDark) "dark" else "light"
			val top = captureRoot()
			assertEquals(colors.background.toArgb(), top.getPixel(2, 2))
			assertEquals(colors.background.toArgb(), top.getPixel(2, top.height - 2))
			assertInk(top, compose.onNodeWithText("PIN").fetchSemanticsNode().boundsInRoot, colors.foreground.toArgb())
			assertInk(top, compose.onNodeWithText("Test error").fetchSemanticsNode().boundsInRoot, colors.error.toArgb())
			val progress = compose.activity.getString(R.string.security_a11y_pin_progress, 0, 6)
			val dots = compose.onNodeWithContentDescription(progress).fetchSemanticsNode().boundsInRoot
			assertInk(top, dots, colors.emptyDot.toArgb())
			save(top, "$mode-empty-header")
			compose.onNodeWithText("Options").performScrollTo().assertIsDisplayed()
			compose.onNodeWithText("0").assertIsDisplayed()
			val bottom = captureRoot()
			assertInk(bottom, compose.onNodeWithText("0").fetchSemanticsNode().boundsInRoot, colors.foreground.toArgb())
			assertInk(bottom, compose.onNodeWithText("Options").fetchSemanticsNode().boundsInRoot, theme(R.attr.colorM3OnSecondaryContainer))
			save(bottom, "$mode-empty-keyboard")
			assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
		}
		assertNotEquals("Both theme captures must really change colors", backgrounds[0], backgrounds[1])
	}

	@Config(sdk = [28, 32], qualifiers = "w852dp-h393dp-mdpi")
	@Test fun landscapeLargeTextAndImeKeepTheLastKeyAndFooterReachableInsideSafeAreas() {
		setTheme(false)
		val bottomInset = mutableStateOf(48)
		compose.setContent {
			MiuixAppTheme {
				val density = LocalDensity.current.density
				CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
					PinScaffold {
						PinScreen(message = "Empty PIN screen", errorMessage = "", digits = 6, enteredCount = 0,
							state = PinScreenState.Default, isEnabled = true, showBiometrics = true,
							onDigit = { error("No PIN input is allowed in this test") }, onBackspace = {},
							contentInsets = WindowInsets(left = 32, top = 24, right = 48, bottom = bottomInset.value),
							footer = { TextButton(text = "Options", onClick = {}) })
					}
				}
			}
		}
		for (imeBottom in listOf(48, 160, 48)) {
			compose.runOnIdle { bottomInset.value = imeBottom }
			compose.onNodeWithText("0").performScrollTo().assertIsDisplayed()
			assertSafe(compose.onNodeWithText("0").fetchSemanticsNode().boundsInRoot, imeBottom)
			compose.onNodeWithText("Options").performScrollTo().assertIsDisplayed()
			assertSafe(compose.onNodeWithText("Options").fetchSemanticsNode().boundsInRoot, imeBottom)
		}
	}

	private fun assertSafe(bounds: Rect, bottom: Int) {
		val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
		assertTrue(bounds.left >= 32f && bounds.right <= root.right - 48f)
		assertTrue(bounds.top >= 24f && bounds.bottom <= root.bottom - bottom + 1f)
	}

	private fun setTheme(dark: Boolean) {
		GlobalUserPreferences.theme = if (dark) GlobalUserPreferences.ThemePreference.DARK else GlobalUserPreferences.ThemePreference.LIGHT
		UiUtils.setUserPreferredTheme(compose.activity)
	}

	private fun captureRoot(): Bitmap {
		lateinit var bitmap: Bitmap
		val root = compose.onRoot().fetchSemanticsNode()
		compose.runOnIdle {
			val view = requireNotNull(contentView)
			val size = root.boundsInRoot
			bitmap = Bitmap.createBitmap(size.width.roundToInt(), size.height.roundToInt(), Bitmap.Config.ARGB_8888)
			view.draw(Canvas(bitmap))
		}
		return bitmap
	}

	private fun assertInk(bitmap: Bitmap, bounds: Rect, color: Int) {
		var count = 0
		for (y in bounds.top.roundToInt().coerceAtLeast(0) until bounds.bottom.roundToInt().coerceAtMost(bitmap.height)) {
			for (x in bounds.left.roundToInt().coerceAtLeast(0) until bounds.right.roundToInt().coerceAtMost(bitmap.width)) {
				val pixel = bitmap.getPixel(x, y)
				if (Color.alpha(pixel) > 240 && abs(Color.red(pixel) - Color.red(color)) <= 4 &&
					abs(Color.green(pixel) - Color.green(color)) <= 4 && abs(Color.blue(pixel) - Color.blue(color)) <= 4) count++
			}
		}
		assertTrue("Expected theme-colored ink in $bounds", count > 3)
	}

	private fun save(bitmap: Bitmap, name: String) {
		val directory = File(System.getProperty("abdl.pin.captures",
			"/home/ZYongX/projects/abdl-android-26-32-evidence/pin-native"))
		assertTrue(directory.isDirectory || directory.mkdirs())
		val output = File(directory, "api-${android.os.Build.VERSION.SDK_INT}-$name.png")
		output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
		assertTrue(output.length() > 0)
	}
}
