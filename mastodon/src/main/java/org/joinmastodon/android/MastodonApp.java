package org.joinmastodon.android;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.app.ActivityManager;
import android.os.Process;
import android.util.Log;
import android.webkit.WebView;

import org.joinmastodon.android.api.PushSubscriptionManager;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.novel.NovelAccountCleanupWorker;
import org.joinmastodon.android.novel.upload.NovelUploadWorker;
import org.joinmastodon.android.novel.sync.NovelSyncWorker;
import org.joinmastodon.android.ui.utils.UiUtils;

import cn.jiguang.api.utils.JCollectionAuth;
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
		String processName=getCurrentProcessName();
		if(BuildConfig.DEBUG && getPackageName().equals(processName)){
			WebView.setWebContentsDebuggingEnabled(true);
		}
		V.setApplicationContext(context);
		ImageCache.Parameters params=new ImageCache.Parameters();
		params.diskCacheSize=100*1024*1024;
		params.maxMemoryCacheSize=Integer.MAX_VALUE;
		ImageCache.setParams(params);
		NetworkUtils.setUserAgent("MastodonAndroid/"+BuildConfig.VERSION_NAME);
		UiUtils.updateLocalizedDateFormatters(context);

		// 初始化极光推送
		JPushInterface.setDebugMode(BuildConfig.DEBUG);
		JCollectionAuth.setAuth(this, true);
		JPushInterface.init(this);
		// 设置 JPush 通知通道为 HIGH 以显示 heads-up 横幅
		try{
			android.app.NotificationManager nm=getSystemService(android.app.NotificationManager.class);
			if(nm!=null && android.os.Build.VERSION.SDK_INT>=android.os.Build.VERSION_CODES.O){
				// v2 渠道：NotificationChannel 的 importance/声音/震动一经创建不可修改，
				// 旧 jpush_high 在部分设备上被系统初始限制后无法恢复，换新 ID 以重置为 HIGH 默认（横幅+声音+震动+锁屏）
				nm.deleteNotificationChannel("jpush_high");
				android.app.NotificationChannel ch=new android.app.NotificationChannel("jpush_high_v2", "推送通知", android.app.NotificationManager.IMPORTANCE_HIGH);
				ch.setDescription("接收推送通知时显示横幅");
				ch.enableVibration(true);
				ch.enableLights(true);
				ch.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
				nm.createNotificationChannel(ch);
			}
		}catch(Exception e){
			Log.e(TAG, "Failed to create JPush HIGH channel", e);
		}
		Log.i(TAG, "JPush initialized");

		try{
			PushSubscriptionManager.tryRegisterFCM();
		}catch(Throwable t){
			// 快捷方式发布失败不影响正常功能（SplashActivity 改变了 LAUNCHER 入口）
		}
		GlobalUserPreferences.load();
		if(isMainProcess(getPackageName(), processName)){
			NovelAccountCleanupWorker.enqueuePending(context);
			for(AccountSession session:AccountSessionManager.getInstance().getLoggedInAccounts()){
				NovelUploadWorker.enqueuePending(context, session.getID());
				NovelSyncWorker.enqueue(context, session.getID());
			}
		}
	}

	static boolean isMainProcess(String packageName, String processName){
		return packageName.equals(processName);
	}

	private String getCurrentProcessName(){
		ActivityManager manager=getSystemService(ActivityManager.class);
		if(manager==null)
			return null;
		for(ActivityManager.RunningAppProcessInfo process:manager.getRunningAppProcesses()){
			if(process.pid==Process.myPid())
				return process.processName;
		}
		return null;
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig){
		super.onConfigurationChanged(newConfig);
		UiUtils.updateLocalizedDateFormatters(context);
	}
}
