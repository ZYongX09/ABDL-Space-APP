package org.joinmastodon.android

import android.app.Activity
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.joinmastodon.android.ui.MediaCameraActivity
import org.joinmastodon.android.ui.views.SpaceBackgroundView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28, 30, 31, 35], application = CompatibilityTestApplication::class)
class LegacyWindowCompatibilityTest {
	@Test fun cameraActivityCreatesAndAcceptsInsetsWithoutApi30Calls() {
		val controller = Robolectric.buildActivity(MediaCameraActivity::class.java).create()
		val activity = controller.get()
		val top = activity.findViewById<View>(R.id.camera_top_controls)
		val insets = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0)).build()
		ViewCompat.onApplyWindowInsets(top, insets)
		assertEquals(24 + (12 * activity.resources.displayMetrics.density).toInt(), top.paddingTop)
		controller.destroy()
	}

	@Test fun spaceBackgroundAcceptsLegacyInsets() {
		val controller = Robolectric.buildActivity(Activity::class.java).create()
		val view = SpaceBackgroundView(controller.get())
		val insets = WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0)).build()
		ViewCompat.onApplyWindowInsets(view, insets)
		assertEquals(24, view.paddingTop)
		controller.destroy()
	}
}
