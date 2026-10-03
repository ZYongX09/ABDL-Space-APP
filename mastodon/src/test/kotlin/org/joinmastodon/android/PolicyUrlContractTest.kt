package org.joinmastodon.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyUrlContractTest {
	private val moduleDir = File(requireNotNull(System.getProperty("user.dir")))

	@Test
	fun canonicalPolicyUrlsAreUsedAcrossAndroidSources() {
		moduleDir.resolve("src/main").walkTopDown()
			.filter { it.isFile && it.extension.lowercase() in setOf("java", "kt", "xml") }
			.forEach { file ->
				assertFalse("legacy agreement URL in ${file.path}", file.readText().contains("https://abdl-space.top/agreement"))
			}

		val strings = source("src/main/res/values/strings.xml")
		assertTrue(strings.contains("https://abdl-space.top/terms"))
		assertTrue(strings.contains("https://abdl-space.top/privacy"))
	}

	@Test
	fun qqLoginConsentContainsFourClickablePolicyLinks() {
		val strings = source("src/main/res/values/strings.xml")
		val message = Regex("<string name=\"qq_consent_message\">(.*?)</string>").find(strings)?.groupValues?.get(1)
			?: error("qq_consent_message missing")
		assertTrue(message.startsWith("继续后将启动腾讯 QQ 应用程序进行授权登录流程。"))
		assertEquals(2, Regex("https://abdl-space.top/terms").findAll(message).count())
		assertEquals(2, Regex("https://abdl-space.top/privacy").findAll(message).count())
		assertEquals(4, Regex("&lt;a href=").findAll(message).count())
	}

	@Test
	fun loginAndBindingConsentRemainSeparateAndClickable() {
		val settings = source("src/main/java/org/joinmastodon/android/fragments/settings/SettingsAccountFragment.java")
		val email = source("src/main/java/org/joinmastodon/android/fragments/auth/LoginEmailFragment.java")
		val password = source("src/main/java/org/joinmastodon/android/fragments/auth/LoginPasswordFragment.java")
		assertTrue(settings.contains("R.string.qq_bind_consent_message"))
		assertFalse(settings.contains("R.string.qq_consent_message"))
		assertTrue(settings.contains("LinkMovementMethod.getInstance()"))
		assertTrue(email.contains("R.string.qq_consent_message"))
		assertTrue(password.contains("R.string.qq_consent_message"))
		assertFalse(email.contains("tvAgreement.setOnClickListener"))
		assertFalse(password.contains("tvAgreement.setOnClickListener"))
	}

	@Test
	fun instanceDocumentFallbackKeepsPrivacyAndTermsDistinct() {
		val source = source("src/main/java/org/joinmastodon/android/fragments/onboarding/GoogleMadeMeAddThisFragment.java")
		assertTrue(source.contains("\"https://\"+instance.getDomain()+\"/privacy\""))
		assertTrue(source.contains("\"https://\"+instance.getDomain()+\"/terms\""))
	}

	private fun source(path: String) = File(moduleDir, path).readText()
}
