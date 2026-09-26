package org.joinmastodon.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdPartyLicensesContractTest {
	private val projectDir = File(requireNotNull(System.getProperty("user.dir")))

	@Test
	fun aboutPageUsesResourceTitleAndQqDisclosure() {
		val about = source("src/main/kotlin/org/joinmastodon/android/ui/compose/AboutPage.kt")
		assertTrue(about.contains("context.getString(R.string.open_source_licenses)"))
		assertTrue(about.contains("context.getString(R.string.qq_sdk_disclosure)"))
		assertFalse(about.contains("title = \"开放源代码许可\""))
	}

	@Test
	fun localizedTitleUsesThirdPartyLicenseWording() {
		val zh = source("src/main/res/values-zh-rCN/strings.xml")
		val defaults = source("src/main/res/values/strings_sk.xml")
		assertTrue(zh.contains("<string name=\"open_source_licenses\">第三方开源许可</string>"))
		assertTrue(defaults.contains("<string name=\"open_source_licenses\">Third Party Licenses</string>"))
	}

	@Test
	fun licensePageUsesMatchingFinalLayouts() {
		val fragment = source("src/main/java/org/joinmastodon/android/fragments/settings/OpenSourceLicensesFragment.java")
		val page = source("src/main/res/layout/fragment_open_source_licenses.xml")
		val item = source("src/main/res/layout/item_open_source_license.xml")

		assertTrue(fragment.contains("R.id.footer_note"))
		assertTrue(fragment.contains("R.id.lib_summary"))
		assertTrue(fragment.contains("FlowReader (reader-core)"))
		assertTrue(fragment.contains("Kotlin"))
		assertTrue(fragment.contains("AndroidX Room / SQLite"))
		assertTrue(page.contains("@drawable/scrollbar_thumb_license"))
		assertTrue(page.contains("android:id=\"@+id/footer_note\""))
		assertFalse(page.contains("header_text"))
		assertTrue(item.contains("android:id=\"@+id/lib_summary\""))
		assertTrue(item.contains("@drawable/bg_license_card"))
		assertTrue(item.contains("@drawable/ic_arrow_right_24px"))
	}

	@Test
	fun licenseLinksRemainClickable() {
		val fragment = source("src/main/java/org/joinmastodon/android/fragments/settings/OpenSourceLicensesFragment.java")
		assertTrue(fragment.contains("Intent.ACTION_VIEW"))
		assertTrue(fragment.contains("Uri.parse(lib.url)"))
	}

	private fun source(path: String) = File(projectDir, path).readText()
}
