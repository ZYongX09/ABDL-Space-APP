package org.joinmastodon.android.updater;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.joinmastodon.android.BuildConfig;
import org.joinmastodon.android.E;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.events.SelfUpdateStateChangedEvent;

import java.io.File;

import androidx.annotation.Keep;
import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;

@Keep
public class GithubSelfUpdaterImpl extends GithubSelfUpdater{
	private static final long CHECK_PERIOD=24*3600*1000L;
	private static final String TAG="SelfUpdater";

	private UpdateState state=UpdateState.NO_UPDATE;
	private UpdateInfo info;
	private long downloadID;
	private BroadcastReceiver downloadCompletionReceiver=new BroadcastReceiver(){
		@Override
		public void onReceive(Context context, Intent intent){
			if(downloadID!=0 && intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0)==downloadID){
				MastodonApp.context.unregisterReceiver(this);
				setState(UpdateState.DOWNLOADED);
			}
		}
	};

	public GithubSelfUpdaterImpl(){
		SharedPreferences prefs=getPrefs();
		int checkedByBuild=prefs.getInt("checkedByBuild", 0);
		if(prefs.contains("version") && checkedByBuild==BuildConfig.VERSION_CODE){
			info=new UpdateInfo();
			info.version=prefs.getString("version", null);
			info.size=prefs.getLong("apkSize", 0);
			downloadID=prefs.getLong("downloadID", 0);
			if(downloadID==0 || !getUpdateApkFile().exists()){
				state=UpdateState.UPDATE_AVAILABLE;
			}else{
				DownloadManager dm=MastodonApp.context.getSystemService(DownloadManager.class);
				state=dm.getUriForDownloadedFile(downloadID)==null ? UpdateState.DOWNLOADING : UpdateState.DOWNLOADED;
				if(state==UpdateState.DOWNLOADING){
					MastodonApp.context.registerReceiver(downloadCompletionReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
				}
			}
		}else if(checkedByBuild!=BuildConfig.VERSION_CODE && checkedByBuild>0){
			long id=getPrefs().getLong("downloadID", 0);
			if(id!=0){
				MastodonApp.context.getSystemService(DownloadManager.class).remove(id);
			}
			getUpdateApkFile().delete();
			getPrefs().edit()
					.remove("apkSize").remove("version").remove("apkURL")
					.remove("checkedByBuild").remove("downloadID").apply();
		}
	}

	private SharedPreferences getPrefs(){
		return MastodonApp.context.getSharedPreferences("githubUpdater", Context.MODE_PRIVATE);
	}

	@Override
	public void maybeCheckForUpdates(){
		if(state!=UpdateState.NO_UPDATE && state!=UpdateState.UPDATE_AVAILABLE) return;
		long timeSinceLastCheck=System.currentTimeMillis()-getPrefs().getLong("lastCheck", 0);
		if(timeSinceLastCheck>CHECK_PERIOD || forceUpdate){
			setState(UpdateState.CHECKING);
			MastodonAPIController.runInBackground(this::actuallyCheckForUpdates);
		}
	}

	private void actuallyCheckForUpdates(){
		Request req=new Request.Builder()
				.url("https://api.abdl-space.top/api/v1/version")
				.build();
		Call call=MastodonAPIController.getHttpClient().newCall(req);
		try(Response resp=call.execute()){
			JsonObject obj=JsonParser.parseReader(resp.body().charStream()).getAsJsonObject();
			int remoteCode=obj.has("versionCode") ? obj.get("versionCode").getAsInt() : 0;
			String remoteName=obj.has("versionName") ? obj.get("versionName").getAsString() : "";
			String downloadUrl=obj.has("downloadUrl") ? obj.get("downloadUrl").getAsString() : "";

			if(remoteCode>BuildConfig.VERSION_CODE && downloadUrl.length()>0){
				Log.d(TAG, "New version: v"+remoteName+" (code "+remoteCode+")");
				UpdateInfo updateInfo=new UpdateInfo();
				updateInfo.version=remoteName;
				updateInfo.size=0;
				this.info=updateInfo;
				getPrefs().edit()
						.putString("version", remoteName)
						.putString("apkURL", downloadUrl)
						.putInt("checkedByBuild", BuildConfig.VERSION_CODE)
						.remove("downloadID").apply();
			}
			getPrefs().edit().putLong("lastCheck", System.currentTimeMillis()).apply();
		}catch(Exception x){
			Log.w(TAG, "actuallyCheckForUpdates", x);
		}finally{
			setState(info==null ? UpdateState.NO_UPDATE : UpdateState.UPDATE_AVAILABLE);
		}
	}

	private void setState(UpdateState state){
		this.state=state;
		E.post(new SelfUpdateStateChangedEvent(state));
	}

	@Override public UpdateState getState(){ return state; }
	@Override public UpdateInfo getUpdateInfo(){ return info; }

	public File getUpdateApkFile(){
		return new File(MastodonApp.context.getExternalCacheDir(), "update.apk");
	}

	@Override
	public void downloadUpdate(){
		if(state==UpdateState.DOWNLOADING) throw new IllegalStateException();
		DownloadManager dm=MastodonApp.context.getSystemService(DownloadManager.class);
		if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU){
			MastodonApp.context.registerReceiver(downloadCompletionReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED);
		}else{
			MastodonApp.context.registerReceiver(downloadCompletionReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
		}
		downloadID=dm.enqueue(
				new DownloadManager.Request(Uri.parse(getPrefs().getString("apkURL", null)))
						.setDestinationUri(Uri.fromFile(getUpdateApkFile()))
		);
		getPrefs().edit().putLong("downloadID", downloadID).apply();
		setState(UpdateState.DOWNLOADING);
	}

	@Override
	public void installUpdate(Activity activity){
		if(state!=UpdateState.DOWNLOADED) throw new IllegalStateException();
		Uri uri;
		Intent intent=new Intent(Intent.ACTION_INSTALL_PACKAGE);
		if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.N){
			uri=new Uri.Builder().scheme("content").authority(activity.getPackageName()+".self_update_provider").path("update.apk").build();
			intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		}else{
			uri=Uri.fromFile(getUpdateApkFile());
		}
		intent.setDataAndType(uri, "application/vnd.android.package-archive");
		activity.startActivity(intent);
	}

	@Override
	public float getDownloadProgress(){
		if(state!=UpdateState.DOWNLOADING) throw new IllegalStateException();
		DownloadManager dm=MastodonApp.context.getSystemService(DownloadManager.class);
		try(Cursor cursor=dm.query(new DownloadManager.Query().setFilterById(downloadID))){
			if(cursor.moveToFirst()){
				long loaded=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
				long total=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
				return total>0 ? (float)loaded/total : 0f;
			}
		}
		return 0;
	}

	@Override
	public void cancelDownload(){
		if(state!=UpdateState.DOWNLOADING) throw new IllegalStateException();
		DownloadManager dm=MastodonApp.context.getSystemService(DownloadManager.class);
		dm.remove(downloadID);
		downloadID=0;
		getPrefs().edit().remove("downloadID").apply();
		setState(UpdateState.UPDATE_AVAILABLE);
	}

	@Override
	public void handleIntentFromInstaller(Intent intent, Activity activity){
		int status=intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, 0);
		if(status==android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION){
			Intent confirmIntent=intent.getParcelableExtra(Intent.EXTRA_INTENT);
			activity.startActivity(confirmIntent);
		}else if(status!=android.content.pm.PackageInstaller.STATUS_SUCCESS){
			String msg=intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE);
			android.widget.Toast.makeText(activity, "更新安装失败: "+msg, android.widget.Toast.LENGTH_LONG).show();
		}
	}

	@Override
	public void reset(){
		getPrefs().edit().clear().apply();
		getUpdateApkFile().delete();
		state=UpdateState.NO_UPDATE;
	}
}
