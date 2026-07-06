package org.joinmastodon.android.fragments.auth;

import android.app.ProgressDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;

import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.joinmastodon.android.ui.views.SpaceBackgroundView;

import java.io.IOException;

import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import me.grishka.appkit.Nav;
import me.grishka.appkit.fragments.AppKitFragment;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class LoginEmailFragment extends AppKitFragment {

    private static final OkHttpClient httpClient = new OkHttpClient();
    private EditText emailEdit;
    private CheckBox cbAgreement;
    private Button btnSendCode;
    private TextView tvAgreement;
    private ImageView logo;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("");
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_login_email, container, false);

        emailEdit = view.findViewById(R.id.email_edit);
        cbAgreement = view.findViewById(R.id.cb_agreement);
        btnSendCode = view.findViewById(R.id.btn_send_code);
        tvAgreement = view.findViewById(R.id.tv_agreement);
        TextView btnPasswordLogin = view.findViewById(R.id.btn_password_login);
        View btnNBW = view.findViewById(R.id.btn_nbw);
        View btnOAuth = view.findViewById(R.id.btn_oauth);
        logo = view.findViewById(R.id.logo);

        // 深色模式检测
        SpaceBackgroundView spaceBg = view.findViewById(R.id.space_bg);
        boolean isDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        spaceBg.setDarkMode(isDark);

        // 标题彩色文字
        TextView titleView = view.findViewById(R.id.title);
        if (isDark) {
            titleView.setText(android.text.Html.fromHtml("<font color='#FFFFFF'>欢迎登录 </font><font color='#A1D9F7'>ABDL Space</font>"));
        } else {
            titleView.setText(android.text.Html.fromHtml("<font color='#333333'>欢迎登录 </font><font color='#A1D9F7'>ABDL Space</font>"));
        }

        // 浅色/深色模式适配
        if (!isDark) {
            tvAgreement.setTextColor(0xFF7788AA);
            emailEdit.setBackgroundResource(R.drawable.bg_input_light);
            emailEdit.setTextColor(Color.BLACK);
            btnNBW.setBackgroundResource(R.drawable.bg_social_light);
            btnOAuth.setBackgroundResource(R.drawable.bg_social_light);
        } else {
            emailEdit.setBackgroundResource(R.drawable.bg_input_dark);
            emailEdit.setTextColor(Color.WHITE);
            btnNBW.setBackgroundResource(R.drawable.bg_social_dark);
            btnOAuth.setBackgroundResource(R.drawable.bg_social_dark);
        }

        // 协议
        tvAgreement.setText(android.text.Html.fromHtml(
            "我已阅读并同意<a href=\"https://abdl-space.top/agreement\">《用户协议》</a>和<a href=\"https://abdl-space.top/privacy\">《隐私政策》</a>"));
        tvAgreement.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());

        // 点击整行切换复选框（含超链接区域）
        View agreementRow = view.findViewById(R.id.agreement_row);
        agreementRow.setOnClickListener(v -> cbAgreement.setChecked(!cbAgreement.isChecked()));
        tvAgreement.setOnClickListener(v -> cbAgreement.setChecked(!cbAgreement.isChecked()));

        cbAgreement.setOnCheckedChangeListener((btn, checked) -> updateButtonState());
        emailEdit.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) { updateButtonState(); }
        });

        btnSendCode.setOnClickListener(v -> {
            String email = emailEdit.getText().toString().trim();
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(getActivity(), "请输入有效的邮箱地址", Toast.LENGTH_SHORT).show();
                return;
            }
            sendCode(email);
        });

        btnPasswordLogin.setOnClickListener(v -> Nav.go(getActivity(), LoginPasswordFragment.class, new Bundle()));

        btnNBW.setOnClickListener(v -> {
            getActivity().getSharedPreferences("nbw_bind", android.content.Context.MODE_PRIVATE)
                .edit().putString("flow", "login").apply();
            Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://abdl-space.top/api/auth/nbw/mobile-start"));
            startActivity(intent);
        });

        btnOAuth.setOnClickListener(v -> UiUtils.launchWebBrowser(getActivity(), "https://abdl-space.top/login"));

        return view;
    }

    private void startFloatingAnimation() {
        if (logo == null) return;
        // Y轴浮动动画
        logo.animate()
            .translationY(-dp(10))
            .setDuration(2000)
            .setInterpolator(new AccelerateDecelerateInterpolator())
            .withEndAction(() -> {
                if (logo != null) {
                    logo.animate()
                        .translationY(0)
                        .setDuration(2000)
                        .setInterpolator(new AccelerateDecelerateInterpolator())
                        .withEndAction(() -> {
                            if (logo != null && isAdded()) startFloatingAnimation();
                        })
                        .start();
                }
            })
            .start();
    }

    private void sendCode(String email) {
        btnSendCode.setEnabled(false);
        ProgressDialog progress = new ProgressDialog(getActivity());
        progress.setMessage("正在发送验证码...");
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
                        btnSendCode.setEnabled(true);
                        Toast.makeText(getActivity(), "网络错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (getActivity() == null) return;
                    final String responseBody = response.body() != null ? response.body().string() : "";
                    getActivity().runOnUiThread(() -> {
                        progress.dismiss();
                        if (response.isSuccessful()) {
                            Toast.makeText(getActivity(), "验证码已发送", Toast.LENGTH_SHORT).show();
                            Bundle args = new Bundle();
                            args.putString("email", email);
                            Nav.go(getActivity(), LoginCodeFragment.class, args);
                        } else {
                            btnSendCode.setEnabled(true);
                            Toast.makeText(getActivity(), "发送失败: " + responseBody, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            });
    }

    private void updateButtonState() {
        if (btnSendCode != null) {
            btnSendCode.setEnabled(cbAgreement.isChecked() && emailEdit.getText().length() > 0);
        }
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static class SendCodeBody {
        public String email, type;
        public SendCodeBody(String email, String type) { this.email = email; this.type = type; }
    }
}
