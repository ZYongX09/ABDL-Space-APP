package org.joinmastodon.android.fragments.auth;

import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
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

    private String email, code, prefillUsername, nbwToken, nbwUid, nbwUsername;
    private boolean fromNBWOAuthRegister;
    private EditText emailEdit, usernameEdit, passwordEdit, confirmEdit;
    private Button btnRegister;
    private boolean passwordVisible = false;
    private boolean confirmVisible = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        email = getArguments() != null ? getArguments().getString("email", "") : "";
        code = getArguments() != null ? getArguments().getString("code", "") : "";
        prefillUsername = getArguments() != null ? getArguments().getString("prefill_username", "") : "";
        nbwToken = getArguments() != null ? getArguments().getString("nbw_token", "") : "";
        nbwUid = getArguments() != null ? getArguments().getString("nbw_uid", "") : "";
        nbwUsername = getArguments() != null ? getArguments().getString("nbw_username", "") : "";
        fromNBWOAuthRegister = getArguments() != null && getArguments().getBoolean("from_nbw_oauth_register", false);
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_register_info, container, false);

        // 深色模式
        org.joinmastodon.android.ui.views.SpaceBackgroundView spaceBg = view.findViewById(R.id.space_bg);
        boolean isDark = (android.os.Build.VERSION.SDK_INT >= 21) &&
            ((getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES);
        spaceBg.setDarkMode(isDark);

        View contentContainer = view.findViewById(R.id.content_container);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            contentContainer.setOnApplyWindowInsetsListener((v, insets) -> {
                int statusBar = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top;
                v.setPadding(0, statusBar, 0, 0);
                return insets;
            });
        } else {
            android.content.Context ctx = getActivity();
            if (ctx != null) {
                int resId = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
                int sb = resId > 0 ? ctx.getResources().getDimensionPixelSize(resId) : 0;
                contentContainer.setPadding(0, sb, 0, 0);
            }
        }

        ImageView btnBack = view.findViewById(R.id.btn_back);
        emailEdit = view.findViewById(R.id.tv_email);
        usernameEdit = view.findViewById(R.id.username_edit);
        passwordEdit = view.findViewById(R.id.password_edit);
        confirmEdit = view.findViewById(R.id.confirm_edit);
        btnRegister = view.findViewById(R.id.btn_register);

        emailEdit.setText(email);
        emailEdit.setEnabled(fromNBWOAuthRegister);
        if(!TextUtils.isEmpty(prefillUsername))
            usernameEdit.setText(prefillUsername);

        // 深浅色输入框适配（与 LoginEmail/LoginPassword 一致）
        if (isDark) {
            usernameEdit.setBackgroundResource(R.drawable.bg_input_dark);
            usernameEdit.setTextColor(android.graphics.Color.WHITE);
            emailEdit.setBackgroundResource(R.drawable.bg_input_dark);
            emailEdit.setTextColor(android.graphics.Color.WHITE);
            passwordEdit.setBackgroundResource(R.drawable.bg_input_dark);
            passwordEdit.setTextColor(android.graphics.Color.WHITE);
            confirmEdit.setBackgroundResource(R.drawable.bg_input_dark);
            confirmEdit.setTextColor(android.graphics.Color.WHITE);
        } else {
            usernameEdit.setBackgroundResource(R.drawable.bg_input_light);
            usernameEdit.setTextColor(android.graphics.Color.BLACK);
            emailEdit.setBackgroundResource(R.drawable.bg_input_light);
            emailEdit.setTextColor(android.graphics.Color.BLACK);
            passwordEdit.setBackgroundResource(R.drawable.bg_input_light);
            passwordEdit.setTextColor(android.graphics.Color.BLACK);
            confirmEdit.setBackgroundResource(R.drawable.bg_input_light);
            confirmEdit.setTextColor(android.graphics.Color.BLACK);
        }

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
        emailEdit.addTextChangedListener(watcher);
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
            String emailValue = emailEdit.getText().toString().trim();
            String password = passwordEdit.getText().toString();
            String confirm = confirmEdit.getText().toString();
            btnRegister.setEnabled(
                emailValue.length() > 0
                && username.length() >= 3 && username.length() <= 30
                && password.length() >= 8
                && password.equals(confirm));
        }
    }

    private void attemptRegister() {
        String username = usernameEdit.getText().toString().trim();
        String emailValue = emailEdit.getText().toString().trim();
        String password = passwordEdit.getText().toString();
        String confirm = confirmEdit.getText().toString();

        if (TextUtils.isEmpty(emailValue) || TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) return;
        if (fromNBWOAuthRegister && TextUtils.isEmpty(nbwToken)) {
            Toast.makeText(getActivity(), "宝宝新天地授权信息缺失，请重新授权", Toast.LENGTH_SHORT).show();
            return;
        }
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

        RegisterBody regBody = new RegisterBody(username, emailValue, password, code, fromNBWOAuthRegister ? nbwToken : null);
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
                                    .edit().putString("email", emailValue).apply();

                                            getActivity().runOnUiThread(() -> {
                                                if(fromNBWOAuthRegister){
                                                    Toast.makeText(getActivity(), "宝宝新天地账号已绑定", Toast.LENGTH_LONG).show();
                                                    if (getActivity() instanceof MainActivity mainActivity) {
                                                        mainActivity.restartHomeFragment();
                                                    }
                                                }else{
                                                    Intent intent = new Intent(getActivity(), NBWPostRegisterActivity.class);
                                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                                    intent.putExtra("nbw_username", username);
                                                    intent.putExtra("nbw_password", password);
                                                    startActivity(intent);
                                                    if (getActivity() instanceof MainActivity mainActivity) {
                                                        mainActivity.finish();
                                                    }
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
        public String username, email, password, code, nbw_token;
        public RegisterBody(String username, String email, String password, String code, String nbwToken) {
            this.username = username;
            this.email = email;
            this.password = password;
            this.code = code;
            this.nbw_token = nbwToken;
        }
    }

    private static class RegisterResponse {
        public String token;
    }
}
