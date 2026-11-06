package org.joinmastodon.android.sponsors;

import android.app.Application;

import com.alipay.sdk.app.EnvUtils;

import org.joinmastodon.android.MastodonApp;

import me.grishka.appkit.utils.V;

/** No production Application lifecycle, accounts, push, maps, updater or background work. */
public final class SandboxApplication extends Application{
	private SandboxSession session;
	private SandboxSponsorSource source;

	@Override public void onCreate(){
		super.onCreate();
		// Fixed before any PayTask is constructed; no Intent/preference/runtime environment switch.
		EnvUtils.setEnv(EnvUtils.EnvEnum.SANDBOX);
		// Only the shared native UI's context/density helpers. Never invoke MastodonApp.onCreate().
		MastodonApp.context=getApplicationContext();
		V.setApplicationContext(getApplicationContext());
		session=new SandboxSession(this);
		source=new SandboxSponsorSource(session); // Construction does not send any request.
	}

	SandboxSession session(){ return session; }
	SandboxSponsorSource source(){ return source; }
}
