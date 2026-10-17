package org.joinmastodon.android.ui.map;

import org.junit.Test;
import static org.junit.Assert.*;

public class MapSheetStateTest{
	@Test public void pageBacksBeforeCollapsing(){
		MapSheetState state=new MapSheetState(); state.open(MapSheetState.Page.PERSON); assertTrue(state.back()); assertEquals(MapSheetState.Page.NEARBY,state.page()); assertEquals(MapSheetState.Height.HALF,state.height()); assertTrue(state.back()); assertEquals(MapSheetState.Height.COLLAPSED,state.height()); assertFalse(state.back());
	}
	@Test public void initialAndSettingsPagesDefaultToHigh(){
		MapSheetState state=new MapSheetState();
		assertEquals(MapSheetState.Height.EXPANDED,state.height());
		for(MapSheetState.Page page:new MapSheetState.Page[]{MapSheetState.Page.NEARBY,MapSheetState.Page.SETTINGS,MapSheetState.Page.PROVIDER,MapSheetState.Page.VISIBILITY,MapSheetState.Page.PRECISION}){
			state.open(page); assertEquals(page,state.page()); assertEquals(MapSheetState.Height.EXPANDED,state.height());
		}
	}
	@Test public void consentLockSurvivesAllNavigationAndHeightChanges(){
		MapSheetState state=new MapSheetState(); state.setConsentRequired(true);
		for(MapSheetState.Page page:MapSheetState.Page.values()){
			state.open(page); assertTrue(state.back());
			for(MapSheetState.Height height:MapSheetState.Height.values()) state.setHeight(height);
			assertFalse(state.onMapDragStarted());
			assertEquals(MapSheetState.Page.CONSENT,state.page()); assertEquals(MapSheetState.Height.EXPANDED,state.height());
		}
		state.setConsentRequired(false); state.open(MapSheetState.Page.PROVIDER);
		assertEquals(MapSheetState.Page.PROVIDER,state.page()); assertTrue(state.back());
	}
	@Test public void visibilityLockCannotBeDismissedAndConsentStillHasPriority(){
		MapSheetState state=new MapSheetState(); state.setVisibilityRequired(true);
		for(MapSheetState.Page page:MapSheetState.Page.values()){
			state.open(page); assertTrue(state.back()); assertFalse(state.onMapDragStarted());
			for(MapSheetState.Height height:MapSheetState.Height.values()) state.setHeight(height);
			assertEquals(MapSheetState.Page.VISIBILITY,state.page()); assertEquals(MapSheetState.Height.EXPANDED,state.height());
		}
		state.setConsentRequired(true); state.setVisibilityRequired(true);
		assertEquals(MapSheetState.Page.CONSENT,state.page());
		state.setConsentRequired(false); assertEquals(MapSheetState.Page.VISIBILITY,state.page());
		state.setVisibilityRequired(false); state.open(MapSheetState.Page.NEARBY); assertTrue(state.back());
		assertEquals(MapSheetState.Height.COLLAPSED,state.height());
	}
	@Test public void onlyHalfNearbyYieldsToMapDrag(){
		MapSheetState state=new MapSheetState();
		for(MapSheetState.Page page:MapSheetState.Page.values()){
			for(MapSheetState.Height height:MapSheetState.Height.values()){
				state.open(page); state.setHeight(height);
				boolean expected=page==MapSheetState.Page.NEARBY&&height==MapSheetState.Height.HALF;
				assertEquals(expected,state.onMapDragStarted());
				assertEquals(expected?MapSheetState.Height.COLLAPSED:height,state.height());
			}
		}
	}
	@Test public void panelHeightsStayInsideAvailableSpace(){
		assertTrue(MapSheetState.panelHeight(900,112,MapSheetState.Height.COLLAPSED)<=900);
		assertTrue(MapSheetState.panelHeight(900,112,MapSheetState.Height.HALF)<=900);
		assertEquals(MapSheetState.Height.EXPANDED,MapSheetState.settleHeight(900,900,112));
	}
}
