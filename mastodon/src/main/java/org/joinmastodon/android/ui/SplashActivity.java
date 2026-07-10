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
 * 启动页 — 显示 SVG 缓存图标，无缓存则等待渲染完成。
 */
public class SplashActivity extends android.app.Activity {

    private ImageView splashIcon;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean navigated = false;

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
            showCachedIcon(cached);
        } else {
            // 无缓存：后台渲染 SVG，完成后显示并跳转
            SplashRenderer.renderInBackground(this, file -> {
                if (!navigated) showCachedIcon(file);
            });
            // 超时保护：5秒后如果还没渲染完，直接跳转
            handler.postDelayed(this::navigate, 5000);
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
        if (navigated || isFinishing()) return;
        navigated = true;
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
