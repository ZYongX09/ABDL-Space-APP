package org.joinmastodon.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.joinmastodon.android.startup.StartupLogoView
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

	@Test
	fun visibleDestinationAndColdDurationAreIndependentGates() {
		assertFalse(StartupLogoView.mayReveal(9000, 1000, false, true))
		assertFalse(StartupLogoView.mayReveal(3999, 1000, true, true))
		assertTrue(StartupLogoView.mayReveal(4000, 1000, true, true))
		assertTrue(StartupLogoView.mayReveal(1300, 1000, true, false)) // Login, activation and verification are not delayed.
		assertTrue(StartupLogoView.mayReveal(1300, 0, true, true))
	}

	@Test
	fun drawableTargetsRealScalePropertiesWithinTheOriginalBounds() {
		val vector = source("src/main/res/drawable/system_splash.xml")
		val anim = source("src/main/res/animator/startup_logo_pulse.xml")
		assertTrue(vector.contains("android:width=\"288dp\""))
		assertTrue(vector.contains("android:pivotX=\"1440\""))
		assertTrue(vector.contains("android:animation=\"@animator/startup_logo_pulse\""))
		for (property in listOf("scaleX", "scaleY")) assertTrue(anim.contains("android:propertyName=\"$property\""))
		assertTrue(anim.contains("android:valueFrom=\"1.0\""))
		assertTrue(anim.contains("android:valueTo=\"0.88\""))
		assertTrue(anim.contains("android:duration=\"1200\""))
		assertTrue(anim.contains("android:repeatMode=\"reverse\""))
	}

	@Test
	fun launcherIsAnAliasAndPlatformWaitsOnMainFirstFrameNotNetwork() {
		val activity = source("src/main/java/org/joinmastodon/android/MainActivity.java")
		val manifest = source("src/main/AndroidManifest.xml")
		assertTrue(manifest.contains("<activity-alias android:name=\".ui.SplashActivity\" android:targetActivity=\".MainActivity\""))
		assertTrue(manifest.contains("android:name=\".MainActivity\" android:exported=\"false\""))
		assertTrue(activity.contains("getSplashScreen().setOnExitAnimationListener"))
		assertTrue(activity.contains("hasStartupContentFrame()"))
		assertTrue(activity.contains("AppSecurity.runAfterUnlock"))
		assertFalse(activity.contains("Intent.FLAG_ACTIVITY_CLEAR_TASK"))
	}

	private fun source(path: String) = File(projectDir, path).readText()
}
