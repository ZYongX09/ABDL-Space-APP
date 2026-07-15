package org.joinmastodon.android;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.google.gson.Gson;

import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.chat.ui.ConversationsFragment;

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

		context.getSharedPreferences("jpush", Context.MODE_PRIVATE)
			.edit()
			.putString("regId", regId)
			.apply();

		uploadSavedRegId(context);
	}

	/**
	 * 登录成功后调用，上传已保存的 regId 到后端
	 */
	public static void uploadSavedRegId(Context context) {
		String regId = context.getSharedPreferences("jpush", Context.MODE_PRIVATE)
			.getString("regId", null);
		if (regId == null || regId.isEmpty()) return;

		AccountSession session = AccountSessionManager.getInstance().getLastActiveAccount();
		if (session == null || !session.activated) return;

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
		// 前台抑制：检查是否正在查看同一会话
		if (shouldSuppressNotification(message)) {
			// 取消通知
			NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
			if (nm != null) {
				nm.cancel(message.notificationId);
			}
			Log.d(TAG, "Suppressed foreground notification for message");
		}
	}

	@Override
	public void onNotifyMessageOpened(Context context, NotificationMessage message) {
		Log.d(TAG, "onNotifyMessageOpened: " + message);
		// 解析 payload 并导航到对应会话
		handleNotificationOpen(context, message);
	}

	@Override
	public void onConnected(Context context, boolean connected) {
		Log.d(TAG, "onConnected: " + connected);
		if (connected) {
			uploadSavedRegId(context);
		}
	}

	/**
	 * 前台抑制逻辑：检查是否正在查看同一账号的同一会话
	 */
	private boolean shouldSuppressNotification(NotificationMessage message) {
		try {
			String extras = message.notificationExtras;
			if (extras == null || extras.isEmpty()) return false;

			com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(extras).getAsJsonObject();
			String type = json.has("type") ? json.get("type").getAsString() : null;
			if (!"message".equals(type)) return false;

			String accountId = json.has("account_id") ? json.get("account_id").getAsString() : null;
			String peerId = json.has("peer_id") ? json.get("peer_id").getAsString() : null;

			if (accountId == null || peerId == null) return false;

			// 检查当前活跃账号是否匹配
			AccountSession currentSession = AccountSessionManager.getInstance().getLastActiveAccount();
			if (currentSession == null) return false;

			String currentAccountId = currentSession.getID();
			if (!accountId.equals(currentAccountId)) return false;

			// TODO: 检查当前是否正在查看该会话
			return false;
		} catch (Exception e) {
			Log.e(TAG, "Error checking suppress: " + e.getMessage());
			return false;
		}
	}

	/**
	 * 处理通知点击：导航到对应会话
	 */
	private void handleNotificationOpen(Context context, NotificationMessage message) {
		try {
			String extras = message.notificationExtras;
			if (extras == null || extras.isEmpty()) {
				openConversations(context);
				return;
			}

			com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(extras).getAsJsonObject();
			String type = json.has("type") ? json.get("type").getAsString() : null;
			String accountId = json.has("account_id") ? json.get("account_id").getAsString() : null;
			String peerId = json.has("peer_id") ? json.get("peer_id").getAsString() : null;
			String messageId = json.has("message_id") ? json.get("message_id").getAsString() : null;

			if (!"message".equals(type) || accountId == null || peerId == null) {
				openConversations(context);
				return;
			}

			// 切换到对应账号
			AccountSession targetSession = AccountSessionManager.getInstance().getAccount(accountId);
			if (targetSession == null) {
				Log.w(TAG, "Account not found: " + accountId);
				openConversations(context);
				return;
			}

			// 启动 MainActivity 并导航到聊天页
			Intent intent = new Intent(context, MainActivity.class);
			intent.putExtra("navigate_to", "chat");
			intent.putExtra("account_id", accountId);
			intent.putExtra("peer_id", Long.parseLong(peerId));
			intent.putExtra("message_id", messageId != null ? Long.parseLong(messageId) : 0);
			intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
			context.startActivity(intent);

		} catch (Exception e) {
			Log.e(TAG, "Error handling notification: " + e.getMessage());
			openConversations(context);
		}
	}

	private void openConversations(Context context) {
		Intent intent = new Intent(context, MainActivity.class);
		intent.putExtra("navigate_to", "conversations");
		intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
		context.startActivity(intent);
	}

	private static class RegisterRequest {
		public String regId;
		public RegisterRequest(String regId) {
			this.regId = regId;
		}
	}
}
