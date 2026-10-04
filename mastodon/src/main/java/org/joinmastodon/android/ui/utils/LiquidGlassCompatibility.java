package org.joinmastodon.android.ui.utils;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.joinmastodon.android.MastodonApp;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Attached-view hardware checks belong to the UI owner, not the settings capability gate. */
public final class LiquidGlassCompatibility{
	public enum Effect{
		NAVIGATION, BACKGROUND, PAGE_BLUR
	}

	private static final String TAG="LiquidGlassCompatibility";
	private static final Object LOCK=new Object();
	private static final Handler MAIN=new Handler(Looper.getMainLooper());
	private static final Map<Effect, SessionState> sessions=new EnumMap<>(Effect.class);

	static{
		for(Effect effect:Effect.values())
			sessions.put(effect, new SessionState());
	}

	private static class SessionState{
		final Map<Runnable, Runnable> failureListeners=new LinkedHashMap<>();
		volatile boolean sessionDisabled;
	}

	private LiquidGlassCompatibility(){}

	public static boolean isSystemSupported(){
		return Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU;
	}

	public static boolean evaluate(int sdkInt, boolean sessionDisabled){
		return sdkInt>=Build.VERSION_CODES.TIRAMISU && !sessionDisabled;
	}

	public static boolean shouldWarnAboutPerformance(){
		Context context=MastodonApp.context;
		if(context==null)
			return false;
		ActivityManager manager=context.getSystemService(ActivityManager.class);
		return manager!=null && (manager.isLowRamDevice() || manager.getMemoryClass()<=128);
	}

	public static boolean isSupported(){
		return isSupported(Effect.NAVIGATION);
	}

	public static boolean isSupported(Effect effect){
		return evaluate(Build.VERSION.SDK_INT, sessions.get(effect).sessionDisabled);
	}

	public static void reportFailure(String operation, Throwable error){
		reportFailure(Effect.NAVIGATION, operation, error);
	}

	/** Disable only the affected effect, then notify on the main queue, never during drawing. */
	public static void reportFailure(Effect effect, String operation, Throwable error){
		Objects.requireNonNull(error, "error");
		if(error instanceof Error fatal && !(fatal instanceof LinkageError) && !(fatal instanceof OutOfMemoryError))
			throw fatal;
		synchronized(LOCK){
			SessionState state=sessions.get(effect);
			if(state.sessionDisabled)
				return;
			state.sessionDisabled=true;
			Log.w(TAG, effect+" disabled for this session: "+operation, error);
			for(Runnable notification:state.failureListeners.values())
				MAIN.post(notification);
		}
	}

	public static void addFailureListener(Runnable listener){
		addFailureListener(Effect.NAVIGATION, listener);
	}

	public static void addFailureListener(Effect effect, Runnable listener){
		Objects.requireNonNull(listener, "listener");
		synchronized(LOCK){
			SessionState state=sessions.get(effect);
			if(state.failureListeners.containsKey(listener))
				return;
			Runnable notification=new Runnable(){
				@Override
				public void run(){
					synchronized(LOCK){
						if(state.failureListeners.get(listener)!=this)
							return;
					}
					listener.run();
				}
			};
			state.failureListeners.put(listener, notification);
			if(state.sessionDisabled)
				MAIN.post(notification);
		}
	}

	public static void removeFailureListener(Runnable listener){
		removeFailureListener(Effect.NAVIGATION, listener);
	}

	public static void removeFailureListener(Effect effect, Runnable listener){
		synchronized(LOCK){
			Runnable notification=sessions.get(effect).failureListeners.remove(listener);
			if(notification!=null)
				MAIN.removeCallbacks(notification);
		}
	}

	// Package-private, for isolated compatibility tests only; never used by production callers.
	static void resetForTests(){
		synchronized(LOCK){
			for(SessionState state:sessions.values()){
				for(Runnable notification:state.failureListeners.values())
					MAIN.removeCallbacks(notification);
				state.failureListeners.clear();
				state.sessionDisabled=false;
			}
		}
	}
}
