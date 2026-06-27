package org.joinmastodon.android.ui.sheets;

import android.app.ProgressDialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;

import org.joinmastodon.android.R;
import org.joinmastodon.android.LanDiscoveryService;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.io.IOException;

import androidx.annotation.NonNull;
import me.grishka.appkit.views.BottomSheet;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 登录授权底部弹窗（支持 QR 和 LAN 两种模式）
 */
public class QRLoginBottomSheet extends BottomSheet {
	private static final OkHttpClient httpClient = new OkHttpClient();
	private static final String API_BASE = "https://api.abdl-space.top";
	private final String sessionId;
	private final boolean isLanMode;

	public QRLoginBottomSheet(@NonNull Context context, String sessionId, boolean isLanMode){
		super(context);
		this.sessionId = sessionId;
		this.isLanMode = isLanMode;

		View content = context.getSystemService(LayoutInflater.class).inflate(R.layout.sheet_qr_login, null);
		setContentView(content);
		setNavigationBarBackground(new ColorDrawable(UiUtils.alphaBlendColors(
			UiUtils.getThemeColor(context, R.attr.colorM3Surface),
			UiUtils.getThemeColor(context, R.attr.colorM3Primary), 0.05f)), !UiUtils.isDarkTheme());

		TextView title = content.findViewById(R.id.sheet_title);
		TextView sessionInfo = content.findViewById(R.id.qr_session_info);
		AccountSession session = AccountSessionManager.getInstance().getLastActiveAccount();

		if(isLanMode){
			title.setText("内网设备登录确认");
			if(session != null && session.self != null){
				sessionInfo.setText("用户 " + session.self.username + " 通过内网设备请求登录电脑端\n\n点击「授权」后，电脑端将自动完成登录。");
			}
			// LAN 模式不需要 scan，直接等待授权
		}else{
			title.setText("扫码登录确认");
			if(session != null && session.self != null){
				sessionInfo.setText("用户 " + session.self.username + " 请求登录电脑端\n\n点击「授权」后，电脑端将自动完成登录。");
			}
			// QR 模式需要 scan
			scanSession();
		}

		content.findViewById(R.id.btn_cancel).setOnClickListener(v -> {
			dismiss();
		});
		content.findViewById(R.id.btn_authorize).setOnClickListener(v -> authorize());
	}

	// 兼容旧的两参数构造函数（QR 模式）
	public QRLoginBottomSheet(@NonNull Context context, String sessionId){
		this(context, sessionId, false);
	}

	private void scanSession(){
		AccountSession session = AccountSessionManager.getInstance().getLastActiveAccount();
		if(session == null || !session.activated) return;

		String json = new Gson().toJson(new SessionRequest(sessionId));
		httpClient.newCall(new Request.Builder()
				.url(API_BASE + "/api/auth/qr/scan")
				.post(RequestBody.create(MediaType.parse("application/json"), json))
				.header("Authorization", "Bearer " + session.token.accessToken)
				.build())
			.enqueue(new okhttp3.Callback(){
				@Override
				public void onFailure(Call call, IOException e){
					Log.e("LoginSheet", "Scan failed: " + e.getMessage());
				}

				@Override
				public void onResponse(Call call, Response response){
					Log.d("LoginSheet", "Scan response: " + response.code());
				}
			});
	}

	private void authorize(){
		AccountSession session = AccountSessionManager.getInstance().getLastActiveAccount();
		if(session == null || !session.activated){
			Toast.makeText(getContext(), "请先登录", Toast.LENGTH_SHORT).show();
			dismiss();
			return;
		}

		ProgressDialog progress = new ProgressDialog(getContext());
		progress.setMessage("正在授权...");
		progress.setCancelable(false);
		progress.show();

		String json = new Gson().toJson(new SessionRequest(sessionId));

		httpClient.newCall(new Request.Builder()
				.url(API_BASE + "/api/auth/qr/authorize")
				.post(RequestBody.create(MediaType.parse("application/json"), json))
				.header("Authorization", "Bearer " + session.token.accessToken)
				.build())
			.enqueue(new okhttp3.Callback(){
				@Override
				public void onFailure(Call call, IOException e){
					getContext().getMainExecutor().execute(() -> {
						try{ progress.dismiss(); }catch(Exception ignored){}
						Toast.makeText(getContext(), "授权失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
					});
				}

				@Override
				public void onResponse(Call call, Response response) throws IOException{
					String body = response.body() != null ? response.body().string() : "";
					getContext().getMainExecutor().execute(() -> {
						try{ progress.dismiss(); }catch(Exception ignored){}
						dismiss();
						if(response.isSuccessful()){
							Toast.makeText(getContext(), "授权成功！电脑端已登录", Toast.LENGTH_SHORT).show();
						}else{
							Toast.makeText(getContext(), "授权失败: " + body, Toast.LENGTH_SHORT).show();
						}
					});
				}
			});
	}

	private static class SessionRequest{
		public String sessionId;
		public SessionRequest(String sessionId){
			this.sessionId = sessionId;
		}
	}
}
