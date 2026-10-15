package org.joinmastodon.android.ui.compose.navigation

import android.graphics.drawable.RotateDrawable
import android.view.ContextThemeWrapper
import android.widget.ImageButton
import android.widget.FrameLayout
import org.joinmastodon.android.CompatibilityTestApplication
import org.joinmastodon.android.MastodonApp
import org.joinmastodon.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * The home FAB must expose a level-driven rotate drawable, otherwise
 * [org.joinmastodon.android.fragments.HomeTabFragment.animateFabIcon] silently does nothing
 * (its `instanceof RotateDrawable` guard would bail) and the plus never turns into a cross.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = CompatibilityTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ComposeFabIconTest {
	@Before fun setUp() {
		MastodonApp.context = RuntimeEnvironment.getApplication()
	}

	@Test fun fabDrawableIsARotateDrawableThatHonoursTheLevelAnimation() {
		// compose_fab.xml tints with ?colorM3Primary, so it only inflates under the app theme.
		val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Mastodon_AutoLightDark)
		val root = FrameLayout(context)
		android.view.LayoutInflater.from(context).inflate(R.layout.compose_fab, root, true)
		val fab = root.findViewById<ImageButton>(R.id.fab)

		val drawable = fab.drawable
		assertTrue("FAB src must stay a RotateDrawable, was ${drawable?.javaClass}", drawable is RotateDrawable)

		val rotate = drawable as RotateDrawable
		assertEquals(0, rotate.level)
		rotate.level = 10000
		assertEquals(10000, rotate.level)
	}
}
