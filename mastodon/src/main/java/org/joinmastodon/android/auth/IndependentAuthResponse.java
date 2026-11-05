package org.joinmastodon.android.auth;

import android.app.Activity;
import android.app.Fragment;
import android.view.View;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import org.joinmastodon.android.api.ServiceResourcePause;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Token;

import java.io.IOException;
import java.util.Objects;

import okhttp3.Response;
import okhttp3.ResponseBody;

/** Small response/lifecycle adapter for the independent native login transports. */
public final class IndependentAuthResponse{
	private static final int MAX_ERROR_BYTES=32*1024;
	public final String text;
	public final ServiceResourcePause pause;
	private IndependentAuthResponse(String text, ServiceResourcePause pause){ this.text=text; this.pause=pause; }
	/** Consume exactly once and close even when the screen has gone away. Never scan successful bodies. */
	public static IndependentAuthResponse read(Response response) throws IOException{
		try(response){
			ResponseBody body=response.body();
			String text="";
			if(body!=null){
				if(response.isSuccessful()) text=body.string();
				else if(body.contentLength()<=MAX_ERROR_BYTES){
					var source=body.source();
					if(!source.request(MAX_ERROR_BYTES+1L) || source.buffer().size()<=MAX_ERROR_BYTES)
						text=source.readUtf8();
				}
			}
			return new IndependentAuthResponse(text, response.isSuccessful() ? null : ServiceResourcePause.fromResponse(response, text));
		}
	}
	public <T> T parse(Class<T> type){
		try{ return new Gson().fromJson(text, type); }catch(RuntimeException invalid){ return null; }
	}
	/** Only an explicit small JSON error string is displayable, never HTML or the raw response. */
	public String errorMessage(String fallback){
		try{
			JsonElement root=JsonParser.parseString(text);
			if(root.isJsonObject()){
				JsonElement error=root.getAsJsonObject().get("error");
				if(error!=null && error.isJsonPrimitive() && error.getAsJsonPrimitive().isString()){
					String value=error.getAsString();
					if(!value.isBlank() && value.length()<=2048 && value.indexOf('<')<0 && value.indexOf('>')<0) return value;
				}
			}
		}catch(RuntimeException invalid){}
		return fallback;
	}

	/** Request-time view and account identity. A late login error must not interrupt another screen/account. */
	public static final class Owner{
		private final Fragment fragment;
		public final Activity activity;
		private final View view;
		private final String accountID, accessToken;
		private final AccountSession session;
		private final Token token;
		public Owner(Fragment fragment){ this(fragment, fragment.getActivity()); }
		public Owner(Activity activity){ this(null, activity); }
		private Owner(Fragment fragment, Activity activity){
			this.fragment=fragment; this.activity=activity; view=fragment==null ? null : fragment.getView();
			AccountSessionManager manager=AccountSessionManager.getInstance();
			accountID=manager.getLastActiveAccountID(); session=manager.tryGetAccount(accountID);
			token=session==null ? null : session.token; accessToken=token==null ? null : token.accessToken;
		}
		public boolean current(){
			AccountSessionManager manager=AccountSessionManager.getInstance();
			return activity!=null && !activity.isFinishing() && !activity.isDestroyed()
					&& (fragment==null || fragment.isAdded() && fragment.getActivity()==activity && view!=null && fragment.getView()==view)
					&& Objects.equals(accountID, manager.getLastActiveAccountID()) && manager.tryGetAccount(accountID)==session
					&& (session==null || session.token==token && Objects.equals(accessToken, token==null ? null : token.accessToken));
		}
		public boolean foreground(){ return current() && (fragment==null ? activity.hasWindowFocus() : fragment.isResumed() && !fragment.isHidden() && view.isShown()); }
	}
}
