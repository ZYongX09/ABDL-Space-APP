package org.joinmastodon.android.ui.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.os.Bundle;
import java.util.function.Consumer;

import com.baidu.mapapi.CoordType;
import com.baidu.mapapi.RequestAuthResultListener;
import com.baidu.mapapi.SDKInitializer;
import com.baidu.mapapi.map.BaiduMap;
import com.baidu.mapapi.map.BitmapDescriptor;
import com.baidu.mapapi.map.BitmapDescriptorFactory;
import com.baidu.mapapi.map.Circle;
import com.baidu.mapapi.map.CircleOptions;
import com.baidu.mapapi.map.Stroke;
import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.utils.UiUtils;
import com.baidu.mapapi.map.MapStatus;
import com.baidu.mapapi.map.MapStatusUpdateFactory;
import com.baidu.mapapi.map.MapView;
import com.baidu.mapapi.map.Marker;
import com.baidu.mapapi.map.MarkerOptions;
import com.baidu.mapapi.model.LatLng;

public final class BaiduMapProvider extends AbstractMapProvider{
	private MapView mapView;
	private BaiduMap map;
	private Circle fuzzCircle;
	private final RequestAuthResultListener authListener=(status,message)->{ if(status!=0) fail("sdk_auth_failed"); };

	public BaiduMapProvider(Context context){ super(context); }
	@Override public String providerId(){ return "baidu"; }

	@Override protected void createMap(Bundle state){
		Context app=context.getApplicationContext();
		SDKInitializer.setAgreePrivacy(app,true);
		SDKInitializer.setHttpsEnable(true);
		SDKInitializer.setDebugMode(false);
		SDKInitializer.setCoordType(CoordType.BD09LL);
		SDKInitializer.addAuthResultListener(authListener);
		if(!SDKInitializer.isInitialized()) SDKInitializer.initialize(app);
		mapView=new MapView(context);
		mapView.onCreate(context,state);
		map=mapView.getMap();
		map.setMyLocationEnabled(false);
		map.getUiSettings().setCompassEnabled(true);
		map.setOnMapLoadedCallback(this::loaded);
		map.setOnMapStatusChangeListener(new BaiduMap.OnMapStatusChangeListener(){
			@Override public void onMapStatusChangeStart(MapStatus status){}
			@Override public void onMapStatusChangeStart(MapStatus status,int reason){}
			@Override public void onMapStatusChange(MapStatus status){}
			@Override public void onMapStatusChangeFinish(MapStatus status){ cameraIdle(); }
		});
		map.setOnMarkerClickListener(marker->{
			Bundle extras=marker.getExtraInfo();
			if(extras!=null) markerClicked(extras.getString("presence_id"));
			return true;
		});
		map.setOnMapLongClickListener(latLng->{
			MapCoordinateConverter.Coordinate coordinate=MapCoordinateConverter.bd09ToWgs84(latLng.latitude,latLng.longitude);
			locationSelected(coordinate.latitude(),coordinate.longitude());
		});
		container.addView(mapView,new android.widget.FrameLayout.LayoutParams(-1,-1));
	}

	private LatLng toSdk(double lat,double lng){
		MapCoordinateConverter.Coordinate coordinate=MapCoordinateConverter.wgs84ToBd09(lat,lng);
		return new LatLng(coordinate.latitude(),coordinate.longitude());
	}
	@Override protected void moveCamera(CameraState camera){
		map.setMapStatus(MapStatusUpdateFactory.newLatLngZoom(toSdk(camera.centerLat(),camera.centerLng()),Math.max(4,Math.min(21,camera.zoom()))));
	}
	@Override protected CameraState readCamera(){
		MapStatus status=map.getMapStatus();
		if(status==null || status.target==null || mapView.getWidth()<1 || mapView.getHeight()<1) return requestedCamera;
		LatLng first=map.getProjection().fromScreenLocation(new Point(0,mapView.getHeight()));
		LatLng second=map.getProjection().fromScreenLocation(new Point(mapView.getWidth(),0));
		if(first==null || second==null) return requestedCamera;
		MapCoordinateConverter.Coordinate a=MapCoordinateConverter.bd09ToWgs84(first.latitude,first.longitude);
		MapCoordinateConverter.Coordinate b=MapCoordinateConverter.bd09ToWgs84(second.latitude,second.longitude);
		MapCoordinateConverter.Coordinate center=MapCoordinateConverter.bd09ToWgs84(status.target.latitude,status.target.longitude);
		return new CameraState(Math.min(a.latitude(),b.latitude()),Math.min(a.longitude(),b.longitude()),Math.max(a.latitude(),b.latitude()),Math.max(a.longitude(),b.longitude()),center.latitude(),center.longitude(),Math.round(status.zoom),requestedCamera==null?null:requestedCamera.regionId());
	}

	@Override protected void applyContentInsets(){
		if(map!=null) map.setViewPadding(0,contentTopInset,0,contentBottomInset);
	}
	@Override protected void applyFuzzArea(){
		if(map==null || fuzzPoint==null) { if(fuzzCircle!=null){ fuzzCircle.remove(); fuzzCircle=null; } return; }
		MapCoordinateConverter.Coordinate center=MapCoordinateConverter.wgs84ToBd09(fuzzPoint.lat,fuzzPoint.lng);
		int radius="5km".equals(fuzzPoint.precisionLevel)?5000:"1km".equals(fuzzPoint.precisionLevel)?1000:"200m".equals(fuzzPoint.precisionLevel)?200:20000;
		int primary=UiUtils.getThemeColor(context,R.attr.colorM3Primary);
		int fill=(primary&0x00ffffff)|0x18000000;
		int stroke=(primary&0x00ffffff)|0x66000000;
		if(fuzzCircle==null) fuzzCircle=(Circle)map.addOverlay(new CircleOptions().center(new LatLng(center.latitude(),center.longitude())).radius(radius).fillColor(fill).stroke(new Stroke(2,stroke)));
		else { fuzzCircle.setCenter(new LatLng(center.latitude(),center.longitude())); fuzzCircle.setRadius(radius); }
	}
	@Override public void requestSnapshot(Consumer<Bitmap> callback){
		if(map==null || destroyed || !isReady()){ callback.accept(null); return; }
		long token=beginSnapshot(); if(token==0){ callback.accept(null); return; }
		map.snapshot(bitmap->{ if(finishSnapshot(token) && !destroyed) callback.accept(bitmap); });
	}
	@Override public void selectPoint(String id){ super.selectPoint(id); }
	@Override protected MarkerHandle addMarker(String id,double lat,double lng){
		BitmapDescriptor initial=BitmapDescriptorFactory.fromBitmap(Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888));
		Bundle extras=new Bundle(); extras.putString("presence_id",id);
		Marker marker=(Marker)map.addOverlay(new MarkerOptions().position(toSdk(lat,lng)).icon(initial).anchor(0.5f,0.5f).extraInfo(extras));
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
	@Override protected void resumeMap(){ if(mapView!=null){ SDKInitializer.onForeground(); mapView.onResume(); } }
	@Override protected void pauseMap(){ if(mapView!=null) mapView.onPause(); }
	@Override protected void saveMap(Bundle state){ if(mapView!=null) mapView.onSaveInstanceState(state); }
	@Override protected void destroyMap(){
		SDKInitializer.removeAuthResultListener(authListener);
		if(map!=null){ map.setOnMapLoadedCallback(null); map.setOnMapStatusChangeListener(null); map.setOnMapLongClickListener(null); }
		if(mapView!=null) mapView.onDestroy();
		map=null; mapView=null;
	}
}
