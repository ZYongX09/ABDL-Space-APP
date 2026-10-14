package org.joinmastodon.android.ui.map;

import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public class MapProviderRegistryTest{
	@Test public void missingKeysHaveNoFakeReleaseProvider(){
		assertTrue(MapProviderRegistry.available(false,false,new String[]{"arm64-v8a"}).isEmpty());
	}
	@Test public void nativeAbiCapabilityIsHonored(){
		assertEquals(List.of("baidu","amap"),MapProviderRegistry.available(true,true,new String[]{"arm64-v8a"}));
		assertEquals(List.of("baidu"),MapProviderRegistry.available(true,true,new String[]{"x86_64"}));
	}
	@Test public void fallbackDoesNotLoop(){
		List<String> available=List.of("baidu","amap");
		assertEquals("baidu",MapProviderRegistry.next(available,"baidu",Set.of()));
		assertEquals("amap",MapProviderRegistry.next(available,"baidu",Set.of("baidu")));
		assertNull(MapProviderRegistry.next(available,"baidu",Set.of("baidu","amap")));
		assertEquals("baidu",MapProviderRegistry.next(available,"amap",Set.of("amap")));
	}
}
