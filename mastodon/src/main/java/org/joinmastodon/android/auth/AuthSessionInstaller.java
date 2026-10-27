package org.joinmastodon.android.auth;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import org.joinmastodon.android.MainActivity;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.requests.accounts.GetOwnAccount;

import java.util.function.BooleanSupplier;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Application;
import org.joinmastodon.android.model.InstanceV2;
import org.joinmastodon.android.model.Token;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;

public final class AuthSessionInstaller{
	private static final String DOMAIN="abdl-space.top";

	public interface Listener{
		void onInstalled(String accountID);
		void onError(ErrorResponse error);
	}

	private AuthSessionInstaller(){}

	public static void install(Activity activity, String accessToken, boolean openMainActivity, Listener listener){
		install(activity, accessToken, openMainActivity, listener, null);
	}

	/** Guarded login checks ownership at delivery, not merely before GetOwnAccount is sent. */
	public static MastodonAPIRequest<Account> install(Activity activity, String accessToken, boolean openMainActivity, Listener listener, BooleanSupplier isCurrent){
		if(activity==null || TextUtils.isEmpty(accessToken)){
			if(listener!=null) listener.onError(new InstallationErrorResponse());
			return null;
		}
		if(isCurrent!=null && (!isCurrent.getAsBoolean() || activity.isFinishing() || activity.isDestroyed())) return null;
		Token token=new Token();
		token.accessToken=accessToken;
		return new GetOwnAccount(){
			@Override public boolean isSensitiveRequest(){ return isCurrent!=null || super.isSensitiveRequest(); }
			@Override public boolean requiresSystemTrust(){ return isCurrent!=null || super.requiresSystemTrust(); }
		}
					.setCallback(new Callback<>(){
						@Override
						public void onSuccess(Account account){
							if(isCurrent!=null && (!isCurrent.getAsBoolean() || activity.isFinishing() || activity.isDestroyed())) return;
							if(account==null || TextUtils.isEmpty(account.id)){
								if(listener!=null) listener.onError(new InstallationErrorResponse());
								return;
							}
							normalizeAccountUrls(account);
							InstanceV2 instance=new InstanceV2();
							instance.domain=DOMAIN;
							instance.title="ABDL Space";
							instance.description="ABDL Space";
							instance.version="4.2.0";
							Application app=new Application();
							app.clientId="";
							app.clientSecret="";
							AccountSessionManager manager=AccountSessionManager.getInstance();
							if(isCurrent!=null && (!isCurrent.getAsBoolean() || activity.isFinishing() || activity.isDestroyed())) return;
							manager.addAccount(instance, token, account, app, null);
							String accountID=instance.getDomain()+"_"+account.id;
							AccountSession installedSession=manager.tryGetAccount(accountID);
							if(installedSession==null){
								if(listener!=null) listener.onError(new InstallationErrorResponse());
								return;
							}
							if(openMainActivity && !activity.isFinishing() && !activity.isDestroyed()){
								Intent intent=new Intent(activity, MainActivity.class);
								intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
								activity.startActivity(intent);
							}
							if(listener!=null) listener.onInstalled(installedSession.getID());
						}

						@Override
						public void onError(ErrorResponse error){
							if(isCurrent!=null && (!isCurrent.getAsBoolean() || activity.isFinishing() || activity.isDestroyed())) return;
							if(listener!=null) listener.onError(error);
						}
					})
					.exec(DOMAIN, token);
	}


	private static final class InstallationErrorResponse extends ErrorResponse{
		@Override
		public void bindErrorView(View view){
			if(view instanceof TextView textView) textView.setText(R.string.qq_login_failed);
		}

		@Override
		public void showToast(Context context){
			Toast.makeText(context, R.string.qq_login_failed, Toast.LENGTH_LONG).show();
		}
	}

	private static void normalizeAccountUrls(Account account){
		if(account.avatar==null) account.avatar="";
		if(account.avatarStatic==null) account.avatarStatic="";
		if(account.header==null) account.header="";
		if(account.headerStatic==null) account.headerStatic="";
		if(account.url==null) account.url="";
	}
}
