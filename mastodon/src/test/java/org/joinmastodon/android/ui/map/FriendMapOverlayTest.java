package org.joinmastodon.android.ui.map;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.R;
import org.joinmastodon.android.model.map.MapModels;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;

import me.grishka.appkit.utils.V;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,31,35}, application=CompatibilityTestApplication.class)
public class FriendMapOverlayTest{
	private Context context;
	private RecordingActions actions;
	@Before public void setup(){
		context=RuntimeEnvironment.getApplication(); context.setTheme(R.style.Theme_Mastodon_Dark);
		V.setApplicationContext(context); actions=new RecordingActions();
	}
	private MapModels.Settings settings(boolean accepted){
		MapModels.Settings settings=new MapModels.Settings(); settings.visibility="hidden"; settings.precisionLevel="city";
		settings.babyVerified=true; settings.visibilityConfirmed=true;
		settings.consent=new MapModels.Consent(); settings.consent.accepted=accepted;
		settings.consent.version=MapModels.CONSENT_VERSION; settings.consent.acceptedVersion=accepted?MapModels.CONSENT_VERSION:null;
		return settings;
	}
	private FriendMapOverlay overlay(boolean accepted){ return overlay(accepted,360,800); }
	private FriendMapOverlay overlay(boolean accepted,int width,int height){
		FriendMapOverlay overlay=new FriendMapOverlay(context,actions); overlay.updateSettings(settings(accepted));
		if(accepted) overlay.hideAccessGate(); else overlay.showConsent();
		layout(overlay,width,height); return overlay;
	}
	private void layout(FriendMapOverlay overlay,int widthDp,int heightDp){
		int width=V.dp(widthDp),height=V.dp(heightDp);
		for(int i=0;i<3;i++){
			overlay.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
			overlay.layout(0,0,width,height);
		}
	}
	private <T> T field(FriendMapOverlay overlay,String name){ return ReflectionHelpers.getField(overlay,name); }
	private Button agree(FriendMapOverlay overlay){ return field(overlay,"consentButton"); }
	private void toBottom(FriendMapOverlay overlay){
		ScrollView scroll=field(overlay,"scroll"); scroll.scrollTo(0,scroll.getChildAt(0).getHeight());
	}
	private String consentDiagnostics(FriendMapOverlay overlay){
		ScrollView scroll=field(overlay,"scroll"); View body=field(overlay,"body");
		return "body="+body.getWidth()+"x"+body.getHeight()+" bottom="+body.getBottom()+" measured="+body.getMeasuredHeight()
				+" scroll="+scroll.getWidth()+"x"+scroll.getHeight()+" y="+scroll.getScrollY()
				+" canUp="+scroll.canScrollVertically(-1)+" canDown="+scroll.canScrollVertically(1)
				+" read="+field(overlay,"consentRead")+" layoutReady="+field(overlay,"consentLayoutReady")
				+" busy="+field(overlay,"busy")+" access="+field(overlay,"access")+" page="+overlay.state().page()
				+" panel="+overlay.findViewById(R.id.friend_map_panel).getHeight()+" lp="+overlay.findViewById(R.id.friend_map_panel).getLayoutParams().height
				+" target="+field(overlay,"panelHeight")+" column="+((View)field(overlay,"panelColumn")).getHeight()+" overlay="+overlay.getHeight();
	}

	@Test public void consentNeedsActualBottomAndEveryNavigationPathStaysLocked(){
		FriendMapOverlay overlay=overlay(false,360,600);
		ScrollView document=field(overlay,"scroll");
		assertTrue(consentDiagnostics(overlay),document.getHeight()>0);
		assertTrue("The reading test needs overflowing content",document.canScrollVertically(1));
		assertFalse(overlay.canSubmitConsent()); assertFalse(agree(overlay).isEnabled());
		agree(overlay).performClick(); assertEquals(0,actions.consents);
		assertTrue(overlay.onBackPressed()); overlay.showSettings(); overlay.showPerson(new MapModels.Point());
		overlay.enterMapDefaults(); overlay.showPin(); overlay.state().back(); overlay.state().setHeight(MapSheetState.Height.COLLAPSED);
		assertEquals(MapSheetState.Page.CONSENT,overlay.state().page()); assertEquals(MapSheetState.Height.EXPANDED,overlay.state().height());
		toBottom(overlay); assertTrue(consentDiagnostics(overlay),overlay.canSubmitConsent()); assertTrue(agree(overlay).isEnabled());
		agree(overlay).performClick(); assertEquals(1,actions.consents);
		overlay.setBusy(true); assertFalse(overlay.canSubmitConsent()); agree(overlay).performClick(); assertEquals(1,actions.consents);
		overlay.setBusy(false); layout(overlay,360,800); assertTrue(overlay.canSubmitConsent());
		overlay.dispose();
	}
	@Test public void oldAcceptedVersionDoesNotUnlockConsent(){
		FriendMapOverlay overlay=overlay(true); MapModels.Settings old=settings(true); old.consent.acceptedVersion="v1";
		overlay.updateSettings(old); overlay.showConsent(); overlay.hideAccessGate();
		assertEquals(MapSheetState.Page.CONSENT,overlay.state().page()); assertTrue(overlay.state().consentRequired());
		assertFalse(overlay.canSubmitConsent()); overlay.dispose();
	}
	@Test public void allGateTransitionsReplaceLoadingAndErrorOffersOnlyRetry(){
		FriendMapOverlay overlay=overlay(true); overlay.showLoadingGate();
		overlay.showErrorGate("Retry me"); List<Button> buttons=new ArrayList<>(); collectButtons(field(overlay,"gateColumn"),buttons);
		assertEquals(1,buttons.size()); assertEquals(context.getString(R.string.map_ui_retry),buttons.get(0).getText().toString());
		buttons.get(0).performClick(); assertEquals(1,actions.refreshes);
		overlay.showVerificationGate(); assertEquals(View.VISIBLE,((View)field(overlay,"verificationGate")).getVisibility());
		assertEquals(View.GONE,overlay.findViewById(R.id.friend_map_panel).getVisibility());
		assertEquals(View.VISIBLE,overlay.mapHost().getVisibility());
		assertEquals(View.VISIBLE,((View)field(overlay,"gateBlanket")).getVisibility());
		assertTrue(((View)field(overlay,"verificationGate")).getBackground() instanceof ColorDrawable);
		ViewGroup column=field(overlay,"gateColumn");
		for(int i=0;i<column.getChildCount();i++) column.getChildAt(i).performClick();
		assertEquals("Icon, title and button each open verification",3,actions.verifications);
		overlay.hideAccessGate(); assertEquals(View.GONE,((View)field(overlay,"verificationGate")).getVisibility());
		assertEquals(View.GONE,((View)field(overlay,"gateBlanket")).getVisibility());
		overlay.updateSettings(settings(false)); overlay.showLoadingGate(); overlay.showConsent();
		assertEquals(View.GONE,((View)field(overlay,"verificationGate")).getVisibility());
		assertEquals(View.VISIBLE,overlay.findViewById(R.id.friend_map_panel).getVisibility()); overlay.dispose();
	}
	@Test public void verificationGateNeverDispatchesTouchesIntoMap(){
		FriendMapOverlay overlay=overlay(true); int[] touches={0};
		View map=new View(context); map.setOnTouchListener((v,e)->{ touches[0]++; return true; }); overlay.mapHost().addView(map,new FrameLayout.LayoutParams(-1,-1));
		overlay.showVerificationGate(); layout(overlay,360,800);
		send(overlay.mapHost(),MotionEvent.ACTION_DOWN,10,10); send(overlay.mapHost(),MotionEvent.ACTION_MOVE,90,90);
		assertEquals(0,touches[0]); overlay.hideAccessGate();
		send(overlay.mapHost(),MotionEvent.ACTION_DOWN,10,10); assertTrue(touches[0]>0); overlay.dispose();
	}
	@Test public void actualMapDragOnlyShrinksHalfNearbyNotProgrammaticCamera(){
		FriendMapOverlay overlay=overlay(true); overlay.state().setHeight(MapSheetState.Height.HALF);
		overlay.onCameraMoved(); assertEquals(MapSheetState.Height.HALF,overlay.state().height());
		send(overlay.mapHost(),MotionEvent.ACTION_DOWN,10,10); send(overlay.mapHost(),MotionEvent.ACTION_MOVE,90,90);
		assertEquals(MapSheetState.Height.COLLAPSED,overlay.state().height());
		overlay.enterMapDefaults(); send(overlay.mapHost(),MotionEvent.ACTION_DOWN,10,10); send(overlay.mapHost(),MotionEvent.ACTION_MOVE,90,90);
		assertEquals(MapSheetState.Height.EXPANDED,overlay.state().height()); overlay.dispose();
	}
	@Test public void nonLiquidCardReusesShellWithoutOrphanGlassButtonAndHighKeepsEntry(){
		boolean old=GlobalUserPreferences.useIosLiquidNavigation; GlobalUserPreferences.useIosLiquidNavigation=false;
		try{
			FriendMapOverlay overlay=overlay(true); View shell=field(overlay,"privacyShell"),button=field(overlay,"privacyButton");
			assertEquals(View.VISIBLE,shell.getVisibility()); assertEquals(View.VISIBLE,button.getVisibility());
			button.performClick(); layout(overlay,360,800);
			assertEquals(MapSheetState.Height.HALF,overlay.state().height()); assertEquals(View.GONE,button.getVisibility());
			assertSame(shell,overlay.findViewById(R.id.friend_map_privacy).getParent()); assertTrue(shell.getWidth()>V.dp(48));
			overlay.setPrivacyExpanded(false,true); layout(overlay,360,800);
			assertEquals(V.dp(48),shell.getWidth()); assertEquals(View.VISIBLE,button.getVisibility()); overlay.dispose();
		}finally{ GlobalUserPreferences.useIosLiquidNavigation=old; }
	}
	@Test public void blankPanelSpaceDragsButScrollableContentRetainsItsGesture(){
		FriendMapOverlay overlay=overlay(true); overlay.showSettings(); layout(overlay,360,800);
		ViewGroup column=field(overlay,"panelColumn"); ScrollView scroll=field(overlay,"scroll");
		assertTrue("Settings must really scroll for this regression",scroll.canScrollVertically(1));
		int panelHeight=((View)overlay.findViewById(R.id.friend_map_panel)).getLayoutParams().height;
		float contentY=scroll.getTop()+Math.min(V.dp(80),scroll.getHeight()/2f);
		send(column,MotionEvent.ACTION_DOWN,V.dp(40),contentY); send(column,MotionEvent.ACTION_MOVE,V.dp(40),contentY-V.dp(60));
		assertEquals(panelHeight,overlay.findViewById(R.id.friend_map_panel).getLayoutParams().height);
		send(column,MotionEvent.ACTION_CANCEL,V.dp(40),contentY-V.dp(60));
		// Column padding is blank, not the ScrollView: a drag here must resize the panel.
		send(column,MotionEvent.ACTION_DOWN,V.dp(2),V.dp(100)); send(column,MotionEvent.ACTION_MOVE,V.dp(2),V.dp(180));
		assertTrue(overlay.findViewById(R.id.friend_map_panel).getLayoutParams().height<panelHeight);
		send(column,MotionEvent.ACTION_UP,V.dp(2),V.dp(180)); overlay.dispose();
	}
	@Test public void pendingMapEntryAppliesHighOnlyAfterGateClears(){
		FriendMapOverlay overlay=overlay(true); overlay.state().setHeight(MapSheetState.Height.HALF);
		overlay.showLoadingGate(); overlay.enterMapDefaults(); overlay.hideAccessGate();
		assertEquals(MapSheetState.Height.EXPANDED,overlay.state().height()); overlay.dispose();
	}
	@Test public void consentAndVerificationRemainUsableWithLargeTextPortraitAndLandscape(){
		Configuration original=new Configuration(context.getResources().getConfiguration());
		Configuration large=new Configuration(original); large.fontScale=1.6f; context.getResources().updateConfiguration(large,context.getResources().getDisplayMetrics());
		try{
			for(int[] size:new int[][]{{360,800},{800,360}}){
				FriendMapOverlay overlay=new FriendMapOverlay(context,actions);
				overlay.setNavigationInsets(V.dp(24),V.dp(80)); overlay.updateSettings(settings(false)); overlay.showConsent();
				layout(overlay,size[0],size[1]);
				ScrollView document=field(overlay,"scroll");
				assertTrue(consentDiagnostics(overlay),document.getHeight()>0);
				if(document.canScrollVertically(1)) assertFalse(consentDiagnostics(overlay),overlay.canSubmitConsent());
				toBottom(overlay); assertTrue(consentDiagnostics(overlay),overlay.canSubmitConsent());
				overlay.updateSettings(settings(true)); overlay.showVerificationGate(); layout(overlay,size[0],size[1]);
				View gate=field(overlay,"verificationGate"); assertTrue(gate.getBottom()<=overlay.getHeight()-V.dp(80));
				ScrollView gateScroll=(ScrollView)((ViewGroup)gate).getChildAt(0); gateScroll.fullScroll(View.FOCUS_DOWN);
				ViewGroup column=field(overlay,"gateColumn"); View action=column.getChildAt(column.getChildCount()-1);
				assertTrue(action.getBottom()-gateScroll.getScrollY()<=gateScroll.getHeight());
				overlay.hideAccessGate(); layout(overlay,size[0],size[1]);
				View shell=field(overlay,"privacyShell"),panel=overlay.findViewById(R.id.friend_map_panel);
				assertEquals(View.VISIBLE,shell.getVisibility()); assertTrue(shell.getBottom()<=panel.getTop()); overlay.dispose();
			}
		}finally{ context.getResources().updateConfiguration(original,context.getResources().getDisplayMetrics()); }
	}
	@Test public void firstVisibilityHasNoDefaultAndEveryNavigationPathRemainsLocked(){
		FriendMapOverlay overlay=overlay(true); MapModels.Settings first=settings(true); first.visibility="public"; first.visibilityConfirmed=false;
		overlay.updateSettings(first); overlay.hideAccessGate(); layout(overlay,360,800);
		assertEquals(MapSheetState.Page.VISIBILITY,overlay.state().page());
		Button confirm=(Button)findText(overlay,context.getString(R.string.map_presence_visibility_continue));
		assertFalse(confirm.isEnabled()); confirm.performClick(); assertNull(actions.confirmed);
		assertFalse(hasSelectedLabel(field(overlay,"body")));
		overlay.enterMapDefaults(); overlay.showSettings(); overlay.showPin(); overlay.showPerson(new MapModels.Point());
		assertTrue(overlay.onBackPressed()); overlay.state().setHeight(MapSheetState.Height.COLLAPSED);
		assertEquals(MapSheetState.Page.VISIBILITY,overlay.state().page()); assertEquals(MapSheetState.Height.EXPANDED,overlay.state().height());
		assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,overlay.findViewById(R.id.friend_map_panel).getImportantForAccessibility());
		((View)findText(field(overlay,"body"),context.getString(R.string.map_ui_everyone)).getParent()).performClick();
		assertNull(actions.confirmed); assertNull(actions.changed);
		confirm=(Button)findText(overlay,context.getString(R.string.map_presence_visibility_continue)); assertTrue(confirm.isEnabled());
		confirm.performClick(); assertEquals("public",actions.confirmed); overlay.dispose();
	}
	@Test public void restrictedChoicesOnlyShowNoticeAndExistingPrivacyHasExplicitRetention(){
		for(String existing:new String[]{"hidden","friends"}){
			FriendMapOverlay overlay=overlay(true); MapModels.Settings first=settings(true); first.visibility=existing; first.visibilityConfirmed=false;
			overlay.updateSettings(first); overlay.showVisibilityGate(); layout(overlay,360,800);
			assertFalse(hasSelectedLabel(field(overlay,"body")));
			assertFalse(((Button)findText(overlay,context.getString(R.string.map_presence_visibility_continue))).isEnabled());
			int previous=actions.visibilityNotices;
			TextView restricted=(TextView)findText(field(overlay,"body"),context.getString(R.string.map_ui_mutual));
			assertTrue(restricted.getAlpha()<1f); assertNotNull(restricted.getCompoundDrawablesRelative()[0]);
			View restrictedCard=(View)restricted.getParent(); assertTrue(restrictedCard.isEnabled());
			assertTrue(restrictedCard.getContentDescription().toString().contains("此选项仅赞助者可选"));
			restrictedCard.performClick();
			((View)findText(field(overlay,"body"),context.getString(R.string.map_ui_invisible)).getParent()).performClick();
			assertEquals(previous+2,actions.visibilityNotices); assertNull(actions.changed); assertNull(actions.confirmed);
			assertNotNull(findText(overlay,"此选项仅赞助者可选"));
			findText(overlay,context.getString(R.string.map_presence_visibility_keep,context.getString("hidden".equals(existing)?R.string.map_ui_invisible:R.string.map_ui_mutual))).performClick();
			assertEquals(existing,actions.confirmed); actions.confirmed=null; overlay.dispose();
		}
	}
	@Test public void sponsorCanExplicitlyChooseHiddenAndRefreshClearsUnsubmittedDecision(){
		FriendMapOverlay overlay=overlay(true); MapModels.Settings first=settings(true); first.visibilityConfirmed=false;
		first.capabilities=new MapModels.Capabilities(); first.capabilities.canChooseVisibility=true;
		overlay.updateSettings(first); overlay.showVisibilityGate();
		((View)findText(field(overlay,"body"),context.getString(R.string.map_ui_invisible)).getParent()).performClick();
		findText(overlay,context.getString(R.string.map_presence_visibility_continue)).performClick(); assertEquals("hidden",actions.confirmed);
		overlay.updateSettings(first); assertFalse(((Button)findText(overlay,context.getString(R.string.map_presence_visibility_continue))).isEnabled()); overlay.dispose();
	}
	@Test public void stopIsNotRestrictedAndEnablingAppearanceDoesNotSilentlyChoosePublic(){
		FriendMapOverlay overlay=overlay(true); MapModels.Settings ready=settings(true); ready.visibility="friends";
		overlay.updateSettings(ready); overlay.hideAccessGate(); overlay.showSettings();
		findText(overlay,context.getString(R.string.map_ui_stop_appearance)).performClick(); assertEquals(1,actions.stops); assertNull(actions.changed);
		ready.visibility="hidden"; overlay.updateSettings(ready); overlay.showSettings();
		android.widget.Switch control=findSwitch(field(overlay,"body")); assertNotNull(control); control.performClick();
		assertEquals(MapSheetState.Page.VISIBILITY,overlay.state().page()); assertNull(actions.changed); overlay.dispose();
	}
	private android.widget.Switch findSwitch(View view){
		if(view instanceof android.widget.Switch control) return control;
		if(view instanceof ViewGroup group) for(int i=0;i<group.getChildCount();i++){ android.widget.Switch result=findSwitch(group.getChildAt(i)); if(result!=null) return result; }
		return null;
	}
	private View findText(View view,String text){
		if(view instanceof TextView label && text.contentEquals(label.getText())) return view;
		if(view instanceof ViewGroup group) for(int i=0;i<group.getChildCount();i++){ View result=findText(group.getChildAt(i),text); if(result!=null) return result; }
		return null;
	}
	private boolean hasSelectedLabel(View view){
		if(view instanceof TextView label && label.getText().toString().contains("✓")) return true;
		if(view instanceof ViewGroup group) for(int i=0;i<group.getChildCount();i++) if(hasSelectedLabel(group.getChildAt(i))) return true;
		return false;
	}
	private void collectButtons(View view,List<Button> buttons){
		if(view instanceof Button button) buttons.add(button);
		if(view instanceof ViewGroup group) for(int i=0;i<group.getChildCount();i++) collectButtons(group.getChildAt(i),buttons);
	}
	private void send(View view,int action,float x,float y){
		MotionEvent event=MotionEvent.obtain(1,2,action,x,y,0); try{ view.dispatchTouchEvent(event); }finally{ event.recycle(); }
	}
	private static final class RecordingActions implements FriendMapOverlay.Actions{
		int consents,refreshes,verifications,visibilityNotices,stops;
		String confirmed,changed;
		public void confirmVisibility(String value){ confirmed=value; }
		public void visibilitySponsor(){ visibilityNotices++; }
		public void stopAppearance(){ stops++; }
		public void refresh(){ refreshes++; } public void locate(){} public void visibility(String value){ changed=value; } public void precision(String value){}
		public void anonymous(boolean value){} public void provider(String value){} public void useDeviceLocation(){} public void beginPin(){}
		public void confirmPin(){} public void cancelPin(){} public void consent(){ consents++; } public void privacy(){}
		public void verification(){ verifications++; } public void sponsor(){} public void profile(MapModels.Point point){}
		public void focus(MapModels.Point point){} public void panelChanged(int top,int bottom){}
	}
}
