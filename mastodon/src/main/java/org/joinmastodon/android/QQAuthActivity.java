package org.joinmastodon.android;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import com.tencent.tauth.IUiListener;
import com.tencent.tauth.Tencent;
import com.tencent.tauth.UiError;

import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.auth.AuthSessionInstaller;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import androidx.annotation.Nullable;
import me.grishka.appkit.api.ErrorResponse;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class QQAuthActivity extends Activity{
	public static final String EXTRA_MODE="mode";
	public static final String EXTRA_ACCOUNT_ID="accountID";
	public static final String EXTRA_PERMISSION_CONFIRMED="permissionConfirmed";
	public static final String MODE_LOGIN="login";
	public static final String MODE_BIND="bind";
	public static final int RESULT_BINDING_CHANGED=Activity.RESULT_FIRST_USER+190;

	private static final MediaType JSON=MediaType.parse("application/json; charset=utf-8");
	private Tencent tencent;
	private ProgressDialog progress;
	private String mode;
	private String accountID;
	private Call exchangeCall;
	private boolean finished;

	private final IUiListener loginListener=new IUiListener(){
		@Override
		public void onComplete(Object response){
			String oneTimeCode=extractOneTimeCode(response);
			if(TextUtils.isEmpty(oneTimeCode)){
				showMessageAndFinish(R.string.qq_login_invalid_response);
				return;
			}
			exchangeAuthorizationCode(oneTimeCode);
		}

		@Override
		public void onError(UiError error){
			showMessageAndFinish(R.string.qq_login_failed);
		}

		@Override
		public void onCancel(){
			showMessageAndFinish(R.string.qq_login_cancelled);
		}

		@Override
		public void onWarning(int code){
			// The SDK may report a non-fatal warning before delivering the final callback.
		}
	};

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState){
		UiUtilsBridge.applyTheme(this);
		super.onCreate(savedInstanceState);
		mode=getIntent().getStringExtra(EXTRA_MODE);
		accountID=getIntent().getStringExtra(EXTRA_ACCOUNT_ID);
		if(!BuildConfig.QQ_LOGIN_ENABLED || (!MODE_LOGIN.equals(mode) && !MODE_BIND.equals(mode)) || !getIntent().getBooleanExtra(EXTRA_PERMISSION_CONFIRMED, false)){
			finish();
			return;
		}
		if(MODE_BIND.equals(mode) && AccountSessionManager.getInstance().tryGetAccount(accountID)==null){
			showMessageAndFinish(R.string.qq_account_session_missing);
			return;
		}
		Tencent.setIsPermissionGranted(true, Build.MODEL);
		tencent=Tencent.createInstance(BuildConfig.QQ_APP_ID, getApplicationContext(), getPackageName()+".fileprovider");
		if(tencent==null){
			showMessageAndFinish(R.string.qq_login_unavailable);
			return;
		}
		progress=new ProgressDialog(this);
		progress.setMessage(getString(R.string.qq_authorizing));
		progress.setCancelable(false);
		progress.show();
		tencent.loginServerSide(this, "get_user_info", loginListener);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data){
		super.onActivityResult(requestCode, resultCode, data);
		Tencent.onActivityResultData(requestCode, resultCode, data, loginListener);
	}

	private String extractOneTimeCode(Object response){
		JSONObject json=response instanceof JSONObject ? (JSONObject)response : null;
		if(json==null && response!=null){
			try{
				json=new JSONObject(String.valueOf(response));
			}catch(JSONException ignored){}
		}
		if(json==null) return null;
		String code=json.optString("code", null);
		return TextUtils.isEmpty(code) ? json.optString("access_token", null) : code;
	}

	private void exchangeAuthorizationCode(String authorizationCode){
		setProgressMessage(R.string.qq_finishing_auth);
		JSONObject json=new JSONObject();
		try{
			json.put("client_id", BuildConfig.QQ_APP_ID);
			json.put("authorization_code", authorizationCode);
		}catch(JSONException impossible){
			showMessageAndFinish(R.string.qq_login_failed);
			return;
		}
		Request.Builder builder=new Request.Builder()
				.url(MODE_BIND.equals(mode) ? "https://api.abdl-space.top/api/auth/qq/android/bind" : "https://api.abdl-space.top/api/auth/qq/android/exchange")
				.post(RequestBody.create(JSON, json.toString()));
		if(MODE_BIND.equals(mode)){
			AccountSession session=AccountSessionManager.getInstance().tryGetAccount(accountID);
			if(session==null){
				showMessageAndFinish(R.string.qq_account_session_missing);
				return;
			}
			builder.header("Authorization", "Bearer "+session.token.accessToken);
		}
		exchangeCall=MastodonAPIController.getHttpClient().newCall(builder.build());
		exchangeCall.enqueue(new Callback(){
			@Override
			public void onFailure(Call call, IOException error){
				if(call.isCanceled()) return;
				runOnUiThread(()->{
					if(isFinishing() || isDestroyed()) return;
					showMessageAndFinish(R.string.qq_network_error);
				});
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException{
				final int status=response.code();
				final okhttp3.ResponseBody body=response.body();
				final String responseBody=body==null ? "" : body.string();
				response.close();
				runOnUiThread(()->{
					if(isFinishing() || isDestroyed()) return;
					handleExchangeResponse(status, responseBody);
				});
			}
		});
	}

	private void handleExchangeResponse(int httpStatus, String responseBody){
		JSONObject json;
		try{
			json=new JSONObject(responseBody);
		}catch(JSONException error){
			showMessageAndFinish(R.string.qq_login_failed);
			return;
		}
		String action=json.optString("action", null);
		String serverCode=firstNonEmpty(json.optString("code", null), json.optString("error", null), json.optString("error_code", null));
		if("QQ_UNIONID_REQUIRED".equals(serverCode)){
			showMessageAndFinish(R.string.qq_unionid_required);
			return;
		}
		if("not_bound".equals(action) || "QQ_ACCOUNT_NOT_BOUND".equals(serverCode)){
			showMessageAndFinish(R.string.qq_not_bound);
			return;
		}
		if(httpStatus<200 || httpStatus>=300){
			showMessageAndFinish(R.string.qq_login_failed);
			return;
		}
		if(MODE_BIND.equals(mode)){
			dismissProgress();
			Toast.makeText(this, R.string.qq_bind_success, Toast.LENGTH_SHORT).show();
			setResult(RESULT_BINDING_CHANGED);
			finish();
			return;
		}
		String abdlToken=json.optString("token", null);
		if(TextUtils.isEmpty(abdlToken) || abdlToken.equals(json.optString("access_token", null))){
			showMessageAndFinish(R.string.qq_login_failed);
			return;
		}
		AuthSessionInstaller.install(this, abdlToken, true, new AuthSessionInstaller.Listener(){
			@Override
			public void onInstalled(String installedAccountID){
				dismissProgress();
				finish();
			}

			@Override
			public void onError(ErrorResponse error){
				dismissProgress();
				Toast.makeText(QQAuthActivity.this, R.string.qq_login_failed, Toast.LENGTH_LONG).show();
				finish();
			}
		});
	}

	private String firstNonEmpty(String... values){
		for(String value:values){
			if(!TextUtils.isEmpty(value)) return value;
		}
		return null;
	}

	private void setProgressMessage(int stringRes){
		if(progress!=null) progress.setMessage(getString(stringRes));
	}

	private void showMessageAndFinish(int stringRes){
		if(finished) return;
		finished=true;
		dismissProgress();
		Toast.makeText(this, stringRes, Toast.LENGTH_LONG).show();
		setResult(Activity.RESULT_CANCELED);
		finish();
	}

	private void dismissProgress(){
		if(progress!=null){
			try{
				progress.dismiss();
			}catch(Exception ignored){}
			progress=null;
		}
	}

	@Override
	protected void onDestroy(){
		if(exchangeCall!=null) exchangeCall.cancel();
		dismissProgress();
		super.onDestroy();
	}

	private static final class UiUtilsBridge{
		private static void applyTheme(Activity activity){
			org.joinmastodon.android.ui.utils.UiUtils.setUserPreferredTheme(activity);
		}
	}
}
