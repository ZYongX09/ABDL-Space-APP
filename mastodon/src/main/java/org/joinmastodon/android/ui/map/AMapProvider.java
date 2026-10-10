package org.joinmastodon.android.ui.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import java.util.function.Consumer;
import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.utils.UiUtils;
import com.amap.api.maps.model.Circle;
import com.amap.api.maps.model.CircleOptions;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.ExceptionLogger;
import com.amap.api.maps.MapView;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.maps.model.BitmapDescriptor;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.CameraPosition;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.LatLngBounds;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;

public final class AMapProvider extends AbstractMapProvider{
	private MapView mapView;
	private AMap map;
	private Circle fuzzCircle;
	private final ExceptionLogger exceptionLogger=new ExceptionLogger(){
		@Override public void onException(Throwable error){ fail("sdk_render_failed"); }
		@Override public void onDownloaderException(int status,int source){ if(status==401 || status==403) fail("sdk_auth_failed"); }
	};

	public AMapProvider(Context context){ super(context); }
	@Override public String providerId(){ return "amap"; }

	@Override protected void createMap(Bundle state){
		Context app=context.getApplicationContext();
		MapsInitializer.updatePrivacyShow(app,true,true);
		MapsInitializer.updatePrivacyAgree(app,true);
		MapsInitializer.setProtocol(MapsInitializer.HTTPS);
		MapsInitializer.setDownloadCoordinateConvertLibrary(false);
		MapsInitializer.setExceptionLogger(exceptionLogger);
		mapView=new MapView(context); mapView.onCreate(state);
		map=mapView.getMap();
		if(map==null) throw new IllegalStateException("Map unavailable");
		map.setMyLocationEnabled(false);
		map.getUiSettings().setMyLocationButtonEnabled(false);
		map.getUiSettings().setCompassEnabled(true);
		map.getUiSettings().setScaleControlsEnabled(true);
		map.setOnMapLoadedListener(this::loaded);
		map.setOnCameraChangeListener(new AMap.OnCameraChangeListener(){
			@Override public void onCameraChange(CameraPosition position){}
			@Override public void onCameraChangeFinish(CameraPosition position){ cameraIdle(); }
		});
		map.setOnMarkerClickListener(marker->{ markerClicked((String)marker.getObject()); return true; });
		map.setOnMapLongClickListener(latLng->{
			MapCoordinateConverter.Coordinate coordinate=MapCoordinateConverter.gcj02ToWgs84(latLng.latitude,latLng.longitude);
			locationSelected(coordinate.latitude(),coordinate.longitude());
		});
		container.addView(mapView,new android.widget.FrameLayout.LayoutParams(-1,-1));
	}

	private LatLng toSdk(double lat,double lng){
		MapCoordinateConverter.Coordinate coordinate=MapCoordinateConverter.wgs84ToGcj02(lat,lng);
		return new LatLng(coordinate.latitude(),coordinate.longitude());
	}
	@Override protected void moveCamera(CameraState camera){
		map.moveCamera(CameraUpdateFactory.newLatLngZoom(toSdk(camera.centerLat(),camera.centerLng()),Math.max(3,Math.min(20,camera.zoom()))));
	}
	@Override protected CameraState readCamera(){
		CameraPosition position=map.getCameraPosition();
		if(position==null || mapView.getWidth()<1 || mapView.getHeight()<1) return requestedCamera;
		LatLngBounds bounds=map.getProjection().getVisibleRegion().latLngBounds;
		MapCoordinateConverter.Coordinate first=MapCoordinateConverter.gcj02ToWgs84(bounds.southwest.latitude,bounds.southwest.longitude);
		MapCoordinateConverter.Coordinate second=MapCoordinateConverter.gcj02ToWgs84(bounds.northeast.latitude,bounds.northeast.longitude);
		MapCoordinateConverter.Coordinate center=MapCoordinateConverter.gcj02ToWgs84(position.target.latitude,position.target.longitude);
		return new CameraState(Math.min(first.latitude(),second.latitude()),Math.min(first.longitude(),second.longitude()),Math.max(first.latitude(),second.latitude()),Math.max(first.longitude(),second.longitude()),center.latitude(),center.longitude(),Math.round(position.zoom),requestedCamera==null?null:requestedCamera.regionId());
	}
	@Override protected void applyContentInsets(){
		if(map==null || mapView==null) return;
		map.getUiSettings().setLogoBottomMargin(contentBottomInset+12);
	}
	@Override protected void applyFuzzArea(){
		if(map==null || fuzzPoint==null){ if(fuzzCircle!=null){ fuzzCircle.remove(); fuzzCircle=null; } return; }
		MapCoordinateConverter.Coordinate center=MapCoordinateConverter.wgs84ToGcj02(fuzzPoint.lat,fuzzPoint.lng);
		float radius="5km".equals(fuzzPoint.precisionLevel)?5000:"1km".equals(fuzzPoint.precisionLevel)?1000:"200m".equals(fuzzPoint.precisionLevel)?200:20000;
		int primary=UiUtils.getThemeColor(context,R.attr.colorM3Primary);
		int fill=(primary&0x00ffffff)|0x18000000; int stroke=(primary&0x00ffffff)|0x66000000;
		if(fuzzCircle==null) fuzzCircle=map.addCircle(new CircleOptions().center(new LatLng(center.latitude(),center.longitude())).radius(radius).fillColor(fill).strokeColor(stroke).strokeWidth(2));
		else { fuzzCircle.setCenter(new LatLng(center.latitude(),center.longitude())); fuzzCircle.setRadius(radius); }
	}
	@Override public void requestSnapshot(Consumer<Bitmap> callback){
		if(map==null || destroyed || !isReady()){ callback.accept(null); return; }
		long token=beginSnapshot(); if(token==0){ callback.accept(null); return; }
		map.getMapScreenShot(new AMap.OnMapScreenShotListener(){
			private void deliver(Bitmap bitmap){ if(finishSnapshot(token) && !destroyed) callback.accept(bitmap); }
			@Override public void onMapScreenShot(Bitmap bitmap){ deliver(bitmap); }
			@Override public void onMapScreenShot(Bitmap bitmap,int status){ deliver(bitmap); }
		});
	}
	@Override protected MarkerHandle addMarker(String id,double lat,double lng){
		BitmapDescriptor initial=BitmapDescriptorFactory.defaultMarker();
		Marker marker=map.addMarker(new MarkerOptions().position(toSdk(lat,lng)).icon(initial).anchor(0.5f,0.5f));
		if(marker==null) throw new IllegalStateException("Marker unavailable");
		marker.setObject(id);
		return new MarkerHandle(){
			private BitmapDescriptor descriptor=initial;
			@Override public void position(double latitude,double longitude){ marker.setPosition(toSdk(latitude,longitude)); }
			@Override public void icon(Bitmap bitmap){
				BitmapDescriptor replacement=BitmapDescriptorFactory.fromBitmap(bitmap);
				marker.setIcon(replacement); descriptor.recycle(); descriptor=replacement;
			}
			@Override public void remove(){ marker.remove(); descriptor.recycle(); }
		};
	}
	@Override protected void resumeMap(){ if(mapView!=null) mapView.onResume(); }
	@Override protected void pauseMap(){ if(mapView!=null) mapView.onPause(); }
	@Override protected void saveMap(Bundle state){ if(mapView!=null) mapView.onSaveInstanceState(state); }
	@Override public void onLowMemory(){ if(mapView!=null) mapView.onLowMemory(); }
	@Override protected void destroyMap(){
		if(MapsInitializer.getExceptionLogger()==exceptionLogger) MapsInitializer.setExceptionLogger(null);
		if(map!=null){ map.setOnCameraChangeListener(null); map.setOnMarkerClickListener(null); map.setOnMapLongClickListener(null); map.setOnMapLoadedListener(null); }
		if(mapView!=null) mapView.onDestroy();
		map=null; mapView=null;
	}
}
