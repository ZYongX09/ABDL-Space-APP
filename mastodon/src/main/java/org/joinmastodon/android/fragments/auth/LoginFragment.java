package org.joinmastodon.android.fragments.auth;

import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
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
import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.requests.accounts.GetOwnAccount;
import org.joinmastodon.android.api.requests.oauth.CreateOAuthApp;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Application;
import org.joinmastodon.android.model.InstanceV2;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.io.IOException;

import androidx.annotation.Nullable;
import me.grishka.appkit.fragments.ToolbarFragment;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class LoginFragment extends ToolbarFragment {
	private static final OkHttpClient httpClient = new OkHttpClient();

	private EditText loginEdit, passwordEdit;
	private Button loginButton, oauthButton, nbwButton;
	private String domain;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		domain = getArguments() != null ? getArguments().getString("domain", "abdl-space.top") : "abdl-space.top";
		setTitle("登录");
	}

	@Nullable
	@Override
	public View onCreateContentView(LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_login_form, container, false);

		loginEdit = view.findViewById(R.id.login_edit);
		passwordEdit = view.findViewById(R.id.password_edit);
		loginButton = view.findViewById(R.id.btn_login);
		oauthButton = view.findViewById(R.id.btn_oauth);
		nbwButton = view.findViewById(R.id.btn_nbw);

		TextWatcher textWatcher = new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {}
			@Override
			public void afterTextChanged(Editable s) {
				updateButtonState();
			}
		};

		loginEdit.addTextChangedListener(textWatcher);
		passwordEdit.addTextChangedListener(textWatcher);

		// 设置协议文本超链接
		TextView tvAgreement = view.findViewById(R.id.tv_agreement);
		tvAgreement.setText(android.text.Html.fromHtml(
			"在登录前您需要仔细阅读<a href=\"https://abdl-space.top/agreement\">《用户协议》</a>和<a href=\"https://abdl-space.top/privacy\">《隐私政策》</a>"));
		tvAgreement.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());

		loginButton.setOnClickListener(v -> showConsentSheet(this::attemptLogin));

		if (oauthButton != null) {
			oauthButton.setOnClickListener(v -> showConsentSheet(() -> startOAuthLogin()));
		}

		if (nbwButton != null) {
			nbwButton.setOnClickListener(v -> showConsentSheet(() -> {
				// 记录当前是登录流程
				getActivity().getSharedPreferences("nbw_bind", android.content.Context.MODE_PRIVATE).edit().putString("flow", "login").apply();
				Intent intent = new Intent(Intent.ACTION_VIEW,
					Uri.parse("https://api.abdl-space.top/api/auth/nbw/mobile-start"));
				startActivity(intent);
			}));
		}

		return view;
	}

	private void updateButtonState() {
		// 复选框状态通过 agreed 数组跟踪
		loginButton.setEnabled(loginEdit.length() > 0 && passwordEdit.length() > 0);
	}

	private void attemptLogin() {
		String login = loginEdit.getText().toString().trim();
		String password = passwordEdit.getText().toString();
		if (TextUtils.isEmpty(login) || TextUtils.isEmpty(password)) return;

		loginButton.setEnabled(false);

		ProgressDialog progress = new ProgressDialog(getActivity());
		progress.setMessage(getString(R.string.loading));
		progress.setCancelable(false);
		progress.show();

		String url = "https://" + domain + "/api/auth/login";
		RequestBody body = RequestBody.create(
			MediaType.parse("application/json"),
			new Gson().toJson(new LoginBody(login, password))
		);

		httpClient.newCall(new Request.Builder().url(url).post(body).build())
			.enqueue(new okhttp3.Callback() {
				@Override
				public void onFailure(Call call, IOException e) {
					if (getActivity() == null) return;
					getActivity().runOnUiThread(() -> {
						progress.dismiss();
						if (loginButton != null) loginButton.setEnabled(true);
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
											instance.domain = domain;
											instance.title = "ABDL Space";
											instance.description = "ABDL Space";
											instance.version = "4.0.0";

											Application app = new Application();
											app.clientId = "";
											app.clientSecret = "";

											AccountSessionManager.getInstance().addAccount(instance, token, account, app, null);

											getActivity().runOnUiThread(() -> {
												if (getActivity() instanceof MainActivity mainActivity) {
													mainActivity.restartHomeFragment();
												}
											});
										}

										@Override
										public void onError(ErrorResponse error) {
											progress.dismiss();
											if (loginButton != null) loginButton.setEnabled(true);
											if (getActivity() == null) return;
											error.showToast(getActivity());
										}
									})
									.exec(domain, token);
							} else {
								progress.dismiss();
								if (loginButton != null) loginButton.setEnabled(true);
								Toast.makeText(getActivity(), "登录失败: " + responseBody, Toast.LENGTH_SHORT).show();
							}
						} else {
							progress.dismiss();
							if (loginButton != null) loginButton.setEnabled(true);
							Toast.makeText(getActivity(), "登录失败: " + responseBody, Toast.LENGTH_SHORT).show();
						}
					});
				}
			});
	}

	private static class LoginBody {
		public String login;
		public String password;
		public LoginBody(String login, String password) {
			this.login = login;
			this.password = password;
		}
	}

	private static class AuthResponse {
		public String token;
		public UserInfo user;
	}

	private static class UserInfo {
		public int id;
		public String username;
		public String avatar;
		public String role;
	}

	private void showConsentSheet(Runnable onConfirm) {
		android.app.Activity activity = getActivity();
		if (activity == null) return;
		View sheetView = LayoutInflater.from(activity).inflate(R.layout.sheet_qr_login, null);
		ImageView iconView = sheetView.findViewById(R.id.icon);
		if (iconView == null) {
			// sheet_qr_login 的 icon 没有 id，通过父容器的第一个 ImageView 找到
			View header = sheetView.findViewById(R.id.sheet_title);
			if (header != null && header.getParent() instanceof ViewGroup) {
				ViewGroup parent = (ViewGroup) header.getParent();
				for (int i = 0; i < parent.getChildCount(); i++) {
					if (parent.getChildAt(i) instanceof ImageView) {
						iconView = (ImageView) parent.getChildAt(i);
						break;
					}
				}
			}
		}
		if (iconView != null) {
			iconView.setImageResource(R.drawable.ic_description_24);
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
			title.setText("确认同意协议");
			sessionInfo.setText(android.text.Html.fromHtml(
				"登录前请仔细阅读<a href=\"https://abdl-space.top/agreement\">《用户协议》</a>" +
				"和<a href=\"https://abdl-space.top/privacy\">《隐私政策》</a>，若您同意以上协议请点击确认按钮。"));
			sessionInfo.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());

			// 改"授权"按钮为"确认"
			TextView authorizeBtn = sheetView.findViewById(R.id.btn_authorize);
			if (authorizeBtn != null) {
				authorizeBtn.setText("确认");
			}

			sheetView.findViewById(R.id.btn_cancel).setOnClickListener(v -> dismiss());
			sheetView.findViewById(R.id.btn_authorize).setOnClickListener(v -> {
				dismiss();
				onConfirm.run();
			});
		}};
		sheet.show();
	}

	private void startOAuthLogin() {
		if (getActivity() == null) return;

		ProgressDialog progress = new ProgressDialog(getActivity());
		progress.setMessage("正在准备授权...");
		progress.setCancelable(false);
		progress.show();

		InstanceV2 instance = new InstanceV2();
		instance.domain = domain;
		instance.title = "ABDL Space";
		instance.description = "ABDL Space";
		instance.version = "4.2.0";

		new CreateOAuthApp()
			.setCallback(new Callback<Application>() {
				@Override
				public void onSuccess(Application app) {
					if (getActivity() == null) return;
					progress.dismiss();
					AccountSessionManager.getInstance().authenticate(getActivity(), instance, app);
				}

				@Override
				public void onError(ErrorResponse error) {
					if (getActivity() == null) return;
					progress.dismiss();
					error.showToast(getActivity());
				}
			})
			.execNoAuth(domain);
	}
}
