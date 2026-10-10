package org.joinmastodon.android.ui.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joinmastodon.android.model.map.MapModels;

abstract class AbstractMapProvider implements MapProvider{
	interface MarkerHandle{
		void position(double latitude, double longitude);
		void icon(Bitmap bitmap);
		void remove();
	}

	protected final Context context;
	protected final FrameLayout container;
	protected final Handler main=new Handler(Looper.getMainLooper());
	protected Listener listener;
	protected CameraState requestedCamera;
	protected boolean ready, destroyed;
	private boolean resumed, failed;
	private Bundle savedState;
	private final MapAvatarRenderer avatars;
	private final Map<String, MarkerHandle> markers=new HashMap<>();
	private final Map<String, MapModels.Point> points=new HashMap<>();
	private final Map<String, String> avatarKeys=new HashMap<>();
	private List<MapModels.Point> pendingPoints=List.of();
	private final Runnable loadTimeout=()->{ if(resumed && !ready) fail("map_load_timeout"); };

	AbstractMapProvider(Context context){
		this.context=context;
		container=new FrameLayout(context);
		avatars=new MapAvatarRenderer(context);
	}

	@Override public View getView(){ return container; }
	@Override public boolean isReady(){ return ready && !destroyed && !failed; }
	@Override public void onCreate(Bundle state){ savedState=state; }
	@Override public void initialize(Listener listener){
		if(destroyed) return;
		this.listener=listener;
		try{ createMap(savedState); }catch(RuntimeException | LinkageError error){ fail("sdk_init_failed"); }
	}
	protected abstract void createMap(Bundle state);
	protected abstract void moveCamera(CameraState camera);
	protected abstract CameraState readCamera();
	protected abstract MarkerHandle addMarker(String id, double latitude, double longitude);
	protected abstract void resumeMap();
	protected abstract void pauseMap();
	protected abstract void saveMap(Bundle state);
	protected abstract void destroyMap();

	protected final void loaded(){
		main.post(()->{
			if(destroyed || failed) return;
			ready=true; main.removeCallbacks(loadTimeout);
			if(requestedCamera!=null) setCamera(requestedCamera);
			render(pendingPoints);
			if(listener!=null) listener.onMapReady();
		});
	}

	protected final void cameraIdle(){
		main.post(()->{
			if(!isReady() || listener==null) return;
			try{ CameraState camera=readCamera(); if(camera!=null) listener.onCameraIdle(camera); }
			catch(RuntimeException error){ fail("camera_failed"); }
		});
	}

	protected final void markerClicked(String id){
		MapModels.Point point=points.get(id);
		if(!destroyed && listener!=null && point!=null) listener.onMarkerClick(point);
	}
	protected final void locationSelected(double lat, double lng){
		main.post(()->{ if(!destroyed && listener!=null) listener.onLocationSelected(lat,lng); });
	}
	protected final void fail(String code){
		main.post(()->{
			if(destroyed || failed) return;
			failed=true; main.removeCallbacks(loadTimeout);
			if(listener!=null) listener.onProviderError(code);
		});
	}

	@Override public void setCamera(CameraState camera){
		requestedCamera=camera;
		if(!ready || destroyed || failed) return;
		try{ moveCamera(camera); }catch(RuntimeException error){ fail("camera_failed"); }
	}
	@Override public CameraState getCamera(){
		if(!isReady()) return requestedCamera;
		try{ return readCamera(); }catch(RuntimeException error){ return requestedCamera; }
	}

	@Override public void render(List<MapModels.Point> newPoints){
		if(destroyed || failed) return;
		pendingPoints=new ArrayList<>(newPoints);
		if(!ready) return;
		Set<String> retained=new HashSet<>();
		try{
			for(MapModels.Point point:newPoints){
				if(point==null || point.id==null || retained.size()>=300) continue;
				new MapCoordinateConverter.Coordinate(point.lat,point.lng);
				String id=point.id; retained.add(id); points.put(id,point);
				MarkerHandle marker=markers.get(id);
				if(marker==null){ marker=addMarker(id,point.lat,point.lng); markers.put(id,marker); }
				else marker.position(point.lat,point.lng);
				String avatarKey=point.anonymous ? "anonymous" : point.account==null ? "default" : String.valueOf(point.account.avatar)+point.account.displayName;
				if(!avatarKey.equals(avatarKeys.get(id))){
					avatarKeys.put(id,avatarKey); MarkerHandle current=marker;
					avatars.render(point,bitmap->{
						if(!destroyed && markers.get(id)==current){
							try{ current.icon(bitmap); }catch(RuntimeException error){ fail("marker_failed"); }
						}
					});
				}
			}
			for(String id:new ArrayList<>(markers.keySet())){
				if(!retained.contains(id)){ markers.remove(id).remove(); points.remove(id); avatarKeys.remove(id); }
			}
		}catch(RuntimeException error){ fail("marker_failed"); }
	}

	@Override public void clear(){
		avatars.clear(); pendingPoints=List.of();
		for(MarkerHandle marker:markers.values()) marker.remove();
		markers.clear(); points.clear(); avatarKeys.clear();
	}
	@Override public void onResume(){
		if(destroyed || failed) return;
		resumed=true;
		try{ resumeMap(); }catch(RuntimeException | LinkageError error){ fail("sdk_resume_failed"); }
		main.removeCallbacks(loadTimeout);
		if(!ready) main.postDelayed(loadTimeout,30_000);
	}
	@Override public void onPause(){
		resumed=false; main.removeCallbacks(loadTimeout);
		if(destroyed) return;
		try{ pauseMap(); }catch(RuntimeException error){ fail("sdk_pause_failed"); }
	}
	@Override public void onSaveInstanceState(Bundle state){ if(!destroyed && !failed) saveMap(state); }
	@Override public void destroy(){
		if(destroyed) return;
		destroyed=true; ready=false; listener=null; main.removeCallbacksAndMessages(null);
		try{ clear(); }finally{ try{ destroyMap(); }finally{ container.removeAllViews(); } }
	}
}
