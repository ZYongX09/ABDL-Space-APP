package org.joinmastodon.android.fragments.settings;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.gson.Gson;

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
	private String accountID;

	@Override
	protected void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_nbw_bind_result);

		stateLoading = findViewById(R.id.state_loading);
		stateSuccess = findViewById(R.id.state_success);
		doneButton = findViewById(R.id.btn_done);

		accountID = getIntent().getStringExtra("account");

		doneButton.setOnClickListener(v -> {
			if(accountID != null){
				AccountSessionManager.get(accountID).updateAccountInfo();
			}
			finish();
		});

		String nbwBindResult = getIntent().getStringExtra("nbw_bind_result");
		String nbwToken = getIntent().getStringExtra("nbw_token");

		if("success".equals(nbwBindResult)){
			// 已经绑定成功
			stateLoading.setVisibility(View.GONE);
			stateSuccess.setVisibility(View.VISIBLE);
			doneButton.setVisibility(View.VISIBLE);
		}else if("need_bind".equals(nbwBindResult) && nbwToken != null && !nbwToken.isEmpty()){
			// 需要绑定 — 调用 bind API
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
						}else{
							stateLoading.setVisibility(View.GONE);
							Toast.makeText(NBWBindResultActivity.this, "绑定失败: " + responseBody, Toast.LENGTH_SHORT).show();
							finish();
						}
					});
				}
			});
	}

	private static class BindBody {
		String code;
		BindBody(String code){ this.code = code; }
	}
}
