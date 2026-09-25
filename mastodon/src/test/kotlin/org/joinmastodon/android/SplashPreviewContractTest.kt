package org.joinmastodon.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashPreviewContractTest {
	private val projectDir = File(requireNotNull(System.getProperty("user.dir")))

	@Test
	fun preAndroid12PreviewUsesStaticAppIcon() {
		val styles = source("src/main/res/values/styles.xml")
		val preview = source("src/main/res/drawable/splash_preview_window.xml")
		assertTrue(styles.contains("<item name=\"android:windowBackground\">@drawable/splash_preview_window</item>"))
		assertTrue(preview.contains("@color/splash_bg"))
		assertTrue(preview.contains("@drawable/ic_splash_abdl"))
		assertTrue(preview.contains("android:gravity=\"center\""))
	}

	@Test
	fun android12PreviewUsesAnimatedAppIcon() {
		val styles = source("src/main/res/values-v31/styles.xml")
		assertTrue(styles.contains("android:windowSplashScreenAnimatedIcon"))
		assertTrue(styles.contains("@drawable/system_splash"))
		assertTrue(styles.contains("android:windowSplashScreenBackground"))
	}

	@Test
	fun launcherActivityUsesSplashTheme() {
		val manifest = source("src/main/AndroidManifest.xml")
		assertTrue(manifest.contains("android:name=\".ui.SplashActivity\""))
		assertTrue(manifest.contains("android:theme=\"@style/Theme.Mastodon.SplashScreen\""))
	}

	private fun source(path: String) = File(projectDir, path).readText()
}
