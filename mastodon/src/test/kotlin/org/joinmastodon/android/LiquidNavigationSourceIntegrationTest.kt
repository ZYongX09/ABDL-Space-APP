package org.joinmastodon.android

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LiquidNavigationSourceIntegrationTest {
	private fun source(path: String) = File(requireNotNull(System.getProperty("user.dir")), "src/main/java/org/joinmastodon/android/$path").readText()

	@Test
	fun settingsCapabilityAndRequestedChoiceRemainSdkGatedWithoutRuntimeFallback() {
		val preferences = source("GlobalUserPreferences.java")
		val settings = source("fragments/settings/SettingsDisplayFragment.java")
		assertTrue(preferences.contains("return LiquidGlassCompatibility.isSystemSupported();"))
		assertTrue(preferences.contains("return LiquidGlassCompatibility.isSupported() && useIosLiquidNavigation;"))
		assertTrue(preferences.contains("if(liquidSupported)\n\t\t\teditor.putBoolean(\"useIosLiquidNavigation\", useIosLiquidNavigation);"))
		assertFalse(preferences.contains("putBoolean(\"useIosLiquidNavigation\", isIosLiquidNavigationEnabled())"))
		assertTrue(settings.contains("if(GlobalUserPreferences.isIosLiquidNavigationSupported()){\n\t\t\titems.add(iosLiquidNavigationItem="))
		assertTrue(settings.contains("item->requestLiquidNavigation(!item.checked)"))
		assertTrue(settings.contains("iosLiquidNavigationItem.checkedChangeListener=this::requestLiquidNavigation"))
		assertFalse(settings.substringAfter("protected void onHidden(){").substringBefore("private void requestLiquidNavigation").contains("useIosLiquidNavigation="))
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
	fun graphicsExceptionsDoNotRegisterSessionFallbackAndUserChoiceStillRestoresClassicUi() {
		val helper = source("ui/utils/LiquidGlassCompatibility.java")
		val home = source("fragments/HomeFragment.java")
		assertFalse(helper.contains("sessionDisabled"))
		assertFalse(helper.contains("reportFailure"))
		assertFalse(helper.contains("FailureListener"))
		assertFalse(helper.contains("SharedPreferences"))
		assertFalse(home.contains("LiquidGlassCompatibility"))
		assertFalse(home.contains("liquidFailureListener"))
		val graphics = home.substringAfter("private void disposeLiquidNavigation(){").substringBefore("private void applyLiquidToolbarInsets")
		assertFalse(graphics.contains("catch("))
		assertTrue(graphics.contains("controller.dispose();"))
		assertTrue(graphics.contains("ownedNavigation.setBackdropBitmap(bottom);"))
		val destroyed = home.substringAfter("public void onDestroyView(){").substringBefore("super.onDestroyView();")
		assertTrue(destroyed.contains("cancelLiquidStartup();"))
		assertTrue(destroyed.contains("stopLiquidCapture();"))
		val classic = home.substringAfter("private void restoreClassicNavigation(){").substringBefore("private void scheduleLiquidStartup")
		assertTrue(classic.contains("stopLiquidCapture();"))
		assertTrue(classic.contains("liquidHardwareVerified=false;"))
		assertTrue(classic.contains("createNavigationBar(LayoutInflater.from(getActivity()));"))
		assertTrue(classic.contains("createLiquidToolbar();"))
		val settingsChanged = home.substringAfter("public void onStatusDisplaySettingsChanged").substringBefore("private void cancelLiquidStartup")
		assertTrue(settingsChanged.contains("if(!GlobalUserPreferences.isIosLiquidNavigationEnabled())"))
		assertTrue(settingsChanged.contains("restoreClassicNavigation();"))
		val toolbar = home.substringAfter("private void createLiquidToolbar(){").substringBefore("private void applyLiquidToolbarInsets")
		assertTrue(toolbar.contains("if(!GlobalUserPreferences.isIosLiquidNavigationEnabled() || !liquidHardwareVerified)"))
		assertTrue(toolbar.contains("homeTabFragment.setLiquidToolbarController(null);"))
		assertTrue(toolbar.contains("toolbarHost.setVisibility(View.GONE);"))
	}

	@Test
	fun captureKeepsLocalResourceBoundsAndRestorationWithoutSessionFailureReporting() {
		val capture = source("ui/views/BackdropCaptureFrameLayout.java")
		assertFalse(capture.contains("reportFailure"))
		assertFalse(capture.contains("failCapture"))
		assertTrue(capture.contains("CAPTURE_MEMORY_BUDGET_BYTES=16L*1024*1024"))
		assertTrue(capture.contains("MAX_CAPTURE_DOWNSAMPLE=4"))
		assertTrue(capture.contains("MAX_CAPTURE_DIMENSION=8192"))
		assertTrue(capture.contains("if(!canvas.isHardwareAccelerated()){\n\t\t\tstopCapture();"))
		assertTrue(capture.contains("if(failure instanceof CaptureBudgetExceededException){\n\t\t\tstopCapture();"))
		assertTrue(capture.contains("rethrow(failure);"))
		assertTrue(capture.contains("Throwable restoreFailure=restoreHardwareBitmaps();"))
		assertTrue(capture.contains("failure.addSuppressed(restoreFailure);"))
		assertTrue(capture.contains("capturing=false;"))
	}
}
