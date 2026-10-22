package org.joinmastodon.android.fragments.settings;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.utils.UiUtils;

public class NBWBindGuideActivity extends Activity {

	@Override
	protected void onCreate(Bundle savedInstanceState){
		UiUtils.setUserPreferredTheme(this);
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_nbw_bind_guide);

		// 状态栏 padding
		View rootView = findViewById(android.R.id.content);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			rootView.setOnApplyWindowInsetsListener((v, insets) -> {
				int statusBar = insets.getInsets(WindowInsets.Type.statusBars()).top;
				rootView.setPadding(0, statusBar, 0, 0);
				return insets;
			});
		} else {
			rootView.setPadding(0, getStatusBarHeight(), 0, 0);
		}

		ImageView backButton = findViewById(R.id.btn_back);
		backButton.setOnClickListener(v -> finish());

		Button authButton = findViewById(R.id.btn_auth);
		authButton.setOnClickListener(v -> {
			String accountID=getIntent().getStringExtra("account");
			var manager=org.joinmastodon.android.api.session.AccountSessionManager.getInstance();
			var session=accountID==null ? manager.getLastActiveAccount() : manager.tryGetAccount(accountID);
			if(session==null){
				android.widget.Toast.makeText(this, R.string.login_expired, android.widget.Toast.LENGTH_LONG).show();
				return;
			}
			long uid;
			try{ uid=Long.parseLong(session.self.id); }catch(NumberFormatException error){ return; }
			String state=NBWPostRegisterActivity.createBindingState(this, session.getID(), uid);
			String url=android.net.Uri.parse("https://api.abdl-space.top/api/auth/nbw/mobile-start")
					.buildUpon().appendQueryParameter("state", state).build().toString();
			UiUtils.launchWebBrowser(this, url);
		});
	}

	private int getStatusBarHeight() {
		int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
		return resourceId > 0 ? getResources().getDimensionPixelSize(resourceId) : 0;
	}
}
