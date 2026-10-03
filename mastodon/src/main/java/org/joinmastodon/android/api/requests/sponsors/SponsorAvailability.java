package org.joinmastodon.android.api.requests.sponsors;

import android.content.Context;
import android.content.SharedPreferences;

import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.requests.instance.GetInstanceV1;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.InstanceV1;
import org.joinmastodon.android.sponsors.SponsorOperation;

import java.util.Locale;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;

public final class SponsorAvailability{
	private SponsorAvailability(){}
	/** Unknown/failure never means unsupported; only a successful instance document can establish it. */
	public static MastodonAPIRequest<?> check(String accountID, Callback<Boolean> callback){
		AccountSession session=AccountSessionManager.getInstance().tryGetAccount(accountID);
		if(session==null){
			callback.onError(new org.joinmastodon.android.api.MastodonErrorResponse("账号会话已失效，请重新登录", 401, null));
			return null;
		}
		SharedPreferences prefs=MastodonApp.context.getSharedPreferences("sponsor_capabilities", Context.MODE_PRIVATE);
		String key=SponsorOperation.hash(accountID);
		String domain=session.domain.toLowerCase(Locale.ROOT);
		boolean known=domain.equals("api.abdl-space.top") || domain.equals("abdl-space.top")
				|| prefs.getBoolean(key+".supported", false)
				|| session.getInstance().map(i->i.capabilities!=null && Boolean.TRUE.equals(i.capabilities.get("sponsors"))).orElse(false);
		if(known){ callback.onSuccess(true); return null; }
		if(prefs.getLong(key+".until", 0)>System.currentTimeMillis()){ callback.onSuccess(false); return null; }
		// This is unauthenticated and same-origin. A fake locally constructed instance is never a negative result.
		return new GetInstanceV1().setCallback(new Callback<>(){
			@Override public void onSuccess(InstanceV1 result){
				boolean supported=result.capabilities!=null && Boolean.TRUE.equals(result.capabilities.get("sponsors"));
				prefs.edit().putBoolean(key+".supported", supported).putLong(key+".until", System.currentTimeMillis()+3600000).apply();
				callback.onSuccess(supported);
			}
			@Override public void onError(ErrorResponse error){ callback.onError(error); }
		}).execNoAuth(session.domain);
	}
}
