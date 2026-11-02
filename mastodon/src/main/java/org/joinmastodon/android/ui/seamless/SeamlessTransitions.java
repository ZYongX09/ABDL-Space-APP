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

import com.squareup.otto.Subscribe;
import org.joinmastodon.android.E;
import org.joinmastodon.android.events.AccountLoggedOutEvent;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/** Optional card-to-Activity route. All failures before launch leave the original navigation untouched. */
public final class SeamlessTransitions{
	public static final int MAX_ARGUMENT_BYTES=128*1024;
	public static final String DETAIL_ENTRY="seamless_detail_entry";
	private static final String NOVEL_ACTIVITY="org.joinmastodon.android.novel.app.NovelAppActivity";
	private static final Map<Activity, Session> sessions=new WeakHashMap<>();
	private static Session pending;
	private static Application installed;
	private static final Handler main=new Handler(Looper.getMainLooper());
	private static final LogoutListener logoutListener=new LogoutListener();
	private static boolean eventsRegistered;
	public static final class LogoutListener{
		@Subscribe public void onLogout(AccountLoggedOutEvent event){ clear(); }
	}
	private SeamlessTransitions(){}

	public static boolean isSupported(){ return SharedViewRom.supportedProtocol()>0; }
	public static void bindCard(View card, String account, String entity, boolean permitted){ SeamlessSource.bind(card, account, entity, permitted, null); }
	public static void bindCard(View card, String account, String entity, boolean permitted, Status status){ SeamlessSource.bind(card, account, entity, permitted, status); }

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
		SeamlessSource source=SeamlessSource.post(activity, list, account, status);
		return launch(activity, SeamlessDetailActivity.intent(activity, SeamlessDetailActivity.POST, args), source);
	}
	public static boolean openAlbum(Activity activity, View card, String account, String album, boolean protectedAlbum, Bundle args){
		if(protectedAlbum || !canStart(activity, account) || !argumentsFit(args)) return false;
		return launch(activity, SeamlessDetailActivity.intent(activity, SeamlessDetailActivity.ALBUM, args), SeamlessSource.card(activity, card, account, album));
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
		try{
			Map<String, String> extras=SharedViewRom.extras();
			boolean window=SharedViewRom.windowCoordinates(extras);
			Rect bounds=source.bounds(window);
			if(bounds==null || bounds.isEmpty()) return false;
			Bundle config=new Bundle();
			config.putFloat(SharedViewRom.RADIUS, source.radius());
			Integer color=source.backgroundColor(); if(color!=null) config.putInt(SharedViewRom.COLOR, color);
			if(SharedViewRom.permits(extras, SharedViewRom.FALLBACK, SharedViewRom.FADE)) config.putInt(SharedViewRom.FALLBACK, SharedViewRom.FADE);
			Bitmap bitmap=null;
			if(source.bitmapOnly()){
				bitmap=source.snapshot();
				if(bitmap==null) return false;
				options=SharedViewRom.make(bitmap, bounds, source.radius(), config);
			}else options=SharedViewRom.make(source.drawingView(), config);
			if(options==null) return false;
			session=new Session(source, window, extras, bitmap, intent.getComponent().getClassName());
		}catch(Exception | LinkageError failure){ return false; }
		// Recheck after capture/ROM IPC: never launch a stale identity or newly locked source.
		if(!source.valid()){ SharedViewRom.cancel(); return false; }
		install(activity.getApplication());
		pending=session;
		main.postDelayed(()->{ if(pending==session){ pending=null; session.bitmap=null; SharedViewRom.cancel(); } }, 30_000);
		try{
			// Options are ready before the only launch; pre-launch failures preserve the original route.
			activity.startActivity(intent, options.toBundle());
			return true;
		}catch(RuntimeException | LinkageError failure){
			boolean targetCreated=sessions.containsValue(session);
			pending=null; SharedViewRom.cancel();
			return targetCreated; // Only an unlaunched route may fall back; never retry after a target exists.
		}
	}

	/** Claims the in-memory launch; neither the opaque identity nor source pixels enter Intent/state. */
	public static void attach(Activity target, String account, String entity){
		Session launch=pending;
		if(launch==null || !target.getClass().getName().equals(launch.targetClass)
				|| !Objects.equals(account, launch.source.account) || !Objects.equals(entity, launch.source.entity)) return;
		pending=null;
		if(!captureAllowed(target, account) || launch.accountSession!=AccountSessionManager.getInstance().tryGetAccount(account)){
			SharedViewRom.cancel(); return;
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
			Bundle config=new Bundle();
			if(session.source.valid()){
				Rect current=session.source.bounds(session.window);
				if(current==null){ fallback(target, session); return; }
				config.putParcelable(SharedViewRom.BOUNDS, current);
				SharedViewRom.override(target, config);
			}else fallback(target, session);
		}catch(Exception | LinkageError ignored){ SharedViewRom.cancel(); }
		finally{ session.bitmap=null; }
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
		if(pending!=null && pending.targetClass.equals(activity.getClass().getName())){
			pending.bitmap=null; pending=null; SharedViewRom.cancel();
		}
		if(session!=null){ session.bitmap=null; SharedViewRom.cancel(); }
	}
	public static void clear(){
		if(pending!=null || !sessions.isEmpty()) SharedViewRom.cancel();
		pending=null; sessions.clear();
	}
	private static void install(Application app){
		if(installed==app) return;
		installed=app;
		if(!eventsRegistered){ E.register(logoutListener); eventsRegistered=true; }
		app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
			@Override public void onActivityCreated(Activity activity, Bundle state){}
			@Override public void onActivityStarted(Activity activity){}
			@Override public void onActivityResumed(Activity activity){
				if(AppSecurity.isLocked()) clear();
				else sessions.entrySet().removeIf(e->{
					Session s=e.getValue();
					if(!currentAccount(s.source.account) || s.accountSession!=AccountSessionManager.getInstance().tryGetAccount(s.source.account)){ s.bitmap=null; SharedViewRom.cancel(); return true; }
					return false;
				});
			}
			@Override public void onActivityPaused(Activity activity){ if(AppSecurity.isLocked()) clear(); }
			@Override public void onActivityStopped(Activity activity){}
			@Override public void onActivitySaveInstanceState(Activity activity, Bundle outState){}
			@Override public void onActivityDestroyed(Activity activity){ protectedContent(activity); }
		});
	}
	private static final class Session{
		final Object opaqueToken=new Object();
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
