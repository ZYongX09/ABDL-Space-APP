package org.joinmastodon.android.ui.map;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.api.requests.map.MapRequest;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.discover.FriendMapFragment;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.model.map.MapModels;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.utils.V;

import static org.junit.Assert.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowLocationManager;

/** Runs the real fragment, overlay, permission and location code; only SDK creation and API transport are replaced. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,31,35}, qualifiers="mdpi", application=CompatibilityTestApplication.class,
        shadows={FriendMapFragmentBehaviorTest.ApiExecution.class,FriendMapFragmentBehaviorTest.Providers.class,FriendMapFragmentBehaviorTest.Permissions.class})
public class FriendMapFragmentBehaviorTest{
    private Activity activity;
    private FriendMapFragment fragment;
    private Map<String,AccountSession> sessions;
    private String accountId;
    private MapModels.Settings settings;
    private final List<View> sources=new ArrayList<>();
    private final List<Bitmap> snapshots=new ArrayList<>();

    @Before @SuppressWarnings("unchecked") public void setup() throws Exception{
        Context context=RuntimeEnvironment.getApplication(); MastodonApp.context=context; V.setApplicationContext(context);
        context.getSharedPreferences("map_permissions",Context.MODE_PRIVATE).edit().clear().commit();
        ((ShadowApplication)Shadow.extract(context)).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION);
        ApiExecution.requests.clear(); Providers.current=null; Permissions.requests.clear();
        var constructor=AccountSession.class.getDeclaredConstructor(); constructor.setAccessible(true);
        AccountSession session=constructor.newInstance(); session.domain="map-offline.example.test";
        session.self=new Account(); session.self.id="map-test"; session.token=new Token(); accountId=session.getID();
        sessions=ReflectionHelpers.getField(AccountSessionManager.getInstance(),"sessions"); sessions.put(accountId,session);
        activity=Robolectric.buildActivity(Activity.class).setup().get(); activity.setTheme(R.style.Theme_Mastodon_Dark);
        FrameLayout root=new FrameLayout(activity); root.setId(12345); activity.setContentView(root);
        fragment=FriendMapFragment.newInstance(accountId); fragment.getArguments().putBoolean("__is_tab",true);
        activity.getFragmentManager().beginTransaction().add(12345,fragment).commit(); activity.getFragmentManager().executePendingTransactions();
        fragment.setMapBackdropListener((source,snapshot)->{ sources.add(source); snapshots.add(snapshot); });
        fragment.setTabVisible(true);
        settings=new MapModels.Settings(); settings.consent=new MapModels.Consent(); settings.consent.version=MapModels.CONSENT_VERSION;
        settings.visibility="public"; settings.precisionLevel="city"; settings.locationMode="device"; settings.settingsVersion=7;
    }
    @After public void cleanup(){
        if(fragment!=null) fragment.onDestroyView();
        if(sessions!=null) sessions.remove(accountId);
        ApiExecution.requests.clear(); Providers.current=null;
    }
    private void accepted(){ settings.consent.accepted=true; settings.consent.acceptedVersion=MapModels.CONSENT_VERSION; }
    private MastodonAPIRequest<?> last(){ return ApiExecution.requests.get(ApiExecution.requests.size()-1); }
    private static Field field(Class<?> type,String name) throws Exception{
        while(type!=null){ try{ Field field=type.getDeclaredField(name); field.setAccessible(true); return field; }catch(NoSuchFieldException ignored){ type=type.getSuperclass(); } }
        throw new NoSuchFieldException(name);
    }
    @SuppressWarnings("unchecked") private void reply(MastodonAPIRequest<?> request,Object value) throws Exception{
        ((Callback<Object>)field(request.getClass(),"callback").get(request)).onSuccess(value);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> body(MastodonAPIRequest<?> request) throws Exception{
        return (Map<String,Object>)field(MastodonAPIRequest.class,"requestBody").get(request);
    }
    private void grantLocation(){
        ((ShadowApplication)Shadow.extract(RuntimeEnvironment.getApplication())).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION);
        LocationManager manager=(LocationManager)activity.getSystemService(Context.LOCATION_SERVICE);
        ((ShadowLocationManager)Shadow.extract(manager)).setProviderEnabled(LocationManager.NETWORK_PROVIDER,true);
        Location location=new Location(LocationManager.NETWORK_PROVIDER); location.setLatitude(31.25); location.setLongitude(121.5);
        location.setElapsedRealtimeNanos(Math.max(1,SystemClock.elapsedRealtimeNanos()));
        ((ShadowLocationManager)Shadow.extract(manager)).setLastKnownLocation(LocationManager.NETWORK_PROVIDER,location);
    }
    private long presenceWrites(){ return ApiExecution.requests.stream().filter(r->r.getMethod().equals("PUT") && r.getURL().getPath().endsWith("/presence")).count(); }
    private FriendMapOverlay overlay(){ return ReflectionHelpers.getField(fragment,"overlay"); }
    private MapModels.Access access(){ return ReflectionHelpers.getField(fragment,"access"); }
    private void readyWithStop() throws Exception{
        accepted(); settings.babyVerified=true; settings.visibilityConfirmed=true; settings.anonymous=true;
        settings.capabilities=new MapModels.Capabilities(); settings.capabilities.canStopService=true;
        reply(last(),settings);
    }
    private MapModels.Settings stoppedSettings(){
        MapModels.Settings result=new MapModels.Settings(); result.consent=new MapModels.Consent(); result.consent.version=MapModels.CONSENT_VERSION;
        result.visibility="hidden"; result.precisionLevel="city"; result.locationMode="device"; result.settingsVersion=8;
        result.babyVerified=true; result.anonymous=true; result.capabilities=new MapModels.Capabilities(); result.capabilities.canStopService=true;
        return result;
    }
    private AlertDialog stopDialog(){ return ReflectionHelpers.getField(fragment,"stopServiceDialog"); }
    private MastodonAPIRequest<?> confirmStop(){
        fragment.stopService(); AlertDialog dialog=stopDialog(); assertNotNull(dialog); assertTrue(dialog.isShowing());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); ShadowLooper.idleMainLooper(); return last();
    }
    @SuppressWarnings("unchecked") private void fail(MastodonAPIRequest<?> request,int status) throws Exception{
        ((Callback<Object>)field(request.getClass(),"callback").get(request)).onError(new MastodonErrorResponse("stop failed",status,null));
    }
    private MapModels.Settings expirySettings(boolean confirmed){
        MapModels.Settings result=new MapModels.Settings(); result.consent=new MapModels.Consent(); result.consent.version=MapModels.CONSENT_VERSION;
        result.consent.accepted=true; result.consent.acceptedVersion=MapModels.CONSENT_VERSION;
        result.visibility="hidden"; result.precisionLevel="city"; result.locationMode="device"; result.settingsVersion=8;
        result.babyVerified=true; result.visibilityConfirmed=confirmed; result.capabilities=new MapModels.Capabilities();
        return result;
    }

    @Test public void strictGateOrderRequiresExplicitChoiceBeforePermissionAndHiddenCentersLocally() throws Exception{
        reply(last(),settings);
        assertNull(Providers.current); assertNull(((ShadowActivity)Shadow.extract(activity)).getLastRequestedPermission()); assertEquals(1,ApiExecution.requests.size());
        fragment.refresh(); accepted(); reply(last(),settings);
        assertNotNull(Providers.current); assertNull(((ShadowActivity)Shadow.extract(activity)).getLastRequestedPermission());
        assertEquals(2,ApiExecution.requests.size());
        fragment.refresh(); settings.babyVerified=true; reply(last(),settings);
        FriendMapOverlay overlay=ReflectionHelpers.getField(fragment,"overlay");
        assertEquals(MapSheetState.Page.VISIBILITY,overlay.state().page()); assertNull(((ShadowActivity)Shadow.extract(activity)).getLastRequestedPermission());
        assertEquals(3,ApiExecution.requests.size());
        settings.capabilities=new MapModels.Capabilities(); settings.capabilities.canChooseVisibility=true;
        fragment.confirmVisibility("hidden");
        assertEquals("hidden",body(last()).get("visibility")); assertEquals(Boolean.TRUE,body(last()).get("visibility_confirmed"));
        settings.visibility="hidden"; settings.visibilityConfirmed=true; reply(last(),settings);
        assertEquals(1,Permissions.requests.size());
        assertArrayEquals(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION},Permissions.requests.get(0));
        grantLocation(); fragment.onRequestPermissionsResult(7041,new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},new int[]{0});
        ShadowLooper.idleMainLooper();
        assertNotNull(Providers.current.camera); assertEquals(31.25,Providers.current.camera.centerLat(),0.0001);
        assertEquals(121.5,Providers.current.camera.centerLng(),0.0001); assertEquals(0,presenceWrites());
    }
    @Test public void publicDeviceFixUploadsOnlyAfterGatesAndUsesSettingsRevision() throws Exception{
        grantLocation(); accepted(); settings.babyVerified=true; settings.visibilityConfirmed=true; reply(last(),settings);
        MapModels.Presence empty=new MapModels.Presence(); reply(last(),empty); ShadowLooper.idleMainLooper();
        assertEquals(1,presenceWrites()); Map<String,Object> upload=body(last());
        assertEquals(31.25,(Double)upload.get("latitude"),0.0001); assertEquals(121.5,(Double)upload.get("longitude"),0.0001);
        assertEquals(7L,upload.get("settings_version")); assertFalse(upload.containsKey("visibility"));
    }
    @Test public void pinnedOrExistingPresenceStillCentersLocallyWithoutAutomaticReplacement() throws Exception{
        for(boolean existing:new boolean[]{false,true}){
            grantLocation(); accepted(); settings.babyVerified=true; settings.visibilityConfirmed=true; settings.locationMode="pinned";
            if(existing) fragment.refresh();
            reply(last(),settings);
            MapModels.Presence presence=new MapModels.Presence(); presence.active=existing;
            if(existing){ presence.point=new MapModels.Point(); presence.point.lat=40; presence.point.lng=116; }
            reply(last(),presence); ShadowLooper.idleMainLooper();
            assertEquals(0,presenceWrites()); assertEquals(31.25,Providers.current.camera.centerLat(),0.0001);
        }
    }
    @Test public void existingDevicePresenceIsNotAutomaticallyOverwrittenByBrowsingFix() throws Exception{
        grantLocation(); accepted(); settings.babyVerified=true; settings.visibilityConfirmed=true; reply(last(),settings);
        MapModels.Presence presence=new MapModels.Presence(); presence.active=true; presence.point=new MapModels.Point(); presence.point.lat=40; presence.point.lng=116;
        reply(last(),presence); ShadowLooper.idleMainLooper();
        assertEquals(0,presenceWrites()); assertEquals(31.25,Providers.current.camera.centerLat(),0.0001);
    }
    @Test public void hidingCancelsPendingLocationAndLifecycleNeverWritesServerPrivacy() throws Exception{
        grantLocation(); accepted(); settings.babyVerified=true; settings.visibilityConfirmed=true; reply(last(),settings);
        reply(last(),new MapModels.Presence());
        fragment.stopAppearance(); settings.visibility="hidden"; reply(last(),settings);
        ShadowLooper.idleMainLooper(); assertEquals(0,presenceWrites());
        int requests=ApiExecution.requests.size(); fragment.setTabVisible(false); fragment.onDestroyView();
        assertEquals(requests,ApiExecution.requests.size());
    }
    @Test public void everyExplicitVisibilityWriteConfirmsAndWithdrawalBypassesExpiredEntitlements() throws Exception{
        accepted(); settings.babyVerified=true; settings.visibilityConfirmed=true; settings.visibility="friends"; reply(last(),settings);
        fragment.visibility("public");
        assertEquals(Boolean.TRUE,body(last()).get("visibility_confirmed")); assertEquals(7L,body(last()).get("settings_version"));
        settings.babyVerified=false; settings.visibility="friends"; reply(last(),settings);
        fragment.stopAppearance();
        assertEquals("hidden",body(last()).get("visibility")); assertFalse(body(last()).containsKey("settings_version"));
        assertFalse(body(last()).containsKey("visibility_confirmed")); assertEquals(0,presenceWrites());
    }
    @Test public void providerFailureStillAllowsWithdrawalWithoutConsentOrSponsorUpgrade() throws Exception{
        accepted(); settings.babyVerified=true; settings.visibilityConfirmed=true; settings.visibility="public"; reply(last(),settings);
        ReflectionHelpers.setField(fragment,"access",MapModels.Access.ERROR);
        FriendMapOverlay overlay=ReflectionHelpers.getField(fragment,"overlay"); overlay.showErrorGate("SDK failure");
        fragment.stopAppearance();
        assertEquals("hidden",body(last()).get("visibility")); assertFalse(body(last()).containsKey("visibility_confirmed"));
        assertFalse(body(last()).containsKey("settings_version")); assertEquals(0,presenceWrites());
    }
    @Test public void existingPrivacyCanBeExplicitlyRetainedWithoutCapabilityAndRestrictedClicksOnlyShowSheet() throws Exception{
        accepted(); settings.babyVerified=true; settings.visibility="friends"; reply(last(),settings);
        int count=ApiExecution.requests.size(); fragment.confirmVisibility("hidden");
        Object sheet=ReflectionHelpers.getField(fragment,"sponsorSheet"); assertNotNull(sheet);
        fragment.confirmVisibility("hidden"); assertSame(sheet,ReflectionHelpers.getField(fragment,"sponsorSheet"));
        assertEquals(count,ApiExecution.requests.size()); assertNull(((ShadowActivity)Shadow.extract(activity)).getLastRequestedPermission());
        ((android.app.Dialog)sheet).dismiss(); fragment.confirmVisibility("friends");
        assertEquals("friends",body(last()).get("visibility")); assertEquals(Boolean.TRUE,body(last()).get("visibility_confirmed"));
        assertEquals(0,presenceWrites());
    }
    @Test public void expiredPinnedFriendsCanBeRetainedWhileReturningToDeviceWithoutPublicIntermediateState() throws Exception{
        accepted(); settings.babyVerified=true; settings.visibility="friends"; settings.locationMode="pinned";
        settings.canChooseLocation=false; reply(last(),settings);
        fragment.confirmVisibility("friends");
        assertEquals("friends",body(last()).get("visibility"));
        assertEquals(Boolean.TRUE,body(last()).get("visibility_confirmed"));
        assertEquals("device",body(last()).get("location_mode"));
        assertEquals(7L,body(last()).get("settings_version")); assertEquals(0,presenceWrites());
    }
    @Test public void cityPrecisionRequiresCapabilityAndExistingCityMayBeRetained() throws Exception{
        accepted(); settings.babyVerified=true; settings.visibilityConfirmed=true; settings.visibility="public"; settings.precisionLevel="5km"; reply(last(),settings);
        int count=ApiExecution.requests.size(); fragment.precision("city");
        assertNotNull(ReflectionHelpers.getField(fragment,"sponsorSheet")); assertEquals(count,ApiExecution.requests.size());
        ((android.app.Dialog)ReflectionHelpers.getField(fragment,"sponsorSheet")).dismiss();
        fragment.refresh(); settings.precisionLevel="city"; reply(last(),settings); fragment.precision("city");
        assertEquals("city",body(last()).get("precision_level")); assertEquals(7L,body(last()).get("settings_version"));
        assertEquals(0,presenceWrites());
    }
    @Test public void stopRequestUsesExactEndpointEmptyBodyAndSensitiveTransport() throws Exception{
        MapRequest<MapModels.Settings> request=MapRequest.stopService();
        assertEquals("POST",request.getMethod()); assertEquals("/api/v1/map/service/stop",request.getURL().getPath());
        assertTrue(body(request).isEmpty()); assertTrue(request.isSensitiveRequest());
        assertEquals(MapModels.Settings.class,field(MastodonAPIRequest.class,"respClass").get(request));
    }
    @Test public void stopRequiresM3ConfirmationCancelDoesNotWriteAndHidingDismissesIt() throws Exception{
        readyWithStop(); int count=ApiExecution.requests.size(); fragment.stopService();
        AlertDialog first=stopDialog(); assertNotNull(first); assertTrue(first.isShowing());
        assertEquals(activity.getString(R.string.map_ui_stop_service),first.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        android.widget.TextView message=first.findViewById(android.R.id.message);
        assertNotNull(message); assertEquals(activity.getString(R.string.map_ui_stop_service_confirm),message.getText().toString());
        fragment.stopService(); assertSame(first,stopDialog()); assertEquals(count,ApiExecution.requests.size());
        first.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); ShadowLooper.idleMainLooper();
        assertNull(stopDialog()); assertEquals(count,ApiExecution.requests.size()); assertEquals(MapModels.Access.READY,access());
        assertTrue(settings.consent.accepted); fragment.stopService(); AlertDialog second=stopDialog(); assertNotNull(second);
        fragment.setTabVisible(false); assertFalse(second.isShowing()); assertNull(stopDialog());
        second.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); assertEquals(count,ApiExecution.requests.size());
        assertTrue(settings.consent.accepted);
    }
    @Test public void successfulStopCancelsEveryOldWorkItemAndReentersConsentWithNoSdk() throws Exception{
        grantLocation(); readyWithStop(); RecordingProvider provider=Providers.current;
        MapModels.Point oldPoint=new MapModels.Point(); oldPoint.lat=40; oldPoint.lng=116;
        ReflectionHelpers.setField(fragment,"self",oldPoint); provider.showFuzzArea(oldPoint);
        ((List<MapModels.Point>)ReflectionHelpers.getField(fragment,"visiblePoints")).add(oldPoint); overlay().setPoints(List.of(oldPoint));
        provider.setCamera(new MapProvider.CameraState(30,120,32,122,31,121,11,null)); fragment.onCameraIdle(provider.camera);
        ShadowLooper.idleMainLooper(450,TimeUnit.MILLISECONDS);
        Consumer<Bitmap> lateSnapshot=provider.snapshot; assertNotNull(lateSnapshot);
        MastodonAPIRequest<?> lateViewport=last(); assertTrue(lateViewport.getURL().getPath().endsWith("/presence"));
        MastodonAPIRequest<?> completedPresence=ApiExecution.requests.stream().filter(r->r.getURL().getPath().endsWith("/presence/me")).findFirst().orElseThrow();
        int generation=ReflectionHelpers.getField(fragment,"generation");
        fragment.stopService(); AlertDialog dialog=stopDialog(); assertNotNull(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        // AlertDialog queues its listener; enqueue the cached fix after that accepted stop intent.
        assertEquals(generation,(int)ReflectionHelpers.getField(fragment,"generation"));
        reply(completedPresence,new MapModels.Presence());
        assertNull("A delivered readSelf response is no longer an in-flight request",ReflectionHelpers.getField(fragment,"presenceRequest"));
        assertNotNull(ReflectionHelpers.getField(fragment,"locationRequest"));
        // Keep a separate read genuinely in flight: completed requests cannot be cancelled retroactively.
        ReflectionHelpers.callInstanceMethod(fragment,"readSelf"); MastodonAPIRequest<?> pendingPresence=last();
        assertNotSame(completedPresence,pendingPresence); assertSame(pendingPresence,ReflectionHelpers.getField(fragment,"presenceRequest"));
        assertFalse((Boolean)field(MastodonAPIRequest.class,"canceled").get(pendingPresence));
        ReflectionHelpers.setField(fragment,"providerState",new Bundle());
        assertEquals(0,presenceWrites());
        ShadowLooper.idleMainLooper(); MastodonAPIRequest<?> request=last();
        assertEquals("/api/v1/map/service/stop",request.getURL().getPath()); assertEquals(MapModels.Access.LOADING,access());
        assertTrue((Integer)ReflectionHelpers.getField(fragment,"generation")>generation);
        assertNull(ReflectionHelpers.getField(fragment,"locationRequest")); assertTrue((Boolean)field(MastodonAPIRequest.class,"canceled").get(lateViewport));
        assertTrue((Boolean)field(MastodonAPIRequest.class,"canceled").get(pendingPresence));
        assertNull(ReflectionHelpers.getField(fragment,"self")); assertNull(provider.fuzz); assertTrue(provider.rendered.isEmpty());
        assertNull(sources.get(sources.size()-1)); assertNull(snapshots.get(snapshots.size()-1));
        assertFalse((Boolean)ReflectionHelpers.getField(fragment,"snapshotBusy")); assertTrue(provider.pauses>0);
        MapModels.Settings result=stoppedSettings(); reply(request,result); ShadowLooper.idleMainLooper();
        assertSame(result,ReflectionHelpers.getField(fragment,"settings")); assertEquals(MapModels.Access.CONSENT,access());
        assertEquals(MapSheetState.Page.CONSENT,overlay().state().page()); assertTrue(overlay().state().consentRequired());
        assertFalse(overlay().canSubmitConsent()); assertNull(ReflectionHelpers.getField(fragment,"provider")); assertTrue(provider.destroyed);
        assertEquals(0,overlay().mapHost().getChildCount()); assertNull(ReflectionHelpers.getField(fragment,"providerState"));
        assertNull(ReflectionHelpers.getField(fragment,"lastCamera")); assertFalse(result.consent.accepted); assertTrue(result.anonymous);
        int count=ApiExecution.requests.size(),events=sources.size();
        lateSnapshot.accept(Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888));
        MapModels.PresenceList oldList=new MapModels.PresenceList(); oldList.points=List.of(oldPoint); reply(lateViewport,oldList);
        reply(completedPresence,new MapModels.Presence()); reply(pendingPresence,new MapModels.Presence());
        ShadowLooper.idleMainLooper(1,TimeUnit.SECONDS);
        assertEquals(count,ApiExecution.requests.size()); assertEquals(events,sources.size()); assertEquals(0,presenceWrites());
        assertTrue(((List<?>)ReflectionHelpers.getField(fragment,"visiblePoints")).isEmpty()); assertNull(fragment.getMapSampleView());
    }
    @Test public void networkFailureAndUnsupportedStopNeverChangeConsentOrFallbackToHide() throws Exception{
        for(int status:new int[]{-1,404,405,500,501}){
            fragment.refresh(); readyWithStop(); RecordingProvider provider=Providers.current; MastodonAPIRequest<?> request=confirmStop();
            int count=ApiExecution.requests.size(); fail(request,status);
            assertEquals(count,ApiExecution.requests.size()); assertSame(settings,ReflectionHelpers.getField(fragment,"settings"));
            assertTrue(settings.consent.accepted); assertEquals("public",settings.visibility); assertTrue(settings.visibilityConfirmed);
            assertEquals(MapModels.Access.ERROR,access()); assertFalse(provider.destroyed); assertFalse(overlay().state().consentRequired());
            assertEquals(0,presenceWrites());
        }
    }
    @Test public void hideOnlyStaleOrMalformedSuccessCannotClaimAFullStop() throws Exception{
        for(String invalid:new String[]{"consent","visibility","confirmed","active","point","capability","revision","version"}){
            fragment.refresh(); readyWithStop(); MastodonAPIRequest<?> request=confirmStop(); MapModels.Settings result=stoppedSettings();
            switch(invalid){
                case "consent" -> { result.consent.accepted=true; result.consent.acceptedVersion=MapModels.CONSENT_VERSION; }
                case "visibility" -> result.visibility="public";
                case "confirmed" -> result.visibilityConfirmed=true;
                case "active" -> result.active=true;
                case "point" -> result.point=new MapModels.Point();
                case "capability" -> result.capabilities=null;
                case "revision" -> result.settingsVersion=7;
                case "version" -> result.consent.version="map-presence-v1";
            }
            int count=ApiExecution.requests.size(); reply(request,result);
            assertEquals(invalid,MapModels.Access.ERROR,access()); assertEquals(count,ApiExecution.requests.size());
            assertSame(settings,ReflectionHelpers.getField(fragment,"settings")); assertTrue(settings.consent.accepted); assertEquals("public",settings.visibility);
        }
    }
    @Test public void missingStopCapabilityNeverSendsAStopOrChangesLegacyWithdrawal() throws Exception{
        accepted(); settings.babyVerified=true; settings.visibilityConfirmed=true; reply(last(),settings);
        int count=ApiExecution.requests.size(); fragment.stopService(); assertNull(stopDialog()); assertEquals(count,ApiExecution.requests.size());
        fragment.stopAppearance(); assertEquals("PUT",last().getMethod()); assertEquals("/api/v1/map/settings",last().getURL().getPath());
        assertEquals(Map.of("visibility","hidden"),body(last())); assertTrue(settings.consent.accepted);
    }
    @Test public void lateStopResponseAfterLeavingIsIgnoredUntilAnAuthoritativeReload() throws Exception{
        readyWithStop(); MastodonAPIRequest<?> request=confirmStop(); fragment.setTabVisible(false); int count=ApiExecution.requests.size();
        reply(request,stoppedSettings()); assertEquals(count,ApiExecution.requests.size()); assertTrue(settings.consent.accepted); assertEquals(MapModels.Access.LOADING,access());
        fragment.setTabVisible(true); assertEquals("GET",last().getMethod()); assertEquals("/api/v1/map/settings",last().getURL().getPath());
        reply(last(),stoppedSettings()); assertEquals(MapModels.Access.CONSENT,access()); assertNull(ReflectionHelpers.getField(fragment,"provider"));
    }
    @Test public void removingAnonymousToggleNeverForcesFalseInAnySettingsWrite() throws Exception{
        readyWithStop(); fragment.visibility("public"); assertFalse(body(last()).containsKey("anonymous"));
        settings.settingsVersion=8; reply(last(),settings); fragment.precision("5km"); assertFalse(body(last()).containsKey("anonymous"));
        settings.settingsVersion=9; reply(last(),settings); fragment.useDeviceLocation(); assertFalse(body(last()).containsKey("anonymous"));
        settings.settingsVersion=10; reply(last(),settings); MastodonAPIRequest<?> stop=confirmStop(); assertTrue(body(stop).isEmpty());
        assertTrue(settings.anonymous); assertEquals(0,presenceWrites());
    }
    @Test public void onShownReloadsPreviouslyHiddenSettingsAndServerExpiryRequiresVisibilityAgain() throws Exception{
        accepted(); settings.babyVerified=true; settings.visibilityConfirmed=true; settings.visibility="hidden"; reply(last(),settings);
        RecordingProvider previous=Providers.current; MapModels.Point old=new MapModels.Point();
        ((List<MapModels.Point>)ReflectionHelpers.getField(fragment,"visiblePoints")).add(old); providerRender(previous,List.of(old));
        int count=ApiExecution.requests.size(); ReflectionHelpers.callInstanceMethod(fragment,"onShown");
        assertEquals(count+1,ApiExecution.requests.size()); assertEquals("GET",last().getMethod()); assertEquals("/api/v1/map/settings",last().getURL().getPath());
        MapModels.Settings expired=expirySettings(false); reply(last(),expired);
        assertEquals(MapModels.Access.VISIBILITY,access()); assertEquals(MapSheetState.Page.VISIBILITY,overlay().state().page());
        assertSame(expired,ReflectionHelpers.getField(fragment,"settings")); assertEquals(8,expired.settingsVersion); assertTrue(expired.consent.accepted);
        assertTrue(previous.destroyed); assertTrue(previous.rendered.isEmpty()); assertTrue(((List<?>)ReflectionHelpers.getField(fragment,"visiblePoints")).isEmpty());
        assertNotSame(previous,Providers.current); assertEquals(35,Providers.current.camera.centerLat(),0.0001); assertEquals(105,Providers.current.camera.centerLng(),0.0001);
        assertTrue(Permissions.requests.isEmpty()); assertEquals(0,presenceWrites());
    }
    @Test public void capabilityLossAloneDoesNotInventAnExpiryOrResetConsent() throws Exception{
        readyWithStop(); ReflectionHelpers.callInstanceMethod(fragment,"onShown"); MapModels.Settings retained=expirySettings(true); reply(last(),retained);
        assertEquals(MapModels.Access.READY,access()); assertTrue(retained.visibilityConfirmed); assertTrue(retained.consent.accepted); assertEquals("hidden",retained.visibility);
        assertEquals(MapModels.Access.READY,MapModels.accessFor(retained)); assertFalse(MapModels.canStopService(retained));
    }
    @Test public void inflightSettingsRefreshAdoptsExpiryRevisionAndRejectsOldViewportPoints() throws Exception{
        readyWithStop(); RecordingProvider previous=Providers.current;
        previous.setCamera(new MapProvider.CameraState(30,120,32,122,31,121,11,null)); fragment.onCameraIdle(previous.camera);
        ShadowLooper.idleMainLooper(450,TimeUnit.MILLISECONDS);
        MastodonAPIRequest<?> viewport=last(); assertEquals("/api/v1/map/presence",viewport.getURL().getPath());
        fragment.refresh(); MastodonAPIRequest<?> refresh=last(); MapModels.Settings expired=expirySettings(false); reply(refresh,expired);
        MapModels.PresenceList old=new MapModels.PresenceList(); old.points=List.of(new MapModels.Point()); reply(viewport,old);
        assertEquals(MapModels.Access.VISIBILITY,access()); assertEquals(8,((MapModels.Settings)ReflectionHelpers.getField(fragment,"settings")).settingsVersion);
        assertTrue(previous.destroyed); assertTrue(((List<?>)ReflectionHelpers.getField(fragment,"visiblePoints")).isEmpty()); assertNull(ReflectionHelpers.getField(fragment,"self"));
        assertTrue(Permissions.requests.isEmpty()); assertEquals(0,presenceWrites());
    }
    private void providerRender(RecordingProvider provider,List<MapModels.Point> points){ provider.render(points); overlay().setPoints(points); }

    @Test public void sdkBackdropReplaysProviderMapIdentityAndInvalidatesOnExitAndLateCallback() throws Exception{
        assertNull(sources.get(0)); assertNull(snapshots.get(0));
        accepted(); settings.babyVerified=true; settings.visibilityConfirmed=true; reply(last(),settings);
        RecordingProvider provider=Providers.current;
        assertSame(provider.map,sources.get(sources.size()-1)); assertNotSame(provider.getView(),sources.get(sources.size()-1));
        ShadowLooper.idleMainLooper(350,TimeUnit.MILLISECONDS); assertNotNull(provider.snapshot);
        Bitmap bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888); provider.snapshot.accept(bitmap); ShadowLooper.idleMainLooper();
        assertSame(bitmap,snapshots.get(snapshots.size()-1));
        List<Bitmap> replay=new ArrayList<>(); fragment.setMapBackdropListener((source,snapshot)->{ assertSame(provider.map,source); replay.add(snapshot); });
        assertEquals(1,replay.size()); assertSame(bitmap,replay.get(0));
        fragment.setMapBackdropListener((source,snapshot)->{ sources.add(source); snapshots.add(snapshot); });
        FriendMapOverlay overlay=ReflectionHelpers.getField(fragment,"overlay");
        assertTrue((Boolean)ReflectionHelpers.getField(overlay,"hasSnapshot"));
        fragment.onCameraMove(); assertSame(provider.map,sources.get(sources.size()-1)); assertSame(bitmap,snapshots.get(snapshots.size()-1));
        assertTrue("Moving the camera must preserve panel glass as well as navigation glass",(Boolean)ReflectionHelpers.getField(overlay,"hasSnapshot"));
        fragment.setTabVisible(false); assertNull(sources.get(sources.size()-1)); assertNull(snapshots.get(snapshots.size()-1));
        assertFalse((Boolean)ReflectionHelpers.getField(overlay,"hasSnapshot"));
        int events=sources.size(); provider.snapshot.accept(bitmap); ShadowLooper.idleMainLooper(); assertEquals(events,sources.size());
        fragment.onDestroyView(); assertNull(sources.get(sources.size()-1)); assertNull(snapshots.get(snapshots.size()-1));
    }
    @Test public void cameraMoveKeepsLastSnapshotUntilTheProviderCanPublishAnother() throws Exception{
        accepted(); settings.babyVerified=true; settings.visibilityConfirmed=true; reply(last(),settings);
        RecordingProvider provider=Providers.current;
        ShadowLooper.idleMainLooper(350,TimeUnit.MILLISECONDS); Bitmap first=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888); provider.snapshot.accept(first); ShadowLooper.idleMainLooper();
        fragment.onCameraMove(); assertSame(first,snapshots.get(snapshots.size()-1));
        fragment.onCameraIdle(provider.getCamera());
        assertSame(first,snapshots.get(snapshots.size()-1));
    }
    @Test public void snapshotTimeoutRejectsLateBitmapAndPreservesPublishedSnapshot() throws Exception{
        accepted(); settings.babyVerified=true; settings.visibilityConfirmed=true; reply(last(),settings);
        ShadowLooper.idleMainLooper(350,TimeUnit.MILLISECONDS); Consumer<Bitmap> late=Providers.current.snapshot;
        Bitmap first=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888); late.accept(first); ShadowLooper.idleMainLooper();
        fragment.onCameraIdle(Providers.current.getCamera()); ShadowLooper.idleMainLooper(350,TimeUnit.MILLISECONDS); late=Providers.current.snapshot;
        ShadowLooper.idleMainLooper(2600,TimeUnit.MILLISECONDS);
        assertNotNull(snapshots.get(snapshots.size()-1));
        int events=sources.size(); late.accept(Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)); ShadowLooper.idleMainLooper(); assertEquals(events,sources.size());
    }
    @Implements(android.app.Fragment.class) public static class Permissions{
        static final List<String[]> requests=new ArrayList<>();
        @Implementation public void requestPermissions(String[] permissions,int requestCode){
            assertEquals(7041,requestCode); requests.add(permissions.clone());
        }
    }
    @Implements(MastodonAPIRequest.class) public static class ApiExecution{
        static final List<MastodonAPIRequest<?>> requests=new ArrayList<>();
        @RealObject MastodonAPIRequest<?> request;
        @Implementation public MastodonAPIRequest<?> exec(String accountId){ requests.add(request); return request; }
    }
    @Implements(MapProviderRegistry.class) public static class Providers{
        static RecordingProvider current;
        @Implementation public static List<String> available(){ return List.of("baidu"); }
        @Implementation public static MapProvider create(Context context,String id){ return current=new RecordingProvider(context); }
    }
    private static final class RecordingProvider implements MapProvider{
        final FrameLayout host; final View map; CameraState camera; Consumer<Bitmap> snapshot;
        final List<MapModels.Point> rendered=new ArrayList<>(); MapModels.Point fuzz; boolean destroyed; int pauses;
        RecordingProvider(Context context){ host=new FrameLayout(context); map=new View(context); host.addView(map); }
        public View getView(){ return host; } public View getSampleSource(){ return map; }
        public void initialize(Listener listener){ listener.onMapReady(); }
        public void setCamera(CameraState value){ camera=value; } public CameraState getCamera(){ return camera; }
        public void render(List<MapModels.Point> points){ rendered.clear(); rendered.addAll(points); }
        public void clear(){ rendered.clear(); } public void destroy(){ destroyed=true; rendered.clear(); fuzz=null; }
        public void showFuzzArea(MapModels.Point point){ fuzz=point; } public void onPause(){ pauses++; }
        public void requestSnapshot(Consumer<Bitmap> callback){ snapshot=callback; }
    }
}
