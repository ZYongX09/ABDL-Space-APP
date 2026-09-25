package org.joinmastodon.android.updater;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.joinmastodon.android.BuildConfig;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.utils.BroadcastCompat;

public abstract class GithubSelfUpdater{
	private static GithubSelfUpdater instance;
	private boolean downloadReceiverRegistered;
	public static boolean forceUpdate;

	public static GithubSelfUpdater getInstance(){
		if(instance==null){
			try{
				Class<?> c=Class.forName("org.joinmastodon.android.updater.GithubSelfUpdaterImpl");
				instance=(GithubSelfUpdater) c.newInstance();
			}catch(IllegalAccessException|InstantiationException|ClassNotFoundException ignored){
			}
		}
		return instance;
	}

	public static boolean isSupported(){
		return BuildConfig.BUILD_TYPE.equals("release");
	}

	protected final void registerDownloadCompletionReceiver(BroadcastReceiver receiver){
		if(downloadReceiverRegistered) return;
		BroadcastCompat.registerSystemReceiver(MastodonApp.context, receiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
		downloadReceiverRegistered=true;
	}

	protected final void unregisterDownloadCompletionReceiver(BroadcastReceiver receiver){
		if(!downloadReceiverRegistered) return;
		try{ MastodonApp.context.unregisterReceiver(receiver); }catch(IllegalArgumentException ignored){}
		downloadReceiverRegistered=false;
	}

	protected static boolean hasAllowedHttpsHost(String value, Set<String> allowedHosts){
		try{
			URI uri=URI.create(value);
			if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo()!=null || uri.getPort()!=-1 || uri.getFragment()!=null) return false;
			String host=uri.getHost();
			if(host==null || host.isBlank()) return false;
			host=host.toLowerCase(Locale.ROOT);
			return !host.equals("localhost") && !host.endsWith(".localhost") && (allowedHosts==null || allowedHosts.contains(host));
		}catch(Exception ignored){
			return false;
		}
	}

	protected static boolean isSafeDownloadUrl(String value, Set<String> allowedHosts){
		if(!hasAllowedHttpsHost(value, allowedHosts)) return false;
		try{
			String host=URI.create(value).getHost();
			for(InetAddress address:InetAddress.getAllByName(host)) if(!isPublicAddress(address)) return false;
			return true;
		}catch(Exception ignored){
			return false;
		}
	}

	private static boolean isPublicAddress(InetAddress address){
		if(address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
				|| address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
		byte[] bytes=address.getAddress();
		if(address instanceof Inet4Address){
			int first=bytes[0]&255, second=bytes[1]&255;
			return first!=0 && first!=10 && first!=127 && !(first==100 && second>=64 && second<=127)
					&& !(first==169 && second==254) && !(first==172 && second>=16 && second<=31)
					&& !(first==192 && (second==0 || second==168)) && !(first==198 && (second==18 || second==19))
					&& !(first==192 && second==0 && (bytes[2]&255)==2)
					&& !(first==198 && second==51 && (bytes[2]&255)==100)
					&& !(first==203 && second==0 && (bytes[2]&255)==113)
					&& first<224;
		}
		if(address instanceof Inet6Address){
			int first=bytes[0]&255, second=bytes[1]&255;
			return (first&0xfe)!=0xfc && !(first==0xfe && (second&0xc0)==0x80) && !address.isLoopbackAddress();
		}
		return false;
	}

	protected static boolean ensureInstallPermission(Activity activity){
		if(Build.VERSION.SDK_INT<Build.VERSION_CODES.O || activity.getPackageManager().canRequestPackageInstalls()) return true;
		Toast.makeText(activity, R.string.update_allow_unknown_apps, Toast.LENGTH_LONG).show();
		try{
			activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:"+activity.getPackageName())));
		}catch(android.content.ActivityNotFoundException ignored){
			activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
		}
		return false;
	}

	public abstract void maybeCheckForUpdates();

	public abstract GithubSelfUpdater.UpdateState getState();

	public abstract GithubSelfUpdater.UpdateInfo getUpdateInfo();

	public abstract void downloadUpdate();

	public abstract void installUpdate(Activity activity);

	public abstract float getDownloadProgress();

	public abstract void cancelDownload();

	public abstract void handleIntentFromInstaller(Intent intent, Activity activity);

	public abstract void reset();

	public enum UpdateState{
		NO_UPDATE,
		CHECKING,
		UPDATE_AVAILABLE,
		DOWNLOADING,
		DOWNLOADED
	}

	public static class UpdateInfo{
		public String version;
		public long size;
	}
}
