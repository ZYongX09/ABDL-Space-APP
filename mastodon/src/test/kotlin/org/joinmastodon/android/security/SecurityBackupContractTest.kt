package org.joinmastodon.android.security

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityBackupContractTest {
	private val moduleDir = File(requireNotNull(System.getProperty("user.dir")))

	@Test
	fun applicationKeepsBackupEnabledAndReferencesBothSecurityBackupRules() {
		val application = xml("src/main/AndroidManifest.xml")
			.getElementsByTagName("application")
			.item(0)
		assertNotNull(application)
		val attributes = application.attributes

		assertEquals("true", attributes.getNamedItem("android:allowBackup").nodeValue)
		assertEquals("@xml/backup_rules", attributes.getNamedItem("android:fullBackupContent").nodeValue)
		assertEquals(
			"@xml/data_extraction_rules",
			attributes.getNamedItem("android:dataExtractionRules").nodeValue,
		)
	}

	@Test
	fun bothRuleFilesExcludeTheDedicatedSecurityPreferencesFile() {
		val legacy = source("src/main/res/xml/backup_rules.xml")
		val modern = source("src/main/res/xml/data_extraction_rules.xml")
		val expected = "<exclude domain=\"sharedpref\" path=\"abdl_space_security.xml\" />"

		assertTrue(legacy.contains(expected))
		assertEquals(2, modern.windowed(expected.length).count { it == expected })
		assertTrue(modern.contains("<cloud-backup>"))
		assertTrue(modern.contains("<device-transfer>"))
	}

	private fun xml(path: String) = DocumentBuilderFactory.newInstance()
		.newDocumentBuilder()
		.parse(File(moduleDir, path))

	private fun source(path: String) = File(moduleDir, path).readText()
}
