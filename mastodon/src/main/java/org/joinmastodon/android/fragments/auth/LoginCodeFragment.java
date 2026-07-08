package org.joinmastodon.android.fragments.auth;

import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;

import org.joinmastodon.android.MainActivity;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.accounts.GetOwnAccount;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Application;
import org.joinmastodon.android.model.InstanceV2;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.ui.views.PinView;

import java.io.IOException;

import androidx.annotation.Nullable;
import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.fragments.AppKitFragment;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 验证码输入页
 * - 6位 PinView
 * - 自动校验 → POST /api/auth/login-by-code
 * - "打开邮件" → 跳转系统邮件 App
 * - "重新发送" 倒计时
 */
public class LoginCodeFragment extends AppKitFragment {

    private static final OkHttpClient httpClient = new OkHttpClient();

    private String email;
    private PinView pinView;
    private TextView tvEmailHint, tvError, tvResend;
    private Button btnOpenEmail;
    private CountDownTimer resendTimer;
    private boolean verifying = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        email = getArguments() != null ? getArguments().getString("email", "") : "";
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_login_code, container, false);

        // 深色模式
        org.joinmastodon.android.ui.views.SpaceBackgroundView spaceBg = view.findViewById(R.id.space_bg);
        boolean isDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        spaceBg.setDarkMode(isDark);

        ImageView btnBack = view.findViewById(R.id.btn_back);
        tvEmailHint = view.findViewById(R.id.tv_email_hint);
        pinView = view.findViewById(R.id.pin_view);
        btnOpenEmail = view.findViewById(R.id.btn_open_email);
        tvResend = view.findViewById(R.id.tv_resend);
        tvError = view.findViewById(R.id.tv_error);

        tvEmailHint.setText("验证码已发送至\n" + email);

        btnBack.setOnClickListener(v -> getActivity().onBackPressed());

        // 打开邮件 App
        btnOpenEmail.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("mailto:"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            try { startActivity(intent); } catch (Exception e) {
                Toast.makeText(getActivity(), "未找到邮件应用", Toast.LENGTH_SHORT).show();
            }
        });

        // 重新发送
        tvResend.setOnClickListener(v -> {
            if (tvResend.isEnabled()) {
                resendCode();
            }
        });

        // PinView 输入完成 → 自动校验
        pinView.setOnPinCompleteListener(pin -> {
            if (!verifying) {
                verifyCode(pin);
            }
        });

        startResendTimer();

        return view;
    }

    private void verifyCode(String code) {
        verifying = true;
        tvError.setVisibility(View.GONE);

        ProgressDialog progress = new ProgressDialog(getActivity());
        progress.setMessage("正在验证...");
        progress.setCancelable(false);
        progress.show();

        String json = new Gson().toJson(new VerifyBody(email, code));
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), json);

        httpClient.newCall(new Request.Builder()
                .url("https://abdl-space.top/api/auth/login-by-code")
                .post(body).build())
            .enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(() -> {
                        progress.dismiss();
                        verifying = false;
                        pinView.clearPin();
                        Toast.makeText(getActivity(), "网络错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    final String responseBody = (response.body() != null) ? response.body().string() : "";
                    if (getActivity() == null) return;

                    getActivity().runOnUiThread(() -> {
                        progress.dismiss();
                        VerifyResponse verifyResp = new Gson().fromJson(responseBody, VerifyResponse.class);

                        if (response.isSuccessful() && verifyResp != null) {
                            if ("login".equals(verifyResp.action) && verifyResp.token != null) {
                                // 已注册 → 登录
                                Token token = new Token();
                                token.accessToken = verifyResp.token;

                                new GetOwnAccount()
                                    .setCallback(new Callback<Account>() {
                                        @Override
                                        public void onSuccess(Account account) {
                                            if (getActivity() == null) return;

                                            InstanceV2 instance = new InstanceV2();
                                            instance.domain = "abdl-space.top";
                                            instance.title = "ABDL Space";
                                            instance.description = "ABDL Space";
                                            instance.version = "4.0.0";

                                            Application app = new Application();
                                            app.clientId = "";
                                            app.clientSecret = "";

                                            AccountSessionManager.getInstance().addAccount(instance, token, account, app, null);

                                // 存储 email 到 SharedPreferences
                                getActivity().getSharedPreferences("login_prefs", android.content.Context.MODE_PRIVATE)
                                    .edit().putString("email", email).apply();

                                            getActivity().runOnUiThread(() -> {
                                                if (getActivity() instanceof MainActivity mainActivity) {
                                                    mainActivity.restartHomeFragment();
                                                }
                                            });
                                        }

                                        @Override
                                        public void onError(ErrorResponse error) {
                                            verifying = false;
                                            pinView.clearPin();
                                            error.showToast(getActivity());
                                        }
                                    })
                                    .exec("abdl-space.top", token);
                            } else if ("register".equals(verifyResp.action)) {
                                // 未注册 → 进入注册流程（不传code，login-by-code已验证过）
                                Bundle args = new Bundle();
                                args.putString("email", email);
                                Nav.go(getActivity(), RegisterInfoFragment.class, args);
                                verifying = false;
                            } else {
                                verifying = false;
                                pinView.clearPin();
                                tvError.setText("验证失败: " + responseBody);
                                tvError.setVisibility(View.VISIBLE);
                            }
                        } else {
                            verifying = false;
                            pinView.clearPin();
                            String errorMsg = verifyResp != null && verifyResp.error != null
                                ? verifyResp.error : "验证码错误";
                            tvError.setText(errorMsg);
                            tvError.setVisibility(View.VISIBLE);
                        }
                    });
                }
            });
    }

    private void resendCode() {
        ProgressDialog progress = new ProgressDialog(getActivity());
        progress.setMessage("正在发送...");
        progress.setCancelable(false);
        progress.show();

        String json = new Gson().toJson(new SendCodeBody(email, "register"));
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), json);

        httpClient.newCall(new Request.Builder()
                .url("https://abdl-space.top/api/auth/send-code")
                .post(body).build())
            .enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(() -> {
                        progress.dismiss();
                        Toast.makeText(getActivity(), "发送失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(() -> {
                        progress.dismiss();
                        if (response.isSuccessful()) {
                            Toast.makeText(getActivity(), "验证码已重新发送", Toast.LENGTH_SHORT).show();
                            startResendTimer();
                        } else {
                            Toast.makeText(getActivity(), "发送失败", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            });
    }

    private void startResendTimer() {
        tvResend.setEnabled(false);
        resendTimer = new CountDownTimer(60000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                tvResend.setText("重新发送（" + (millisUntilFinished / 1000) + "s）");
            }

            @Override
            public void onFinish() {
                tvResend.setEnabled(true);
                tvResend.setText("重新发送");
            }
        }.start();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (resendTimer != null) resendTimer.cancel();
    }

    private static class VerifyBody {
        public String email, code;
        public VerifyBody(String email, String code) { this.email = email; this.code = code; }
    }

    private static class VerifyResponse {
        public String action, token, email, error;
    }

    private static class SendCodeBody {
        public String email, type;
        public SendCodeBody(String email, String type) { this.email = email; this.type = type; }
    }
}
