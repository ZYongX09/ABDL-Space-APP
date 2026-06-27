package org.joinmastodon.android.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.joinmastodon.android.MainActivity;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.session.AccountSessionManager;

/**
 * 启动动画页面：全屏 WebView 播放星空粒子动画。
 * 首次安装显示动画，已登录用户跳过。
 */
public class SplashActivity extends Activity {

    private WebView webView;
    private static final long MAX_DURATION_MS = 8000;
    private static final String PREF_NAME = "splash_prefs";
    private static final String KEY_SHOWN = "splash_shown";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        boolean hasAccount = !AccountSessionManager.getInstance().getLoggedInAccounts().isEmpty();
        boolean splashShown = prefs.getBoolean(KEY_SHOWN, false);
        boolean fromEasterEgg = getIntent() != null && getIntent().getBooleanExtra("from_easter_egg", false);

        // 已登录 + 看过动画 + 不是彩蛋 → 直接进主页
        if (hasAccount && splashShown && !fromEasterEgg) {
            goToMain();
            return;
        }

        // 标记已看过动画
        prefs.edit().putBoolean(KEY_SHOWN, true).apply();

        // 全屏沉浸模式
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );

		// 根容器
		FrameLayout root = new FrameLayout(this);
		root.setBackgroundColor(0xFF0a0a12); // 深蓝黑色背景，匹配 WebView 星空动画

		// 居中显示 ABDL Space logo（WebView 加载前的占位）
		android.widget.ImageView icon = new android.widget.ImageView(this);
		icon.setImageResource(R.drawable.ic_ntf_logo);
		icon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
		FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(120), dp(120));
		iconParams.gravity = Gravity.CENTER;
		root.addView(icon, iconParams);

		// WebView 全屏（覆盖在 icon 上方）
		webView = new WebView(this);
		WebSettings settings = webView.getSettings();
		settings.setJavaScriptEnabled(true);
		settings.setDomStorageEnabled(true);
		settings.setAllowFileAccess(true);
		settings.setAllowContentAccess(true);
		settings.setMediaPlaybackRequiresUserGesture(false);
		settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
		webView.setWebViewClient(new WebViewClient());
		webView.setWebChromeClient(new WebChromeClient());
		webView.setBackgroundColor(0x00000000);
		webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.loadUrl("file:///android_asset/splash.html");
        root.addView(webView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // 右上角跳过按钮
        TextView skipBtn = new TextView(this);
        skipBtn.setText("跳过 >");
        skipBtn.setTextColor(0xCCFFFFFF);
        skipBtn.setTextSize(14f);
        skipBtn.setPadding(dp(20), dp(16), dp(16), dp(16));
        skipBtn.setOnClickListener(v -> goToMain());
        FrameLayout.LayoutParams skipParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        skipParams.gravity = Gravity.TOP | Gravity.END;
        root.addView(skipBtn, skipParams);

        setContentView(root);

        // 超时保护：8 秒后自动进入主页
        root.postDelayed(this::goToMain, MAX_DURATION_MS);
    }

    private void goToMain() {
        if (isFinishing()) return;
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override
    public void onBackPressed() {
        goToMain();
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
