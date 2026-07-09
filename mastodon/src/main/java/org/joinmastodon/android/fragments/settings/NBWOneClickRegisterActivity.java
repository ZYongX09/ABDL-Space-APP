package org.joinmastodon.android.fragments.settings;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;

import org.joinmastodon.android.MainActivity;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 一键注册宝宝新天地页面（三阶段）
 * 阶段1：加载中 → 获取注册链接
 * 阶段2：提示去补充信息
 * 阶段3：WebView + 轮询检测注册状态
 */
public class NBWOneClickRegisterActivity extends Activity {

    private static final OkHttpClient httpClient = new OkHttpClient();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private LinearLayout phaseLoading, phaseInfo, phaseWebView;
    private ProgressBar progressBar;
    private TextView tvError;
    private WebView webView;
    private Button btnComplete;
    private Button btnRetry;

    private String email;
    private String nbwUsername;
    private String nbwPassword;
    private String registerUrl;
    private long phase3StartTime;
    private boolean checking = false;
    private Runnable pollRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 全屏透明状态栏
        android.view.Window window = getWindow();
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        } else {
            window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }

        setContentView(R.layout.activity_nbw_one_click_register);

        // 深色模式
        org.joinmastodon.android.ui.views.SpaceBackgroundView spaceBg = findViewById(R.id.space_bg);
        boolean isDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        spaceBg.setDarkMode(isDark);

        // 状态栏 padding — 应用到内容容器而非 android.R.id.content
        View contentContainer = findViewById(R.id.content_container);
        contentContainer.setOnApplyWindowInsetsListener((v, insets) -> {
            int statusBar = insets.getInsets(WindowInsets.Type.statusBars()).top;
            v.setPadding(0, statusBar, 0, 0);
            return insets;
        });

        // 获取当前用户邮箱（从 SharedPreferences 读取）
        email = getSharedPreferences("login_prefs", MODE_PRIVATE).getString("email", "");
        nbwUsername = getIntent().getStringExtra("nbw_username");
        nbwPassword = getIntent().getStringExtra("nbw_password");

        // 初始化视图
        phaseLoading = findViewById(R.id.phase_loading);
        phaseInfo = findViewById(R.id.phase_info);
        phaseWebView = findViewById(R.id.phase_webview);
        progressBar = findViewById(R.id.progress_bar);
        tvError = findViewById(R.id.tv_error);
        webView = findViewById(R.id.web_view);
        btnComplete = findViewById(R.id.btn_complete);
        btnRetry = findViewById(R.id.btn_retry);

        ImageView btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> finish());

        ImageView btnRefresh = findViewById(R.id.btn_refresh);
        btnRefresh.setOnClickListener(v -> {
            if (webView != null) webView.reload();
        });

        Button btnGoWeb = findViewById(R.id.btn_go_web);
        btnGoWeb.setOnClickListener(v -> showPhase3());

        btnComplete.setOnClickListener(v -> checkRegisterStatus());
        btnRetry.setOnClickListener(v -> {
            tvError.setVisibility(View.GONE);
            btnRetry.setVisibility(View.GONE);
            progressBar.setVisibility(View.VISIBLE);
            fetchRegisterUrl();
        });

        // 配置 WebView
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                if (nbwUsername != null && nbwPassword != null) {
                    String safeU = nbwUsername.replace("\\", "\\\\").replace("'", "\\'");
                    String safeP = nbwPassword.replace("\\", "\\\\").replace("'", "\\'");
                    String js = "javascript:(function(){" +
                        "var u=document.getElementById('yonghuname');" +
                        "var p=document.getElementById('psmima');" +
                        "var r=document.getElementById('remima');" +
                        "if(u){u.value='" + safeU + "';u.dispatchEvent(new Event('input',{bubbles:true}));}" +
                        "if(p){p.value='" + safeP + "';p.dispatchEvent(new Event('input',{bubbles:true}));}" +
                        "if(r){r.value='" + safeP + "';r.dispatchEvent(new Event('input',{bubbles:true}));}" +
                        "})()";
                    view.evaluateJavascript(js, null);
                    Toast.makeText(NBWOneClickRegisterActivity.this,
                        "已成功帮您自动填写您在 ABDL Space 留下的信息，您可以在页面内修改这些信息",
                        Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });

        // 开始阶段1
        showPhase1();
        fetchRegisterUrl();
    }

    private void showPhase1() {
        phaseLoading.setVisibility(View.VISIBLE);
        phaseInfo.setVisibility(View.GONE);
        phaseWebView.setVisibility(View.GONE);
    }

    private void showPhase2() {
        phaseLoading.setVisibility(View.GONE);
        phaseInfo.setVisibility(View.VISIBLE);
        phaseWebView.setVisibility(View.GONE);
        if (pollRunnable != null) handler.removeCallbacks(pollRunnable);
    }

    private void showPhase3() {
        phaseLoading.setVisibility(View.GONE);
        phaseInfo.setVisibility(View.GONE);
        phaseWebView.setVisibility(View.VISIBLE);
        phase3StartTime = System.currentTimeMillis();
        startPolling();

        if (registerUrl != null && !registerUrl.isEmpty()) {
            webView.loadUrl(registerUrl);
        }
    }

    private void fetchRegisterUrl() {
        String json = new Gson().toJson(new EmailBody(email));
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), json);

        AccountSession session = AccountSessionManager.getInstance().getLastActiveAccount();
        String token = session != null ? session.token.accessToken : "";

        httpClient.newCall(new Request.Builder()
                .url("https://api.abdl-space.top/api/auth/nbw/get-register-url")
                .addHeader("Authorization", "Bearer " + token)
                .post(body).build())
            .enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        tvError.setText("网络错误: " + e.getMessage());
                        tvError.setVisibility(View.VISIBLE);
                        btnRetry.setVisibility(View.VISIBLE);
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        try {
                            RegisterUrlResponse resp = new Gson().fromJson(responseBody, RegisterUrlResponse.class);
                            if (resp != null && resp.code == 200 && resp.data != null && resp.data.register_url != null) {
                                registerUrl = resp.data.register_url;
                                showPhase2();
                            } else {
                                String msg = resp != null && resp.msg != null ? resp.msg : "获取注册链接失败";
                                if (response.code() == 401) {
                                    msg = "登录已过期，请重新登录";
                                } else if (response.code() == 400) {
                                    msg = "请求参数错误";
                                } else if (!response.isSuccessful()) {
                                    msg = "服务器错误 (" + response.code() + ")";
                                }
                                tvError.setText(msg);
                                tvError.setVisibility(View.VISIBLE);
                                btnRetry.setVisibility(View.VISIBLE);
                            }
                        } catch (Exception e) {
                            tvError.setText("服务器响应异常");
                            tvError.setVisibility(View.VISIBLE);
                            btnRetry.setVisibility(View.VISIBLE);
                        }
                    });
                }
            });
    }

    private void startPolling() {
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                long elapsed = (System.currentTimeMillis() - phase3StartTime) / 1000;
                long interval;
                if (elapsed >= 20) interval = 3000;
                else if (elapsed >= 10) interval = 5000;
                else interval = 10000;

                checkRegisterStatus();
                handler.postDelayed(this, interval);
            }
        };
        handler.postDelayed(pollRunnable, 10000);
    }

    private void checkRegisterStatus() {
        if (checking) return;
        checking = true;
        btnComplete.setEnabled(false);

        String json = new Gson().toJson(new EmailBody(email));
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), json);

        AccountSession session = AccountSessionManager.getInstance().getLastActiveAccount();
        String token = session != null ? session.token.accessToken : "";

        httpClient.newCall(new Request.Builder()
                .url("https://api.abdl-space.top/api/auth/nbw/check-register")
                .addHeader("Authorization", "Bearer " + token)
                .post(body).build())
            .enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> {
                        checking = false;
                        btnComplete.setEnabled(true);
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    runOnUiThread(() -> {
                        checking = false;
                        btnComplete.setEnabled(true);
                        try {
                            CheckResponse resp = new Gson().fromJson(responseBody, CheckResponse.class);
                            if (resp != null && resp.registered) {
                                // 注册成功 → 绑定
                                if (pollRunnable != null) handler.removeCallbacks(pollRunnable);
                                bindByEmail();
                            }
                        } catch (Exception e) {
                            // 继续轮询
                        }
                    });
                }
            });
    }

    private void bindByEmail() {
        btnComplete.setEnabled(false);
        Toast.makeText(this, "正在绑定...", Toast.LENGTH_SHORT).show();

        String json = new Gson().toJson(new EmailBody(email));
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), json);

        AccountSession session = AccountSessionManager.getInstance().getLastActiveAccount();
        String token = session != null ? session.token.accessToken : "";

        httpClient.newCall(new Request.Builder()
                .url("https://api.abdl-space.top/api/auth/nbw/bind-by-email")
                .addHeader("Authorization", "Bearer " + token)
                .post(body).build())
            .enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> {
                        Toast.makeText(NBWOneClickRegisterActivity.this, "网络错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        btnComplete.setEnabled(true);
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String respBody = response.body() != null ? response.body().string() : "";
                    runOnUiThread(() -> {
                        if (response.isSuccessful()) {
                            Toast.makeText(NBWOneClickRegisterActivity.this, "绑定成功！", Toast.LENGTH_SHORT).show();
                            navigateToMain();
                        } else {
                            String errorMsg = "绑定失败";
                            try {
                                BindErrorResponse errResp = new Gson().fromJson(respBody, BindErrorResponse.class);
                                if (errResp != null && errResp.error != null) {
                                    errorMsg = errResp.error;
                                }
                            } catch (Exception ignored) {}
                            if (response.code() == 404) {
                                errorMsg = "未找到对应的宝宝新天地账户，请确认注册已完成";
                            } else if (response.code() == 409) {
                                errorMsg = "该宝宝新天地账户已被其他用户绑定";
                            } else if (response.code() == 401) {
                                errorMsg = "登录已过期，请重新登录";
                            }
                            Toast.makeText(NBWOneClickRegisterActivity.this, errorMsg, Toast.LENGTH_LONG).show();
                            btnComplete.setEnabled(true);
                        }
                    });
                }
            });
    }

    private void navigateToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pollRunnable != null) handler.removeCallbacks(pollRunnable);
        if (webView != null) webView.destroy();
    }

    // --- 数据类 ---

    private static class EmailBody {
        String email;
        EmailBody(String email) { this.email = email; }
    }

    private static class RegisterUrlResponse {
        int code;
        String msg;
        RegisterUrlData data;
    }

    private static class RegisterUrlData {
        String register_url;
    }

    private static class CheckResponse {
        boolean registered;
    }

    private static class BindErrorResponse {
        String error;
    }
}
