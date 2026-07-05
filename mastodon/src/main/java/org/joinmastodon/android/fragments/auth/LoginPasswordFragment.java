package org.joinmastodon.android.fragments.auth;

import android.app.ProgressDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.joinmastodon.android.ui.views.SpaceBackgroundView;

import com.google.gson.Gson;

import org.joinmastodon.android.MainActivity;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.accounts.GetOwnAccount;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Application;
import org.joinmastodon.android.model.InstanceV2;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.ui.utils.UiUtils;

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
 * 账号密码登录页（美团风格）
 * - 邮箱 + 密码
 * - "登录" → POST /api/auth/login
 * - "验证码登录" → LoginEmailFragment
 * - 第三方登录
 */
public class LoginPasswordFragment extends AppKitFragment {

    private static final OkHttpClient httpClient = new OkHttpClient();

    private EditText emailEdit, passwordEdit;
    private CheckBox cbAgreement;
    private Button btnLogin;
    private boolean passwordVisible = false;

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_login_password, container, false);

        emailEdit = view.findViewById(R.id.email_edit);
        passwordEdit = view.findViewById(R.id.password_edit);
        cbAgreement = view.findViewById(R.id.cb_agreement);
        btnLogin = view.findViewById(R.id.btn_login);
        TextView tvAgreement = view.findViewById(R.id.tv_agreement);
        TextView btnCodeLogin = view.findViewById(R.id.btn_code_login);
        View btnNBW = view.findViewById(R.id.btn_nbw);
        View btnOAuth = view.findViewById(R.id.btn_oauth);
        TextView titleView = view.findViewById(R.id.title);

        // 深色模式检测
        SpaceBackgroundView spaceBg = view.findViewById(R.id.space_bg);
        boolean isDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        spaceBg.setDarkMode(isDark);

        if (isDark) {
            titleView.setText(android.text.Html.fromHtml("<font color='#FFFFFF'>欢迎登录 </font><font color='#88AAFF'>ABDL Space</font>"));
            emailEdit.setBackgroundResource(R.drawable.bg_input_dark);
            passwordEdit.setBackgroundResource(R.drawable.bg_input_dark);
            btnNBW.setBackgroundResource(R.drawable.bg_social_dark);
            btnOAuth.setBackgroundResource(R.drawable.bg_social_dark);
        } else {
            titleView.setText(android.text.Html.fromHtml("<font color='#333333'>欢迎登录 </font><font color='#4A90D9'>ABDL Space</font>"));
            emailEdit.setBackgroundResource(R.drawable.bg_input_light);
            passwordEdit.setBackgroundResource(R.drawable.bg_input_light);
            btnNBW.setBackgroundResource(R.drawable.bg_social_light);
            btnOAuth.setBackgroundResource(R.drawable.bg_social_light);
        }

        // 协议文本
        tvAgreement.setText(android.text.Html.fromHtml(
            "我已阅读并同意<a href=\"https://abdl-space.top/agreement\">《用户协议》</a>和<a href=\"https://abdl-space.top/privacy\">《隐私政策》</a>"));
        tvAgreement.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());

        // 密码可见切换
        passwordEdit.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                int drawableEnd = 2;
                if (event.getRawX() >= passwordEdit.getRight() - passwordEdit.getCompoundDrawables()[drawableEnd].getBounds().width()) {
                    passwordVisible = !passwordVisible;
                    passwordEdit.setTransformationMethod(passwordVisible
                        ? HideReturnsTransformationMethod.getInstance()
                        : PasswordTransformationMethod.getInstance());
                    passwordEdit.setSelection(passwordEdit.getText().length());
                    return true;
                }
            }
            return false;
        });

        // 按钮状态
        cbAgreement.setOnCheckedChangeListener((btn, checked) -> updateButtonState());
        emailEdit.addTextChangedListener(simpleWatcher);
        passwordEdit.addTextChangedListener(simpleWatcher);

        // 登录
        btnLogin.setOnClickListener(v -> attemptLogin());

        // 验证码登录 → 返回 LoginEmailFragment
        btnCodeLogin.setOnClickListener(v -> {
            getActivity().onBackPressed();
        });

        // 宝宝新天地登录
        btnNBW.setOnClickListener(v -> {
            getActivity().getSharedPreferences("nbw_bind", android.content.Context.MODE_PRIVATE)
                .edit().putString("flow", "login").apply();
            Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://abdl-space.top/api/auth/nbw/mobile-start"));
            startActivity(intent);
        });

        // OAuth 登录
        btnOAuth.setOnClickListener(v -> {
            UiUtils.launchWebBrowser(getActivity(), "https://abdl-space.top/login");
        });

        return view;
    }

    private final android.text.TextWatcher simpleWatcher = new android.text.TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override public void afterTextChanged(android.text.Editable s) { updateButtonState(); }
    };

    private void updateButtonState() {
        if (btnLogin != null) {
            btnLogin.setEnabled(cbAgreement.isChecked()
                && emailEdit.getText().length() > 0
                && passwordEdit.getText().length() > 0);
        }
    }

    private void attemptLogin() {
        String email = emailEdit.getText().toString().trim();
        String password = passwordEdit.getText().toString();
        if (TextUtils.isEmpty(email) || TextUtils.isEmpty(password)) return;

        btnLogin.setEnabled(false);
        ProgressDialog progress = new ProgressDialog(getActivity());
        progress.setMessage(getString(R.string.loading));
        progress.setCancelable(false);
        progress.show();

        String json = new Gson().toJson(new LoginBody(email, password));
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), json);

        httpClient.newCall(new Request.Builder()
                .url("https://abdl-space.top/api/auth/login")
                .post(body).build())
            .enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(() -> {
                        progress.dismiss();
                        if (btnLogin != null) btnLogin.setEnabled(true);
                        Toast.makeText(getActivity(), "网络错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    final String responseBody = (response.body() != null) ? response.body().string() : "";
                    if (getActivity() == null) return;

                    getActivity().runOnUiThread(() -> {
                        if (response.isSuccessful()) {
                            AuthResponse authResponse = new Gson().fromJson(responseBody, AuthResponse.class);
                            if (authResponse != null && authResponse.token != null) {
                                Token token = new Token();
                                token.accessToken = authResponse.token;

                                new GetOwnAccount()
                                    .setCallback(new Callback<Account>() {
                                        @Override
                                        public void onSuccess(Account account) {
                                            progress.dismiss();
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
                                            progress.dismiss();
                                            if (btnLogin != null) btnLogin.setEnabled(true);
                                            if (getActivity() == null) return;
                                            error.showToast(getActivity());
                                        }
                                    })
                                    .exec("abdl-space.top", token);
                            } else {
                                progress.dismiss();
                                if (btnLogin != null) btnLogin.setEnabled(true);
                                Toast.makeText(getActivity(), "登录失败: " + responseBody, Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            progress.dismiss();
                            if (btnLogin != null) btnLogin.setEnabled(true);
                            Toast.makeText(getActivity(), "登录失败: " + responseBody, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            });
    }

    private static class LoginBody {
        public String login, password;
        public LoginBody(String login, String password) { this.login = login; this.password = password; }
    }

    private static class AuthResponse {
        public String token;
    }
}
