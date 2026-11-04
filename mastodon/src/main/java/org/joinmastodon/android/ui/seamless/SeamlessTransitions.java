package org.joinmastodon.android.ui.seamless;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.view.View;
import android.view.WindowManager;

import androidx.recyclerview.widget.RecyclerView;

import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.security.AppSecurity;
import org.joinmastodon.android.ui.displayitems.StatusDisplayItem;

import com.squareup.otto.Subscribe;
import org.joinmastodon.android.E;
import org.joinmastodon.android.events.AccountLoggedOutEvent;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/** Optional card-to-Activity route. All failures before launch leave the original navigation untouched. */
public final class SeamlessTransitions{
	public static final int MAX_ARGUMENT_BYTES=128*1024;
	static final int MAX_RETAINED_PIXELS=2*SeamlessSource.MAX_PIXELS;
	public static final String DETAIL_ENTRY="seamless_detail_entry";
	private static final String NOVEL_ACTIVITY="org.joinmastodon.android.novel.app.NovelAppActivity";
	private static final Map<Activity, Session> sessions=new WeakHashMap<>();
	private static Session pending;
	private static Application installed;
	private static final Handler main=new Handler(Looper.getMainLooper());
	// One cancellable watchdog, never a posted lambda retaining a Session/Bitmap after attach.
	private static final Runnable pendingTimeout=()->{
		if(pending!=null){ release(takePending()); SharedViewRom.cancel(); }
	};
	private static final LogoutListener logoutListener=new LogoutListener();
	private static boolean eventsRegistered;
	public static final class LogoutListener{
		@Subscribe public void onLogout(AccountLoggedOutEvent event){ clear(); }
	}
	private SeamlessTransitions(){}

	public static boolean isSupported(){ return SharedViewRom.supportedProtocol()>0; }
	public static void bindCard(View card, String account, String entity, boolean permitted){ SeamlessSource.bind(card, account, entity, permitted, null); }
	public static void bindCard(View card, String account, String entity, boolean permitted, Status status){ SeamlessSource.bind(card, account, entity, permitted, status); }
	public static void bindFriendCard(View card, String account, String entity, boolean permitted, Status owner, Status content){ SeamlessSource.bindFriendCard(card, account, entity, permitted, owner, content); }

	static boolean currentAccount(String account){
		if(account==null) return false;
		try{
			AccountSessionManager manager=AccountSessionManager.getInstance();
			return account.equals(manager.getLastActiveAccountID()) && manager.tryGetAccount(account)!=null;
		}catch(RuntimeException unavailable){ return false; }
	}
	static boolean captureAllowed(Activity activity, String account){
		// No null-tracker exception for tests: an uninitialised security layer is locked.
		return activity!=null && !activity.isFinishing() && !activity.isDestroyed() && currentAccount(account)
				&& !AppSecurity.isLocked() && !secure(activity) && GlobalUserPreferences.useSeamlessTransitions
				&& !GlobalUserPreferences.reduceMotion && animationsEnabled();
	}
	private static boolean animationsEnabled(){
		try{ return ValueAnimator.areAnimatorsEnabled(); }catch(RuntimeException failure){ return false; }
	}
	private static boolean secure(Activity activity){ return (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE)!=0; }

	public static boolean argumentsFit(Bundle args){
		if(args==null) return false;
		Parcel parcel=Parcel.obtain();
		try{ parcel.writeBundle(args); return parcel.dataSize()<=MAX_ARGUMENT_BYTES; }
		catch(RuntimeException failure){ return false; }
		finally{ parcel.recycle(); }
	}

	public static boolean openPost(Activity activity, RecyclerView list, String account, Status status, Bundle args){
		if(!canStart(activity, account) || status==null || !argumentsFit(args)) return false;
		return launch(activity, SeamlessDetailActivity.intent(activity, SeamlessDetailActivity.POST, args), SeamlessSource.post(activity, list, account, status));
	}
	public static boolean openPost(Activity activity, RecyclerView list, String account, Status status, Bundle args, String parentID, Status visibilityStatus, List<StatusDisplayItem> models){
		if(!canStart(activity, account) || status==null || !argumentsFit(args)) return false;
		SeamlessSource source=SeamlessSource.post(activity, list, account, status, parentID, visibilityStatus, models);
		return launch(activity, SeamlessDetailActivity.intent(activity, SeamlessDetailActivity.POST, args), source);
	}
	public static boolean openAlbum(Activity activity, View card, String account, String album, boolean protectedAlbum, Bundle args){
		if(protectedAlbum || !canStart(activity, account) || !argumentsFit(args)) return false;
		return launch(activity, SeamlessDetailActivity.intent(activity, SeamlessDetailActivity.ALBUM, args), SeamlessSource.card(activity, card, account, album));
	}
	public static boolean openDiaper(Activity activity, View card, String account, int diaper, Bundle args){
		if(diaper<=0 || !canStart(activity, account) || !argumentsFit(args) || !Objects.equals(account, args.getString("account")) || args.getInt("diaper_id")!=diaper) return false;
		return launch(activity, SeamlessDetailActivity.intent(activity, SeamlessDetailActivity.DIAPER, args), SeamlessSource.card(activity, card, account, Integer.toString(diaper)));
	}
	public static boolean openFriendCard(Activity activity, View card, String account, String cardID, Bundle args){
		if(cardID==null || cardID.isBlank() || !canStart(activity, account) || !argumentsFit(args) || !Objects.equals(account, args.getString("account")) || !cardID.equals(args.getString("card_id"))) return false;
		return launch(activity, SeamlessDetailActivity.intent(activity, SeamlessDetailActivity.FRIEND_CARD, args), SeamlessSource.card(activity, card, account, cardID));
	}
	public static boolean openNovel(Activity activity, View card, String account, String work){
		if(!canStart(activity, account)) return false;
		Intent intent=new Intent().setClassName(activity, NOVEL_ACTIVITY).putExtra("account", account).putExtra("work_id", work).putExtra(DETAIL_ENTRY, true);
		return launch(activity, intent, SeamlessSource.card(activity, card, account, work));
	}
	private static boolean canStart(Activity activity, String account){
		if(Looper.myLooper()!=Looper.getMainLooper()) return false;
		if(!captureAllowed(activity, account)){
			if(AppSecurity.isLocked() || !currentAccount(account) || !GlobalUserPreferences.useSeamlessTransitions) clear();
			return false;
		}
		return isSupported() && pending==null && sessions.size()<4;
	}

	private static boolean launch(Activity activity, Intent intent, SeamlessSource source){
		if(source==null || !source.valid()) return false;
		ActivityOptions options;
		Session session;
		Bitmap bitmap=null;
		try{
			Map<String, String> extras=SharedViewRom.extras();
			boolean window=SharedViewRom.windowCoordinates(extras);
			Rect screen=source.screenBounds(), bounds=source.coordinates(screen, window);
			if(bounds==null || bounds.isEmpty()) return false;
			Bundle config=new Bundle();
			config.putFloat(SharedViewRom.RADIUS, source.radius());
			Integer color=source.backgroundColor(); if(color!=null) config.putInt(SharedViewRom.COLOR, color);
			if(SharedViewRom.permits(extras, SharedViewRom.FALLBACK, SharedViewRom.FADE)) config.putInt(SharedViewRom.FALLBACK, SharedViewRom.FADE);
			if(source.bitmapOnly()){
				int remaining=MAX_RETAINED_PIXELS-retainedPixels();
				if(remaining<=0) return false;
				bitmap=source.snapshot(screen, remaining); // At most one allocation/capture per click.
				if(bitmap==null) return false;
				options=SharedViewRom.make(bitmap, bounds, source.radius(), config);
			}else options=SharedViewRom.make(source.drawingView(), config); // Stable cards never get a redundant app screenshot.
			if(options==null){ SharedViewRom.cancel(); return false; }
			session=new Session(source, window, extras, bitmap, intent.getComponent().getClassName());
		}catch(Exception | LinkageError failure){ SharedViewRom.cancel(); return false; }
		// Recheck after capture/ROM IPC: never launch a stale identity or newly locked source.
		if(!source.valid()){ release(session); SharedViewRom.cancel(); return false; }
		install(activity.getApplication());
		pending=session;
		main.postDelayed(pendingTimeout, 30_000);
		try{
			// Options are ready before the only launch; pre-launch failures preserve the original route.
			activity.startActivity(intent, options.toBundle());
			return true;
		}catch(RuntimeException | LinkageError failure){
			boolean targetCreated=sessions.containsValue(session);
			if(pending==session) release(takePending());
			if(!targetCreated) release(session);
			SharedViewRom.cancel();
			return targetCreated; // Only an unlaunched route may fall back; never retry after a target exists.
		}
	}

	/** Claims the in-memory launch; neither the opaque identity nor source pixels enter Intent/state. */
	public static void attach(Activity target, String account, String entity){
		Session launch=pending;
		if(launch==null || !target.getClass().getName().equals(launch.targetClass)
				|| !Objects.equals(account, launch.source.account) || !Objects.equals(entity, launch.source.entity)) return;
		takePending();
		if(!captureAllowed(target, account) || launch.accountSession!=AccountSessionManager.getInstance().tryGetAccount(account)){
			release(launch); SharedViewRom.cancel(); return;
		}
		sessions.put(target, launch);
	}

	public static void beforeFinish(Activity target){
		Session session=sessions.remove(target);
		if(session==null) return; // Process restoration has content, but no stale return animation session.
		try{
			if(!captureAllowed(target, session.source.account) || SharedViewRom.supportedProtocol()==0 || session.accountSession!=AccountSessionManager.getInstance().tryGetAccount(session.source.account)){
				SharedViewRom.cancel(); return;
			}
			if(session.source.valid()){
				Rect current=session.source.bounds(session.window);
				if(current==null){ fallback(target, session); return; }
				Bundle config=new Bundle(); config.putParcelable(SharedViewRom.BOUNDS, current);
				// Synchronous, before finish: no posted return bounds or second bitmap capture.
				SharedViewRom.override(target, config);
			}else fallback(target, session);
		}catch(Exception | LinkageError ignored){ SharedViewRom.cancel(); }
		finally{ release(session); }
	}
	private static void fallback(Activity target, Session session) throws Exception{
		Bundle config=new Bundle();
		if(SharedViewRom.permits(session.extras, SharedViewRom.BACK, SharedViewRom.FADE)) config.putInt(SharedViewRom.BACK, SharedViewRom.FADE);
		else if(SharedViewRom.permits(session.extras, SharedViewRom.BACK, SharedViewRom.SYSTEM)) config.putInt(SharedViewRom.BACK, SharedViewRom.SYSTEM);
		else{ SharedViewRom.cancel(); return; }
		SharedViewRom.override(target, config);
	}
	public static void protectedContent(Activity activity){
		Session session=sessions.remove(activity);
		if(pending!=null && (pending.targetClass.equals(activity.getClass().getName()) || pending.source.owner.get()==activity)){
			release(takePending()); SharedViewRom.cancel();
		}
		if(session!=null){ release(session); SharedViewRom.cancel(); }
	}
	public static void clear(){
		boolean active=pending!=null || !sessions.isEmpty();
		release(takePending());
		for(Session session:sessions.values()) release(session);
		sessions.clear();
		if(active) SharedViewRom.cancel();
	}
	private static Session takePending(){
		Session session=pending; pending=null; main.removeCallbacks(pendingTimeout); return session;
	}
	private static void release(Session session){
		// Once handed to the ROM, a bitmap must not be recycled while the ROM may still read it.
		// Drop our strong reference; never retain source pixels in a Runnable, Intent or saved state.
		if(session!=null) session.bitmap=null;
	}
	static int retainedPixels(){
		long pixels=pending==null || pending.bitmap==null ? 0 : (long)pending.bitmap.getWidth()*pending.bitmap.getHeight();
		for(Session session:sessions.values()) if(session.bitmap!=null) pixels+=(long)session.bitmap.getWidth()*session.bitmap.getHeight();
		return (int)Math.min(Integer.MAX_VALUE, pixels);
	}
	private static void install(Application app){
		if(installed==app) return;
		installed=app;
		if(!eventsRegistered){ E.register(logoutListener); eventsRegistered=true; }
		app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
			@Override public void onActivityCreated(Activity activity, Bundle state){}
			@Override public void onActivityStarted(Activity activity){}
			@Override public void onActivityResumed(Activity activity){
				if(AppSecurity.isLocked() || !GlobalUserPreferences.useSeamlessTransitions){ clear(); return; }
				if(pending!=null && !identityCurrent(pending)){ release(takePending()); SharedViewRom.cancel(); }
				sessions.entrySet().removeIf(e->{
					Session s=e.getValue();
					if(!identityCurrent(s)){ release(s); SharedViewRom.cancel(); return true; }
					return false;
				});
			}
			@Override public void onActivityPaused(Activity activity){ if(AppSecurity.isLocked()) clear(); }
			@Override public void onActivityStopped(Activity activity){}
			@Override public void onActivitySaveInstanceState(Activity activity, Bundle outState){}
			@Override public void onActivityDestroyed(Activity activity){
				protectedContent(activity);
				sessions.entrySet().removeIf(e->{
					if(e.getValue().source.owner.get()==activity){ release(e.getValue()); SharedViewRom.cancel(); return true; }
					return false;
				});
			}
		});
	}
	private static boolean identityCurrent(Session session){
		return currentAccount(session.source.account) && session.accountSession==AccountSessionManager.getInstance().tryGetAccount(session.source.account);
	}
	private static final class Session{
		final SeamlessSource source;
		final boolean window;
		final Map<String, String> extras;
		final AccountSession accountSession;
		final String targetClass;
		Bitmap bitmap;
		Session(SeamlessSource source, boolean window, Map<String, String> extras, Bitmap bitmap, String targetClass){
			this.source=source; this.window=window; this.extras=Map.copyOf(extras); this.bitmap=bitmap; this.targetClass=targetClass;
			accountSession=AccountSessionManager.getInstance().tryGetAccount(source.account);
		}
	}
}
