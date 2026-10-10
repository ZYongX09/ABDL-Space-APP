package org.joinmastodon.android.ui.map;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import java.util.function.Consumer;

/** Cancellable foreground-only fix. Raw location is neither logged nor persisted. */
public final class MapDeviceLocation{
	private MapDeviceLocation(){}
	public interface Request{ void cancel(); }
	public static boolean permitted(Context context){
		return ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
				|| ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
	}

	@SuppressLint("MissingPermission")
	public static Request once(Context context,Consumer<Location> callback){
		LocationManager manager=(LocationManager)context.getSystemService(Context.LOCATION_SERVICE);
		Handler main=new Handler(Looper.getMainLooper());
		class Fix implements LocationListener,Request{
			boolean finished;
			final Runnable timeout=()->finish(null);
			void finish(Location location){
				if(finished) return;
				finished=true; main.removeCallbacks(timeout);
				if(manager!=null){ try{ manager.removeUpdates(this); }catch(SecurityException ignored){} }
				callback.accept(location);
			}
			@Override public void cancel(){
				if(finished) return;
				finished=true; main.removeCallbacks(timeout);
				if(manager!=null){ try{ manager.removeUpdates(this); }catch(SecurityException ignored){} }
			}
			@Override public void onLocationChanged(Location location){ finish(location); }
			@Override public void onStatusChanged(String provider,int status,Bundle extras){}
			@Override public void onProviderEnabled(String provider){}
			@Override public void onProviderDisabled(String provider){}
		}
		Fix fix=new Fix();
		if(manager==null || !permitted(context)){ main.post(()->fix.finish(null)); return fix; }
		try{
			Location best=null;
			for(String provider:manager.getProviders(true)){
				Location candidate=manager.getLastKnownLocation(provider);
				if(candidate==null || candidate.getElapsedRealtimeNanos()<=0 || SystemClock.elapsedRealtimeNanos()-candidate.getElapsedRealtimeNanos()>120_000_000_000L) continue;
				if(best==null || candidate.getElapsedRealtimeNanos()>best.getElapsedRealtimeNanos()) best=candidate;
			}
			if(best!=null){ Location result=new Location(best); main.post(()->fix.finish(result)); return fix; }
			boolean fine=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
			String provider=manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER
					: fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : null;
			if(provider==null){ main.post(()->fix.finish(null)); return fix; }
			manager.requestSingleUpdate(provider,fix,Looper.getMainLooper());
			main.postDelayed(fix.timeout,20_000);
		}catch(SecurityException | IllegalArgumentException error){ main.post(()->fix.finish(null)); }
		return fix;
	}
}
