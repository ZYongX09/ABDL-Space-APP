package org.joinmastodon.android.ui.compose.navigation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the non-liquid home compose fan spec: a rotating plus, options unfolding upwards 60ms
 * apart with their labels fading in from the right, and a reverse-order fold.
 */
class ComposeFanMenuContractTest {
	private val projectDir = File(requireNotNull(System.getProperty("user.dir")))

	@Test
	fun optionsUnfoldUpwardsInSequenceAndFoldBackInReverse() {
		val menu = source("src/main/kotlin/org/joinmastodon/android/ui/compose/navigation/ComposeFanMenu.kt")

		assertTrue(menu.contains("private const val STAGGER_MS = 60L"))
		assertTrue(menu.contains("private const val LABEL_DELAY_MS = 90L"))
		assertTrue(menu.contains("private const val LABEL_SLIDE_DP = 24"))
		// Opening: index 0 (nearest the FAB) starts first, springy.
		assertTrue(menu.contains("delay(if (reduceMotion) 0 else index * STAGGER_MS)"))
		assertTrue(menu.contains("spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow)"))
		// Labels follow their circle a little later and slide in from the right.
		assertTrue(menu.contains("delay(if (reduceMotion) 0 else index * STAGGER_MS + LABEL_DELAY_MS)"))
		assertTrue(menu.contains("translationX = LABEL_SLIDE_DP * (1f - label.value) * density"))
		// Closing: the option furthest from the FAB leaves first.
		assertTrue(menu.contains("val delayMs = if (reduceMotion) 0L else (count - 1 - index) * STAGGER_MS"))
		// The page behind is dimmed and the dimmed area dismisses.
		assertTrue(menu.contains("Color.Black.copy(alpha = SCRIM_ALPHA * scrim.value)"))
		assertTrue(menu.contains("detectTapGestures { onDismiss() }"))
		// Rendered bottom-up so index 0 sits closest to the FAB.
		assertTrue(menu.contains("for (index in items.indices.reversed())"))
		assertTrue(menu.contains("ComposeFanMenuItem(R.id.compose_post"))
		assertTrue(menu.contains("ComposeFanMenuItem(R.id.compose_friend_request"))
		assertTrue(menu.contains("ComposeFanMenuItem(R.id.compose_album"))
	}

	@Test
	fun fabIsAPlusThatRotatesIntoACrossAndStaysAboveTheOverlay() {
		val drawable = source("src/main/res/drawable/compose_fab_icon.xml")
		assertTrue(drawable.contains("android:drawable=\"@drawable/ic_add_24px\""))
		assertTrue(drawable.contains("android:fromDegrees=\"0\""))
		assertTrue(drawable.contains("android:toDegrees=\"45\""))
		assertTrue(drawable.contains("android:pivotX=\"50%\""))
		assertTrue(drawable.contains("android:pivotY=\"50%\""))

		val fabLayout = source("src/main/res/layout/compose_fab.xml")
		assertTrue(fabLayout.contains("android:src=\"@drawable/compose_fab_icon\""))
		assertFalse(fabLayout.contains("@drawable/ic_fluent_compose_24_filled"))

		val homeTab = source("src/main/java/org/joinmastodon/android/fragments/HomeTabFragment.java")
		assertTrue(homeTab.contains("new ComposeFanMenuController(getContext(), this::openComposeAction, this::onFanMenuExpandedChanged)"))
		assertTrue(homeTab.contains("ValueAnimator.ofInt(((RotateDrawable) drawable).getLevel(), open ? 10000 : 0)"))
		// The FAB must out-rank the overlay so the cross stays tappable.
		assertTrue(homeTab.contains("fab.setTranslationZ(V.dp(2))"))
		assertTrue(homeTab.contains("fanMenu.getView().setTranslationZ(V.dp(1))"))
		// Back gesture, liquid switch and FAB hiding all fold the fan first.
		assertTrue(homeTab.contains("addBackCallback(collapseFanMenuOnBack)"))
		assertTrue(homeTab.contains("fanMenu.setBottomInset(insetPx)"))
		assertFalse(homeTab.contains("new ComposeActionSheet(getActivity(), this::openComposeAction).show()"))
	}

	private fun source(path: String) = File(projectDir, path).readText()
}
