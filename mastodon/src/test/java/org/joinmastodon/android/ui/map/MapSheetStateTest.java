package org.joinmastodon.android.ui.map;

import org.junit.Test;
import static org.junit.Assert.*;

public class MapSheetStateTest{
	@Test public void pageBacksBeforeCollapsing(){
		MapSheetState state=new MapSheetState(); state.open(MapSheetState.Page.PERSON); assertTrue(state.back()); assertEquals(MapSheetState.Page.NEARBY,state.page()); assertEquals(MapSheetState.Height.HALF,state.height()); assertTrue(state.back()); assertEquals(MapSheetState.Height.COLLAPSED,state.height()); assertFalse(state.back());
	}
	@Test public void panelHeightsStayInsideAvailableSpace(){
		assertTrue(MapSheetState.panelHeight(900,112,MapSheetState.Height.COLLAPSED)<=900);
		assertTrue(MapSheetState.panelHeight(900,112,MapSheetState.Height.HALF)<=900);
		assertEquals(MapSheetState.Height.EXPANDED,MapSheetState.settleHeight(900,900,112));
	}
}
