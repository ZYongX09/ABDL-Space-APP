package cn.jiguang.demo;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;

import androidx.annotation.Nullable;

import cn.jiguang.api.utils.JCollectionAuth;
import cn.jiguang.demo.baselibrary.BaseApplication;
import cn.jiguang.demo.baselibrary.SPUtils;

public class PrivacyActivity extends Activity {

    private static final String url = "https://jverification.jiguang.cn/scripts/jdemo_privacy_v5.html";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (SPUtils.isShowPrivacy(this)) {
            JCollectionAuth.setAuth(PrivacyActivity.this, true);
            init();
            startActivity(new Intent(PrivacyActivity.this, MainActivity.class));
            finish();
        } else {
            setContentView(R.layout.d_activity_web_view);
            WebView webview = findViewById(R.id.webview);
            findViewById(R.id.btn_one).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                    System.exit(0);
                }
            });
            findViewById(R.id.btn_two).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    SPUtils.setIsShowPrivacy(PrivacyActivity.this, true);
                    JCollectionAuth.setAuth(PrivacyActivity.this, true);
                    init();
                    startActivity(new Intent(PrivacyActivity.this, MainActivity.class));
                    finish();
                }
            });
            webview.loadUrl(url);
        }
    }

    private void init() {
        BaseApplication.initRouter();
    }
}
