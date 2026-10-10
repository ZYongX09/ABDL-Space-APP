package org.joinmastodon.android.ui.map;

import android.os.Bundle;
import android.view.View;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import android.graphics.Bitmap;

import org.joinmastodon.android.model.map.MapModels;

/** Providers consume protected WGS84 points; SDK coordinates stay inside the adapter. */
public interface MapProvider{
	interface Listener{
		void onCameraIdle(CameraState camera);
		void onMarkerClick(MapModels.Point point);
		void onProviderError(String code);
		default void onMapReady(){}
		default void onLocationSelected(double latitude, double longitude){}
	}
	record CameraState(double minLat, double minLng, double maxLat, double maxLng,
			double centerLat, double centerLng, int zoom, String regionId){}
	View getView();
	void initialize(Listener listener);
	void setCamera(CameraState camera);
	CameraState getCamera();
	void render(List<MapModels.Point> points);
	void clear();
	void destroy();
	default String providerId(){ return "fake"; }
	default boolean isReady(){ return true; }
	default void onCreate(Bundle state){}
	default void onStart(){}
	default void onResume(){}
	default void onPause(){}
	default void onSaveInstanceState(Bundle state){}
	default void onLowMemory(){}
	default void requestSnapshot(Consumer<Bitmap> callback){ callback.accept(null); }
	default void setContentInsets(int top,int bottom){}
	default void selectPoint(String id){}
	default void showFuzzArea(MapModels.Point point){}
	default void renderSafe(List<MapModels.Point> points){ render(points==null ? Collections.emptyList() : points); }
}
