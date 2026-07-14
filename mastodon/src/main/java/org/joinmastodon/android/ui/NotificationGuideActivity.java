package org.joinmastodon.android.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.joinmastodon.android.ui.utils.OemUtils;

public class NotificationGuideActivity extends Activity {
	private WebView webView;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		getWindow().setStatusBarColor(0x00000000);
		getWindow().setNavigationBarColor(0x00000000);

		webView = new WebView(this);
		webView.setBackgroundColor(0x00000000);
		WebSettings settings = webView.getSettings();
		settings.setJavaScriptEnabled(true);
		settings.setDomStorageEnabled(true);
		webView.setWebViewClient(new WebViewClient());
		webView.addJavascriptInterface(new JsBridge(), "Android");
		webView.loadUrl("file:///android_asset/notification_guide.html");
		setContentView(webView);
	}

	@Override
	public void onBackPressed() {
		if (webView != null && webView.canGoBack()) {
			webView.goBack();
		} else {
			super.onBackPressed();
		}
	}

	private class JsBridge {
		@JavascriptInterface
		public String getVendor() {
			return OemUtils.detectVendor().name();
		}

		@JavascriptInterface
		public void openSettings(String type) {
			Intent intent;
			switch (type) {
				case "autostart":
					intent = OemUtils.getAutostartIntent(NotificationGuideActivity.this);
					break;
				case "battery":
					intent = OemUtils.getBatteryIntent(NotificationGuideActivity.this);
					break;
				default:
					intent = OemUtils.getAppSettingsIntent(NotificationGuideActivity.this);
					break;
			}
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			try {
				startActivity(intent);
			} catch (Exception e) {
				startActivity(OemUtils.getAppSettingsIntent(NotificationGuideActivity.this));
			}
		}

		@JavascriptInterface
		public boolean isAutoStartGranted() {
			return OemUtils.isAutoStartGranted();
		}

		@JavascriptInterface
		public void finish() {
			runOnUiThread(() -> {
				setResult(RESULT_OK);
				finish();
			});
		}
	}
}
