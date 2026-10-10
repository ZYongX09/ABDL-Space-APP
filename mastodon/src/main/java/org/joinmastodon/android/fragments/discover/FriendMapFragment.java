package org.joinmastodon.android.fragments.discover;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.fragments.LoaderFragment;
import me.grishka.appkit.utils.V;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.requests.map.MapRequest;
import org.joinmastodon.android.fragments.ProfileFragment;
import org.joinmastodon.android.fragments.sponsors.SponsorCenterFragment;
import org.joinmastodon.android.model.map.MapModels;
import org.joinmastodon.android.ui.map.MapDeviceLocation;
import org.joinmastodon.android.ui.map.MapProvider;
import org.joinmastodon.android.ui.map.MapProviderRegistry;
import org.joinmastodon.android.ui.sheets.SponsorNoticeSheet;
import org.joinmastodon.android.ui.utils.UiUtils;

public class FriendMapFragment extends LoaderFragment implements MapProvider.Listener{
	private static final int LOCATION_REQUEST=7041;
	private static final String CONSENT_VERSION="map-presence-v1";
	private final Handler main=new Handler(Looper.getMainLooper());
	private final Set<String> attemptedProviders=new HashSet<>();
	private final List<MapModels.Point> visiblePoints=new ArrayList<>();
	private String accountID, preferredProvider="baidu";
	private MapProvider provider;
	private MapModels.Settings settings;
	private MapModels.Point self;
	private MapProvider.CameraState lastLegalCamera;
	private MapDeviceLocation.Request locationRequest;
	private MastodonAPIRequest<?> settingsRequest, presenceRequest, viewportRequest;
	private FrameLayout mapHost;
	private TextView status;
	private Button locateButton, providerButton;
	private boolean destroyed, resumed, consentDialogShowing, restoringCamera;
	private int generation, queryGeneration;
	private long lastGateAt;
	private Runnable viewportDebounce;
	private Bundle providerState;

	public static FriendMapFragment newInstance(String accountID){
		FriendMapFragment fragment=new FriendMapFragment();
		Bundle args=new Bundle(); args.putString("account",accountID); fragment.setArguments(args);
		return fragment;
	}
	@Override public void onCreate(Bundle state){
		super.onCreate(state); accountID=getArguments().getString("account"); setTitle(R.string.map_presence_title);
		if(state!=null){ preferredProvider=state.getString("map_provider","baidu"); providerState=state.getBundle("map_sdk_state"); }
	}
	@Override protected void doLoadData(){ if(status!=null) loadSettings(); }
	@Override public void onRefresh(){ if(status!=null) loadSettings(); }

	@Override public View onCreateContentView(LayoutInflater inflater,ViewGroup container,Bundle state){
		destroyed=false; generation++;
		LinearLayout root=new LinearLayout(getActivity()); root.setOrientation(LinearLayout.VERTICAL);
		root.setBackgroundColor(UiUtils.getThemeColor(getActivity(),R.attr.colorM3Surface));
		status=new TextView(getActivity()); status.setTextSize(14); status.setPadding(V.dp(16),V.dp(12),V.dp(16),V.dp(8));
		status.setTextColor(UiUtils.getThemeColor(getActivity(),R.attr.colorM3OnSurface));
		root.addView(status,new LinearLayout.LayoutParams(-1,-2));
		LinearLayout controls=new LinearLayout(getActivity()); controls.setPadding(V.dp(8),0,V.dp(8),V.dp(8));
		locateButton=button(controls,R.string.map_presence_locate,this::locate);
		providerButton=button(controls,R.string.map_presence_provider,this::chooseProvider);
		button(controls,R.string.map_presence_preferences,this::showSettings);
		button(controls,R.string.map_presence_refresh,this::loadSettings);
		root.addView(controls,new LinearLayout.LayoutParams(-1,-2));
		mapHost=new FrameLayout(getActivity()); root.addView(mapHost,new LinearLayout.LayoutParams(-1,0,1));
		main.post(()->{ if(!destroyed){ dataLoaded(); loadSettings(); } });
		return root;
	}
	private Button button(LinearLayout parent,int label,Runnable clicked){
		Button button=new Button(getActivity()); button.setText(label); button.setTextSize(12); button.setOnClickListener(v->clicked.run());
		parent.addView(button,new LinearLayout.LayoutParams(0,-2,1)); return button;
	}
	private boolean live(int expected){ return !destroyed && generation==expected && getActivity()!=null; }

	private void loadSettings(){
		if(destroyed) return;
		if(settingsRequest!=null) settingsRequest.cancel();
		int expected=generation; status.setText(R.string.map_presence_loading);
		settingsRequest=MapRequest.settings().setCallback(new Callback<>(){
			@Override public void onSuccess(MapModels.Settings result){
				if(!live(expected)) return;
				settingsRequest=null; settings=result; updateHeader();
				if(result.consent==null || !result.consent.accepted){ showConsent(); return; }
				ensureProvider(); readSelf();
			}
			@Override public void onError(ErrorResponse error){ if(live(expected)){ settingsRequest=null; status.setText(R.string.map_presence_settings_failed); } }
		}).exec(accountID);
	}
	private void updateHeader(){
		if(settings==null || status==null) return;
		String region=settings.currentRegion==null || settings.currentRegion.name==null ? getString(R.string.map_presence_current_city) : settings.currentRegion.name;
		status.setText("nationwide".equals(settings.mapScope) ? getString(R.string.map_presence_nationwide) : getString(R.string.map_presence_city_scope,region));
	}
	private void showConsent(){
		if(consentDialogShowing || destroyed) return;
		consentDialogShowing=true;
		AlertDialog dialog=new AlertDialog.Builder(getActivity()).setTitle(R.string.map_presence_privacy_title)
				.setMessage(R.string.map_presence_privacy_body)
				.setPositiveButton(R.string.map_presence_agree,(d,w)->saveSettings(Map.of("consent_version",CONSENT_VERSION),this::loadSettings))
				.setNeutralButton(R.string.map_presence_privacy_link,(d,w)->UiUtils.launchWebBrowser(getActivity(),"https://abdl-space.top/privacy"))
				.setNegativeButton(R.string.map_presence_later,null).create();
		dialog.setOnDismissListener(d->consentDialogShowing=false); dialog.show();
	}
	private boolean consentAccepted(){ return settings!=null && settings.consent!=null && settings.consent.accepted; }
	private void ensureProvider(){ if(provider==null && consentAccepted()) startProvider(preferredProvider); }
	private void startProvider(String preferred){
		if(destroyed || !consentAccepted()) return;
		String next=MapProviderRegistry.next(MapProviderRegistry.available(),preferred,attemptedProviders);
		if(next==null){
			if(provider!=null){ try{ provider.onPause(); provider.destroy(); }catch(RuntimeException ignored){} provider=null; }
			mapHost.removeAllViews(); status.setText(R.string.map_presence_provider_unavailable); return;
		}
		attemptedProviders.add(next);
		MapProvider.CameraState camera=provider==null ? lastLegalCamera : provider.getCamera();
		if(provider!=null){ try{ provider.onPause(); provider.destroy(); }catch(RuntimeException ignored){} provider=null; }
		mapHost.removeAllViews();
		try{
			provider=MapProviderRegistry.create(getActivity(),next); preferredProvider=next;
			provider.onCreate(providerState); providerState=null;
			mapHost.addView(provider.getView(),new FrameLayout.LayoutParams(-1,-1));
			provider.initialize(this); provider.renderSafe(visiblePoints);
			if(camera!=null) provider.setCamera(camera);
			if(resumed) provider.onResume();
			providerButton.setText("baidu".equals(next)?R.string.map_presence_baidu:R.string.map_presence_amap);
		}catch(RuntimeException | LinkageError error){ onProviderError("sdk_init_failed"); }
	}
	private void chooseProvider(){
		List<String> choices=MapProviderRegistry.available();
		if(choices.isEmpty()){ status.setText(R.string.map_presence_provider_unavailable); return; }
		String[] labels=choices.stream().map(id->getString("baidu".equals(id)?R.string.map_presence_baidu:R.string.map_presence_amap)).toArray(String[]::new);
		new AlertDialog.Builder(getActivity()).setTitle(R.string.map_presence_provider).setItems(labels,(d,index)->{
			attemptedProviders.clear(); startProvider(choices.get(index));
		}).show();
	}
	private void readSelf(){
		int expected=generation;
		if(presenceRequest!=null) presenceRequest.cancel();
		presenceRequest=MapRequest.mine().setCallback(new Callback<>(){
			@Override public void onSuccess(MapModels.Presence result){
				if(!live(expected)) return;
				presenceRequest=null;
				if(result.active && result.point!=null){ self=result.point; centerOnSelf(); }
				else if("hidden".equals(settings.visibility)){ status.setText(R.string.map_presence_hidden); }
				else if("pinned".equals(settings.locationMode) && settings.canChooseLocation){
					status.setText(R.string.map_presence_long_press);
					if(provider!=null) provider.setCamera(new MapProvider.CameraState(18,73,54,135,35,105,5,null));
				}
				else locate();
			}
			@Override public void onError(ErrorResponse error){ if(live(expected)){ presenceRequest=null; status.setText(R.string.map_presence_presence_failed); } }
		}).exec(accountID);
	}

	private void locate(){
		if(!consentAccepted()){ if(settings!=null) showConsent(); return; }
		if("hidden".equals(settings.visibility)){ showSettings(); return; }
		if(!MapDeviceLocation.permitted(getActivity())){
			boolean requested=getActivity().getSharedPreferences("map_permissions",Context.MODE_PRIVATE).getBoolean("requested",false);
			if(requested && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)){
				new AlertDialog.Builder(getActivity()).setMessage(R.string.map_presence_permission_needed).setPositiveButton(R.string.map_presence_open_settings,(d,w)->{
					Intent intent=new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",getActivity().getPackageName(),null)); startActivity(intent);
				}).setNegativeButton(R.string.map_presence_later,null).show();
			}else{
				getActivity().getSharedPreferences("map_permissions",Context.MODE_PRIVATE).edit().putBoolean("requested",true).apply();
				requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION},LOCATION_REQUEST);
			}
			return;
		}
		if(locationRequest!=null) locationRequest.cancel();
		int expected=generation; status.setText(R.string.map_presence_locating);
		locationRequest=MapDeviceLocation.once(getActivity(),location->{
			if(!live(expected)) return;
			locationRequest=null;
			if(location==null){ status.setText(R.string.map_presence_location_failed); return; }
			upload(location.getLatitude(),location.getLongitude(),"device");
		});
	}
	@Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
		super.onRequestPermissionsResult(code,permissions,results);
		if(code==LOCATION_REQUEST){ if(MapDeviceLocation.permitted(getActivity())) locate(); else status.setText(R.string.map_presence_permission_needed); }
	}
	private void upload(double lat,double lng,String mode){
		int expected=generation;
		Map<String,Object> body=new HashMap<>(); body.put("latitude",lat); body.put("longitude",lng);
		body.put("coordinate_system","wgs84"); body.put("precision_level",settings.precisionLevel);
		body.put("visibility",settings.visibility); body.put("anonymous",settings.anonymous); body.put("location_mode",mode);
		if(presenceRequest!=null) presenceRequest.cancel();
		presenceRequest=MapRequest.updatePresence(body).setCallback(new Callback<>(){
			@Override public void onSuccess(MapModels.Presence result){
				if(!live(expected)) return;
				presenceRequest=null; self=result.point;
				settings.currentRegion=result.currentRegion!=null?result.currentRegion:self==null?null:self.region;
				settings.locationMode=mode; centerOnSelf(); updateHeader();
			}
			@Override public void onError(ErrorResponse error){ if(live(expected)){ presenceRequest=null; handleError(error); } }
		}).exec(accountID);
	}
	private void centerOnSelf(){
		if(self==null || self.region==null) return;
		MapProvider.CameraState camera=new MapProvider.CameraState(self.lat-0.05,self.lng-0.05,self.lat+0.05,self.lng+0.05,self.lat,self.lng,11,self.region.id);
		lastLegalCamera=camera; ensureProvider();
		if(provider!=null) provider.setCamera(camera);
	}

	@Override public void onMapReady(){
		if(destroyed) return;
		if(lastLegalCamera!=null && provider!=null) provider.setCamera(lastLegalCamera);
		updateHeader();
	}
	@Override public void onCameraIdle(MapProvider.CameraState camera){
		if(destroyed || !resumed || settings==null || camera==null) return;
		if(!settings.canViewNationwide && (self==null || settings.currentRegion==null)) return;
		if(restoringCamera){ restoringCamera=false; return; }
		if(viewportDebounce!=null) main.removeCallbacks(viewportDebounce);
		viewportDebounce=()->queryViewport(camera);
		main.postDelayed(viewportDebounce,400);
	}
	private void queryViewport(MapProvider.CameraState camera){
		if(destroyed || !resumed || !consentAccepted()) return;
		if(viewportRequest!=null) viewportRequest.cancel();
		int expected=generation, query=++queryGeneration;
		String region=settings.currentRegion==null?null:settings.currentRegion.id;
		viewportRequest=MapRequest.list(camera.minLat(),camera.minLng(),camera.maxLat(),camera.maxLng(),camera.centerLat(),camera.centerLng(),region,camera.zoom(),100).setCallback(new Callback<>(){
			@Override public void onSuccess(MapModels.PresenceList result){
				if(!live(expected) || query!=queryGeneration) return;
				viewportRequest=null; lastLegalCamera=camera; visiblePoints.clear(); visiblePoints.addAll(result.points);
				if(provider!=null) provider.renderSafe(visiblePoints); updateHeader();
			}
			@Override public void onError(ErrorResponse error){
				if(!live(expected) || query!=queryGeneration) return;
				viewportRequest=null;
				if(error instanceof MapRequest.MapErrorResponse mapError && "map_sponsor_required".equals(mapError.code)){
					if(provider!=null && lastLegalCamera!=null){ restoringCamera=true; provider.setCamera(lastLegalCamera); }
					showSponsorGate();
				}else handleError(error);
			}
		}).exec(accountID);
	}
	private void handleError(ErrorResponse error){
		if(error instanceof MapRequest.MapErrorResponse mapError && "map_sponsor_required".equals(mapError.code)) showSponsorGate();
		else status.setText(R.string.map_presence_presence_failed);
	}
	private void showSponsorGate(){
		long now=System.currentTimeMillis(); if(now-lastGateAt<10_000) return; lastGateAt=now;
		new SponsorNoticeSheet(getActivity(),getString(R.string.map_presence_other_city),getString(R.string.map_presence_sponsor_body),getString(R.string.map_presence_back_city),this::centerOnSelf,()->{
			Bundle args=new Bundle(); args.putString("account",accountID); Nav.go(getActivity(),SponsorCenterFragment.class,args);
		}).show();
	}
	private void showSettings(){
		if(settings==null) return;
		String[] options={getString(R.string.map_presence_public),getString(R.string.map_presence_friends),getString(R.string.map_presence_hide),getString(R.string.map_presence_fuzz),getString(R.string.map_presence_anonymous),getString(R.string.map_presence_pin)};
		new AlertDialog.Builder(getActivity()).setTitle(R.string.map_presence_preferences).setItems(options,(d,index)->{
			switch(index){
				case 0,1 -> saveSettings(Map.of("visibility",index==0?"public":"friends"),this::locate);
				case 2 -> saveSettings(Map.of("visibility","hidden"),()->{ self=null; visiblePoints.clear(); if(provider!=null) provider.clear(); status.setText(R.string.map_presence_hidden); });
				case 3 -> choosePrecision();
				case 4 -> saveSettings(Map.of("anonymous",!settings.anonymous),this::locate);
				case 5 -> { if(!settings.canChooseLocation) showSponsorGate(); else status.setText(R.string.map_presence_long_press); }
			}
		}).show();
	}
	private void choosePrecision(){
		String[] levels={"city","5km","1km","200m"};
		String[] labels=getResources().getStringArray(R.array.map_presence_precisions);
		new AlertDialog.Builder(getActivity()).setTitle(R.string.map_presence_fuzz).setItems(labels,(d,index)->saveSettings(Map.of("precision_level",levels[index]),this::locate)).show();
	}
	private void saveSettings(Map<String,Object> changes,Runnable done){
		int expected=generation;
		if(settingsRequest!=null) settingsRequest.cancel();
		settingsRequest=MapRequest.updateSettings(changes).setCallback(new Callback<>(){
			@Override public void onSuccess(MapModels.Settings result){ if(live(expected)){ settingsRequest=null; settings=result; updateHeader(); done.run(); } }
			@Override public void onError(ErrorResponse error){ if(live(expected)){ settingsRequest=null; handleError(error); } }
		}).exec(accountID);
	}
	@Override public void onLocationSelected(double latitude,double longitude){
		if(settings==null || destroyed) return;
		if(!settings.canChooseLocation){ showSponsorGate(); return; }
		if("hidden".equals(settings.visibility)){ showSettings(); return; }
		new AlertDialog.Builder(getActivity()).setTitle(R.string.map_presence_pin).setMessage(R.string.map_presence_pin_confirm)
				.setPositiveButton(R.string.map_presence_confirm,(d,w)->upload(latitude,longitude,"pinned"))
				.setNegativeButton(R.string.map_presence_later,null).show();
	}
	@Override public void onMarkerClick(MapModels.Point point){
		if(point==null || point.anonymous || point.account==null || point.account.id==null) return;
		Bundle args=new Bundle(); args.putString("account",accountID); args.putString("profileAccountID",point.account.id); Nav.go(getActivity(),ProfileFragment.class,args);
	}
	@Override public void onProviderError(String code){
		if(destroyed) return;
		status.setText(R.string.map_presence_switching);
		startProvider(preferredProvider);
	}
	@Override public void onStart(){ super.onStart(); if(provider!=null) provider.onStart(); }
	@Override public void onResume(){
		super.onResume(); resumed=true;
		if(provider!=null) provider.onResume();
		if(settings!=null) loadSettings();
	}
	@Override public void onPause(){
		resumed=false; queryGeneration++;
		if(viewportDebounce!=null) main.removeCallbacks(viewportDebounce);
		if(viewportRequest!=null){ viewportRequest.cancel(); viewportRequest=null; }
		if(locationRequest!=null){ locationRequest.cancel(); locationRequest=null; }
		if(provider!=null) provider.onPause();
		super.onPause();
	}
	@Override public void onSaveInstanceState(Bundle state){
		super.onSaveInstanceState(state); state.putString("map_provider",preferredProvider);
		if(provider!=null){ Bundle sdk=new Bundle(); provider.onSaveInstanceState(sdk); state.putBundle("map_sdk_state",sdk); }
	}
	@Override public void onLowMemory(){ super.onLowMemory(); if(provider!=null) provider.onLowMemory(); }
	@Override public void onDestroyView(){
		destroyed=true; generation++; queryGeneration++; main.removeCallbacksAndMessages(null);
		for(MastodonAPIRequest<?> request:new MastodonAPIRequest<?>[]{settingsRequest,presenceRequest,viewportRequest}) if(request!=null) request.cancel();
		settingsRequest=presenceRequest=viewportRequest=null;
		if(locationRequest!=null){ locationRequest.cancel(); locationRequest=null; }
		if(provider!=null){ try{ provider.destroy(); }finally{ provider=null; } }
		attemptedProviders.clear(); mapHost=null; status=null;
		super.onDestroyView();
	}
}
