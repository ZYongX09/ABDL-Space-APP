package org.joinmastodon.android.ui.utils;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

import org.joinmastodon.android.MastodonApp;

/** System capability and advisory performance information, independent of runtime failures. */
public final class LiquidGlassCompatibility{
	private LiquidGlassCompatibility(){}

	public static boolean evaluate(int sdkInt){
		return sdkInt>=Build.VERSION_CODES.TIRAMISU;
	}

	public static boolean isSystemSupported(){
		return evaluate(Build.VERSION.SDK_INT);
	}

	public static boolean isSupported(){
		return isSystemSupported();
	}

	public static boolean shouldWarnAboutPerformance(){
		Context context=MastodonApp.context;
		if(context==null)
			return false;
		ActivityManager manager=context.getSystemService(ActivityManager.class);
		return manager!=null && (manager.isLowRamDevice() || manager.getMemoryClass()<=128);
	}
}
