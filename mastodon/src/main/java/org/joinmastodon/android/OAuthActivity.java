package org.joinmastodon.android;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import org.joinmastodon.android.api.requests.accounts.GetOwnAccount;
import org.joinmastodon.android.api.requests.oauth.GetOauthToken;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.settings.NBWBindResultActivity;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Application;
import org.joinmastodon.android.model.Instance;
import org.joinmastodon.android.model.InstanceV2;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.ui.utils.UiUtils;

import androidx.annotation.Nullable;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;

public class OAuthActivity extends Activity{
	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState){
		UiUtils.setUserPreferredTheme(this);
		super.onCreate(savedInstanceState);
		Uri uri=getIntent().getData();
		if(uri==null){
			finish();
			return;
		}
		if(uri.getQueryParameter("error")!=null){
			String error=uri.getQueryParameter("error_description");
			if(TextUtils.isEmpty(error))
				error=uri.getQueryParameter("error");
			Toast.makeText(this, error, Toast.LENGTH_LONG).show();
			restartMainActivity();
			finish();
			return;
		}

		// NBW 绑定回调: ?nbw_bind=success/need_bind&nbw_user=xxx
		String nbwBind=uri.getQueryParameter("nbw_bind");
		if(nbwBind!=null){
			if("success".equals(nbwBind)){
				String nbwUser=uri.getQueryParameter("nbw_user");
				Intent intent=new Intent(this, NBWBindResultActivity.class);
				intent.putExtra("nbw_bind_result", nbwBind);
				intent.putExtra("nbw_user", nbwUser);
				AccountSession session=AccountSessionManager.getInstance().getLastActiveAccount();
				if(session!=null)
					intent.putExtra("account", session.getID());
				startActivity(intent);
			}else{
				Toast.makeText(this, "该宝宝新天地账号尚未绑定 ABDL Space 账号，请先在网页端完成绑定", Toast.LENGTH_LONG).show();
			}
			restartMainActivity();
			finish();
			return;
		}

		// NBW 登录: ?token=jwt
		String jwtToken=uri.getQueryParameter("token");
		if(!TextUtils.isEmpty(jwtToken)){
			handleNBWTokenLogin(jwtToken);
			return;
		}

		// 标准 OAuth 登录: ?code=xxx
		String code=uri.getQueryParameter("code");
		if(TextUtils.isEmpty(code)){
			finish();
			return;
		}
		Instance instance=AccountSessionManager.getInstance().getAuthenticatingInstance();
		Application app=AccountSessionManager.getInstance().getAuthenticatingApp();
		final Instance finalInstance;
		final Application finalApp;
		if(instance==null || app==null){
			InstanceV2 inst=new InstanceV2();
			inst.domain="abdl-space.top";
			inst.title="ABDL Space";
			inst.description="ABDL Space";
			inst.version="4.2.0";
			finalInstance=inst;
			finalApp=new Application();
		}else{
			finalInstance=instance;
			finalApp=app;
		}
		ProgressDialog progress=new ProgressDialog(this);
		progress.setMessage(getString(R.string.finishing_auth));
		progress.setCancelable(false);
		progress.show();
		new GetOauthToken(finalApp.clientId, finalApp.clientSecret, code, GetOauthToken.GrantType.AUTHORIZATION_CODE)
			.setCallback(new Callback<>(){
				@Override
				public void onSuccess(Token token){
					new GetOwnAccount()
						.setCallback(new Callback<>(){
							@Override
							public void onSuccess(Account account){
								AccountSessionManager.getInstance().addAccount(instance, token, account, app, null);
								safeDismiss(progress);
								finish();
								Intent intent=new Intent(OAuthActivity.this, MainActivity.class);
								intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
								startActivity(intent);
							}

							@Override
							public void onError(ErrorResponse error){
								handleError(error);
								safeDismiss(progress);
							}
						})
								.exec(finalInstance.getDomain(), token);
				}

				@Override
				public void onError(ErrorResponse error){
					handleError(error);
					safeDismiss(progress);
				}
			})
					.execNoAuth("api." + finalInstance.getDomain());
	}

	private void safeDismiss(ProgressDialog progress){
		try{ progress.dismiss(); } catch(Exception ignored){}
	}

	private void handleNBWTokenLogin(String jwtToken){
		ProgressDialog progress=new ProgressDialog(this);
		progress.setMessage(getString(R.string.finishing_auth));
		progress.setCancelable(false);
		progress.show();

		String domain="abdl-space.top";
		Token token=new Token();
		token.accessToken=jwtToken;

		InstanceV2 instance=new InstanceV2();
		instance.domain=domain;
		instance.title="ABDL Space";
		instance.description="ABDL Space";
		instance.version="4.2.0";

		new GetOwnAccount()
			.setCallback(new Callback<Account>(){
				@Override
				public void onSuccess(Account account){
					safeDismiss(progress);
					Application app=new Application();
					app.clientId="";
					app.clientSecret="";
					AccountSessionManager.getInstance().addAccount(instance, token, account, app, null);
					finish();
					Intent intent=new Intent(OAuthActivity.this, MainActivity.class);
					intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
					startActivity(intent);
				}

				@Override
				public void onError(ErrorResponse error){
					safeDismiss(progress);
					error.showToast(OAuthActivity.this);
					finish();
					restartMainActivity();
				}
			})
			.exec(domain, token);
	}

	private void handleError(ErrorResponse error){
		error.showToast(OAuthActivity.this);
		finish();
		restartMainActivity();
	}

	private void restartMainActivity(){
		Intent intent=new Intent(this, MainActivity.class);
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
		startActivity(intent);
	}
}
