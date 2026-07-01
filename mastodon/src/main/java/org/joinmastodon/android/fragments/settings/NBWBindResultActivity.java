package org.joinmastodon.android.fragments.settings;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.session.AccountSessionManager;

public class NBWBindResultActivity extends Activity {

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
		if("success".equals(nbwBindResult)){
			stateLoading.setVisibility(View.GONE);
			stateSuccess.setVisibility(View.VISIBLE);
			doneButton.setVisibility(View.VISIBLE);
		}else if("need_bind".equals(nbwBindResult)){
			stateLoading.setVisibility(View.GONE);
			Toast.makeText(this, "请先在 ABDL Space 网页端绑定宝宝新天地账户", Toast.LENGTH_LONG).show();
			finish();
		}else{
			stateLoading.setVisibility(View.GONE);
			Toast.makeText(this, "绑定失败，请重试", Toast.LENGTH_SHORT).show();
			finish();
		}
	}
}
