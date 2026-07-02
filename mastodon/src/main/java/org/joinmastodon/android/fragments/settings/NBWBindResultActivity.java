package org.joinmastodon.android.fragments.settings;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.joinmastodon.android.MainActivity;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class NBWBindResultActivity extends Activity {
	private static final OkHttpClient httpClient = new OkHttpClient();
	private LinearLayout stateLoading;
	private LinearLayout stateSuccess;
	private Button doneButton;
	private TextView nbwUsernameText;
	private String accountID;
	private String nbwUser;

	@Override
	protected void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_nbw_bind_result);

		// 状态栏 padding
		View rootView = findViewById(R.id.root);
		rootView.setOnApplyWindowInsetsListener((v, insets) -> {
			int statusBar = insets.getInsets(WindowInsets.Type.statusBars()).top;
			rootView.setPadding(0, statusBar, 0, 0);
			return WindowInsets.CONSUMED;
		});

		stateLoading = findViewById(R.id.state_loading);
		stateSuccess = findViewById(R.id.state_success);
		doneButton = findViewById(R.id.btn_done);
		nbwUsernameText = findViewById(R.id.tv_nbw_username);

		accountID = getIntent().getStringExtra("account");
		nbwUser = getIntent().getStringExtra("nbw_user");

		ImageView btnBack = findViewById(R.id.btn_back);
		btnBack.setOnClickListener(v -> {
			Intent intent = new Intent(this, MainActivity.class);
			intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
			startActivity(intent);
			finish();
		});

		doneButton.setOnClickListener(v -> {
			updateLocalAccount();
			Intent intent = new Intent(this, MainActivity.class);
			intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
			startActivity(intent);
			finish();
		});

		String nbwBindResult = getIntent().getStringExtra("nbw_bind_result");
		String nbwToken = getIntent().getStringExtra("nbw_token");

		if("success".equals(nbwBindResult)){
			stateLoading.setVisibility(View.GONE);
			stateSuccess.setVisibility(View.VISIBLE);
			doneButton.setVisibility(View.VISIBLE);
			if(nbwUser != null) nbwUsernameText.setText("@" + nbwUser);
		}else if("need_bind".equals(nbwBindResult) && nbwToken != null && !nbwToken.isEmpty()){
			executeBind(nbwToken);
		}else{
			stateLoading.setVisibility(View.GONE);
			Toast.makeText(this, "绑定失败，请重试", Toast.LENGTH_SHORT).show();
			finish();
		}
	}

	private void executeBind(String nbwToken){
		stateLoading.setVisibility(View.VISIBLE);
		stateSuccess.setVisibility(View.GONE);
		doneButton.setVisibility(View.GONE);

		AccountSession session = AccountSessionManager.getInstance().getAccount(accountID);
		String token = session != null ? session.token.accessToken : "";

		String json = new Gson().toJson(new BindBody(nbwToken));
		RequestBody body = RequestBody.create(MediaType.parse("application/json"), json);

		httpClient.newCall(new Request.Builder()
				.url("https://api.abdl-space.top/api/auth/nbw/bind")
				.addHeader("Authorization", "Bearer " + token)
				.post(body)
				.build())
			.enqueue(new okhttp3.Callback(){
				@Override
				public void onFailure(Call call, IOException e){
					runOnUiThread(() -> {
						stateLoading.setVisibility(View.GONE);
						Toast.makeText(NBWBindResultActivity.this, "网络错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
						finish();
					});
				}

				@Override
				public void onResponse(Call call, Response response) throws IOException{
					String responseBody = response.body() != null ? response.body().string() : "";
					runOnUiThread(() -> {
						if(response.isSuccessful()){
							stateLoading.setVisibility(View.GONE);
							stateSuccess.setVisibility(View.VISIBLE);
							doneButton.setVisibility(View.VISIBLE);

							try{
								JsonObject jsonResp = new Gson().fromJson(responseBody, JsonObject.class);
								String respUsername = jsonResp.has("nbw_username") ? jsonResp.get("nbw_username").getAsString() : null;
								if(respUsername != null) nbwUser = respUsername;
							}catch(Exception ignored){}

							if(nbwUser != null) nbwUsernameText.setText("@" + nbwUser);

							updateLocalAccount();
						}else{
							stateLoading.setVisibility(View.GONE);
							Toast.makeText(NBWBindResultActivity.this, "绑定失败: " + responseBody, Toast.LENGTH_SHORT).show();
							finish();
						}
					});
				}
			});
	}

	private void updateLocalAccount(){
		if(accountID == null) return;
		AccountSession session = AccountSessionManager.getInstance().getAccount(accountID);
		if(session == null || session.self == null) return;

		session.self.nbwUsername = nbwUser;
		AccountSessionManager.getInstance().updateAccountInfo(accountID, session.self);
	}

	private static class BindBody {
		String access_token;
		BindBody(String token){ this.access_token = token; }
	}
}
