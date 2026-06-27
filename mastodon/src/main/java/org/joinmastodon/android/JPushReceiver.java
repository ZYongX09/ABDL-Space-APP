package org.joinmastodon.android;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;

import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;

import java.io.IOException;

import cn.jpush.android.api.CustomMessage;
import cn.jpush.android.api.NotificationMessage;
import cn.jpush.android.service.JPushMessageReceiver;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 极光推送消息接收器
 */
public class JPushReceiver extends JPushMessageReceiver {
	private static final String TAG = "JPushReceiver";
	private static final String API_BASE = "https://api.abdl-space.top";
	private static final OkHttpClient httpClient = new OkHttpClient();

	@Override
	public void onRegister(Context context, String regId) {
		Log.d(TAG, "onRegister: " + regId);
		if (regId == null || regId.isEmpty()) return;

		// 保存 regId
		context.getSharedPreferences("jpush", Context.MODE_PRIVATE)
			.edit()
			.putString("regId", regId)
			.apply();

		// 注册到后端
		registerWithBackend(regId);
	}

	private void registerWithBackend(String regId) {
		AccountSession session = AccountSessionManager.getInstance().getLastActiveAccount();
		if (session == null || !session.activated) {
			Log.w(TAG, "No active session, skipping registration");
			return;
		}

		String json = new Gson().toJson(new RegisterRequest(regId));
		httpClient.newCall(new Request.Builder()
				.url(API_BASE + "/api/jpush/register")
				.post(RequestBody.create(MediaType.parse("application/json"), json))
				.header("Authorization", "Bearer " + session.token.accessToken)
				.build())
			.enqueue(new okhttp3.Callback() {
				@Override
				public void onFailure(Call call, IOException e) {
					Log.e(TAG, "Register failed: " + e.getMessage());
				}

				@Override
				public void onResponse(Call call, Response response) throws IOException {
					String body = response.body() != null ? response.body().string() : "";
					Log.d(TAG, "Register response: " + response.code() + " " + body);
				}
			});
	}

	@Override
	public void onMessage(Context context, CustomMessage message) {
		Log.d(TAG, "onMessage: " + message);
	}

	@Override
	public void onNotifyMessageArrived(Context context, NotificationMessage message) {
		Log.d(TAG, "onNotifyMessageArrived: " + message);
	}

	@Override
	public void onNotifyMessageOpened(Context context, NotificationMessage message) {
		Log.d(TAG, "onNotifyMessageOpened: " + message);
	}

	@Override
	public void onConnected(Context context, boolean connected) {
		Log.d(TAG, "onConnected: " + connected);
		if (connected) {
			// 连接成功后尝试获取 regId
			String regId = context.getSharedPreferences("jpush", Context.MODE_PRIVATE)
				.getString("regId", null);
			if (regId != null) {
				registerWithBackend(regId);
			}
		}
	}

	private static class RegisterRequest {
		public String regId;
		public RegisterRequest(String regId) {
			this.regId = regId;
		}
	}
}
