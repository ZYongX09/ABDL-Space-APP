package org.joinmastodon.android.auth;

import static org.junit.Assert.*;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Source-contract coverage for the approved narrow change boundary, not an invented network pass. */
public class HumanCheckLegacyBoundaryTest{
	private static Path repository(){
		Path cwd=Path.of(System.getProperty("user.dir")).toAbsolutePath();
		if(Files.isDirectory(cwd.resolve("mastodon/src/main"))) return cwd;
		if(Files.isDirectory(cwd.resolve("src/main"))) return cwd.getParent();
		throw new AssertionError("Android repository root not found");
	}
	private static String source(String name) throws Exception{ return Files.readString(repository().resolve("mastodon/src/main/java/org/joinmastodon/android/"+name)); }
	@Test public void onlyTwoEmailSendEntriesUseNewCoordinatorNoOldSendFallback() throws Exception{
		for(String name:List.of("LoginEmailFragment","LoginCodeFragment")){
			String source=source("fragments/auth/"+name+".java");
			assertTrue(source.contains("humanCheck.begin(")); assertFalse(source.contains("/api/auth/send-code"));
			assertTrue(source.contains("RESULT_OK")); assertTrue(source.contains("consumeSent("));
		}
	}
	@Test public void originalCodeLoginPasswordAndRegistrationBusinessEndpointsRemainPresent() throws Exception{
		assertTrue(source("fragments/auth/LoginCodeFragment.java").contains("https://abdl-space.top/api/auth/login-by-code"));
		assertTrue(source("fragments/auth/LoginPasswordFragment.java").contains("/api/auth/"));
		assertTrue(source("fragments/auth/RegisterInfoFragment.java").contains("/api/auth/"));
	}
	@Test public void webViewHasNoUnsafeJavascriptBridgeOrBrowserFallback() throws Exception{
		String activity=source("HumanCheckActivity.java");
		assertTrue(activity.contains("WebViewCompat.addWebMessageListener")); assertFalse(activity.contains("addJavascriptInterface("));
		assertFalse(activity.contains("Intent.ACTION_VIEW")); assertFalse(activity.contains("launchWebBrowser"));
		assertFalse(activity.contains("evaluateJavascript(")); assertFalse(activity.contains("setUserAgentString("));
	}
	@Test public void proofsAreNotWrittenToIntentsBundlesPreferencesOrLogs() throws Exception{
		String coordinator=source("auth/HumanCheckCoordinator.java"), request=source("api/requests/auth/HumanCheckRequest.java");
		for(String forbidden:List.of("getSharedPreferences(","Log.","putExtra(","putString(")) assertFalse(coordinator,coordinator.contains(forbidden));
		assertFalse(request.contains("Log.")); assertTrue(request.contains("CookieJar.NO_COOKIES")); assertTrue(request.contains("requiresSystemTrust(){ return true; }"));
		String activity=source("HumanCheckActivity.java");
		assertFalse(activity.contains("putString(")); assertTrue(activity.contains("setSaveEnabled(false)"));
	}
}
