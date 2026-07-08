package org.joinmastodon.android.fragments.settings;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import org.joinmastodon.android.MainActivity;
import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.nio.charset.StandardCharsets;

/**
 * 注册后 NBW 绑定引导页（三选项）
 * - "绑定已有宝宝新天地账号" → OAuth
 * - "一键注册宝宝新天地新账号" → NBWOneClickRegisterActivity
 * - "暂时跳过" → MainActivity
 */
public class NBWPostRegisterActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        UiUtils.setUserPreferredTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nbw_post_register);

        // 深色模式检测
        org.joinmastodon.android.ui.views.SpaceBackgroundView spaceBg = findViewById(R.id.space_bg);
        boolean isDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        spaceBg.setDarkMode(isDark);

        // 标题颜色
        TextView titleView = findViewById(R.id.title);
        if (titleView != null) titleView.setTextColor(Color.WHITE);

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

        // 返回按钮
        ImageView btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> {
            navigateToMain();
        });

        // 绑定已有账号
        Button btnBindExisting = findViewById(R.id.btn_bind_existing);
        btnBindExisting.setOnClickListener(v -> {
            getSharedPreferences("nbw_bind", MODE_PRIVATE).edit().putString("flow", "bind").apply();
            String state = Base64.encodeToString(
                ("{\"ts\":" + System.currentTimeMillis() + "}").getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP);
            String url = "https://api.abdl-space.top/api/auth/nbw/mobile-start?state=" + state;
            UiUtils.launchWebBrowser(this, url);
        });

        // 一键注册
        Button btnOneClick = findViewById(R.id.btn_one_click);
        btnOneClick.setOnClickListener(v -> {
            Intent intent = new Intent(this, NBWOneClickRegisterActivity.class);
            startActivity(intent);
        });

        // 暂时跳过
        Button btnSkip = findViewById(R.id.btn_skip);
        btnSkip.setOnClickListener(v -> {
            navigateToMain();
        });
    }

    private void navigateToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    private int getStatusBarHeight() {
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return resourceId > 0 ? getResources().getDimensionPixelSize(resourceId) : 0;
    }
}
