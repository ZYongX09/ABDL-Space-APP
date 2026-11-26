package org.joinmastodon.android.startup;

import android.content.Context;

import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.api.session.ContentAccessCoordinator;
import org.joinmastodon.android.security.AppSecurity;

import java.util.concurrent.atomic.AtomicBoolean;

/** Optional one-shot startup work, after the app-local lock has allowed entry. */
public final class StartupWarmup{
	private static final AtomicBoolean STARTED=new AtomicBoolean();
	private StartupWarmup(){}

	public static void start(Context context){
		if(AppSecurity.isLocked() || !STARTED.compareAndSet(false,true)) return;
		AccountSession session=AccountSessionManager.getInstance().getLastActiveAccount();
		if(session==null) return;
		if(ContentAccessCoordinator.applies(session)) session.getContentAccessCoordinator().refreshIfStale(60_000);
		session.getCacheController().warmHomeTimelineSnapshot();
	}
}
