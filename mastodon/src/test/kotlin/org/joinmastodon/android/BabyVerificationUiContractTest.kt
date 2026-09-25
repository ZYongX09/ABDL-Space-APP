package org.joinmastodon.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class BabyVerificationUiContractTest {
	private val projectDir = File(requireNotNull(System.getProperty("user.dir")))

	@Test
	fun verificationPageKeepsTheDesignedStructure() {
		val layout = parse("src/main/res/layout/fragment_baby_verification.xml")
		for (id in listOf(
			"verification_progress_steps",
			"verification_loading_state",
			"verification_form_card",
			"verification_message_card",
			"verification_security_card",
			"verification_qq_input",
			"verification_start_button",
		)) {
			assertNotNull("missing @$id", elementWithId(layout.documentElement, id))
		}
		val form = requireNotNull(elementWithId(layout.documentElement, "verification_form_card"))
		assertEquals("@drawable/bg_verification_card", form.getAttributeNS(ANDROID_NS, "background"))
		val input = requireNotNull(elementWithId(layout.documentElement, "verification_qq_input"))
		assertEquals("false", input.getAttributeNS(ANDROID_NS, "saveEnabled"))
		assertEquals("20", input.getAttributeNS(ANDROID_NS, "maxLength"))
		val fragment = File(projectDir, "src/main/java/org/joinmastodon/android/fragments/settings/BabyVerificationFragment.java").readText()
		assertTrue(fragment.contains("qqInput.setSaveFromParentEnabled(false)"))
		assertTrue(fragment.contains("adult.setSaveFromParentEnabled(false)"))
	}

	@Test
	fun verificationThemeUsesGlobalMd3SemanticTokens() {
		val attrs = File(projectDir, "src/main/res/values/attrs.xml").readText()
		val styles = File(projectDir, "src/main/res/values/styles.xml").readText()
		for (name in listOf(
			"colorVerificationBackground",
			"colorVerificationCardSurface",
			"colorVerificationPrimary",
			"colorVerificationTextPrimary",
			"colorVerificationSecurityContainer",
		)) {
			assertTrue(attrs.contains("name=\"$name\""))
		}
		for (token in listOf(
			"?colorM3Background",
			"?colorM3Surface",
			"?colorM3OutlineVariant",
			"?colorM3SurfaceVariant",
			"?colorM3Outline",
			"?colorM3Primary",
			"?colorM3OnPrimary",
			"?colorM3OnSurface",
			"?colorM3OnSurfaceVariant",
			"?colorM3SecondaryContainer",
		)) {
			assertTrue("missing global token $token", styles.contains(token))
		}
		assertFalse(styles.contains("@color/verification_light_"))
		assertFalse(styles.contains("@color/verification_dark_"))
	}

	@Test
	fun verificationProgressIsOneAccessibleNode() {
		val source = File(projectDir, "src/main/java/org/joinmastodon/android/ui/views/BabyVerificationProgressView.java").readText()
		assertTrue(source.contains("setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES)"))
		assertTrue(source.contains("verification_progress_current"))
		assertTrue(source.contains("verification_progress_complete"))
	}

	private fun parse(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
		.newDocumentBuilder().parse(File(projectDir, path))

	private fun elementWithId(root: Element, id: String): Element? {
		if (root.getAttributeNS(ANDROID_NS, "id") == "@+id/$id" || root.getAttributeNS(ANDROID_NS, "id") == "@id/$id") return root
		val children = root.childNodes
		for (index in 0 until children.length) {
			val child = children.item(index)
			if (child is Element) elementWithId(child, id)?.let { return it }
		}
		return null
	}

	companion object {
		private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
	}
}
