package org.joinmastodon.android

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LiquidNavigationSourceIntegrationTest {
	private fun source(path: String) = File(requireNotNull(System.getProperty("user.dir")), "src/main/java/org/joinmastodon/android/$path").readText()

	@Test
	fun preferencesAndSettingsUseSharedGateWithNullableHiddenItem() {
		val preferences = source("GlobalUserPreferences.java")
		val settings = source("fragments/settings/SettingsDisplayFragment.java")
		assertTrue(preferences.contains("return LiquidGlassCompatibility.isSupported();"))
		assertTrue(preferences.contains("return isIosLiquidNavigationSupported() && useIosLiquidNavigation;"))
		assertTrue(preferences.contains("if(!liquidSupported)\n\t\t\tuseIosLiquidNavigation=false;"))
		assertTrue(preferences.contains("if(liquidSupported)\n\t\t\teditor.putBoolean(\"useIosLiquidNavigation\", useIosLiquidNavigation);"))
		assertFalse(preferences.contains("putBoolean(\"useIosLiquidNavigation\", isIosLiquidNavigationEnabled())"))
		assertTrue(settings.contains("if(GlobalUserPreferences.isIosLiquidNavigationSupported()){\n\t\t\titems.add(iosLiquidNavigationItem="))
		assertTrue(settings.contains("if(iosLiquidNavigationItem!=null && GlobalUserPreferences.isIosLiquidNavigationSupported())"))
		val writes = settings.lineSequence().filter { it.contains("GlobalUserPreferences.useIosLiquidNavigation=") }.toList()
		assertEquals(2, writes.size)
		assertTrue(writes.all { it.contains("GlobalUserPreferences.isIosLiquidNavigationSupported() &&") })
	}

	@Test
	fun homeUsesOneOwnedPostAndRevalidatesAttachedViewBeforeHardwareCheck() {
		val home = source("fragments/HomeFragment.java")
		assertFalse(home.contains("GlobalUserPreferences.useIosLiquidNavigation"))
		assertFalse(home.contains("postOnAnimation"))
		val startup = home.substringAfter("private void scheduleLiquidStartup(){").substringBefore("private void createNavigationBar")
		assertTrue(startup.contains("liquidStartupRunnable=new Runnable()"))
		assertTrue(startup.contains("if(liquidStartupRunnable!=this)"))
		assertTrue(startup.contains("content!=ownedContent || fragmentContainer!=ownedContainer"))
		assertTrue(startup.contains("!ownedContainer.isAttachedToWindow()"))
		assertTrue(startup.indexOf("!ownedContainer.isAttachedToWindow()") < startup.indexOf("!ownedContainer.isHardwareAccelerated()"))
		assertTrue(startup.indexOf("!ownedContainer.isHardwareAccelerated()") < startup.indexOf("createNavigationBar(LayoutInflater.from(getActivity()))"))
		assertEquals(1, Regex("ownedContent\\.post\\(liquidStartupRunnable\\)").findAll(startup).count())
		assertTrue(startup.contains("liquidNavigationController!=ownedNavigation || liquidToolbarController!=ownedToolbar"))
		assertTrue(home.contains("content.removeCallbacks(liquidStartupRunnable)"))
		val detached = home.substringAfter("public void onViewDetachedFromWindow(View view){").substringBefore("\n\t\t\t}")
		assertTrue(detached.contains("cancelLiquidStartup();"))
		assertTrue(detached.contains("stopLiquidCapture();"))
	}

	@Test
	fun failureFallbackIsQueuedAndBoundToTheActiveView() {
		val helper = source("ui/utils/LiquidGlassCompatibility.java")
		val home = source("fragments/HomeFragment.java")
		assertTrue(helper.contains("MAIN.post(notification)"))
		assertTrue(helper.indexOf("sessionDisabled=true;") < helper.indexOf("MAIN.post(notification)"))
		assertFalse(helper.contains("SharedPreferences"))
		assertFalse(home.contains("catch(Throwable"))
		assertFalse(home.contains("catch(Error"))
		assertTrue(home.contains("content!=ownedContent || !ownedContent.isAttachedToWindow() || getActivity()==null"))
		assertTrue(home.contains("LiquidGlassCompatibility.addFailureListener(liquidFailureListener)"))
		val destroyed = home.substringAfter("public void onDestroyView(){").substringBefore("super.onDestroyView();")
		assertTrue(destroyed.contains("LiquidGlassCompatibility.removeFailureListener(liquidFailureListener)"))
		assertTrue(destroyed.contains("cancelLiquidStartup();"))
		assertTrue(destroyed.contains("stopLiquidCapture();"))
		val fallback = home.substringAfter("private void restoreClassicNavigation(){").substringBefore("private void scheduleLiquidStartup")
		assertTrue(fallback.contains("stopLiquidCapture();"))
		assertTrue(fallback.contains("createNavigationBar(LayoutInflater.from(getActivity()));"))
		assertTrue(fallback.contains("createLiquidToolbar();"))
	}
}
