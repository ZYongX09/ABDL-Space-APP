package org.joinmastodon.android.fragments.settings;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.nio.charset.StandardCharsets;

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
			// 记录当前是绑定流程
			getSharedPreferences("nbw_bind", MODE_PRIVATE).edit().putString("flow", "bind").apply();
			String state = Base64.encodeToString(
					("{\"ts\":" + System.currentTimeMillis() + "}").getBytes(StandardCharsets.UTF_8),
					Base64.NO_WRAP);
			String url = "https://api.abdl-space.top/api/auth/nbw/mobile-start?state=" + state;
			UiUtils.launchWebBrowser(this, url);
		});
	}

	private int getStatusBarHeight() {
		int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
		return resourceId > 0 ? getResources().getDimensionPixelSize(resourceId) : 0;
	}
}
