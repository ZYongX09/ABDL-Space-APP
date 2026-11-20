package org.joinmastodon.android.ui.verification;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Window;

import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.util.Map;
import java.util.WeakHashMap;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/** The verification flow is a full-screen surface: content draws behind the status bar while the
 * page's own top bar keeps a status-bar inset. Stacked verification pages share one enter/exit count. */
public final class VerificationWindowMode{
	private static final Map<Activity,Integer> depths=new WeakHashMap<>();
	private VerificationWindowMode(){}
	public static void enter(Activity activity){
		if(activity==null) return;
		Integer depth=depths.get(activity);
		if(depth==null){
			WindowCompat.setDecorFitsSystemWindows(activity.getWindow(),false);
			applySurface(activity);
			depths.put(activity,1);
		}else{
			depths.put(activity,depth+1);
		}
	}
	/** Re-apply after AppKit/FragmentStackActivity resets system bar colours on navigation. */
	public static void reapply(Activity activity){
		if(activity==null || !depths.containsKey(activity)) return;
		WindowCompat.setDecorFitsSystemWindows(activity.getWindow(),false);
		applySurface(activity);
	}
	public static void exit(Activity activity){
		if(activity==null) return;
		Integer depth=depths.get(activity);
		if(depth==null) return;
		if(depth>1){ depths.put(activity,depth-1); return; }
		depths.remove(activity);
		Window window=activity.getWindow();
		WindowCompat.setDecorFitsSystemWindows(window,true);
		int background=UiUtils.getThemeColor(activity,R.attr.colorM3Background);
		window.setStatusBarColor(background);
		window.setNavigationBarColor(background);
		window.setBackgroundDrawable(new ColorDrawable(background));
		WindowInsetsControllerCompat controller=WindowCompat.getInsetsController(window,window.getDecorView());
		boolean night=UiUtils.isDarkTheme();
		controller.setAppearanceLightStatusBars(!night);
		controller.setAppearanceLightNavigationBars(!night);
	}
	private static void applySurface(Activity activity){
		Window window=activity.getWindow();
		int page=UiUtils.getThemeColor(activity,R.attr.vrPage);
		window.setStatusBarColor(Color.TRANSPARENT);
		window.setNavigationBarColor(page);
		// Even if a parent view is still inset, the strip behind the status bar matches the page.
		window.setBackgroundDrawable(new ColorDrawable(page));
		WindowInsetsControllerCompat controller=WindowCompat.getInsetsController(window,window.getDecorView());
		boolean night=UiUtils.isDarkTheme();
		controller.setAppearanceLightStatusBars(!night);
		controller.setAppearanceLightNavigationBars(!night);
	}
}
