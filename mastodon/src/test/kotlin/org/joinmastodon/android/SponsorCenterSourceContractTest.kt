package org.joinmastodon.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SponsorCenterSourceContractTest {
	private val projectDir = File(requireNotNull(System.getProperty("user.dir")))

	@Test
	fun originalBenefitHasNoDisplaySettingsAction() {
		val source = File(projectDir, "src/main/java/org/joinmastodon/android/fragments/sponsors/SponsorCenterFragment.java").readText()
		assertFalse(source.contains("else if(\"original\".equals(benefit.action))"))
		assertFalse(source.contains("openDisplaySettings"))
	}

	@Test
	fun colorAndClaimActionsRemainAvailable() {
		val source = File(projectDir, "src/main/java/org/joinmastodon/android/fragments/sponsors/SponsorCenterFragment.java").readText()
		assertTrue(source.contains("if(\"color\".equals(benefit.action))"))
		assertTrue(source.contains("this::openColorPage"))
		assertTrue(source.contains("else if(\"claim\".equals(benefit.action) && \"available\".equals(benefit.status))"))
		assertTrue(source.contains("SponsorRequest.claim(benefit.id"))
	}
}
