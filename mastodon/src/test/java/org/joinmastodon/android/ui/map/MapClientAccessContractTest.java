package org.joinmastodon.android.ui.map;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.joinmastodon.android.model.map.MapModels;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.*;

/** Pure JVM state + source contracts. Does not create an SDK, use a device, or contact an API. */
public class MapClientAccessContractTest{
	private static final Gson GSON=new GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();
	private static final String JAVA="src/main/java/org/joinmastodon/android/";
	private String source(String file) throws Exception{ return Files.readString(Path.of(file)); }
	private String fragment() throws Exception{ return source(JAVA+"fragments/discover/FriendMapFragment.java"); }
	private String method(String source,String signature){
		int start=source.indexOf(signature);
		assertTrue("Missing method: "+signature,start>=0);
		int open=source.indexOf('{',start), depth=1, end=open+1;
		for(;depth>0 && end<source.length();end++){
			char c=source.charAt(end); if(c=='{') depth++; else if(c=='}') depth--;
		}
		assertEquals("Unclosed method "+signature,0,depth);
		return source.substring(open+1,end-1);
	}
	private MapModels.Settings settings(String current,String accepted,boolean verified){
		MapModels.Settings result=new MapModels.Settings(); result.consent=new MapModels.Consent();
		result.consent.version=current; result.consent.acceptedVersion=accepted; result.consent.accepted=true; result.babyVerified=verified; result.visibilityConfirmed=true;
		return result;
	}

	@Test public void missingSettingsAndErrorsNeverPermitMapOrPersonalData(){
		assertEquals(MapModels.Access.LOADING,MapModels.accessFor(null));
		for(MapModels.Access access:new MapModels.Access[]{MapModels.Access.LOADING,MapModels.Access.ERROR,MapModels.Access.CONSENT}){
			assertFalse(MapModels.allowsBaseMap(access)); assertFalse(MapModels.allowsPersonalData(access));
		}
	}
	@Test public void v1MissingAndFutureAcceptanceRequireRereading(){
		for(String current:new String[]{null,"map-presence-v1",MapModels.CONSENT_VERSION,"map-presence-v3"}){
			for(String accepted:new String[]{null,"map-presence-v1",MapModels.CONSENT_VERSION,"map-presence-v3"}){
				MapModels.Settings settings=settings(current,accepted,true);
				boolean ready=MapModels.CONSENT_VERSION.equals(current) && MapModels.CONSENT_VERSION.equals(accepted);
				assertEquals(ready,MapModels.consentAccepted(settings.consent));
				assertEquals(ready?MapModels.Access.READY:MapModels.Access.CONSENT,MapModels.accessFor(settings));
			}
		}
		MapModels.Settings settings=settings(MapModels.CONSENT_VERSION,MapModels.CONSENT_VERSION,true);
		settings.consent.accepted=false; assertEquals(MapModels.Access.CONSENT,MapModels.accessFor(settings));
		settings.consent=null; assertEquals(MapModels.Access.CONSENT,MapModels.accessFor(settings));
	}
	@Test public void sdkConsentPrecedesVerificationAndOnlyVerifiedUsersReadPoints(){
		MapModels.Settings unverified=settings(MapModels.CONSENT_VERSION,"map-presence-v1",false);
		assertEquals(MapModels.Access.CONSENT,MapModels.accessFor(unverified));
		unverified.consent.acceptedVersion=MapModels.CONSENT_VERSION;
		assertEquals(MapModels.Access.VERIFICATION,MapModels.accessFor(unverified));
		assertTrue(MapModels.allowsBaseMap(MapModels.accessFor(unverified)));
		assertFalse(MapModels.allowsPersonalData(MapModels.accessFor(unverified)));
		unverified.babyVerified=true;
		assertEquals(MapModels.Access.READY,MapModels.accessFor(unverified));
		assertTrue(MapModels.allowsPersonalData(MapModels.accessFor(unverified)));
	}
	@Test public void firstVisibilityGateIsRequiredAfterConsentAndVerification(){
		MapModels.Settings value=settings(MapModels.CONSENT_VERSION,MapModels.CONSENT_VERSION,true); value.visibilityConfirmed=false;
		assertEquals(MapModels.Access.VISIBILITY,MapModels.accessFor(value));
		assertTrue(MapModels.allowsBaseMap(MapModels.accessFor(value))); assertFalse(MapModels.allowsPersonalData(MapModels.accessFor(value)));
		value.babyVerified=false; assertEquals(MapModels.Access.VERIFICATION,MapModels.accessFor(value));
		value.consent.accepted=false; assertEquals(MapModels.Access.CONSENT,MapModels.accessFor(value));
	}
	@Test public void wireCapabilitiesFailClosedAndExistingPrivacyCanOnlyBeExplicitlyRetained(){
		MapModels.Settings value=GSON.fromJson("{\"visibility\":\"friends\",\"visibility_confirmed\":false,\"capabilities\":{\"can_choose_visibility\":false}}",MapModels.Settings.class);
		assertFalse(value.visibilityConfirmed); assertFalse(MapModels.canChooseVisibility(value,"friends")); assertFalse(MapModels.canChooseVisibility(value,"hidden"));
		assertTrue(MapModels.canChooseVisibility(value,"public")); assertTrue(MapModels.canConfirmVisibility(value,"friends")); assertFalse(MapModels.canConfirmVisibility(value,"hidden"));
		value.visibility="hidden"; assertTrue(MapModels.canConfirmVisibility(value,"hidden")); assertFalse(MapModels.canConfirmVisibility(value,"friends"));
		value.capabilities=null; assertFalse(MapModels.canChooseVisibility(value,"hidden"));
		value.capabilities=new MapModels.Capabilities(); value.capabilities.canChooseVisibility=true;
		for(String visibility:new String[]{"hidden","friends","public"}) assertTrue(MapModels.canChooseVisibility(value,visibility));
		assertFalse(MapModels.canChooseVisibility(value,"unknown"));
	}
	@Test public void deviceLocationPublicationRequiresEveryGateAndNeverHiddenOrPinned(){
		MapModels.Settings value=settings(MapModels.CONSENT_VERSION,MapModels.CONSENT_VERSION,true); value.locationMode="device"; value.visibility="public";
		assertTrue(MapModels.canPublishDeviceLocation(value)); value.visibility="hidden"; assertFalse(MapModels.canPublishDeviceLocation(value));
		value.visibility="friends"; assertTrue(MapModels.canPublishDeviceLocation(value)); value.locationMode="pinned"; assertFalse(MapModels.canPublishDeviceLocation(value));
		value.locationMode="device"; value.visibilityConfirmed=false; assertFalse(MapModels.canPublishDeviceLocation(value));
		value.visibilityConfirmed=true; value.babyVerified=false; assertFalse(MapModels.canPublishDeviceLocation(value));
		value.babyVerified=true; value.consent.accepted=false; assertFalse(MapModels.canPublishDeviceLocation(value));
	}
	@Test public void consentAndSettingsRevisionUseSeparateWireFields(){
		MapModels.Settings result=GSON.fromJson("{\"consent\":{\"version\":\"map-presence-v2\",\"accepted_version\":\"map-presence-v1\",\"accepted\":true},\"settings_version\":42,\"baby_verified\":true}",MapModels.Settings.class);
		assertEquals(42L,result.settingsVersion); assertFalse(MapModels.consentAccepted(result.consent));
		result.consent.acceptedVersion=MapModels.CONSENT_VERSION; assertTrue(MapModels.consentAccepted(result.consent));
		assertTrue(GSON.toJson(result).contains("\"settings_version\":42"));
	}
	@Test public void consentActionChecksScrollProofAndCurrentVersionAgain() throws Exception{
		String action=method(fragment(),"void consent()");
		assertTrue(action.contains("access!=MapModels.Access.CONSENT"));
		assertTrue(action.contains("MapModels.CONSENT_VERSION.equals(settings.consent.version)"));
		assertTrue(action.contains("!overlay.canSubmitConsent()"));
		assertTrue(action.contains("Map.of(\"consent_version\",MapModels.CONSENT_VERSION)"));
	}
	@Test public void cityPrecisionIsSponsorGatedButStoredCityCanBeRetainedOrCoarsened(){
		MapModels.Settings value=GSON.fromJson("{\"precision_level\":\"5km\",\"capabilities\":{\"can_choose_city_precision\":false}}",MapModels.Settings.class);
		assertFalse(MapModels.canChoosePrecision(value,"city"));
		assertTrue(MapModels.canChoosePrecision(value,"5km"));
		assertTrue(MapModels.canChoosePrecision(value,"1km"));
		value.precisionLevel="city";
		assertTrue(MapModels.canChoosePrecision(value,"city"));
		assertTrue(MapModels.canChoosePrecision(value,"5km"));
		value.capabilities.canChooseCityPrecision=true;
		assertTrue(MapModels.canChoosePrecision(value,"city"));
	}
	@Test public void noSdkInitializationBeforeConsentAndUnverifiedBaseMapHasNoSavedLocation() throws Exception{
		String text=fragment();
		String start=method(text,"void startProvider(");
		assertTrue(start.indexOf("if(!baseMapAllowed()) return;")<start.indexOf("MapProviderRegistry.create"));
		assertTrue(method(text,"boolean baseMapAllowed()").contains("consentAccepted()"));
		assertTrue(start.contains("if(!personalDataAllowed()){ camera=neutralCamera(); providerState=null; }"));
		String apply=method(text,"boolean applySettings(");
		assertTrue(apply.indexOf("Access.CONSENT")<apply.indexOf("Access.VERIFICATION"));
		assertTrue(apply.contains("providerState=null; lastCamera=null; overlay.showVerificationGate()"));
		String verification=apply.substring(apply.indexOf("access==MapModels.Access.VERIFICATION"));
		assertTrue(verification.indexOf("provider.destroy(); provider=null")<verification.indexOf("startProvider(preferredProvider)"));
	}
	@Test public void allPersonalDataEntrypointsFailClosed() throws Exception{
		String text=fragment();
		for(String signature:new String[]{"void readSelf(","void locate(","void requestDeviceLocation(boolean","void upload(","void beginPin(","void confirmPin(","void onLocationSelected(","void focus(","void profile(","void queryViewport("}){
			assertTrue(signature,method(text,signature).contains("!personalDataAllowed()"));
		}
		String gate=method(text,"boolean personalDataAllowed()");
		assertTrue(gate.contains("sessionValid()")); assertTrue(gate.contains("consentAccepted()"));
		assertTrue(gate.contains("settings.babyVerified")); assertTrue(gate.contains("MapModels.allowsPersonalData(access)"));
		assertTrue(method(text,"void onRequestPermissionsResult(").contains("code==LOCATION_REQUEST && personalDataAllowed()"));
	}
	@Test public void loadingAndErrorsCancelStaleWorkAndKeepMapInaccessible() throws Exception{
		String text=fragment();
		assertTrue(method(text,"View onCreateView(").contains("overlay.showLoadingGate()"));
		assertTrue(method(text,"void loadSettings(boolean").contains("blockAccess(MapModels.Access.LOADING); overlay.showLoadingGate()"));
		String block=method(text,"void blockAccess(");
		assertTrue(block.contains("generation++")); assertTrue(block.contains("queryGeneration++"));
		assertTrue(block.contains("cancelAll(); clearSnapshotWork()"));
		assertTrue(block.contains("self=null; lastCamera=null; visiblePoints.clear()"));
		assertTrue(block.contains("provider.renderSafe(visiblePoints)"));
		assertTrue(block.contains("overlay.setSampleSource(null)"));
		assertTrue(method(text,"void failAccess(").contains("blockAccess(MapModels.Access.ERROR)"));
		assertTrue(method(text,"void failAccess(").contains("overlay.showErrorGate(message)"));
		assertTrue(method(text,"void showError(").contains("failAccess(getString(message))"));
		assertTrue(method(text,"void cancelAll(").contains("locationRequest.cancel()"));
	}
	@Test public void browsingHasNoSponsorRegionFilterOrCameraBounce() throws Exception{
		String text=fragment();
		assertFalse(text.contains("canViewNationwide"));
		assertFalse(text.contains("restoringCamera")); assertFalse(text.contains("lastLegalCamera"));
		assertTrue(method(text,"void queryViewport(").contains("camera.centerLng(),null,camera.zoom(),100"));
		assertFalse(method(text,"void onCameraIdle(").contains("currentRegion"));
		assertTrue(method(text,"void beginPin(").contains("!settings.canChooseLocation"));
		assertTrue(method(text,"void upload(").contains("\"pinned\".equals(mode) && !settings.canChooseLocation"));
		assertTrue(method(text,"void sponsor(").contains("R.string.map_presence_pin"));
	}
	@Test public void writesUseSettingsRevisionAndPresenceCannotChangePrivacy() throws Exception{
		String text=fragment(),upload=method(text,"void upload(");
		assertTrue(upload.contains("body.put(\"settings_version\",settings.settingsVersion)"));
		for(String field:new String[]{"visibility","precision_level","anonymous"}) assertFalse(upload.contains("body.put(\""+field+"\""));
		String save=method(text,"void saveSettings(");
		assertTrue(save.contains("if(!\"hidden\".equals(changes.get(\"visibility\"))) body.put(\"settings_version\",settings.settingsVersion)"));
		assertTrue(save.contains("MapRequest.updateSettings(body)"));
	}
	@Test public void settingsConflictReloadsWithoutReplayingLocationOrSettingsWrite() throws Exception{
		String text=fragment(),error=method(text,"void showError(");
		assertTrue(error.contains("\"map_settings_changed\".equals(mapError.code)"));
		assertTrue(error.contains("loadSettings(false); return;"));
		String load=method(text,"void loadSettings(boolean");
		assertTrue(load.contains("if(readPresence) readSelf();"));
		assertTrue(load.contains("map_ui_settings_changed"));
		assertFalse(load.contains("requestDeviceLocation()")); assertFalse(load.contains("upload("));
	}
	@Test public void pinModeIsSavedBeforeUploadAndReplyRevisionIsAdopted() throws Exception{
		String text=fragment(),pin=method(text,"void confirmPin(");
		assertTrue(pin.contains("double lat=camera.centerLat(),lng=camera.centerLng()"));
		assertTrue(pin.contains("saveSettings(Map.of(\"location_mode\",\"pinned\"),()->upload(lat,lng,\"pinned\"))"));
		String upload=method(text,"void upload(");
		assertTrue(upload.contains("!mode.equals(settings.locationMode)"));
		assertTrue(upload.contains("if(result.settingsVersion>0) settings.settingsVersion=result.settingsVersion"));
		assertTrue(method(text,"void requestDeviceLocation(boolean").contains("MapModels.canPublishDeviceLocation(settings)"));
		assertTrue(method(text,"void useDeviceLocation(").contains("saveSettings(Map.of(\"location_mode\",\"device\"),this::requestDeviceLocation)"));
	}
	@Test public void revokedVerificationReloadsGateAndVerificationSnapshotsDoNotRequireLiquidMode() throws Exception{
		String text=fragment(),error=method(text,"void showError(");
		assertTrue(error.contains("\"map_verification_required\".equals(mapError.code)"));
		assertTrue(error.contains("\"map_consent_required\".equals(mapError.code)"));
		assertTrue(error.contains("loadSettings(false); return;"));
		assertTrue(method(text,"void scheduleSnapshot(").contains("access!=MapModels.Access.VERIFICATION && !GlobalUserPreferences.isIosLiquidNavigationEnabled()"));
		String activate=method(text,"void updateActivityState(");
		assertTrue(activate.indexOf("overlay.enterMapDefaults()")<activate.indexOf("if(firstPrivacyReveal)"));
	}
	@Test public void offlineLogoutAndLifecycleNeverDeleteOrHideServerPresence() throws Exception{
		String text=fragment();
		assertFalse(text.contains("MapRequest.delete("));
		assertFalse(text.contains("Map.of(\"visibility\",\"hidden\")"));
		for(String signature:new String[]{"void blockAccess(","void cancelAll(","void onPause(","void onDestroyView("}){
			String method=method(text,signature); assertFalse(method.contains("saveSettings(")); assertFalse(method.contains("updatePresence("));
		}
		String sessions=source(JAVA+"api/session/AccountSessionManager.java");
		assertFalse(method(sessions,"void removeAccount(").contains("MapRequest"));
		String cleanup=source("src/main/kotlin/org/joinmastodon/android/novel/NovelAccountDataCleaner.kt");
		assertFalse(cleanup.contains("MapRequest")); assertFalse(cleanup.contains("/map/presence"));
		String read=method(text,"void readSelf(");
		assertTrue(read.contains("requestDeviceLocation(!existing && MapModels.canPublishDeviceLocation(settings))"));
	}
	@Test public void standaloneTitleIsRemovedWithoutChangingTabLabels() throws Exception{
		assertTrue(method(fragment(),"void onCreate(").contains("setTitle(\"\")"));
		assertFalse(fragment().contains("setTitle(R.string.map_presence_title)"));
	}
	@Test public void privacyCopyExplainsVisibilityPersistentLocationAndNoBrowsePaywall() throws Exception{
		String presence=source("src/main/res/values/map_presence.xml"),ui=source("src/main/res/values/map_ui.xml");
		for(String required:new String[]{"我同意以上协议","按你选择的精度和可见性","离线或退出登录不会自动隐藏或删除位置","手动隐藏位置或删除账号","滚动至底部","不受城市或赞助身份限制"}) assertTrue(required,presence.contains(required));
		for(String misleading:new String[]{"普通用户只能查看","仅限定位所在城市","允许的地图范围","已显示的合法地图数据会保留"}){
			assertFalse(misleading,presence.contains(misleading)); assertFalse(misleading,ui.contains(misleading));
		}
		DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
		factory.newDocumentBuilder().parse(Path.of("src/main/res/values/map_presence.xml").toFile());
		factory.newDocumentBuilder().parse(Path.of("src/main/res/values/map_ui.xml").toFile());
	}
}
