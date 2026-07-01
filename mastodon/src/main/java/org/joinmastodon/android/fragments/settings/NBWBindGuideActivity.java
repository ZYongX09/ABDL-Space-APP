package org.joinmastodon.android.fragments.settings;

import android.app.Activity;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
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

		ImageView backButton = findViewById(R.id.btn_back);
		backButton.setOnClickListener(v -> finish());

		Button authButton = findViewById(R.id.btn_auth);
		authButton.setOnClickListener(v -> {
			String state = Base64.encodeToString(
					("{\"ts\":" + System.currentTimeMillis() + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8),
					Base64.NO_WRAP);
			String url = "https://api.abdl-space.top/api/auth/nbw/mobile-start?state=" + state;
			UiUtils.launchWebBrowser(this, url);
		});
	}
}
