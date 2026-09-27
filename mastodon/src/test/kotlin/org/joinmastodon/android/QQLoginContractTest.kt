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
	fun qqLoginStartsMainActivityBeforeSuccessListenerCanFinishAuthActivity() {
		val installer = File(moduleDir, "src/main/java/org/joinmastodon/android/auth/AuthSessionInstaller.java").readText()
		val startMain = installer.indexOf("activity.startActivity(intent);")
		val notifyInstalled = installer.indexOf("listener.onInstalled(installedSession.getID())")
		assertTrue("MainActivity must be started before the listener can finish QQAuthActivity", startMain >= 0 && notifyInstalled > startMain)
		assertTrue(installer.contains("Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK"))
	}

	@Test
	fun accountSettingsUseFixedDescriptionsAndBindingSpecificConsent() {
		val strings = File(moduleDir, "src/main/res/values/strings.xml").readText()
		val settings = File(moduleDir, "src/main/java/org/joinmastodon/android/fragments/settings/SettingsAccountFragment.java").readText()
		assertTrue(strings.contains("<string name=\"verification_description\">认证宝宝身份 获得专属证书</string>"))
		assertTrue(strings.contains("<string name=\"qq_account_description\">授权绑定腾讯QQ账户</string>"))
		assertTrue(strings.contains("<string name=\"qq_bind_consent_title\">绑定腾讯 QQ 账号</string>"))
		assertTrue(strings.contains("继续后将启动腾讯 QQ 应用程序进行授权流程。ABDL Space将会从腾讯 QQ 获取您的用户ID、用户昵称、头像和用于兼容性诊断的设备型号。"))
		assertTrue(strings.contains("<string name=\"qq_consent_message\">继续后将启动腾讯 QQ 应用程序进行授权登录流程。"))
		assertTrue(settings.contains("R.string.verification_description"))
		assertTrue(settings.contains("R.string.qq_account_description"))
		assertTrue(settings.contains("R.string.qq_bind_consent_title"))
		assertTrue(settings.contains("R.string.qq_bind_consent_message"))
		assertTrue(settings.contains("QQBindingState.UNKNOWN"))
		assertTrue(settings.contains("QQBindingState.BOUND"))
		assertTrue(settings.contains("QQBindingState.UNBOUND"))
		assertTrue(settings.contains("qqItem.subtitle=state==QQBindingState.BOUND ? nickname"))
		assertTrue(settings.contains("verificationItem.subtitle=verificationSubtitle(account)"))
		assertTrue(settings.contains("R.string.verification_account_approved"))
		assertFalse(settings.contains("refreshVerificationStatus()"))
	}

	@Test
	fun listItemsResetIconTintAcrossRecycledRows() {
		val model = File(moduleDir, "src/main/java/org/joinmastodon/android/model/viewmodel/ListItem.java").readText()
		val holder = File(moduleDir, "src/main/java/org/joinmastodon/android/ui/viewholders/ListItemViewHolder.java").readText()
		val settings = File(moduleDir, "src/main/java/org/joinmastodon/android/fragments/settings/SettingsAccountFragment.java").readText()
		assertTrue(model.contains("public boolean iconTintEnabled=true"))
		assertTrue(holder.contains("defaultIconTint=icon==null ? null : icon.getImageTintList()"))
		assertTrue(holder.contains("if(icon==null) return"))
		assertTrue(holder.contains("if(icon!=null && item.iconRes!=0)"))
		assertTrue(holder.contains("ColorStateList tint=item.iconTintEnabled ? defaultIconTint : null"))
		assertTrue(holder.contains("icon.setImageTintList(tint)"))
		assertTrue(settings.contains("R.drawable.ic_qq_login"))
		assertFalse(settings.contains("qqItem.iconTintEnabled=false"))
	}

	@Test
	fun bindingResponsesRequireBoundTrueAndUseBindingSpecificErrors() {
		val auth = File(moduleDir, "src/main/java/org/joinmastodon/android/QQAuthActivity.java").readText()
		assertTrue(auth.contains("json==null || !json.optBoolean(\"bound\", false)"))
		assertTrue(auth.contains("bindingErrorMessage(httpStatus, serverCode)"))
		assertTrue(auth.contains("R.string.qq_bind_session_expired"))
		assertTrue(auth.contains("R.string.qq_binding_exists"))
		assertTrue(auth.contains("R.string.qq_already_bound"))
		assertTrue(auth.contains("R.string.qq_credential_invalid"))
		assertTrue(auth.contains("R.string.qq_service_unavailable"))
		assertTrue(auth.contains("R.string.qq_too_many_requests"))
		assertTrue(auth.contains("json.optString(\"code\", null)"))
		assertTrue(auth.contains("json.optString(\"access_token\", null)"))
	}

	@Test
	fun qqAssetsComeFromDensitySpecificWebpResources() {
		val expected = mapOf(
			"mdpi" to Pair(30, 24),
			"hdpi" to Pair(45, 36),
			"xhdpi" to Pair(60, 48),
			"xxhdpi" to Pair(90, 72),
			"xxxhdpi" to Pair(120, 96),
		)
		expected.forEach { (density, dimensions) ->
			assertVp8lDimensions(File(moduleDir, "src/main/res/drawable-$density/ic_qq_login.webp"), dimensions.first, dimensions.first)
			assertVp8lDimensions(File(moduleDir, "src/main/res/drawable-$density/ic_field_qq.webp"), dimensions.second, dimensions.second)
		}
		assertFalse(File(moduleDir, "src/main/res/drawable/ic_qq_login.xml").exists())
		assertFalse(File(moduleDir, "src/main/res/drawable/ic_field_qq.xml").exists())
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

	private fun assertVp8lDimensions(file: File, expectedWidth: Int, expectedHeight: Int) {
		assertTrue("missing ${file.path}", file.isFile)
		val bytes = file.readBytes()
		assertTrue("${file.path} must be a lossless WebP", bytes.size >= 25 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WEBP" && String(bytes, 12, 4) == "VP8L" && bytes[20].toInt() and 0xff == 0x2f)
		val b1 = bytes[21].toInt() and 0xff
		val b2 = bytes[22].toInt() and 0xff
		val b3 = bytes[23].toInt() and 0xff
		val b4 = bytes[24].toInt() and 0xff
		val width = 1 + ((b1 or (b2 shl 8)) and 0x3fff)
		val height = 1 + (((b2 shr 6) or (b3 shl 2) or (b4 shl 10)) and 0x3fff)
		assertEquals(expectedWidth, width)
		assertEquals(expectedHeight, height)
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
