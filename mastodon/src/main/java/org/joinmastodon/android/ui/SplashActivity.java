package org.joinmastodon.android.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import org.joinmastodon.android.MainActivity;
import org.joinmastodon.android.api.session.AccountSessionManager;

/**
 * 技术过渡页 — 纯背景色匹配，无延时，无动画。
 * 系统 SplashScreen (Android 12+) 负责展示图标，
 * 此 Activity 仅做判断：已登录→主页，未登录→登录页。
 */
public class SplashActivity extends android.app.Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Android 12+ 系统 SplashScreen 退出过渡
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                getSplashScreen().setOnExitAnimationListener(splashScreenView -> splashScreenView.remove());
            } catch (Exception ignored) {}
        }

        if (!AccountSessionManager.getInstance().getLoggedInAccounts().isEmpty()) {
            startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        } else {
            startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        }
        overridePendingTransition(0, 0);
        finish();
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
