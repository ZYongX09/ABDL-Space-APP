package org.joinmastodon.android.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;

import org.joinmastodon.android.MainActivity;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.nsfw.SplashRenderer;

import java.io.File;

/**
 * 技术过渡页 — 检查缓存图标，有则显示，无则后台渲染。
 */
public class SplashActivity extends android.app.Activity {

    private ImageView splashIcon;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        splashIcon = findViewById(R.id.splash_icon);

        // Android 12+ 系统 SplashScreen 退出过渡
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                getSplashScreen().setOnExitAnimationListener(splashScreenView -> splashScreenView.remove());
            } catch (Exception ignored) {}
        }

        // 检查缓存
        File cached = SplashRenderer.getCachedFile(this);
        if (cached.exists() && cached.length() > 0) {
            // 有缓存：显示自定义图标
            showCachedIcon(cached);
        } else {
            // 无缓存：后台渲染 SVG
            SplashRenderer.renderInBackground(this, file -> showCachedIcon(file));
            // 延迟跳转（给渲染一些时间）
            handler.postDelayed(this::navigate, 500);
        }
    }

    private void showCachedIcon(File file) {
        try {
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (bitmap != null) {
                splashIcon.setImageBitmap(bitmap);
                splashIcon.setVisibility(View.VISIBLE);
                // 显示 300ms 后跳转
                handler.postDelayed(this::navigate, 300);
            } else {
                navigate();
            }
        } catch (Exception e) {
            navigate();
        }
    }

    private void navigate() {
        if (isFinishing()) return;
        startActivity(new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        overridePendingTransition(0, 0);
        finish();
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
