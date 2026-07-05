package org.joinmastodon.android.fragments.auth;

import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;

import org.joinmastodon.android.MainActivity;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.accounts.GetOwnAccount;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.settings.NBWPostRegisterActivity;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Application;
import org.joinmastodon.android.model.InstanceV2;
import org.joinmastodon.android.model.Token;

import java.io.IOException;

import androidx.annotation.Nullable;
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
 * 注册信息页
 * - 邮箱（只读）
 * - 用户名 + 密码 + 确认密码
 * - "注册" → POST /api/auth/register
 * - 注册成功 → NBWPostRegisterActivity
 */
public class RegisterInfoFragment extends AppKitFragment {

    private static final OkHttpClient httpClient = new OkHttpClient();

    private String email, code;
    private EditText usernameEdit, passwordEdit, confirmEdit;
    private Button btnRegister;
    private boolean passwordVisible = false;
    private boolean confirmVisible = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        email = getArguments() != null ? getArguments().getString("email", "") : "";
        code = getArguments() != null ? getArguments().getString("code", "") : "";
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_register_info, container, false);

        ImageView btnBack = view.findViewById(R.id.btn_back);
        TextView tvEmail = view.findViewById(R.id.tv_email);
        usernameEdit = view.findViewById(R.id.username_edit);
        passwordEdit = view.findViewById(R.id.password_edit);
        confirmEdit = view.findViewById(R.id.confirm_edit);
        btnRegister = view.findViewById(R.id.btn_register);

        tvEmail.setText(email);

        btnBack.setOnClickListener(v -> getActivity().onBackPressed());

        // 密码可见切换
        setupPasswordToggle(passwordEdit, () -> passwordVisible = !passwordVisible, () -> passwordVisible);
        setupPasswordToggle(confirmEdit, () -> confirmVisible = !confirmVisible, () -> confirmVisible);

        // 按钮状态
        android.text.TextWatcher watcher = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) { updateButtonState(); }
        };
        usernameEdit.addTextChangedListener(watcher);
        passwordEdit.addTextChangedListener(watcher);
        confirmEdit.addTextChangedListener(watcher);

        // 注册
        btnRegister.setOnClickListener(v -> attemptRegister());

        return view;
    }

    private void setupPasswordToggle(EditText edit, Runnable toggle, java.util.function.BooleanSupplier getVisible) {
        edit.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                int drawableEnd = 2;
                if (event.getRawX() >= edit.getRight() - edit.getCompoundDrawables()[drawableEnd].getBounds().width()) {
                    toggle.run();
                    edit.setTransformationMethod(getVisible.getAsBoolean()
                        ? HideReturnsTransformationMethod.getInstance()
                        : PasswordTransformationMethod.getInstance());
                    edit.setSelection(edit.getText().length());
                    return true;
                }
            }
            return false;
        });
    }

    private void updateButtonState() {
        if (btnRegister != null) {
            String username = usernameEdit.getText().toString().trim();
            String password = passwordEdit.getText().toString();
            String confirm = confirmEdit.getText().toString();
            btnRegister.setEnabled(
                username.length() >= 3 && username.length() <= 30
                && password.length() >= 8
                && password.equals(confirm));
        }
    }

    private void attemptRegister() {
        String username = usernameEdit.getText().toString().trim();
        String password = passwordEdit.getText().toString();
        String confirm = confirmEdit.getText().toString();

        if (TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) return;
        if (!password.equals(confirm)) {
            Toast.makeText(getActivity(), "两次密码不一致", Toast.LENGTH_SHORT).show();
            return;
        }
        if (password.length() < 8) {
            Toast.makeText(getActivity(), "密码至少8位", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!password.matches(".*[a-z].*") || !password.matches(".*[A-Z].*") || !password.matches(".*\\d.*")) {
            Toast.makeText(getActivity(), "密码需包含大小写字母和数字", Toast.LENGTH_SHORT).show();
            return;
        }

        btnRegister.setEnabled(false);
        ProgressDialog progress = new ProgressDialog(getActivity());
        progress.setMessage("正在注册...");
        progress.setCancelable(false);
        progress.show();

        RegisterBody regBody = new RegisterBody(username, email, password, code);
        String json = new Gson().toJson(regBody);
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), json);

        httpClient.newCall(new Request.Builder()
                .url("https://abdl-space.top/api/auth/register")
                .post(body).build())
            .enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(() -> {
                        progress.dismiss();
                        if (btnRegister != null) btnRegister.setEnabled(true);
                        Toast.makeText(getActivity(), "网络错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    final String responseBody = (response.body() != null) ? response.body().string() : "";
                    if (getActivity() == null) return;

                    getActivity().runOnUiThread(() -> {
                        progress.dismiss();
                        if (response.isSuccessful()) {
                            RegisterResponse regResp = new Gson().fromJson(responseBody, RegisterResponse.class);
                            if (regResp != null && regResp.token != null) {
                                // 注册成功 → 自动登录
                                Token token = new Token();
                                token.accessToken = regResp.token;

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

                                            // 跳转 NBW 绑定引导页
                                            getActivity().runOnUiThread(() -> {
                                                Intent intent = new Intent(getActivity(), NBWPostRegisterActivity.class);
                                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                                startActivity(intent);
                                                if (getActivity() instanceof MainActivity mainActivity) {
                                                    mainActivity.finish();
                                                }
                                            });
                                        }

                                        @Override
                                        public void onError(ErrorResponse error) {
                                            if (btnRegister != null) btnRegister.setEnabled(true);
                                            error.showToast(getActivity());
                                        }
                                    })
                                    .exec("abdl-space.top", token);
                            } else {
                                if (btnRegister != null) btnRegister.setEnabled(true);
                                Toast.makeText(getActivity(), "注册失败: " + responseBody, Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            if (btnRegister != null) btnRegister.setEnabled(true);
                            Toast.makeText(getActivity(), "注册失败: " + responseBody, Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            });
    }

    private static class RegisterBody {
        public String username, email, password, code;
        public RegisterBody(String username, String email, String password, String code) {
            this.username = username;
            this.email = email;
            this.password = password;
            this.code = code;
        }
    }

    private static class RegisterResponse {
        public String token;
    }
}
