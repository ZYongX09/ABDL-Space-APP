package org.joinmastodon.android.ui.map;

import org.junit.Test;
import static org.junit.Assert.*;

public class MapGlassPolicyTest{
	@Test public void oldApisAndSoftwareRenderingNeverEnableLiquid(){
		for(int sdk:new int[]{26,28,30,31,32}) assertFalse(MapGlassPolicy.supportsLiquid(sdk,true,true,true));
		assertFalse(MapGlassPolicy.supportsLiquid(35,false,true,true));
		assertFalse(MapGlassPolicy.supportsLiquid(35,true,false,true));
		assertFalse(MapGlassPolicy.supportsLiquid(35,true,true,false));
	}
	@Test public void hardwareWithValidSampleUsesSubtleBackgroundOnly(){
		assertTrue(MapGlassPolicy.supportsLiquid(33,true,true,true));
		assertTrue(MapGlassPolicy.supportsLiquid(35,true,true,true));
		assertEquals(.35f,MapGlassPolicy.surfaceAlpha(true,false),0);
		assertEquals(.48f,MapGlassPolicy.surfaceAlpha(true,true),0);
		assertEquals(.96f,MapGlassPolicy.surfaceAlpha(false,true),0);
	}
	@Test public void darkThemeGlassIsDarkRegardlessOfLightMapTiles(){
		assertEquals(.72f,MapGlassPolicy.liquidSurfaceAlpha(true,false),0);
		assertEquals(.78f,MapGlassPolicy.liquidSurfaceAlpha(true,true),0);
		assertEquals(.28f,MapGlassPolicy.liquidSurfaceAlpha(false,false),0);
		assertTrue(MapGlassPolicy.liquidSurfaceAlpha(true,true)>.6f);
	}
}
