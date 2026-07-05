package org.joinmastodon.android.fragments.auth;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.utils.UiUtils;

import androidx.annotation.Nullable;
import me.grishka.appkit.Nav;
import me.grishka.appkit.fragments.AppKitFragment;

/**
 * 验证码登录页（美团风格）
 * - 邮箱输入
 * - 协议复选框
 * - "获取验证码" → LoginCodeFragment
 * - "账号密码登录" → LoginPasswordFragment
 * - 第三方登录：宝宝新天地、ABDL Space OAuth
 */
public class LoginEmailFragment extends AppKitFragment {

    private EditText emailEdit;
    private CheckBox cbAgreement;
    private Button btnSendCode;
    private TextView tvAgreement;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("登录");
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

        // 协议文本
        tvAgreement.setText(android.text.Html.fromHtml(
            "我已阅读并同意<a href=\"https://abdl-space.top/agreement\">《用户协议》</a>和<a href=\"https://abdl-space.top/privacy\">《隐私政策》</a>"));
        tvAgreement.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());

        // 协议变化 → 更新按钮状态
        cbAgreement.setOnCheckedChangeListener((btn, checked) -> updateButtonState());
        emailEdit.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) { updateButtonState(); }
        });

        // 获取验证码
        btnSendCode.setOnClickListener(v -> {
            String email = emailEdit.getText().toString().trim();
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                Toast.makeText(getActivity(), "请输入有效的邮箱地址", Toast.LENGTH_SHORT).show();
                return;
            }
            Bundle args = new Bundle();
            args.putString("email", email);
            Nav.go(getActivity(), LoginCodeFragment.class, args);
        });

        // 账号密码登录
        btnPasswordLogin.setOnClickListener(v -> Nav.go(getActivity(), LoginPasswordFragment.class, new Bundle()));

        // 宝宝新天地登录
        btnNBW.setOnClickListener(v -> {
            getActivity().getSharedPreferences("nbw_bind", android.content.Context.MODE_PRIVATE)
                .edit().putString("flow", "login").apply();
            Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://api.abdl-space.top/api/auth/nbw/mobile-start"));
            startActivity(intent);
        });

        // OAuth 登录
        btnOAuth.setOnClickListener(v -> {
            UiUtils.launchWebBrowser(getActivity(), "https://abdl-space.top/login");
        });

        return view;
    }

    private void updateButtonState() {
        if (btnSendCode != null) {
            btnSendCode.setEnabled(cbAgreement.isChecked() && emailEdit.getText().length() > 0);
        }
    }
}
