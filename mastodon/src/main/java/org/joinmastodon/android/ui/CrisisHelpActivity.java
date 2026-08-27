package org.joinmastodon.android.ui;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

import org.joinmastodon.android.R;

/**
 * 心理危机干预帮助页：全屏自适应裁切展示干预海报，左上角悬浮返回按钮。
 * 从发帖页/帖子详情页危机提示卡的“我需要帮助”按钮进入。
 */
public class CrisisHelpActivity extends Activity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Window window = getWindow();
		window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
		window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
		window.setStatusBarColor(Color.TRANSPARENT);
		window.setNavigationBarColor(Color.TRANSPARENT);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			window.setDecorFitsSystemWindows(false);
		} else {
			window.getDecorView().setSystemUiVisibility(
					View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
		}

		setContentView(R.layout.activity_crisis_help);

		View back = findViewById(R.id.crisis_help_back);
		back.setOnClickListener(v -> finish());
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			back.setOnApplyWindowInsetsListener((v, insets) -> {
				int statusBar = insets.getInsets(WindowInsets.Type.statusBars()).top;
				v.setTranslationY(statusBar);
				return insets;
			});
		} else {
			back.setOnApplyWindowInsetsListener((v, insets) -> {
				v.setTranslationY(insets.getSystemWindowInsetTop());
				return insets;
			});
		}
	}
}
