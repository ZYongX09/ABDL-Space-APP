package org.joinmastodon.android.startup;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.joinmastodon.android.api.cache.HomeTimelineSnapshot;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.api.session.ContentAccessCoordinator;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Attachment;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.ui.utils.ContentMediaImageDownloader;

import java.util.concurrent.atomic.AtomicBoolean;

import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.V;

/** Runs inside the 3s brand splash: account/session, access probe, home snapshot and first images. */
public final class StartupWarmup{
	private static final long MAX_WAIT_MS=2800, RETRY_MS=80;
	private static final AtomicBoolean STARTED=new AtomicBoolean(), SNAPSHOT_DONE=new AtomicBoolean();
	private static final Handler MAIN=new Handler(Looper.getMainLooper());
	private StartupWarmup(){}

	public static void start(Context context){
		if(!STARTED.compareAndSet(false,true)) return;
		long deadline=SystemClock.uptimeMillis()+MAX_WAIT_MS;
		waitForSession(deadline);
	}

	private static void waitForSession(long deadline){
		AccountSession session=AccountSessionManager.getInstance().getLastActiveAccount();
		if(session==null){
			if(SystemClock.uptimeMillis()<deadline) MAIN.postDelayed(()->waitForSession(deadline),RETRY_MS);
			return;
		}
		android.util.Log.i("StartupWarmup","session ready "+session.getID());
		if(ContentAccessCoordinator.applies(session)){
			ContentAccessCoordinator coordinator=session.getContentAccessCoordinator();
			coordinator.refresh();
			coordinator.observe(snapshot->{
				if(snapshot!=null && snapshot.isOpen()) loadSnapshot(session);
			});
		}else{
			loadSnapshot(session);
		}
	}

	private static void loadSnapshot(AccountSession session){
		if(!SNAPSHOT_DONE.compareAndSet(false,true)) return;
		var cache=session.getCacheController();
		var lease=cache.beginHomeTimelineSnapshot();
		if(lease==null){ SNAPSHOT_DONE.set(false); return; }
		cache.getHomeTimelineSnapshot(lease, snapshot->{
			if(snapshot==null){ SNAPSHOT_DONE.set(false); return; }
			android.util.Log.i("StartupWarmup","snapshot posts "+snapshot.posts.size());
			prefetch(snapshot);
		});
	}

	private static void prefetch(HomeTimelineSnapshot snapshot){
		int count=0;
		for(Status status:snapshot.posts){
			if(count>=12) break;
			Account account=status.account;
			if(account==null) continue;
			String avatar=account.avatarStatic==null || account.avatarStatic.isBlank() ? account.avatar : account.avatarStatic;
			if(avatar!=null && !avatar.isBlank()){
				ContentMediaImageDownloader.prefetch(new UrlImageLoaderRequest(avatar, V.dp(50), V.dp(50)));
				count++;
			}
			if(status.mediaAttachments!=null){
				for(Attachment attachment:status.mediaAttachments){
					if(count>=12) break;
					if(attachment.previewUrl==null || attachment.previewUrl.isBlank()) continue;
					ContentMediaImageDownloader.prefetch(new UrlImageLoaderRequest(attachment.previewUrl, V.dp(320), V.dp(320)));
					count++;
				}
			}
		}
	}
}
