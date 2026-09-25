package org.joinmastodon.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationThemeSemanticContractTest {
	private val projectDir = File(requireNotNull(System.getProperty("user.dir")))

	@Test
	fun verificationThemeDoesNotReferenceFixedBluePalette() {
		val styles = File(projectDir, "src/main/res/values/styles.xml").readText()
		assertFalse(styles.contains("@color/verification_light_"))
		assertFalse(styles.contains("@color/verification_dark_"))
		for (token in listOf(
			"?colorM3Background", "?colorM3Surface", "?colorM3SurfaceVariant",
			"?colorM3Primary", "?colorM3OnPrimary", "?colorM3OnSurface",
			"?colorM3OnSurfaceVariant", "?colorM3Outline", "?colorM3OutlineVariant",
			"?colorM3SecondaryContainer",
		)) assertTrue("missing semantic token $token", styles.contains(token))
	}

	@Test
	fun unavailablePhotoCheckHasDifferentUserFacingCopy() {
		val fragment = File(projectDir, "src/main/java/org/joinmastodon/android/fragments/settings/BabyVerificationFragment.java").readText()
		assertTrue(fragment.contains("verification_photo_check_unavailable"))
		assertTrue(fragment.contains("verification_unavailable"))
	}
}
