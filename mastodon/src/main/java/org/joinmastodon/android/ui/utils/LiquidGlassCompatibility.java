package org.joinmastodon.android.ui.utils;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.joinmastodon.android.MastodonApp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Process-local compatibility gate. Attached-view hardware checks belong to the UI owner. */
public final class LiquidGlassCompatibility{
	private static final String TAG="LiquidGlassCompatibility";
	private static final Object LOCK=new Object();
	private static final Handler MAIN=new Handler(Looper.getMainLooper());
	private static final Map<Runnable, Runnable> failureListeners=new LinkedHashMap<>();
	private static volatile boolean sessionDisabled;

	private LiquidGlassCompatibility(){}

	public static boolean evaluate(int sdkInt, boolean lowRam, boolean sessionDisabled){
		return sdkInt>=Build.VERSION_CODES.TIRAMISU && !lowRam && !sessionDisabled;
	}

	public static boolean isSupported(){
		if(Build.VERSION.SDK_INT<Build.VERSION_CODES.TIRAMISU || sessionDisabled)
			return false;
		Context context=MastodonApp.context;
		if(context==null)
			return false;
		ActivityManager manager=context.getSystemService(ActivityManager.class);
		return manager!=null && evaluate(Build.VERSION.SDK_INT, manager.isLowRamDevice(), sessionDisabled);
	}

	/** Disable immediately, but notify on the main queue, never inside a drawing callback. */
	public static void reportFailure(String operation, Throwable error){
		Objects.requireNonNull(error, "error");
		// Only these recoverable graphics errors may be converted into a classic-UI fallback.
		if(error instanceof Error fatal && !(fatal instanceof LinkageError) && !(fatal instanceof OutOfMemoryError))
			throw fatal;
		synchronized(LOCK){
			if(sessionDisabled)
				return;
			sessionDisabled=true;
			Log.w(TAG, "Liquid glass disabled for this session: "+operation, error);
			for(Runnable notification:failureListeners.values())
				MAIN.post(notification);
		}
	}

	public static void addFailureListener(Runnable listener){
		Objects.requireNonNull(listener, "listener");
		synchronized(LOCK){
			if(failureListeners.containsKey(listener))
				return;
			Runnable notification=new Runnable(){
				@Override
				public void run(){
					synchronized(LOCK){
						if(failureListeners.get(listener)!=this)
							return;
					}
					listener.run();
				}
			};
			failureListeners.put(listener, notification);
			if(sessionDisabled)
				MAIN.post(notification);
		}
	}

	public static void removeFailureListener(Runnable listener){
		synchronized(LOCK){
			Runnable notification=failureListeners.remove(listener);
			if(notification!=null)
				MAIN.removeCallbacks(notification);
		}
	}

	// Package-private, for isolated compatibility tests only; never used by production callers.
	static void resetForTests(){
		synchronized(LOCK){
			for(Runnable notification:failureListeners.values())
				MAIN.removeCallbacks(notification);
			failureListeners.clear();
			sessionDisabled=false;
		}
	}
}
