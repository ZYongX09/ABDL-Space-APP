package org.joinmastodon.android.ui.map;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class MapSdkContractTest{
	private String source(String file) throws Exception{ return Files.readString(Path.of(file)); }
	@Test public void manifestUsesOnlyAndroidKeyPlaceholders() throws Exception{
		String manifest=source("src/main/AndroidManifest.xml");
		assertTrue(manifest.contains("${BAIDU_MAP_ANDROID_AK}"));
		assertTrue(manifest.contains("${AMAP_ANDROID_KEY}"));
		assertFalse(manifest.contains("ACCESS_BACKGROUND_LOCATION"));
		assertFalse(manifest.contains("com.baidu.location.f"));
		assertFalse(manifest.contains("com.amap.api.location.APSService"));
	}
	@Test public void productionPageDoesNotSelectFakeMap() throws Exception{
		String fragment=source("src/main/java/org/joinmastodon/android/fragments/discover/FriendMapFragment.java");
		assertFalse(fragment.contains("new FakeMapProvider"));
		assertTrue(fragment.contains("provider.onResume()"));
		assertTrue(fragment.contains("provider.onPause()"));
		assertTrue(fragment.contains("provider.destroy()"));
		assertTrue(fragment.contains("postDelayed(viewportDebounce,400)"));
		assertTrue(fragment.contains("args.putString(\"profileAccountID\",point.account.id)"));
	}
	@Test public void mapSdksCannotEnableTheirLocationSource() throws Exception{
		for(String file:new String[]{"BaiduMapProvider.java","AMapProvider.java"}){
			String provider=source("src/main/java/org/joinmastodon/android/ui/map/"+file);
			assertTrue(provider.contains("setMyLocationEnabled(false)"));
			assertFalse(provider.contains("setMyLocationEnabled(true)"));
		}
	}
}
