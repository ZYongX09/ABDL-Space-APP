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
		TextView agreeText = view.findViewById(R.id.cb_agree);
		final boolean[] agreed = {false};

		agreeText.setOnClickListener(v -> {
			agreed[0] = !agreed[0];
			agreeText.setCompoundDrawablesRelativeWithIntrinsicBounds(
				agreed[0] ? R.drawable.ic_baseline_check_box_24 : R.drawable.ic_baseline_check_box_outline_blank_24,
				0, 0, 0);
			updateButtonState();
		});
		agreeText.setText(android.text.Html.fromHtml(
			"我已阅读并同意<a href=\"https://abdl-space.top/agreement\">《用户协议》</a>和<a href=\"https://abdl-space.top/privacy\">《隐私政策》</a>"));
		agreeText.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());

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
		loginButton.setOnClickListener(v -> attemptLogin());

		if (oauthButton != null) {
			oauthButton.setOnClickListener(v -> {
				if (!agreed[0]) {
					Toast.makeText(getActivity(), "请先同意用户协议和隐私政策", Toast.LENGTH_SHORT).show();
					return;
				}
				showConsentSheet(() -> startOAuthLogin());
			});
		}

		if (nbwButton != null) {
			nbwButton.setOnClickListener(v -> {
				if (!agreed[0]) {
					Toast.makeText(getActivity(), "请先同意用户协议和隐私政策", Toast.LENGTH_SHORT).show();
					return;
				}
				showConsentSheet(() -> {
					Intent intent = new Intent(Intent.ACTION_VIEW,
						Uri.parse("https://api.abdl-space.top/api/auth/nbw/mobile-start"));
					startActivity(intent);
				});
			});
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
		me.grishka.appkit.views.BottomSheet sheet = new me.grishka.appkit.views.BottomSheet(activity) {{
			setContentView(sheetView);
			setNavigationBarBackground(new android.graphics.drawable.ColorDrawable(
				UiUtils.alphaBlendColors(
					UiUtils.getThemeColor(activity, R.attr.colorM3Surface),
					UiUtils.getThemeColor(activity, R.attr.colorM3Primary), 0.05f)),
				!UiUtils.isDarkTheme());

			TextView title = sheetView.findViewById(R.id.sheet_title);
			TextView sessionInfo = sheetView.findViewById(R.id.qr_session_info);
			title.setText("授权登录确认");
			sessionInfo.setText(android.text.Html.fromHtml(
				"点击「授权」后，将跳转浏览器进行授权登录。\n\n" +
				"授权即表示您同意<a href=\"https://abdl-space.top/agreement\">《用户协议》</a>" +
				"和<a href=\"https://abdl-space.top/privacy\">《隐私政策》</a>。"));
			sessionInfo.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());

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
