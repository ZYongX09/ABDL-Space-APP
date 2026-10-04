package org.joinmastodon.android

import android.app.ActivityManager
import android.os.Build
import org.joinmastodon.android.ui.compose.utils.GraphicsSafety
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
		MastodonApp.context = RuntimeEnvironment.getApplication()
		shadowOf(MastodonApp.context.getSystemService(ActivityManager::class.java)).apply {
			setIsLowRamDevice(false)
			setMemoryClass(256)
		}
		GlobalUserPreferences.getPrefs().edit().clear().putBoolean("perAccountMigrationDone", true).commit()
	}

	@After
	fun teardown() {
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
	@Config(sdk = [33, 34, 35])
	fun actualGraphicsExceptionDoesNotDisableExplicitChoiceOrPreventToggling() {
		val prefs = GlobalUserPreferences.getPrefs()
		assertTrue(prefs.edit().putBoolean("useIosLiquidNavigation", true).commit())
		GlobalUserPreferences.load()
		assertTrue(GlobalUserPreferences.isIosLiquidNavigationEnabled())
		assertGraphicsFailurePropagates(IllegalStateException("test backdrop"))
		assertTrue(LiquidGlassCompatibility.isSupported())
		assertTrue(GlobalUserPreferences.isIosLiquidNavigationSupported())
		assertTrue(GlobalUserPreferences.useIosLiquidNavigation)
		assertTrue(GlobalUserPreferences.isIosLiquidNavigationEnabled())
		GlobalUserPreferences.showCWs = !GlobalUserPreferences.showCWs
		GlobalUserPreferences.save()
		assertTrue(prefs.getBoolean("useIosLiquidNavigation", false))
		assertFalse(prefs.contains("sessionDisabled"))
		GlobalUserPreferences.load()
		assertTrue(GlobalUserPreferences.isIosLiquidNavigationEnabled())

		GlobalUserPreferences.useIosLiquidNavigation = false
		GlobalUserPreferences.save()
		GlobalUserPreferences.load()
		assertFalse(GlobalUserPreferences.useIosLiquidNavigation)
		assertFalse(GlobalUserPreferences.isIosLiquidNavigationEnabled())
		assertFalse(prefs.getBoolean("useIosLiquidNavigation", true))

		GlobalUserPreferences.useIosLiquidNavigation = true
		GlobalUserPreferences.save()
		GlobalUserPreferences.load()
		assertTrue(GlobalUserPreferences.useIosLiquidNavigation)
		assertTrue(GlobalUserPreferences.isIosLiquidNavigationEnabled())
		assertTrue(prefs.getBoolean("useIosLiquidNavigation", false))
		assertFalse(prefs.contains("sessionDisabled"))
	}

	@Test
	@Config(sdk = [33, 34, 35])
	fun actualGraphicsOutOfMemoryDoesNotOverrideTheDefaultChoice() {
		val prefs = GlobalUserPreferences.getPrefs()
		GlobalUserPreferences.load()
		assertTrue(GlobalUserPreferences.isIosLiquidNavigationEnabled())
		assertFalse(prefs.contains("useIosLiquidNavigation"))
		assertGraphicsFailurePropagates(OutOfMemoryError("test startup"))
		assertTrue(LiquidGlassCompatibility.isSupported())
		assertTrue(GlobalUserPreferences.isIosLiquidNavigationEnabled())
		GlobalUserPreferences.save()
		assertTrue(prefs.contains("useIosLiquidNavigation"))
		assertTrue(prefs.getBoolean("useIosLiquidNavigation", false))
		GlobalUserPreferences.load()
		assertTrue(GlobalUserPreferences.isIosLiquidNavigationEnabled())
		assertFalse(prefs.contains("sessionDisabled"))
	}

	@Test
	fun compatibilityHelperHasNoRuntimeFailureApiOrSessionState() {
		val compatibility = LiquidGlassCompatibility::class.java
		assertFalse(compatibility.declaredMethods.any {
			it.name == "reportFailure" || it.name.contains("FailureListener") ||
				it.name.startsWith("reset")
		})
		val supportMethods = compatibility.declaredMethods.filter { it.name == "isSupported" }
		assertEquals(1, supportMethods.size)
		assertEquals(0, supportMethods.single().parameterCount)
		assertFalse(compatibility.declaredClasses.any { it.simpleName == "Effect" })
		assertFalse(compatibility.declaredFields.any {
			it.name.contains("sessionDisabled", ignoreCase = true) ||
				it.name.contains("listener", ignoreCase = true)
		})
	}

	@Test
	fun savingDefaultChoiceCreatesAPreferenceOnlyOnSupportedSdk() {
		val prefs = GlobalUserPreferences.getPrefs()
		GlobalUserPreferences.load()
		GlobalUserPreferences.save()
		assertEquals(Build.VERSION.SDK_INT >= 33, prefs.contains("useIosLiquidNavigation"))
		if (Build.VERSION.SDK_INT >= 33) {
			assertTrue(prefs.getBoolean("useIosLiquidNavigation", false))
		}
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

	private fun assertGraphicsFailurePropagates(error: Throwable) {
		var attempted = false
		var fallbackCalled = false
		var failureCallbackCalled = false
		val thrown = runCatching {
			GraphicsSafety.guarded(
				operation = "test graphics operation",
				fallback = { fallbackCalled = true },
				onFailure = { failureCallbackCalled = true },
			) {
				attempted = true
				throw error
			}
		}.exceptionOrNull()
		assertTrue("The real graphics operation must execute", attempted)
		assertSame("Graphics exceptions must propagate unchanged", error, thrown)
		assertFalse("A supported device must not silently use fallback", fallbackCalled)
		assertFalse("No runtime failure callback may disable the user's choice", failureCallbackCalled)
	}
}
