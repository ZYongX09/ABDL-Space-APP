package org.joinmastodon.android.ui.verification;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
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
			Window window=activity.getWindow();
			WindowCompat.setDecorFitsSystemWindows(window,false);
			window.setStatusBarColor(Color.TRANSPARENT);
			applyIconMode(activity);
			depths.put(activity,1);
		}else{
			depths.put(activity,depth+1);
		}
	}
	public static void exit(Activity activity){
		if(activity==null) return;
		Integer depth=depths.get(activity);
		if(depth==null) return;
		if(depth>1){ depths.put(activity,depth-1); return; }
		depths.remove(activity);
		Window window=activity.getWindow();
		WindowCompat.setDecorFitsSystemWindows(window,true);
		window.setStatusBarColor(UiUtils.getThemeColor(activity,R.attr.colorM3Background));
		WindowInsetsControllerCompat controller=WindowCompat.getInsetsController(window,window.getDecorView());
		boolean night=(activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
		controller.setAppearanceLightStatusBars(!night);
	}
	private static void applyIconMode(Activity activity){
		Window window=activity.getWindow();
		WindowInsetsControllerCompat controller=WindowCompat.getInsetsController(window,window.getDecorView());
		boolean night=(activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
		controller.setAppearanceLightStatusBars(!night);
	}
}
