package org.joinmastodon.android;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.util.Log;
import android.webkit.WebView;

import org.joinmastodon.android.api.PushSubscriptionManager;
import org.joinmastodon.android.ui.utils.UiUtils;

import cn.jpush.android.api.JPushInterface;
import me.grishka.appkit.imageloader.ImageCache;
import me.grishka.appkit.utils.NetworkUtils;
import me.grishka.appkit.utils.V;

public class MastodonApp extends Application{
	private static final String TAG = "MastodonApp";

	@SuppressLint("StaticFieldLeak") // it's not a leak
	public static Context context;

	@Override
	public void onCreate(){
		super.onCreate();
		context=getApplicationContext();
		V.setApplicationContext(context);
		ImageCache.Parameters params=new ImageCache.Parameters();
		params.diskCacheSize=100*1024*1024;
		params.maxMemoryCacheSize=Integer.MAX_VALUE;
		ImageCache.setParams(params);
		NetworkUtils.setUserAgent("MastodonAndroid/"+BuildConfig.VERSION_NAME);
		UiUtils.updateLocalizedDateFormatters(context);

		// 初始化极光推送
		JPushInterface.setDebugMode(BuildConfig.DEBUG);
		JPushInterface.init(this);
		Log.i(TAG, "JPush initialized");

		try{
			PushSubscriptionManager.tryRegisterFCM();
		}catch(Throwable t){
			// 快捷方式发布失败不影响正常功能（SplashActivity 改变了 LAUNCHER 入口）
		}
		GlobalUserPreferences.load();
		if(BuildConfig.DEBUG){
			WebView.setWebContentsDebuggingEnabled(true);
		}

		// 初始化 NSFW 本地检测模型
		try{
			org.joinmastodon.android.nsfw.NsfwDetector.init(this);
		}catch(Throwable t){
			// 模型加载失败不影响正常功能
		}
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig){
		super.onConfigurationChanged(newConfig);
		UiUtils.updateLocalizedDateFormatters(context);
	}
}
