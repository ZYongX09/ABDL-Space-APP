package org.joinmastodon.android

import java.io.File
import java.util.jar.JarFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QQLoginContractTest {
	private val moduleDir = File(requireNotNull(System.getProperty("user.dir")))

	@Test
	fun bothActualLoginLayoutsExposeQqButton() {
		listOf("fragment_login_email.xml", "fragment_login_password.xml").forEach { name ->
			val layout = File(moduleDir, "src/main/res/layout/$name").readText()
			assertTrue("$name must expose btn_qq", layout.contains("android:id=\"@+id/btn_qq\""))
			assertTrue("$name must use the dedicated QQ icon", layout.contains("@drawable/ic_qq_login"))
		}
	}

	@Test
	fun qqUnboundFlowOnlyReturnsToLogin() {
		val source = File(moduleDir, "src/main/java/org/joinmastodon/android/QQAuthActivity.java").readText()
		assertTrue(source.contains("\"not_bound\".equals(action)"))
		assertTrue(source.contains("R.string.qq_not_bound"))
		assertFalse(source.contains("RegisterInfoFragment"))
		assertFalse(source.contains("NBWOneClickRegisterActivity"))
	}

	@Test
	fun gradleAndManifestsKeepApprovedVariantBoundary() {
		val gradle = File(moduleDir, "build.gradle").readText()
		assertTrue(gradle.contains("buildConfigField \"String\", \"QQ_APP_ID\", '\"1905661071\"'"))
		assertEquals(2, Regex("buildConfigField \\\"boolean\\\", \\\"QQ_LOGIN_ENABLED\\\", \\\"true\\\"").findAll(gradle).count())
		assertEquals(6, Regex("buildConfigField \\\"boolean\\\", \\\"QQ_LOGIN_ENABLED\\\", \\\"false\\\"").findAll(gradle).count())
		assertTrue(gradle.contains("implementation files('libs/open_sdk_3.5.19_r9483ffc7_lite.jar')"))

		listOf("src/debug/AndroidManifest.xml", "src/release/AndroidManifest.xml").forEach { path ->
			val manifest = File(moduleDir, path).readText()
			assertTrue(manifest.contains("com.tencent.tauth.AuthActivity"))
			assertTrue(manifest.contains("android:exported=\"true\""))
			assertTrue(manifest.contains("android:launchMode=\"singleTask\""))
			assertTrue(manifest.contains("android:noHistory=\"true\""))
			assertTrue(manifest.contains("android:scheme=\"tencent1905661071\""))
			assertTrue(manifest.contains("com.tencent.connect.common.AssistActivity"))
			assertTrue(manifest.contains("android:exported=\"false\""))
			assertFalse(manifest.contains("com.qzone"))
			listOf("com.tencent.mobileqq", "com.tencent.tim", "com.tencent.minihd.qq", "com.tencent.qqlite").forEach {


				assertTrue(manifest.contains("android:name=\"$it\""))
			}
		}
	}

	@Test
	fun bindingAlwaysUsesExplicitAccountId() {
		val auth = File(moduleDir, "src/main/java/org/joinmastodon/android/QQAuthActivity.java").readText()
		val settings = File(moduleDir, "src/main/java/org/joinmastodon/android/fragments/settings/SettingsAccountFragment.java").readText()
		assertTrue(settings.contains("putExtra(QQAuthActivity.EXTRA_ACCOUNT_ID, accountID)"))
		assertTrue(settings.contains(".header(\"Authorization\", \"Bearer \"+session.token.accessToken)"))
		assertTrue(settings.contains("super.onActivityResult(requestCode, resultCode, data)"))
		assertTrue(auth.contains("tryGetAccount(accountID)"))
		assertFalse(auth.contains("getLastActiveAccount"))
	}

	@Test
	fun sdkCredentialsStayEphemeralAndOnlyAbdlTokensAreInstalled() {
		val source = File(moduleDir, "src/main/java/org/joinmastodon/android/QQAuthActivity.java").readText()
		assertTrue(source.contains("json.optString(\"code\", null)"))
		assertTrue(source.contains("json.optString(\"access_token\", null)"))
		assertTrue(source.contains("String abdlToken=json.optString(\"token\", null)"))
		assertTrue(source.contains("AuthSessionInstaller.install(this, abdlToken"))
		assertFalse(source.contains("putString("))
		assertFalse(source.contains("Log."))
		assertFalse(source.contains("System.out"))
	}

	@Test
	fun repositoryDoesNotContainQqAppKeyMaterial() {
		val textExtensions = setOf("java", "kt", "kts", "gradle", "xml", "properties", "json", "txt")
		moduleDir.walkTopDown()
			.filter { it.isFile && it.extension.lowercase() in textExtensions && !it.path.contains("${File.separator}build${File.separator}") }
			.forEach { file ->
					val text = file.readText()
					assertFalse("QQ App Key value must not be committed: ${file.path}", Regex("(?im)^\\s*(?:QQ_ANDROID_APP_KEY|QQ_APP_KEY|qq[_ -]?app[_ -]?key)\\s*[:=]\\s*['\\\"]?[^#\\s'\\\"]+").containsMatchIn(text))

			}
	}

	@Test
	fun bundledSdkIsOnlyTheApprovedLiteJar() {
		val libs = File(moduleDir, "libs").listFiles()?.filter { it.isFile } ?: emptyList()
		assertTrue(libs.any { it.name == "open_sdk_3.5.19_r9483ffc7_lite.jar" })
		assertFalse(libs.any { it.extension.equals("apk", ignoreCase = true) })
		val sdkJar = File(moduleDir, "libs/open_sdk_3.5.19_r9483ffc7_lite.jar")
		JarFile(sdkJar).use { jar ->
			assertTrue(jar.getEntry("com/tencent/tauth/Tencent.class") != null)
		}
		val digest = java.security.MessageDigest.getInstance("SHA-256").digest(sdkJar.readBytes())
		val sha256 = digest.joinToString("") { "%02x".format(it) }
		assertEquals("9d57fe61ff9026d34ac84bc63dc719f61da6aa40533a299cc6f73d4ce9df7af8", sha256)
	}
}
