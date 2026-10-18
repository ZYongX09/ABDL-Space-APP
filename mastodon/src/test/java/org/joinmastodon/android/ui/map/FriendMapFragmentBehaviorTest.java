package org.joinmastodon.android.ui.map;

import android.Manifest;
import android.app.Activity;
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
@Config(sdk=31, application=CompatibilityTestApplication.class,
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
        fragment.onCameraMove(); assertSame(provider.map,sources.get(sources.size()-1)); assertSame(bitmap,snapshots.get(snapshots.size()-1));
        fragment.setTabVisible(false); assertNull(sources.get(sources.size()-1)); assertNull(snapshots.get(snapshots.size()-1));
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
        RecordingProvider(Context context){ host=new FrameLayout(context); map=new View(context); host.addView(map); }
        public View getView(){ return host; } public View getSampleSource(){ return map; }
        public void initialize(Listener listener){ listener.onMapReady(); }
        public void setCamera(CameraState value){ camera=value; } public CameraState getCamera(){ return camera; }
        public void render(List<MapModels.Point> points){} public void clear(){} public void destroy(){}
        public void requestSnapshot(Consumer<Bitmap> callback){ snapshot=callback; }
    }
}
