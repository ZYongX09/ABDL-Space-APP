package org.joinmastodon.android.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
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
		getWindow().setStatusBarColor(Color.TRANSPARENT);
		getWindow().setNavigationBarColor(Color.TRANSPARENT);
		getWindow().getDecorView().setSystemUiVisibility(
				View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

		webView = new WebView(this);
		webView.setBackgroundColor(Color.TRANSPARENT);
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
		setResult(RESULT_CANCELED);
		super.onBackPressed();
	}

	private class JsBridge {
		@JavascriptInterface
		public String getVendor() {
			return OemUtils.detectVendor().name();
		}

		@JavascriptInterface
		public String getVendorDisplayName() {
			return OemUtils.detectVendor().displayName;
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
				NotificationGuideActivity.this.finish();
			});
		}

		@JavascriptInterface
		public void goBack() {
			runOnUiThread(() -> {
				setResult(RESULT_CANCELED);
				NotificationGuideActivity.this.finish();
			});
		}

		@JavascriptInterface
		public int getStatusBarHeight() {
			int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
			return resId > 0 ? getResources().getDimensionPixelSize(resId) : 0;
		}

		@JavascriptInterface
		public int getNavigationBarHeight() {
			int resId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
			return resId > 0 ? getResources().getDimensionPixelSize(resId) : 0;
		}
	}
}
