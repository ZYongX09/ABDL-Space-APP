package org.joinmastodon.android

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.ContextThemeWrapper
import android.view.View
import org.joinmastodon.android.ui.views.ItshoverNavigationIconView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28, 30, 31, 32, 33], application = CompatibilityTestApplication::class)
class ItshoverNavigationIconViewCompatibilityTest {
	@Test fun constructionWithoutAttributesKeepsDefaultsAndAllowsColorUpdates() {
		// Exercise the real constructor and framework TypedArray, including pre-31 APIs
		// where TypedArray does not implement AutoCloseable.
		val view = ItshoverNavigationIconView(RuntimeEnvironment.getApplication())

		assertEquals(ItshoverNavigationIconView.ICON_HOME, view.iconType)
		assertFalse(view.isClickable)
		assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, view.importantForAccessibility)
		assertDrawColor(view, Color.BLACK)

		view.setIconColor(Color.MAGENTA)
		assertDrawColor(view, Color.MAGENTA)
	}

	@Test fun constructionReadsIconTypeAndLiteralTintAttributes() {
		val icons = mapOf(
			"home" to ItshoverNavigationIconView.ICON_HOME,
			"magnifier" to ItshoverNavigationIconView.ICON_MAGNIFIER,
			"star" to ItshoverNavigationIconView.ICON_STAR,
			"globe" to ItshoverNavigationIconView.ICON_GLOBE,
		)
		for ((name, type) in icons) {
			val attrs = Robolectric.buildAttributeSet()
				.addAttribute(R.attr.iconType, name)
				.addAttribute(R.attr.iconTint, "#123456")
				.build()
			val view = ItshoverNavigationIconView(RuntimeEnvironment.getApplication(), attrs, 0)

			assertEquals(type, view.iconType)
			assertDrawColor(view, Color.rgb(0x12, 0x34, 0x56))
		}
	}

	@Test fun constructionPreservesResourceTintForSelectedAndDefaultStates() {
		val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Mastodon_Light)
		val attrs = Robolectric.buildAttributeSet()
			.addAttribute(R.attr.iconType, "star")
			.addAttribute(R.attr.iconTint, "@color/tab_bar_icon")
			.build()
		val view = ItshoverNavigationIconView(context, attrs)
		val tint = context.getColorStateList(R.color.tab_bar_icon)
		val selectedColor = tint.getColorForState(intArrayOf(android.R.attr.state_selected), tint.defaultColor)

		assertTrue(tint.isStateful)
		assertTrue(selectedColor != tint.defaultColor)
		assertEquals(ItshoverNavigationIconView.ICON_STAR, view.iconType)
		assertDrawColor(view, tint.defaultColor)
		view.isSelected = true
		assertDrawColor(view, selectedColor)
		view.isSelected = false
		assertDrawColor(view, tint.defaultColor)
	}

	private fun assertDrawColor(view: ItshoverNavigationIconView, color: Int) {
		val canvas = RecordingCanvas()
		view.layout(0, 0, 48, 48)
		view.draw(canvas)
		assertTrue("The actual View must draw its icon", canvas.colors.isNotEmpty())
		assertTrue("Every icon stroke must use the resolved tint", canvas.colors.all { it == color })
	}

	private class RecordingCanvas : Canvas() {
		val colors = mutableListOf<Int>()

		override fun drawPath(path: Path, paint: Paint) {
			colors.add(paint.color)
		}

		override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
			colors.add(paint.color)
		}
	}
}
