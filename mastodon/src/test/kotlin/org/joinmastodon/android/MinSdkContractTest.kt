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
	fun supportsAndroid80OrNewer() {
		assertTrue(buildGradle.contains(Regex("""\bminSdk\s+26\b""")))
	}

	@Test
	fun explicitlyEnablesCoreLibraryDesugaring() {
		assertTrue(buildGradle.contains(Regex("""coreLibraryDesugaringEnabled\s+true""")))
		assertTrue(buildGradle.contains("coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:"))
	}

	@Test
	fun declaresLegacyStoragePermissions() {
		// Android 12 及以下仍需传统存储权限（分区存储权限模型只覆盖 13+）
		assertTrue(manifest.contains(Regex("""android.permission.READ_EXTERNAL_STORAGE"[^>]*android:maxSdkVersion="32""")))
		assertTrue(manifest.contains(Regex("""android.permission.WRITE_EXTERNAL_STORAGE"[^>]*android:maxSdkVersion="28""")))
	}
}
