package org.joinmastodon.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginConsentLayoutContractTest {
	private val projectDir = File(requireNotNull(System.getProperty("user.dir")))

	@Test
	fun consentSheetDeclaresEveryRequiredView() {
		val layout = source("src/main/res/layout/sheet_qr_login.xml")
		for (id in listOf("icon", "sheet_title", "qr_session_info", "btn_cancel", "btn_authorize")) {
			assertTrue("missing $id", layout.contains("android:id=\"@+id/$id\""))
		}
	}

	@Test
	fun actualLoginPagesGuardConsentCallbacks() {
		for (path in listOf(
			"src/main/java/org/joinmastodon/android/fragments/auth/LoginEmailFragment.java",
			"src/main/java/org/joinmastodon/android/fragments/auth/LoginPasswordFragment.java",
		)) {
			val page = source(path)
			assertTrue(page.contains("if (iconView != null) iconView.setImageResource(iconRes)"))
			assertTrue(page.contains("isAdded() && !activity.isFinishing() && !activity.isDestroyed()"))
		}
	}

	@Test
	fun qrLoginCallbacksRemainCompatibleWithAndroid8() {
		val sheet = source("src/main/java/org/joinmastodon/android/ui/sheets/QRLoginBottomSheet.java")
		assertTrue(sheet.contains("ContextCompat.getMainExecutor(getContext())"))
		assertFalse(sheet.contains("getContext().getMainExecutor()"))
	}

	private fun source(path: String) = File(projectDir, path).readText()
}
