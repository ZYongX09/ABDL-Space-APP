package org.joinmastodon.android.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.joinmastodon.android.MainActivity;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.session.AccountSessionManager;

/**
 * 品牌启动页 — 居中 LOGO + 渐入动画 + 品牌名
 * 已登录用户直接跳过
 */
public class SplashActivity extends android.app.Activity {

    private static final long SPLASH_DURATION = 2000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 已登录 → 直接进主页
        if (!AccountSessionManager.getInstance().getLoggedInAccounts().isEmpty()) {
            goToMain();
            return;
        }

        // 全屏沉浸
        getWindow().setFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );

        // 根容器 — 与登录页一致的背景色
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(isNightMode() ? 0xFF06080F : 0xFFF4F7FF);

        // 居中 LOGO
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_abdl_icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int iconSize = dp(120);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(iconSize, iconSize);
        iconParams.gravity = Gravity.CENTER;
        iconParams.topMargin = dp(-40);
        icon.setAlpha(0f);
        root.addView(icon, iconParams);

        // 品牌名
        TextView brandText = new TextView(this);
        brandText.setText("ABDL Space");
        brandText.setTextSize(22);
        brandText.setTextColor(isNightMode() ? 0xFFA1D9F7 : 0xFFA1D9F7);
        brandText.setAlpha(0f);
        FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        textParams.gravity = Gravity.CENTER;
        textParams.topMargin = dp(40);
        root.addView(brandText, textParams);

        setContentView(root);

        // 淡入动画
        icon.animate().alpha(1f).setDuration(800).setInterpolator(new AccelerateDecelerateInterpolator()).start();
        brandText.animate().alpha(1f).setStartDelay(300).setDuration(600).setInterpolator(new AccelerateDecelerateInterpolator()).start();

        // 延迟后进入主页
        root.postDelayed(this::goToMain, SPLASH_DURATION);
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

    private boolean isNightMode() {
        return (getResources().getConfiguration().uiMode
            & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
