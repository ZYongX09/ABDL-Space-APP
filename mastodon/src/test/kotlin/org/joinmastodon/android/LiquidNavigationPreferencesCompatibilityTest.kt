package org.joinmastodon.android

import android.app.ActivityManager
import android.os.Build
import org.joinmastodon.android.ui.utils.LiquidGlassCompatibility
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 27, 28, 29, 30, 31, 32, 33, 34, 35], application = CompatibilityTestApplication::class)
class LiquidNavigationPreferencesCompatibilityTest {
	@Before
	fun setup() {
		resetCompatibility()
		MastodonApp.context = RuntimeEnvironment.getApplication()
		shadowOf(MastodonApp.context.getSystemService(ActivityManager::class.java)).apply {
			setIsLowRamDevice(false)
			setMemoryClass(256)
		}
		GlobalUserPreferences.getPrefs().edit().clear().putBoolean("perAccountMigrationDone", true).commit()
	}

	@After
	fun teardown() {
		resetCompatibility()
		GlobalUserPreferences.useIosLiquidNavigation = false
		MastodonApp.context = null
	}

	@Test
	fun resolverDefaultsAndExplicitChoiceRemainSdkGated() {
		for (sdk in 26..35) {
			assertEquals(sdk >= 33, GlobalUserPreferences.resolveIosLiquidNavigationEnabled(sdk, false, false))
			assertEquals(sdk >= 33, GlobalUserPreferences.resolveIosLiquidNavigationEnabled(sdk, true, true))
			assertFalse(GlobalUserPreferences.resolveIosLiquidNavigationEnabled(sdk, true, false))
		}
	}

	@Test
	fun loadAndSaveNormalizeUnsupportedSdkWithoutErasingRequestedChoice() {
		val prefs = GlobalUserPreferences.getPrefs()
		prefs.edit().putBoolean("useIosLiquidNavigation", true).commit()
		GlobalUserPreferences.load()
		assertEquals(Build.VERSION.SDK_INT >= 33, GlobalUserPreferences.isIosLiquidNavigationSupported())
		assertEquals(Build.VERSION.SDK_INT >= 33, GlobalUserPreferences.isIosLiquidNavigationEnabled())
		// A raw stale/restored true must not bypass save normalization on old SDKs.
		GlobalUserPreferences.useIosLiquidNavigation = true
		GlobalUserPreferences.save()
		assertEquals(Build.VERSION.SDK_INT >= 33, GlobalUserPreferences.useIosLiquidNavigation)
		assertTrue(prefs.getBoolean("useIosLiquidNavigation", false))
	}

	@Test
	fun lowRamKeepsSupportedEntryAndExplicitChoiceWithPerformanceWarning() {
		val prefs = GlobalUserPreferences.getPrefs()
		prefs.edit().putBoolean("useIosLiquidNavigation", true).commit()
		shadowOf(MastodonApp.context.getSystemService(ActivityManager::class.java)).setIsLowRamDevice(true)
		GlobalUserPreferences.load()
		assertTrue(LiquidGlassCompatibility.shouldWarnAboutPerformance())
		assertEquals(Build.VERSION.SDK_INT >= 33, GlobalUserPreferences.isIosLiquidNavigationSupported())
		assertEquals(Build.VERSION.SDK_INT >= 33, GlobalUserPreferences.useIosLiquidNavigation)
		assertEquals(Build.VERSION.SDK_INT >= 33, GlobalUserPreferences.isIosLiquidNavigationEnabled())
		GlobalUserPreferences.save()
		assertEquals(Build.VERSION.SDK_INT >= 33, GlobalUserPreferences.useIosLiquidNavigation)
		assertTrue(prefs.getBoolean("useIosLiquidNavigation", false))
	}

	@Test
	fun failureAndUnrelatedSaveDoNotPersistSessionDisable() {
		val prefs = GlobalUserPreferences.getPrefs()
		prefs.edit().putBoolean("useIosLiquidNavigation", true).commit()
		GlobalUserPreferences.load()
		LiquidGlassCompatibility.reportFailure("test backdrop", IllegalStateException("test"))
		assertFalse(GlobalUserPreferences.isIosLiquidNavigationEnabled())
		GlobalUserPreferences.showCWs = !GlobalUserPreferences.showCWs
		GlobalUserPreferences.save()
		assertEquals(Build.VERSION.SDK_INT >= 33, GlobalUserPreferences.useIosLiquidNavigation)
		assertEquals(Build.VERSION.SDK_INT >= 33, GlobalUserPreferences.isIosLiquidNavigationSupported())
		assertTrue(prefs.getBoolean("useIosLiquidNavigation", false))
		assertFalse(prefs.contains("sessionDisabled"))
		resetCompatibility()
		GlobalUserPreferences.load()
		assertEquals(Build.VERSION.SDK_INT >= 33, GlobalUserPreferences.isIosLiquidNavigationEnabled())
	}

	@Test
	fun sessionFailureSaveStoresRequestedChoiceOnlyOnSupportedSdk() {
		GlobalUserPreferences.load()
		LiquidGlassCompatibility.reportFailure("test startup", OutOfMemoryError("test"))
		GlobalUserPreferences.save()
		assertEquals(Build.VERSION.SDK_INT >= 33, GlobalUserPreferences.getPrefs().contains("useIosLiquidNavigation"))
		if (Build.VERSION.SDK_INT >= 33) assertTrue(GlobalUserPreferences.getPrefs().getBoolean("useIosLiquidNavigation", false))
	}

	@Test
	fun limitedMemoryDefaultsOffUntilUserConfirmsButDoesNotHideEntry() {
		shadowOf(MastodonApp.context.getSystemService(ActivityManager::class.java)).setIsLowRamDevice(true)
		GlobalUserPreferences.load()
		assertFalse(GlobalUserPreferences.useIosLiquidNavigation)
		assertEquals(Build.VERSION.SDK_INT >= 33, GlobalUserPreferences.isIosLiquidNavigationSupported())
	}

	@Test
	fun smallAppHeapWarnsWithoutDisablingExplicitChoice() {
		shadowOf(MastodonApp.context.getSystemService(ActivityManager::class.java)).setMemoryClass(128)
		assertTrue(LiquidGlassCompatibility.shouldWarnAboutPerformance())
		GlobalUserPreferences.getPrefs().edit().putBoolean("useIosLiquidNavigation", true).commit()
		GlobalUserPreferences.load()
		assertEquals(Build.VERSION.SDK_INT >= 33, GlobalUserPreferences.isIosLiquidNavigationEnabled())
	}

	@Test
	fun explicitOptOutRemainsOffAndCanBeSavedWhenSupported() {
		val prefs = GlobalUserPreferences.getPrefs()
		prefs.edit().putBoolean("useIosLiquidNavigation", false).commit()
		GlobalUserPreferences.load()
		assertFalse(GlobalUserPreferences.isIosLiquidNavigationEnabled())
		GlobalUserPreferences.save()
		assertFalse(prefs.getBoolean("useIosLiquidNavigation", true))
	}

	@Test
	fun systemCapabilityDoesNotDependOnApplicationContext() {
		MastodonApp.context = null
		assertEquals(Build.VERSION.SDK_INT >= 33, LiquidGlassCompatibility.isSupported())
		assertEquals(Build.VERSION.SDK_INT >= 33, GlobalUserPreferences.isIosLiquidNavigationSupported())
		assertFalse(LiquidGlassCompatibility.shouldWarnAboutPerformance())
	}

	private fun resetCompatibility() {
		LiquidGlassCompatibility::class.java.getDeclaredMethod("resetForTests").apply {
			isAccessible = true
		}.invoke(null)
	}
}
