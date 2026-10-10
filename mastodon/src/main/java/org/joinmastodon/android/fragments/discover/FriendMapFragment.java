package org.joinmastodon.android.fragments.discover;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.fragments.AppKitFragment;

import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.api.requests.map.MapRequest;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.ProfileFragment;
import org.joinmastodon.android.fragments.sponsors.SponsorCenterFragment;
import org.joinmastodon.android.model.map.MapModels;
import org.joinmastodon.android.ui.map.FriendMapOverlay;
import org.joinmastodon.android.ui.map.MapDeviceLocation;
import org.joinmastodon.android.ui.map.MapProvider;
import org.joinmastodon.android.ui.map.MapProviderRegistry;
import org.joinmastodon.android.ui.sheets.SponsorNoticeSheet;
import org.joinmastodon.android.ui.utils.UiUtils;

public class FriendMapFragment extends AppKitFragment implements MapProvider.Listener,FriendMapOverlay.Actions{
	private static final int LOCATION_REQUEST=7041;
	private static final String CONSENT_VERSION="map-presence-v1";
	private final Handler main=new Handler(Looper.getMainLooper());
	private final Set<String> attemptedProviders=new HashSet<>();
	private final List<MapModels.Point> visiblePoints=new ArrayList<>();
	private String accountID,preferredProvider="auto";
	private AccountSession session;
	private MapProvider provider;
	private MapModels.Settings settings;
	private MapModels.Point self;
	private MapProvider.CameraState lastLegalCamera;
	private MapDeviceLocation.Request locationRequest;
	private MastodonAPIRequest<?> settingsRequest,presenceRequest,viewportRequest;
	private FriendMapOverlay overlay;
	private boolean destroyed=true,resumed,tabVisible,active,picking,restoringCamera,snapshotBusy;
	private int generation,queryGeneration,snapshotGeneration,topInset,bottomInset;
	private long lastGateAt;
	private Runnable viewportDebounce,snapshotDebounce,snapshotTimeout;
	private Bundle providerState;

	public static FriendMapFragment newInstance(String accountID){
		FriendMapFragment fragment=new FriendMapFragment(); Bundle args=new Bundle(); args.putString("account",accountID); fragment.setArguments(args); return fragment;
	}
	@Override public void onCreate(Bundle state){
		super.onCreate(state); accountID=getArguments().getString("account"); session=AccountSessionManager.getInstance().tryGetAccount(accountID);
		tabVisible=!getArguments().getBoolean("__is_tab",false); setTitle(R.string.map_presence_title);
		if(state!=null){ preferredProvider=state.getString("map_provider","auto"); providerState=state.getBundle("map_sdk_state"); }
	}
	@Override public View onCreateView(LayoutInflater inflater,ViewGroup container,Bundle state){
		destroyed=false; generation++;
		overlay=new FriendMapOverlay(getActivity(),this); overlay.setNavigationInsets(topInset,bottomInset);
		updateActivityState(); return overlay;
	}
	public void setNavigationInsets(int top,int bottom){ topInset=Math.max(0,top); bottomInset=Math.max(0,bottom); if(overlay!=null) overlay.setNavigationInsets(topInset,bottomInset); }
	public void setTabVisible(boolean visible){ tabVisible=visible; updateActivityState(); }
	@Override public void onHiddenChanged(boolean hidden){ super.onHiddenChanged(hidden); if(hidden) setTabVisible(false); else if(!getArguments().getBoolean("__is_tab",false)) setTabVisible(true); }
	@Override public void onApplyWindowInsets(WindowInsets insets){
		if(!getArguments().getBoolean("__is_tab",false)) setNavigationInsets(insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetBottom());
	}
	private boolean sessionValid(){ return session!=null && AccountSessionManager.getInstance().tryGetAccount(accountID)==session; }
	private boolean live(int expected){ return !destroyed && active && generation==expected && getActivity()!=null && sessionValid(); }
	private boolean consentAccepted(){ return settings!=null && settings.consent!=null && settings.consent.accepted; }
	private void updateActivityState(){
		boolean next=!destroyed && resumed && tabVisible && overlay!=null && sessionValid();
		if(next==active) return;
		active=next;
		if(active){ if(provider!=null) provider.onResume(); loadSettings(); }
		else{
			generation++; queryGeneration++; cancelAll(); clearSnapshotWork();
			if(provider!=null) provider.onPause();
			if(overlay!=null){ overlay.setBusy(false); overlay.setSnapshot(null); }
		}
	}
	@Override public void refresh(){ attemptedProviders.clear(); if(provider==null && consentAccepted()) startProvider(preferredProvider); loadSettings(); }
	private void loadSettings(){
		if(!active || destroyed) return;
		if(settingsRequest!=null) settingsRequest.cancel();
		int expected=generation; overlay.setBusy(true);
		settingsRequest=MapRequest.settings().setCallback(new Callback<>(){
			@Override public void onSuccess(MapModels.Settings result){
				if(!live(expected)) return;
				settingsRequest=null; settings=result; overlay.setBusy(false); overlay.updateSettings(result); overlay.setMessage("",false);
				if(!consentAccepted()){ overlay.showConsent(); return; }
				if(provider==null) startProvider(preferredProvider); readSelf();
			}
			@Override public void onError(ErrorResponse error){ if(live(expected)){ settingsRequest=null; overlay.setBusy(false); showError(error); } }
		}).exec(accountID);
	}
	@Override public void consent(){ saveSettings(Map.of("consent_version",CONSENT_VERSION),this::loadSettings); }
	@Override public void privacy(){ UiUtils.launchWebBrowser(getActivity(),"https://abdl-space.top/privacy"); }
	private void startProvider(String preferred){
		if(!active || !consentAccepted()) return;
		String chosen=MapProviderRegistry.next(MapProviderRegistry.available(),"auto".equals(preferred)?"baidu":preferred,attemptedProviders);
		MapProvider.CameraState camera=provider==null?lastLegalCamera:provider.getCamera();
		clearSnapshotWork();
		if(provider!=null){ provider.onPause(); provider.destroy(); provider=null; }
		overlay.mapHost().removeAllViews(); overlay.setSnapshot(null);
		if(chosen==null){ overlay.setMessage(getString(R.string.map_presence_provider_unavailable),true); return; }
		attemptedProviders.add(chosen);
		try{
			provider=MapProviderRegistry.create(getActivity(),chosen); provider.onCreate(providerState); providerState=null;
			overlay.mapHost().addView(provider.getView(),new ViewGroup.LayoutParams(-1,-1));
			provider.initialize(this); provider.renderSafe(visiblePoints); provider.onResume(); overlay.setProvider(chosen);
			if(camera!=null) provider.setCamera(camera);
		}catch(RuntimeException | LinkageError error){ onProviderError("sdk_init_failed"); }
	}
	@Override public void provider(String id){ preferredProvider=id; attemptedProviders.clear(); startProvider(id); }
	private void readSelf(){
		int expected=generation; if(presenceRequest!=null) presenceRequest.cancel();
		presenceRequest=MapRequest.mine().setCallback(new Callback<>(){
			@Override public void onSuccess(MapModels.Presence result){
				if(!live(expected)) return;
				presenceRequest=null;
				if(result.active && result.point!=null){ self=result.point; centerOnSelf(); }
				else if("hidden".equals(settings.visibility)) overlay.setMessage(getString(R.string.map_ui_enable_first),false);
				else if("pinned".equals(settings.locationMode) && settings.canChooseLocation){ if(provider!=null) provider.setCamera(new MapProvider.CameraState(18,73,54,135,35,105,5,null)); }
				else locate();
			}
			@Override public void onError(ErrorResponse error){ if(live(expected)){ presenceRequest=null; showError(error); } }
		}).exec(accountID);
	}
	@Override public void locate(){
		if(!active) return;
		if(!consentAccepted()){ if(settings!=null) overlay.showConsent(); return; }
		if(self!=null && "pinned".equals(settings.locationMode)){ centerOnSelf(); return; }
		if("hidden".equals(settings.visibility)){ overlay.setMessage(getString(R.string.map_ui_enable_first),false); overlay.showSettings(); return; }
		requestDeviceLocation();
	}
	private void requestDeviceLocation(){
		if(!MapDeviceLocation.permitted(getActivity())){
			boolean requested=getActivity().getSharedPreferences("map_permissions",Context.MODE_PRIVATE).getBoolean("requested",false);
			if(requested && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)){
				overlay.setMessage(getString(R.string.map_presence_permission_needed),false);
				startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",getActivity().getPackageName(),null)));
			}else{
				getActivity().getSharedPreferences("map_permissions",Context.MODE_PRIVATE).edit().putBoolean("requested",true).apply();
				requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION},LOCATION_REQUEST);
			}
			return;
		}
		if(locationRequest!=null) locationRequest.cancel();
		int expected=generation; overlay.setBusy(true); overlay.setMessage(getString(R.string.map_presence_locating),false);
		locationRequest=MapDeviceLocation.once(getActivity(),location->{
			if(!live(expected)) return;
			locationRequest=null; overlay.setBusy(false);
			if(location==null){ overlay.setMessage(getString(R.string.map_presence_location_failed),false); return; }
			upload(location.getLatitude(),location.getLongitude(),"device");
		});
	}
	@Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
		super.onRequestPermissionsResult(code,permissions,results);
		if(code==LOCATION_REQUEST && active){ if(MapDeviceLocation.permitted(getActivity())) requestDeviceLocation(); else overlay.setMessage(getString(R.string.map_presence_permission_needed),false); }
	}
	private void upload(double lat,double lng,String mode){
		int expected=generation; Map<String,Object> body=new HashMap<>(); body.put("latitude",lat); body.put("longitude",lng);
		body.put("coordinate_system","wgs84"); body.put("precision_level",settings.precisionLevel); body.put("visibility",settings.visibility); body.put("anonymous",settings.anonymous); body.put("location_mode",mode);
		if(presenceRequest!=null) presenceRequest.cancel(); overlay.setBusy(true);
		presenceRequest=MapRequest.updatePresence(body).setCallback(new Callback<>(){
			@Override public void onSuccess(MapModels.Presence result){
				if(!live(expected)) return;
				presenceRequest=null; self=result.point; settings.currentRegion=result.currentRegion!=null?result.currentRegion:self==null?null:self.region;
				settings.locationMode=mode; overlay.setBusy(false); overlay.setMessage("",false); overlay.updateSettings(settings); if(picking) cancelPin(); centerOnSelf();
			}
			@Override public void onError(ErrorResponse error){ if(live(expected)){ presenceRequest=null; overlay.setBusy(false); showError(error); } }
		}).exec(accountID);
	}
	private void centerOnSelf(){
		if(self==null || self.region==null || provider==null) return;
		MapProvider.CameraState camera=new MapProvider.CameraState(self.lat-.05,self.lng-.05,self.lat+.05,self.lng+.05,self.lat,self.lng,11,self.region.id);
		lastLegalCamera=camera; provider.setCamera(camera); provider.showFuzzArea(self);
	}
	@Override public void visibility(String value){
		if(!consentAccepted()){ if(settings!=null) overlay.showConsent(); return; }
		saveSettings(Map.of("visibility",value),()->{
			if("hidden".equals(value)){ self=null; if(provider!=null) provider.showFuzzArea(null); overlay.setMessage(getString(R.string.map_ui_enable_first),false); }
			else requestDeviceLocation();
		});
	}
	@Override public void precision(String value){ saveSettings(Map.of("precision_level",value),this::requestDeviceLocation); }
	@Override public void anonymous(boolean value){ saveSettings(Map.of("anonymous",value),this::requestDeviceLocation); }
	@Override public void useDeviceLocation(){ saveSettings(Map.of("location_mode","device"),this::requestDeviceLocation); }
	private void saveSettings(Map<String,Object> changes,Runnable done){
		if(!active) return;
		int expected=generation; if(settingsRequest!=null) settingsRequest.cancel(); overlay.setBusy(true);
		settingsRequest=MapRequest.updateSettings(changes).setCallback(new Callback<>(){
			@Override public void onSuccess(MapModels.Settings result){ if(live(expected)){ settingsRequest=null; settings=result; overlay.setBusy(false); overlay.updateSettings(result); done.run(); } }
			@Override public void onError(ErrorResponse error){ if(live(expected)){ settingsRequest=null; overlay.setBusy(false); showError(error); } }
		}).exec(accountID);
	}
	@Override public void beginPin(){
		if(settings==null || !active) return;
		if(!settings.canChooseLocation){ sponsor(); return; }
		if("hidden".equals(settings.visibility)){ overlay.setMessage(getString(R.string.map_ui_enable_first),false); return; }
		picking=true; overlay.showPin();
	}
	@Override public void confirmPin(){
		if(!picking || provider==null || !settings.canChooseLocation) return;
		MapProvider.CameraState camera=provider.getCamera(); if(camera!=null) upload(camera.centerLat(),camera.centerLng(),"pinned");
	}
	@Override public void cancelPin(){ picking=false; if(overlay!=null) overlay.finishPin(); }
	@Override public void onLocationSelected(double lat,double lng){
		if(settings==null || !active) return;
		if(!settings.canChooseLocation){ sponsor(); return; }
		beginPin(); if(picking && provider!=null) provider.setCamera(new MapProvider.CameraState(lat-.03,lng-.03,lat+.03,lng+.03,lat,lng,13,null));
	}
	@Override public void focus(MapModels.Point point){
		if(provider==null) return;
		provider.setCamera(new MapProvider.CameraState(point.lat-.03,point.lng-.03,point.lat+.03,point.lng+.03,point.lat,point.lng,13,settings.currentRegion==null?null:settings.currentRegion.id));
		provider.selectPoint(point.id); provider.showFuzzArea(point);
	}
	@Override public void profile(MapModels.Point point){
		if(point==null || point.anonymous || point.account==null || point.account.id==null) return;
		Bundle args=new Bundle(); args.putString("account",accountID); args.putString("profileAccountID",point.account.id); Nav.go(getActivity(),ProfileFragment.class,args);
	}
	@Override public void onMarkerClick(MapModels.Point point){ if(active){ focus(point); overlay.showPerson(point); } }
	@Override public void sponsor(){
		if(!active) return;
		long now=System.currentTimeMillis(); if(now-lastGateAt<10_000) return; lastGateAt=now;
		new SponsorNoticeSheet(getActivity(),getString(R.string.map_presence_other_city),getString(R.string.map_presence_sponsor_body),getString(R.string.map_presence_back_city),this::centerOnSelf,()->{
			Bundle args=new Bundle(); args.putString("account",accountID); Nav.go(getActivity(),SponsorCenterFragment.class,args);
		}).show();
	}
	@Override public void onMapReady(){ if(active){ if(lastLegalCamera!=null) provider.setCamera(lastLegalCamera); scheduleSnapshot(); } }
	@Override public void onCameraIdle(MapProvider.CameraState camera){
		if(!active || camera==null || settings==null) return;
		snapshotGeneration++; scheduleSnapshot();
		if(picking) return;
		if(!settings.canViewNationwide && (self==null || settings.currentRegion==null)) return;
		if(restoringCamera){ restoringCamera=false; return; }
		if(viewportDebounce!=null) main.removeCallbacks(viewportDebounce);
		viewportDebounce=()->queryViewport(camera); main.postDelayed(viewportDebounce,400);
	}
	private void queryViewport(MapProvider.CameraState camera){
		if(!active || !consentAccepted()) return;
		if(viewportRequest!=null) viewportRequest.cancel(); int expected=generation,query=++queryGeneration;
		String region=settings.currentRegion==null?null:settings.currentRegion.id;
		viewportRequest=MapRequest.list(camera.minLat(),camera.minLng(),camera.maxLat(),camera.maxLng(),camera.centerLat(),camera.centerLng(),region,camera.zoom(),100).setCallback(new Callback<>(){
			@Override public void onSuccess(MapModels.PresenceList result){
				if(!live(expected) || query!=queryGeneration) return;
				viewportRequest=null; lastLegalCamera=camera; visiblePoints.clear(); visiblePoints.addAll(result.points);
				if(provider!=null) provider.renderSafe(visiblePoints); overlay.setPoints(visiblePoints); overlay.setMessage("",false); scheduleSnapshot();
			}
			@Override public void onError(ErrorResponse error){
				if(!live(expected) || query!=queryGeneration) return;
				viewportRequest=null;
				if(error instanceof MapRequest.MapErrorResponse mapError && "map_sponsor_required".equals(mapError.code)){
					if(provider!=null && lastLegalCamera!=null){ restoringCamera=true; provider.setCamera(lastLegalCamera); } sponsor();
				}else showError(error);
			}
		}).exec(accountID);
	}
	private void showError(ErrorResponse error){
		if(overlay==null) return;
		if(error instanceof MapRequest.MapErrorResponse mapError && "map_sponsor_required".equals(mapError.code)){ sponsor(); return; }
		int status=error instanceof MastodonErrorResponse response?response.httpStatus:-1;
		int message=status==404?R.string.map_ui_not_deployed:status==401?R.string.map_ui_login_required:status>=500?R.string.map_ui_server_failed:status<0?R.string.map_ui_network_failed:R.string.map_ui_protocol_failed;
		overlay.setMessage(getString(message),status==404 || settings==null);
	}
	@Override public void onProviderError(String code){ if(active){ overlay.setMessage(getString(R.string.map_presence_switching),false); startProvider(preferredProvider); } }
	@Override public void panelChanged(int top,int bottom){ if(provider!=null) provider.setContentInsets(top,bottom); }
	private void scheduleSnapshot(){
		if(!active || provider==null || !provider.isReady() || !GlobalUserPreferences.isIosLiquidNavigationEnabled()) return;
		if(snapshotDebounce!=null) main.removeCallbacks(snapshotDebounce); snapshotDebounce=this::sampleMap; main.postDelayed(snapshotDebounce,300);
	}
	private void sampleMap(){
		if(!active || snapshotBusy || provider==null || !provider.isReady()) return;
		MapProvider current=provider; int expected=generation,sample=++snapshotGeneration; snapshotBusy=true;
		snapshotTimeout=()->{ snapshotBusy=false; }; main.postDelayed(snapshotTimeout,2500);
		current.requestSnapshot(bitmap->main.post(()->{
			if(!live(expected) || current!=provider) return;
			snapshotBusy=false; if(snapshotTimeout!=null) main.removeCallbacks(snapshotTimeout);
			if(sample!=snapshotGeneration){ scheduleSnapshot(); return; }
			if(bitmap!=null && !bitmap.isRecycled()) overlay.setSnapshot(bitmap);
		}));
	}
	private void clearSnapshotWork(){
		snapshotGeneration++; snapshotBusy=false;
		if(snapshotDebounce!=null) main.removeCallbacks(snapshotDebounce); if(snapshotTimeout!=null) main.removeCallbacks(snapshotTimeout);
	}
	private void cancelAll(){
		if(viewportDebounce!=null) main.removeCallbacks(viewportDebounce);
		for(MastodonAPIRequest<?> request:new MastodonAPIRequest<?>[]{settingsRequest,presenceRequest,viewportRequest}) if(request!=null) request.cancel();
		settingsRequest=presenceRequest=viewportRequest=null;
		if(locationRequest!=null){ locationRequest.cancel(); locationRequest=null; }
	}
	public boolean onBackPressed(){ return overlay!=null && overlay.onBackPressed(); }
	@Override public void onStart(){ super.onStart(); if(provider!=null && active) provider.onStart(); }
	@Override public void onResume(){ super.onResume(); resumed=true; updateActivityState(); }
	@Override public void onPause(){ resumed=false; updateActivityState(); super.onPause(); }
	@Override public void onSaveInstanceState(Bundle state){
		super.onSaveInstanceState(state); state.putString("map_provider",preferredProvider);
		if(provider!=null){ Bundle sdk=new Bundle(); provider.onSaveInstanceState(sdk); state.putBundle("map_sdk_state",sdk); }
	}
	@Override public void onLowMemory(){ super.onLowMemory(); if(provider!=null) provider.onLowMemory(); }
	@Override public void onDestroyView(){
		destroyed=true; updateActivityState(); generation++; cancelAll(); clearSnapshotWork(); main.removeCallbacksAndMessages(null);
		if(provider!=null){ provider.destroy(); provider=null; } if(overlay!=null){ overlay.dispose(); overlay=null; }
		attemptedProviders.clear(); super.onDestroyView();
	}
}
