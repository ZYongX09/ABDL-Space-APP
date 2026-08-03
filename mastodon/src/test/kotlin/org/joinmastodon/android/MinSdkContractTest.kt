package org.joinmastodon.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MinSdkContractTest {
	private val projectDir = File(requireNotNull(System.getProperty("user.dir")))
	private val buildGradle = File(projectDir, "build.gradle").readText()
	private val manifest = File(projectDir, "src/main/AndroidManifest.xml").readText()

	@Test
	fun requiresAndroid13OrNewer() {
		assertTrue(buildGradle.contains(Regex("""\bminSdk\s+33\b""")))
	}

	@Test
	fun doesNotDeclareReadExternalStorage() {
		assertFalse(manifest.contains("android.permission.READ_EXTERNAL_STORAGE"))
	}

	@Test
	fun doesNotDeclareWriteExternalStorage() {
		assertFalse(manifest.contains("android.permission.WRITE_EXTERNAL_STORAGE"))
	}
}
