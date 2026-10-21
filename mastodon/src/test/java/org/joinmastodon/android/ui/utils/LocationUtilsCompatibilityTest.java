package org.joinmastodon.android.ui.utils;

import static org.junit.Assert.*;

import android.Manifest;
import android.content.Context;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowApplication;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,27,28,29,30,31,32}, application=CompatibilityTestApplication.class,
        shadows=LocationUtilsCompatibilityTest.FreshLocationManager.class)
public class LocationUtilsCompatibilityTest{
    @Implements(LocationManager.class)
    public static class FreshLocationManager{
        static LocationListener listener;
        static String provider;
        static Looper looper;
        @Implementation public List<String> getProviders(boolean enabledOnly){ return List.of(); }
        @Implementation public boolean isProviderEnabled(String value){ return LocationManager.NETWORK_PROVIDER.equals(value); }
        @Implementation public void requestSingleUpdate(String value,LocationListener callback,Looper target){
            provider=value; listener=callback; looper=target;
        }
    }

    @Before public void setup(){
        FreshLocationManager.listener=null;
        FreshLocationManager.provider=null;
        FreshLocationManager.looper=null;
        ((ShadowApplication)org.robolectric.shadow.api.Shadow.extract(RuntimeEnvironment.getApplication()))
                .grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    @Test public void freshFixImplementsAllLegacyCallbacksWithoutResolvingOrPostingOnStatusChanges(){
        Context context=RuntimeEnvironment.getApplication();
        AtomicInteger resolutions=new AtomicInteger();
        LocationUtils.fetchAndResolve(context,result->resolutions.incrementAndGet());
        LocationListener listener=FreshLocationManager.listener;
        assertNotNull(listener);
        assertEquals(LocationManager.NETWORK_PROVIDER,FreshLocationManager.provider);
        assertSame(Looper.getMainLooper(),FreshLocationManager.looper);
        listener.onStatusChanged(LocationManager.NETWORK_PROVIDER,0,new Bundle());
        listener.onProviderEnabled(LocationManager.NETWORK_PROVIDER);
        listener.onProviderDisabled(LocationManager.NETWORK_PROVIDER);
        assertEquals(0,resolutions.get());
    }

    @Test public void deniedPermissionDoesNotRegisterAListenerAndReportsNoLocation(){
        ((ShadowApplication)org.robolectric.shadow.api.Shadow.extract(RuntimeEnvironment.getApplication()))
                .denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION);
        AtomicInteger resolutions=new AtomicInteger();
        LocationUtils.fetchAndResolve(RuntimeEnvironment.getApplication(),result->{ assertNull(result); resolutions.incrementAndGet(); });
        assertEquals(1,resolutions.get());
        assertNull(FreshLocationManager.listener);
    }
}
