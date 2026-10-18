package org.joinmastodon.android.fragments.discover;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

import androidx.annotation.Nullable;

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
import org.joinmastodon.android.fragments.settings.BabyVerificationFragment;
import org.joinmastodon.android.fragments.sponsors.SponsorCenterFragment;
import org.joinmastodon.android.model.map.MapModels;
import org.joinmastodon.android.ui.map.FriendMapOverlay;
import org.joinmastodon.android.ui.map.MapDeviceLocation;
import org.joinmastodon.android.ui.map.MapProvider;
import org.joinmastodon.android.ui.map.MapProviderRegistry;
import org.joinmastodon.android.ui.sheets.SponsorNoticeSheet;
import org.joinmastodon.android.ui.utils.UiUtils;

public class FriendMapFragment extends AppKitFragment implements MapProvider.Listener,FriendMapOverlay.Actions{
	public interface MapBackdropListener{
		void onMapBackdropChanged(@Nullable View source,@Nullable Bitmap snapshot);
	}
	private MapBackdropListener mapBackdropListener;
	private View backdropSource;
	private Bitmap backdropSnapshot;
	/** Registration is independent of activation; Home binds before setTabVisible. */
	public void setMapBackdropListener(@Nullable MapBackdropListener listener){
		mapBackdropListener=listener;
		if(listener!=null){
			listener.onMapBackdropChanged(backdropSource,backdropSnapshot!=null && !backdropSnapshot.isRecycled()?backdropSnapshot:null);
			if(backdropSnapshot==null) scheduleSnapshot();
		}
	}
	private void publishBackdrop(@Nullable View source,@Nullable Bitmap snapshot){
		backdropSource=source; backdropSnapshot=source!=null && snapshot!=null && !snapshot.isRecycled()?snapshot:null;
		if(mapBackdropListener!=null) mapBackdropListener.onMapBackdropChanged(backdropSource,backdropSnapshot);
	}
	private void publishProviderBackdrop(){
		// Keep the last completed SDK frame while the camera is moving; replace it only after a fresh capture.
		View source=baseMapAllowed() && provider!=null && provider.isReady()?provider.getSampleSource():null;
		if(source==null) publishBackdrop(null,null);
		else if(backdropSource!=source) publishBackdrop(source,null);
	}
	private static final int LOCATION_REQUEST=7041;
	// The privacy summary shows fully only on the first map open of this app process.
	private static boolean firstPrivacyReveal=true;
	private final Handler main=new Handler(Looper.getMainLooper());
	private final Set<String> attemptedProviders=new HashSet<>();
	private final List<MapModels.Point> visiblePoints=new ArrayList<>();
	private String accountID,preferredProvider="auto";
	private AccountSession session;
	private MapProvider provider;
	private MapModels.Settings settings;
	private MapModels.Access access=MapModels.Access.LOADING;
	private MapModels.Point self;
	private MapProvider.CameraState lastCamera;
	private MapDeviceLocation.Request locationRequest;
	private MastodonAPIRequest<?> settingsRequest,presenceRequest,viewportRequest;
	private FriendMapOverlay overlay;
	private boolean destroyed=true,resumed,tabVisible,active,picking,snapshotBusy,returningFromGate;
	private SponsorNoticeSheet sponsorSheet;
	private boolean publishOnLocation,snapshotPending;
	private int generation,queryGeneration,snapshotGeneration,snapshotRequest,topInset,bottomInset;
	private Runnable viewportDebounce,snapshotDebounce,snapshotTimeout;
	private Bundle providerState;

	public static FriendMapFragment newInstance(String accountID){
		FriendMapFragment fragment=new FriendMapFragment(); Bundle args=new Bundle(); args.putString("account",accountID); fragment.setArguments(args); return fragment;
	}
	@Override public void onCreate(Bundle state){
		super.onCreate(state); accountID=getArguments().getString("account"); session=AccountSessionManager.getInstance().tryGetAccount(accountID);
		// The bottom-tab label belongs to navigation, not this standalone page's toolbar.
		tabVisible=!getArguments().getBoolean("__is_tab",false); setTitle("");
		if(state!=null){ preferredProvider=state.getString("map_provider","auto"); providerState=state.getBundle("map_sdk_state"); }
	}
	@Override public View onCreateView(LayoutInflater inflater,ViewGroup container,Bundle state){
		destroyed=false; generation++;
		overlay=new FriendMapOverlay(getActivity(),this); overlay.setNavigationInsets(topInset,bottomInset); overlay.showLoadingGate();
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
	private boolean consentAccepted(){ return settings!=null && MapModels.consentAccepted(settings.consent); }
	private boolean personalDataAllowed(){ return active && !destroyed && sessionValid() && consentAccepted() && settings.babyVerified && MapModels.allowsPersonalData(access); }
	private boolean baseMapAllowed(){ return active && !destroyed && sessionValid() && consentAccepted() && MapModels.allowsBaseMap(access); }
	private void updateActivityState(){
		boolean next=!destroyed && resumed && tabVisible && overlay!=null && sessionValid();
		if(next==active) return;
		active=next;
		if(active){
			returningFromGate=false; // This activation already reloads current verification and capabilities.
			overlay.enterMapDefaults();
			if(firstPrivacyReveal){ firstPrivacyReveal=false; if(!overlay.privacyExpanded()) overlay.setPrivacyExpanded(true,false); }
			loadSettings();
		}else{
			blockAccess(MapModels.Access.LOADING);
			if(provider!=null) provider.onPause();
			if(overlay!=null){ overlay.setBusy(false); overlay.setSampleSource(null); overlay.setSnapshot(null); overlay.showLoadingGate(); }
		}
	}
	/** Local cancellation must never delete/hide server presence: offline and logout retain it. */
	private void blockAccess(MapModels.Access next){
		access=next; generation++; queryGeneration++; cancelAll(); clearSnapshotWork(); publishBackdrop(null,null);
		self=null; lastCamera=null; visiblePoints.clear();
		if(provider!=null){ provider.renderSafe(visiblePoints); provider.showFuzzArea(null); }
		if(overlay!=null){ if(picking) cancelPin(); overlay.setPoints(visiblePoints); overlay.setSnapshot(null); overlay.setSampleSource(null); }
	}
	@Override public void refresh(){ attemptedProviders.clear(); loadSettings(); }
	private void loadSettings(){ loadSettings(true); }
	private void loadSettings(boolean readPresence){
		if(!active || destroyed) return;
		blockAccess(MapModels.Access.LOADING); overlay.showLoadingGate(); overlay.setBusy(true);
		int expected=generation;
		settingsRequest=MapRequest.settings().setCallback(new Callback<>(){
			@Override public void onSuccess(MapModels.Settings result){
				if(!live(expected)) return;
				settingsRequest=null;
				if(applySettings(result) && personalDataAllowed()){
					if(readPresence) readSelf();
					else{ overlay.setMessage(getString(R.string.map_ui_settings_changed),false); queryCurrentViewport(); }
				}
			}
			@Override public void onError(ErrorResponse error){ if(live(expected)){ settingsRequest=null; showError(error); } }
		}).exec(accountID);
	}
	/** Consent is checked before verification: even a decorative base map must await SDK authorization. */
	private boolean applySettings(MapModels.Settings result){
		settings=result; overlay.setBusy(false);
		if(result==null || result.consent==null || !MapModels.CONSENT_VERSION.equals(result.consent.version)){
			failAccess(getString(R.string.map_ui_protocol_failed)); return false;
		}
		overlay.updateSettings(result); overlay.setMessage("",false); access=MapModels.accessFor(result);
		if(access==MapModels.Access.CONSENT){
			providerState=null;
			if(provider!=null){ provider.onPause(); provider.destroy(); provider=null; overlay.mapHost().removeAllViews(); }
			overlay.showConsent(); return false;
		}
		if(access==MapModels.Access.VISIBILITY){
			providerState=null; lastCamera=null; overlay.showVisibilityGate();
			// Do not expose the previous SDK frame while an asynchronous camera reset is pending.
			if(provider!=null){ provider.onPause(); provider.destroy(); provider=null; overlay.mapHost().removeAllViews(); }
			attemptedProviders.clear(); startProvider(preferredProvider);
			return false;
		}
		if(access==MapModels.Access.VERIFICATION){
			// No personal markers, saved SDK camera state, viewport reads, permission request or upload.
			providerState=null; lastCamera=null; overlay.showVerificationGate();
			// Recreate instead of sampling a previous verified user's rendered frame.
			if(provider!=null){ provider.onPause(); provider.destroy(); provider=null; overlay.mapHost().removeAllViews(); }
			attemptedProviders.clear(); startProvider(preferredProvider);
			return false;
		}
		overlay.hideAccessGate();
		if(provider==null) startProvider(preferredProvider);
		else{ provider.onResume(); overlay.setSampleSource(provider.getSampleSource()); publishProviderBackdrop(); scheduleSnapshot(); }
		return personalDataAllowed();
	}
	private MapProvider.CameraState neutralCamera(){ return new MapProvider.CameraState(18,73,54,135,35,105,5,null); }
	@Override public void consent(){
		if(!active || access!=MapModels.Access.CONSENT || settings==null || settings.consent==null
				|| !MapModels.CONSENT_VERSION.equals(settings.consent.version) || !overlay.canSubmitConsent()) return;
		saveSettings(Map.of("consent_version",MapModels.CONSENT_VERSION),this::readSelf);
	}
	@Override public void privacy(){ UiUtils.launchWebBrowser(getActivity(),"https://abdl-space.top/privacy"); }
	@Override public void verification(){
		if(!active || access!=MapModels.Access.VERIFICATION) return;
		returningFromGate=true;
		Bundle args=new Bundle(); args.putString("account",accountID); Nav.go(getActivity(),BabyVerificationFragment.class,args);
	}
	private void startProvider(String preferred){
		if(!baseMapAllowed()) return;
		String chosen=MapProviderRegistry.next(MapProviderRegistry.available(),"auto".equals(preferred)?"baidu":preferred,attemptedProviders);
		MapProvider.CameraState camera=provider==null?lastCamera:provider.getCamera();
		if(!personalDataAllowed()){ camera=neutralCamera(); providerState=null; }
		clearSnapshotWork(); publishBackdrop(null,null);
		if(provider!=null){ provider.onPause(); provider.destroy(); provider=null; }
		overlay.mapHost().removeAllViews(); overlay.setSnapshot(null); overlay.setSampleSource(null);
		if(chosen==null){ failAccess(getString(R.string.map_presence_provider_unavailable)); return; }
		attemptedProviders.add(chosen);
		try{
			MapProvider current=MapProviderRegistry.create(getActivity(),chosen); provider=current;
			current.onCreate(providerState); providerState=null;
			overlay.mapHost().addView(current.getView(),new ViewGroup.LayoutParams(-1,-1));
			current.initialize(this);
			// initialize may synchronously fail and replace/destroy this provider.
			if(provider!=current || !baseMapAllowed()) return;
			current.renderSafe(visiblePoints); current.onResume(); overlay.setProvider(chosen); overlay.setSampleSource(current.getSampleSource());
			if(camera!=null) current.setCamera(camera);
			publishProviderBackdrop(); scheduleSnapshot();
		}catch(RuntimeException | LinkageError error){ onProviderError("sdk_init_failed"); }
	}
	@Override public void provider(String id){ if(!personalDataAllowed()) return; preferredProvider=id; attemptedProviders.clear(); startProvider(id); }
	private void readSelf(){
		if(!personalDataAllowed()) return;
		int expected=generation; if(presenceRequest!=null) presenceRequest.cancel();
		presenceRequest=MapRequest.mine().setCallback(new Callback<>(){
			@Override public void onSuccess(MapModels.Presence result){
				if(!live(expected) || !personalDataAllowed()) return;
				presenceRequest=null;
				boolean existing=result.active && result.point!=null;
				if(existing && !"hidden".equals(settings.visibility)){ self=result.point; if(provider!=null) provider.showFuzzArea(self); }
				queryCurrentViewport();
				// Always obtain a local browsing fix after the gates, without replacing an existing presence.
				requestDeviceLocation(!existing && MapModels.canPublishDeviceLocation(settings));
			}
			@Override public void onError(ErrorResponse error){ if(live(expected)){ presenceRequest=null; showError(error); } }
		}).exec(accountID);
	}
	@Override public void locate(){
		if(!personalDataAllowed()) return;
		requestDeviceLocation();
	}
	private void requestDeviceLocation(){ requestDeviceLocation(MapModels.canPublishDeviceLocation(settings)); }
	private void requestDeviceLocation(boolean publish){
		if(!personalDataAllowed()) return;
		publishOnLocation=publish && MapModels.canPublishDeviceLocation(settings);
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
		int expected=generation; boolean shouldPublish=publishOnLocation;
		overlay.setBusy(true); overlay.setMessage(getString(R.string.map_presence_locating),false);
		locationRequest=MapDeviceLocation.once(getActivity(),location->{
			if(!live(expected) || !personalDataAllowed()) return;
			locationRequest=null; overlay.setBusy(false);
			if(location==null){ overlay.setMessage(getString(R.string.map_presence_location_failed),false); return; }
			centerOnDevice(location.getLatitude(),location.getLongitude());
			if(shouldPublish && MapModels.canPublishDeviceLocation(settings)) upload(location.getLatitude(),location.getLongitude(),"device");
			else{ overlay.setMessage(getString(R.string.map_presence_local_location),false); queryCurrentViewport(); }
		});
	}
	@Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
		super.onRequestPermissionsResult(code,permissions,results);
		if(code==LOCATION_REQUEST && personalDataAllowed()){ if(MapDeviceLocation.permitted(getActivity())) requestDeviceLocation(publishOnLocation); else overlay.setMessage(getString(R.string.map_presence_permission_needed),false); }
	}
	private void upload(double lat,double lng,String mode){
		if(!personalDataAllowed() || "hidden".equals(settings.visibility) || !mode.equals(settings.locationMode)
				|| ("pinned".equals(mode) && !settings.canChooseLocation)) return;
		int expected=generation; Map<String,Object> body=new HashMap<>(); body.put("latitude",lat); body.put("longitude",lng);
		body.put("coordinate_system","wgs84"); body.put("location_mode",mode); body.put("settings_version",settings.settingsVersion);
		if(presenceRequest!=null) presenceRequest.cancel(); overlay.setBusy(true);
		presenceRequest=MapRequest.updatePresence(body).setCallback(new Callback<>(){
			@Override public void onSuccess(MapModels.Presence result){
				if(!live(expected) || !personalDataAllowed()) return;
				presenceRequest=null; self=result.point;
				if(result.settingsVersion>0) settings.settingsVersion=result.settingsVersion;
				settings.currentRegion=result.currentRegion!=null?result.currentRegion:self==null?null:self.region;
				settings.locationMode=mode; overlay.setBusy(false); overlay.setMessage("",false); overlay.updateSettings(settings); if(picking) cancelPin(); centerOnSelf(); queryCurrentViewport();
			}
			@Override public void onError(ErrorResponse error){ if(live(expected)){ presenceRequest=null; overlay.setBusy(false); showError(error); } }
		}).exec(accountID);
	}
	/** Raw device coordinates are used only for the local camera, never a self marker or fuzz circle. */
	private void centerOnDevice(double lat,double lng){
		if(!personalDataAllowed() || provider==null) return;
		lastCamera=new MapProvider.CameraState(lat-.05,lng-.05,lat+.05,lng+.05,lat,lng,11,null);
		provider.setCamera(lastCamera);
	}
	private void centerOnSelf(){
		if(!personalDataAllowed() || self==null || provider==null) return;
		MapProvider.CameraState camera=new MapProvider.CameraState(self.lat-.05,self.lng-.05,self.lat+.05,self.lng+.05,self.lat,self.lng,11,null);
		lastCamera=camera; provider.setCamera(camera); provider.showFuzzArea(self);
	}
	@Override public void visibility(String value){
		if(!personalDataAllowed() || !MapModels.validVisibility(value)) return;
		if(!MapModels.canChooseVisibility(settings,value)){ visibilitySponsor(); return; }
		writeVisibility(value,true);
	}
	@Override public void confirmVisibility(String value){
		if(!visibilityGateAllowed() || !MapModels.validVisibility(value)) return;
		if(!MapModels.canConfirmVisibility(settings,value)){ visibilitySponsor(); return; }
		writeVisibility(value,true);
	}
	@Override public void stopAppearance(){
		if(!active || destroyed || !sessionValid() || settings==null) return;
		// Safety withdrawal bypasses consent/verification/sponsorship gates, like the server.
		writeVisibility("hidden",false);
	}
	private boolean visibilityGateAllowed(){
		return active && !destroyed && sessionValid() && consentAccepted() && settings.babyVerified && access==MapModels.Access.VISIBILITY;
	}
	private void writeVisibility(String value,boolean confirm){
		Map<String,Object> changes=new HashMap<>(); changes.put("visibility",value);
		// Every explicit visibility choice (not just first use) carries the server's intent flag.
		if(confirm){
			changes.put("visibility_confirmed",true);
			if("pinned".equals(settings.locationMode) && !settings.canChooseLocation && !"hidden".equals(value))
				changes.put("location_mode","device");
		}
		saveSettings(changes,()->{
			if(confirm) requestDeviceLocation();
			else if("hidden".equals(value)){ overlay.setMessage(getString(R.string.map_presence_local_location),false); queryCurrentViewport(); }
			else requestDeviceLocation();
		});
	}
	@Override public void precision(String value){
		if(!personalDataAllowed() || !MapModels.validPrecision(value)) return;
		if(!MapModels.canChoosePrecision(settings,value)){ cityPrecisionSponsor(); return; }
		saveSettings(Map.of("precision_level",value),this::readSelf);
	}
	@Override public void anonymous(boolean value){ if(personalDataAllowed()) saveSettings(Map.of("anonymous",value),this::readSelf); }
	@Override public void useDeviceLocation(){ if(personalDataAllowed()) saveSettings(Map.of("location_mode","device"),this::requestDeviceLocation); }
	private void saveSettings(Map<String,Object> changes,Runnable done){
		boolean withdrawing="hidden".equals(changes.get("visibility")) && !changes.containsKey("visibility_confirmed");
		if(!active || destroyed || !sessionValid() || settings==null || (!withdrawing && !personalDataAllowed()
				&& !(access==MapModels.Access.CONSENT && changes.containsKey("consent_version"))
				&& !(visibilityGateAllowed() && changes.containsKey("visibility")))) return;
		Map<String,Object> body=new HashMap<>(changes);
		if(!"hidden".equals(changes.get("visibility"))) body.put("settings_version",settings.settingsVersion);
		blockAccess(MapModels.Access.LOADING); overlay.showLoadingGate(); overlay.setBusy(true);
		int expected=generation;
		settingsRequest=MapRequest.updateSettings(body).setCallback(new Callback<>(){
			@Override public void onSuccess(MapModels.Settings result){ if(live(expected)){ settingsRequest=null; if(applySettings(result)) done.run(); } }
			@Override public void onError(ErrorResponse error){ if(live(expected)){ settingsRequest=null; showError(error); } }
		}).exec(accountID);
	}
	@Override public void beginPin(){
		if(!personalDataAllowed()) return;
		if(!settings.canChooseLocation){ sponsor(); return; }
		if("hidden".equals(settings.visibility)){ overlay.setMessage(getString(R.string.map_ui_enable_first),false); return; }
		picking=true; overlay.showPin();
	}
	@Override public void confirmPin(){
		if(!personalDataAllowed() || !picking || provider==null || !settings.canChooseLocation) return;
		MapProvider.CameraState camera=provider.getCamera(); if(camera==null) return;
		double lat=camera.centerLat(),lng=camera.centerLng();
		if("pinned".equals(settings.locationMode)) upload(lat,lng,"pinned");
		else saveSettings(Map.of("location_mode","pinned"),()->upload(lat,lng,"pinned"));
	}
	@Override public void cancelPin(){ picking=false; if(overlay!=null) overlay.finishPin(); }
	@Override public void onLocationSelected(double lat,double lng){
		if(!personalDataAllowed()) return;
		if(!settings.canChooseLocation){ sponsor(); return; }
		beginPin(); if(picking && provider!=null) provider.setCamera(new MapProvider.CameraState(lat-.03,lng-.03,lat+.03,lng+.03,lat,lng,13,null));
	}
	@Override public void focus(MapModels.Point point){
		if(!personalDataAllowed() || provider==null || point==null) return;
		provider.setCamera(new MapProvider.CameraState(point.lat-.03,point.lng-.03,point.lat+.03,point.lng+.03,point.lat,point.lng,13,null));
		provider.selectPoint(point.id); provider.showFuzzArea(point);
	}
	@Override public void profile(MapModels.Point point){
		if(!personalDataAllowed() || point==null || point.anonymous || point.account==null || point.account.id==null) return;
		Bundle args=new Bundle(); args.putString("account",accountID); args.putString("profileAccountID",point.account.id); Nav.go(getActivity(),ProfileFragment.class,args);
	}
	@Override public void onMarkerClick(MapModels.Point point){ if(personalDataAllowed()){ focus(point); overlay.showPerson(point); } }
	@Override public void sponsor(){
		if(personalDataAllowed()) showSponsorNotice(R.string.map_presence_pin,R.string.map_presence_sponsor_body);
	}
	@Override public void visibilitySponsor(){
		if(personalDataAllowed() || visibilityGateAllowed()) showSponsorNotice(R.string.map_ui_visibility,R.string.map_presence_visibility_sponsor_body);
	}
	@Override public void cityPrecisionSponsor(){
		if(personalDataAllowed()) showSponsorNotice(R.string.map_ui_precision,R.string.map_presence_city_precision_sponsor_body);
	}
	private void showSponsorNotice(int title,int body){
		if(sponsorSheet!=null) return;
		sponsorSheet=new SponsorNoticeSheet(getActivity(),getString(title),getString(body),null,null,()->{
			returningFromGate=true;
			Bundle args=new Bundle(); args.putString("account",accountID); Nav.go(getActivity(),SponsorCenterFragment.class,args);
		});
		sponsorSheet.setOnDismissListener(dialog->sponsorSheet=null); sponsorSheet.show();
	}
	@Override public void onMapReady(){ if(baseMapAllowed() && provider!=null){ if(personalDataAllowed() && lastCamera!=null) provider.setCamera(lastCamera); publishProviderBackdrop(); scheduleSnapshot(); queryCurrentViewport(); } }
	@Override public void onCameraMove(){
		if(!baseMapAllowed()) return;
		snapshotGeneration++;
		if(overlay!=null){ overlay.setSnapshot(null); if(personalDataAllowed()) overlay.onCameraMoved(); }
		publishProviderBackdrop();
	}
	public View getMapSampleView(){ return baseMapAllowed() && provider!=null?provider.getSampleSource():null; }
	@Override public void onCameraIdle(MapProvider.CameraState camera){
		if(!baseMapAllowed() || camera==null) return;
		snapshotGeneration++; scheduleSnapshot();
		if(!personalDataAllowed() || picking) return;
		if(viewportDebounce!=null) main.removeCallbacks(viewportDebounce);
		viewportDebounce=()->queryViewport(camera); main.postDelayed(viewportDebounce,400);
	}
	private void queryCurrentViewport(){ if(personalDataAllowed() && provider!=null && provider.isReady()){ MapProvider.CameraState camera=provider.getCamera(); if(camera!=null) onCameraIdle(camera); } }
	private void queryViewport(MapProvider.CameraState camera){
		if(!personalDataAllowed()) return;
		if(viewportRequest!=null) viewportRequest.cancel(); int expected=generation,query=++queryGeneration;
		// Browsing has no sponsor/current-city constraint, including for hidden users without a location.
		viewportRequest=MapRequest.list(camera.minLat(),camera.minLng(),camera.maxLat(),camera.maxLng(),camera.centerLat(),camera.centerLng(),null,camera.zoom(),100).setCallback(new Callback<>(){
			@Override public void onSuccess(MapModels.PresenceList result){
				if(!live(expected) || !personalDataAllowed() || query!=queryGeneration) return;
				viewportRequest=null; lastCamera=camera; visiblePoints.clear(); visiblePoints.addAll(result.points);
				if(provider!=null) provider.renderSafe(visiblePoints); overlay.setPoints(visiblePoints); overlay.setMessage("",false); scheduleSnapshot();
			}
			@Override public void onError(ErrorResponse error){ if(!live(expected) || query!=queryGeneration) return; viewportRequest=null; showError(error); }
		}).exec(accountID);
	}
	private void showError(ErrorResponse error){
		if(overlay==null) return;
		if(error instanceof MapRequest.MapErrorResponse mapError && ("map_settings_changed".equals(mapError.code)
				|| "map_verification_required".equals(mapError.code) || "map_baby_verification_required".equals(mapError.code)
				|| "map_consent_required".equals(mapError.code) || "map_visibility_confirmation_required".equals(mapError.code)
				|| "map_visibility_sponsor_required".equals(mapError.code) || "map_sponsor_required".equals(mapError.code))){
			// Re-read current gates/settings, never replay a stale setting write or location upload.
			loadSettings(false); return;
		}
		int status=error instanceof MastodonErrorResponse response?response.httpStatus:-1;
		int message=status==404?R.string.map_ui_not_deployed:status==401?R.string.map_ui_login_required:status>=500?R.string.map_ui_server_failed:status<0?R.string.map_ui_network_failed:R.string.map_ui_protocol_failed;
		failAccess(getString(message));
	}
	private void failAccess(CharSequence message){
		blockAccess(MapModels.Access.ERROR);
		if(overlay!=null){ overlay.setBusy(false); overlay.showErrorGate(message); }
	}
	@Override public void onProviderError(String code){ if(baseMapAllowed()){ overlay.setMessage(getString(R.string.map_presence_switching),false); startProvider(preferredProvider); } }
	@Override public void panelChanged(int top,int bottom){ if(provider!=null) provider.setContentInsets(top,bottom); }
	private void scheduleSnapshot(){
		if(!baseMapAllowed() || provider==null || !provider.isReady()
				|| (mapBackdropListener==null && access!=MapModels.Access.VERIFICATION && !GlobalUserPreferences.isIosLiquidNavigationEnabled())) return;
		snapshotPending=true;
		if(snapshotBusy) return;
		if(snapshotDebounce!=null) main.removeCallbacks(snapshotDebounce); snapshotDebounce=this::sampleMap; main.postDelayed(snapshotDebounce,300);
	}
	private void sampleMap(){
		if(!baseMapAllowed() || provider==null || !provider.isReady()) return;
		if(snapshotBusy){ snapshotPending=true; return; }
		snapshotPending=false;
		MapProvider current=provider; View source=current.getSampleSource();
		int expected=generation,sample=snapshotGeneration,request=++snapshotRequest; snapshotBusy=true;
		snapshotTimeout=()->{
			if(live(expected) && current==provider && request==snapshotRequest){
				snapshotRequest++; snapshotBusy=false; publishProviderBackdrop();
				if(snapshotPending || sample!=snapshotGeneration) scheduleSnapshot();
			}
		}; main.postDelayed(snapshotTimeout,2500);
		current.requestSnapshot(bitmap->main.post(()->{
			if(!live(expected) || !baseMapAllowed() || current!=provider || request!=snapshotRequest) return;
			snapshotRequest++; snapshotBusy=false; if(snapshotTimeout!=null) main.removeCallbacks(snapshotTimeout);
			if(sample!=snapshotGeneration){ scheduleSnapshot(); return; }
			Bitmap safe=bitmap!=null && !bitmap.isRecycled()?bitmap:null;
			if(safe!=null){ overlay.setSnapshot(safe); publishBackdrop(source,safe); }
			if(snapshotPending || sample!=snapshotGeneration) scheduleSnapshot();
		}));
	}
	private void clearSnapshotWork(){
			snapshotGeneration++; snapshotRequest++; snapshotBusy=false; snapshotPending=false;
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
	@Override public void onResume(){ super.onResume(); resumed=true; updateActivityState(); refreshAfterGate(); }
	@Override protected void onShown(){ super.onShown(); refreshAfterGate(); }
	private void refreshAfterGate(){
		if(returningFromGate && active && overlay!=null){ returningFromGate=false; loadSettings(); }
	}
	@Override public void onPause(){ resumed=false; updateActivityState(); super.onPause(); }
	@Override public void onSaveInstanceState(Bundle state){
		super.onSaveInstanceState(state); state.putString("map_provider",preferredProvider);
		if(provider!=null && personalDataAllowed()){ Bundle sdk=new Bundle(); provider.onSaveInstanceState(sdk); state.putBundle("map_sdk_state",sdk); }
	}
	@Override public void onLowMemory(){ super.onLowMemory(); if(provider!=null) provider.onLowMemory(); }
	@Override public void onDestroyView(){
		destroyed=true; updateActivityState(); generation++; cancelAll(); clearSnapshotWork(); publishBackdrop(null,null); main.removeCallbacksAndMessages(null);
		if(sponsorSheet!=null){ sponsorSheet.dismiss(); sponsorSheet=null; }
		if(provider!=null){ provider.destroy(); provider=null; } if(overlay!=null){ overlay.dispose(); overlay=null; }
		attemptedProviders.clear(); super.onDestroyView();
	}
}
