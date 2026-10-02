package org.joinmastodon.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdPartyLicensesContractTest {
	private val projectDir = File(requireNotNull(System.getProperty("user.dir")))
	private val fragmentPath = "src/main/java/org/joinmastodon/android/fragments/settings/OpenSourceLicensesFragment.java"

	@Test
	fun aboutPageUsesResourceTitleWithoutQqSdkText() {
		val about = source("src/main/kotlin/org/joinmastodon/android/ui/compose/AboutPage.kt")
		assertTrue(about.contains("context.getString(R.string.open_source_licenses)"))
		assertTrue(about.contains("onClick = onOpenSourceLicenses"))
		assertFalse(about.contains("qq_sdk_disclosure"))
		assertFalse(about.contains("title = \"开放源代码许可\""))
		// Remove only the About presentation, not the shared disclosure or login consent.
		val disclosures = source("src/main/res/values/strings.xml")
		assertTrue(disclosures.contains("name=\"qq_sdk_disclosure\""))
		assertTrue(disclosures.contains("name=\"qq_consent_message\""))
		assertTrue(disclosures.contains("name=\"qq_bind_consent_message\""))
	}

	@Test
	fun localizedTitleAndFooterUseResources() {
		val zh = source("src/main/res/values-zh-rCN/strings.xml")
		val defaults = source("src/main/res/values/strings_sk.xml")
		assertTrue(zh.contains("<string name=\"open_source_licenses\">第三方开源许可</string>"))
		assertTrue(defaults.contains("<string name=\"open_source_licenses\">Third Party Licenses</string>"))
		for (strings in listOf(zh, defaults)) {
			assertTrue(strings.contains("name=\"third_party_licenses_footer\""))
			assertTrue(strings.contains("NOTICE"))
			assertTrue(strings.contains("JPush"))
			assertTrue(strings.contains("QQ"))
		}
		assertTrue(source(fragmentPath).contains("footer.setText(R.string.third_party_licenses_footer)"))
		assertFalse(source(fragmentPath).contains("footer.setText(\""))
	}

	@Test
	fun licensePageUsesMatchingFinalLayouts() {
		val fragment = source(fragmentPath)
		val page = source("src/main/res/layout/fragment_open_source_licenses.xml")
		val item = source("src/main/res/layout/item_open_source_license.xml")

		assertTrue(fragment.contains("R.id.footer_note"))
		assertTrue(fragment.contains("R.id.lib_summary"))
		assertTrue(page.contains("@drawable/scrollbar_thumb_license"))
		assertTrue(page.contains("android:id=\"@+id/footer_note\""))
		assertFalse(page.contains("header_text"))
		assertTrue(item.contains("android:id=\"@+id/lib_summary\""))
		assertTrue(item.contains("@drawable/bg_license_card"))
		assertTrue(item.contains("@drawable/ic_arrow_right_24px"))
	}

	@Test
	fun resolvedVersionsAndUpstreamLicensesAreNotFlattened() {
		val fragment = source(fragmentPath)
		for (entry in listOf(
			"\"AppKit (grishka)\", \"1.4.8\", \"Unlicense\"",
			"\"LiteX RecyclerView\", \"1.2.1.1\", \"Apache-2.0\"",
			"\"LiteX SwipeRefreshLayout\", \"1.2.0-beta01\", \"Apache-2.0\"",
			"\"LiteX Browser\", \"1.4.0\", \"Apache-2.0\"",
			"\"LiteX Concurrent\", \"1.1.0\", \"Apache-2.0\"",
			"\"LiteX DynamicAnimation\", \"1.1.0-alpha03\", \"Apache-2.0\"",
			"\"LiteX ViewPager / ViewPager2 / Palette\", \"1.0.0\", \"Apache-2.0\"",
			"\"AndroidX Room\", \"2.8.3\"",
			"\"AndroidX SQLite\", \"2.6.1\"",
			"\"AndroidX Biometric\", \"1.1.0\"",
			"\"AndroidX NavigationEvent / NavigationEvent Compose\", \"1.0.2\"",
			"\"AndroidX SavedState\", \"1.4.0\"",
			"\"MaterialKolor Material Color Utilities (Kotlin port)\", \"4.1.1\", \"MIT\"",
			"\"Jsoup\", \"1.18.3\", \"MIT\"",
			"\"Compose Material3 / Window Size Class (AndroidX)\", \"1.4.0\"",
			"\"Compose Material Icons (AndroidX)\", \"1.7.6\"",
			"\"Compose Material Ripple (AndroidX)\", \"1.8.1\"",
			"\"2FAS Security (adapted source)\", \"5.6.0\", \"GPL-3.0-only\"",
			"\"JSpecify\", \"1.0.0\", \"Apache-2.0\"",
		)) assertTrue("Missing corrected entry: $entry", fragment.contains(entry))
		assertFalse(fragment.contains("AndroidX Room / SQLite"))
		assertFalse(fragment.contains("\"LiteX (grishka)\""))
	}

	@Test
	fun sourceAndArtworkCreditsAreNotPresentedAsBinaryDependencies() {
		val fragment = source(fragmentPath)
		assertTrue(fragment.contains("AndroidLiquidGlass (adapted effects, not a binary dependency)"))
		assertTrue(fragment.contains("itshover (adapted animated icons)"))
		assertTrue(fragment.contains("Diff Match and Patch (bundled source)"))
		assertTrue(fragment.contains("FlowReader (reader-core)"))
		assertTrue(source("src/main/assets/licenses/itshover-NOTICE.txt").contains("Apache License"))
		assertTrue(source("src/main/assets/licenses/2fas-security-NOTICE.txt").contains("GPL-3.0-only"))
	}

	@Test
	fun licenseLinksRemainClickableWithoutCrashingWhenNoBrowserExists() {
		val fragment = source(fragmentPath)
		assertTrue(fragment.contains("Intent.ACTION_VIEW"))
		assertTrue(fragment.contains("Uri.parse(lib.url)"))
		assertTrue(fragment.contains("catch (ActivityNotFoundException e)"))
		assertTrue(fragment.contains("R.string.no_app_to_handle_action"))
	}

	private fun source(path: String) = File(projectDir, path).readText()
}
