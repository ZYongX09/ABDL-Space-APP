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


import org.joinmastodon.android.BuildConfig;
import org.joinmastodon.android.QQAuthActivity;
import org.joinmastodon.android.NBWLoginActivity;
import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.joinmastodon.android.ui.views.SpaceBackgroundView;


import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import me.grishka.appkit.Nav;
import me.grishka.appkit.fragments.AppKitFragment;

public class LoginEmailFragment extends AppKitFragment {

    private EditText emailEdit;
    private CheckBox cbAgreement;
    private Button btnSendCode;
    private TextView tvAgreement;
    private ImageView logo;
    private static final int HUMAN_CHECK_RESULT = 4871;
    private final org.joinmastodon.android.auth.HumanCheckCoordinator humanCheck = org.joinmastodon.android.auth.HumanCheckCoordinator.getInstance();
    private org.joinmastodon.android.auth.HumanCheckCoordinator.Handle humanCheckHandle;
    private ProgressDialog humanCheckProgress;
    private String humanCheckEmail;
    private boolean humanCheckLaunched;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("");
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_login_email, container, false);

        // 状态栏 padding
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

        // 返回按钮（仅从其他页面跳入时显示）
        ImageView btnBack = view.findViewById(R.id.btn_back);
        boolean showBack = getArguments() != null && getArguments().getBoolean("show_back", false);
        if (showBack) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setOnClickListener(v -> getActivity().onBackPressed());
        }

        emailEdit = view.findViewById(R.id.email_edit);
        cbAgreement = view.findViewById(R.id.cb_agreement);
        btnSendCode = view.findViewById(R.id.btn_send_code);
        tvAgreement = view.findViewById(R.id.tv_agreement);
        TextView btnPasswordLogin = view.findViewById(R.id.btn_password_login);
        View btnNBW = view.findViewById(R.id.btn_nbw);
        View btnQQ = view.findViewById(R.id.btn_qq);
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
            tvAgreement.setTextColor(androidx.core.content.ContextCompat.getColor(getActivity(), R.color.m3_sys_light_on_surface_variant));
            emailEdit.setBackgroundResource(R.drawable.bg_input_light);
            emailEdit.setTextColor(Color.BLACK);
            btnNBW.setBackgroundResource(R.drawable.bg_social_light);
            btnQQ.setBackgroundResource(R.drawable.bg_social_light);
            btnOAuth.setBackgroundResource(R.drawable.bg_social_light);
        } else {
            emailEdit.setBackgroundResource(R.drawable.bg_input_dark);
            emailEdit.setTextColor(Color.WHITE);
            tvAgreement.setTextColor(androidx.core.content.ContextCompat.getColor(getActivity(), R.color.m3_sys_dark_on_surface_variant));
            btnNBW.setBackgroundResource(R.drawable.bg_social_dark);
            btnQQ.setBackgroundResource(R.drawable.bg_social_dark);
            btnOAuth.setBackgroundResource(R.drawable.bg_social_dark);
        }

        // 协议
        tvAgreement.setText(android.text.Html.fromHtml(getString(R.string.login_agreement_text)));
        tvAgreement.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());

        View agreementRow = view.findViewById(R.id.agreement_row);
        agreementRow.setOnClickListener(v -> cbAgreement.setChecked(!cbAgreement.isChecked()));

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

        btnNBW.setVisibility(View.VISIBLE);
        btnNBW.setOnClickListener(v -> showNBWConsentSheet());

        btnQQ.setVisibility(BuildConfig.QQ_LOGIN_ENABLED ? View.VISIBLE : View.GONE);
        btnQQ.setOnClickListener(v -> showQQConsentSheet());

        btnOAuth.setOnClickListener(v -> showConsentSheet(() -> UiUtils.launchWebBrowser(getActivity(), "https://abdl-space.top/login")));

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
        if (humanCheckHandle != null) {
            var current = humanCheck.snapshot(humanCheckHandle);
            if (current != null && current.canQuery) { humanCheck.query(humanCheckHandle); renderHumanCheck(); return; }
            if (current != null && current.busy) return;
        }
        humanCheckEmail = email;
        humanCheckLaunched = false;
        humanCheckHandle = humanCheck.begin(email, org.joinmastodon.android.auth.HumanCheckCoordinator.Action.SEND, this::renderHumanCheck);
        if (humanCheckHandle == null) {
            Toast.makeText(getActivity(), R.string.human_check_unknown, Toast.LENGTH_LONG).show();
            return;
        }
        renderHumanCheck();
    }

    private void renderHumanCheck() {
        if (getActivity() == null || getView() == null || humanCheckHandle == null) return;
        var snapshot = humanCheck.snapshot(humanCheckHandle);
        if (snapshot == null) { clearHumanCheck(); updateButtonState(); return; }
        boolean waiting = snapshot.phase == org.joinmastodon.android.auth.HumanCheckCoordinator.Phase.SENDING;
        if (waiting && humanCheckProgress == null) {
            humanCheckProgress = new ProgressDialog(getActivity());
            humanCheckProgress.setMessage(getString(R.string.human_check_sending));
            humanCheckProgress.setCancelable(true);
            humanCheckProgress.setOnCancelListener(dialog -> humanCheck.cancel(humanCheckHandle));
            humanCheckProgress.show();
        } else if (!waiting && humanCheckProgress != null) {
            humanCheckProgress.dismiss(); humanCheckProgress = null;
        }
        emailEdit.setEnabled(!snapshot.busy && !humanCheckLaunched);
        btnSendCode.setEnabled(false);
        if (snapshot.phase == org.joinmastodon.android.auth.HumanCheckCoordinator.Phase.PROMPT && !humanCheckLaunched) {
            humanCheckLaunched = true;
            startActivityForResult(org.joinmastodon.android.HumanCheckActivity.intent(getActivity(), humanCheckHandle), HUMAN_CHECK_RESULT);
        } else if (snapshot.phase == org.joinmastodon.android.auth.HumanCheckCoordinator.Phase.SENT && !humanCheckLaunched) {
            finishHumanCheckSent(humanCheckHandle.generation, humanCheckHandle.owner);
        } else if (snapshot.canQuery) {
            btnSendCode.setText(R.string.human_check_query); btnSendCode.setEnabled(true);
            emailEdit.setEnabled(false);
            Toast.makeText(getActivity(), snapshot.message, Toast.LENGTH_LONG).show();
        } else if (!snapshot.busy && !humanCheckLaunched) {
            Toast.makeText(getActivity(), snapshot.message, Toast.LENGTH_LONG).show();
            clearHumanCheck(); updateButtonState();
        }
    }

    private void finishHumanCheckSent(long generation, long owner) {
        if (!humanCheck.consumeSent(humanCheckHandle, humanCheckEmail, org.joinmastodon.android.auth.HumanCheckCoordinator.Action.SEND, generation, owner)) return;
        String sentEmail = humanCheckEmail;
        clearHumanCheck(); updateButtonState();
        Toast.makeText(getActivity(), R.string.human_check_sent, Toast.LENGTH_SHORT).show();
        Bundle args = new Bundle(); args.putString("email", sentEmail);
        Nav.go(getActivity(), LoginCodeFragment.class, args);
    }

    private void clearHumanCheck() {
        if (humanCheckProgress != null) { humanCheckProgress.dismiss(); humanCheckProgress = null; }
        humanCheckHandle = null; humanCheckEmail = null; humanCheckLaunched = false;
        if (emailEdit != null) emailEdit.setEnabled(true);
        if (btnSendCode != null) btnSendCode.setText(R.string.human_check_send_code);
    }

    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != HUMAN_CHECK_RESULT || humanCheckHandle == null) return;
        if (resultCode == android.app.Activity.RESULT_OK && data != null && data.getExtras() != null
                && data.getExtras().keySet().equals(java.util.Set.of(org.joinmastodon.android.HumanCheckActivity.EXTRA_GENERATION, org.joinmastodon.android.HumanCheckActivity.EXTRA_OWNER))) {
            finishHumanCheckSent(data.getLongExtra(org.joinmastodon.android.HumanCheckActivity.EXTRA_GENERATION, 0), data.getLongExtra(org.joinmastodon.android.HumanCheckActivity.EXTRA_OWNER, 0));
        } else {
            humanCheck.cancel(humanCheckHandle); humanCheckLaunched = false;
            var state = humanCheck.snapshot(humanCheckHandle);
            if (state != null && state.canQuery) renderHumanCheck();
            else { clearHumanCheck(); updateButtonState(); }
        }
    }

    @Override public void onDestroyView() {
        humanCheck.detachEntry(humanCheckHandle);
        clearHumanCheck();
        super.onDestroyView();
    }

    private void updateButtonState() {
        if (btnSendCode != null) {
            var state = humanCheck.snapshot(humanCheckHandle);
            btnSendCode.setEnabled(!humanCheckLaunched && (state == null || !state.busy)
                && cbAgreement.isChecked() && emailEdit.getText().length() > 0);
        }
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void showNBWConsentSheet() {
        if (!cbAgreement.isChecked()) {
            Toast.makeText(getActivity(), R.string.nbw_login_agreement_required, Toast.LENGTH_SHORT).show();
            return;
        }
        showConsentSheet(getString(R.string.nbw_login_title), getString(R.string.nbw_login_consent_message), R.drawable.ic_nbw, false, () -> {
            android.app.Activity activity = getActivity();
            if (activity == null || !cbAgreement.isChecked()) return;
            Intent intent = new Intent(activity, NBWLoginActivity.class);
            intent.putExtra(NBWLoginActivity.EXTRA_PERMISSION_CONFIRMED, true);
            startActivity(intent);
        });
    }

    private void showQQConsentSheet() {
        showConsentSheet(
            getString(R.string.qq_consent_title),
            getString(R.string.qq_consent_message),
            R.drawable.ic_qq_login,
            false,
            () -> {
                android.app.Activity activity = getActivity();
                if (activity == null) return;
                Intent intent = new Intent(activity, QQAuthActivity.class);
                intent.putExtra(QQAuthActivity.EXTRA_MODE, QQAuthActivity.MODE_LOGIN);
                intent.putExtra(QQAuthActivity.EXTRA_PERMISSION_CONFIRMED, true);
                startActivity(intent);
            });
    }

    private void showConsentSheet(Runnable onConfirm) {
        showConsentSheet(getString(R.string.consent_title), getString(R.string.consent_message), R.drawable.ic_description_24, true, onConfirm);
    }

    private void showConsentSheet(String titleText, String messageText, int iconRes, boolean iconTintEnabled, Runnable onConfirm) {
        android.app.Activity activity = getActivity();
        if (activity == null) return;
        View sheetView = LayoutInflater.from(activity).inflate(R.layout.sheet_qr_login, null);
        ImageView iconView = sheetView.findViewById(R.id.icon);
        if (iconView != null) {
            android.content.res.ColorStateList defaultIconTint = iconView.getImageTintList();
            iconView.setImageResource(iconRes);
            iconView.setImageTintList(iconTintEnabled ? defaultIconTint : null);
        }
        me.grishka.appkit.views.BottomSheet sheet = new me.grishka.appkit.views.BottomSheet(activity) {{
            setContentView(sheetView);
            setNavigationBarBackground(new android.graphics.drawable.ColorDrawable(
                UiUtils.alphaBlendColors(
                    UiUtils.getThemeColor(activity, R.attr.colorM3Surface),
                    UiUtils.getThemeColor(activity, R.attr.colorM3Primary), 0.05f)),
                !UiUtils.isDarkTheme());

            TextView title = sheetView.findViewById(R.id.sheet_title);
            TextView sessionInfo = sheetView.findViewById(R.id.qr_session_info);
            title.setText(titleText);
            sessionInfo.setText(android.text.Html.fromHtml(messageText));
            sessionInfo.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());

            TextView authorizeBtn = sheetView.findViewById(R.id.btn_authorize);
            if (authorizeBtn != null) {
                authorizeBtn.setText(R.string.confirm);
            }

            sheetView.findViewById(R.id.btn_cancel).setOnClickListener(v -> dismiss());
            sheetView.findViewById(R.id.btn_authorize).setOnClickListener(v -> {
                dismiss();
                if (isAdded() && !activity.isFinishing() && !activity.isDestroyed()) onConfirm.run();
            });
        }};
        sheet.show();
    }

    private static class SendCodeBody {
        public String email, type;
        public SendCodeBody(String email, String type) { this.email = email; this.type = type; }
    }
}
