package org.joinmastodon.android.chat;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class ChatRealtimeClient {
	private static final String TAG = "ChatRealtimeClient";
	private static final long RECONNECT_MIN_MS = 1000;
	private static final long RECONNECT_MAX_MS = 30000;
	private static final long HEARTBEAT_MS = 25000;

	private final String accountId;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final OkHttpClient client = new OkHttpClient.Builder()
			.readTimeout(0, TimeUnit.MILLISECONDS)
			.build();

	private WebSocket ws;
	private long reconnectDelay = RECONNECT_MIN_MS;
	private boolean stopped;
	private Runnable heartbeatRunnable;
	private long syncBoundary;
	private boolean syncing;

	public ChatRealtimeClient(String accountId) {
		this.accountId = accountId;
	}

	public void connect() {
		if (stopped) return;
		AccountSession session = AccountSessionManager.getInstance().getAccount(accountId);
		if (session == null || session.token == null) return;

		String domain = session.domain;
		String token = session.token.accessToken;
		if (token == null || token.isEmpty()) return;

		String url = "wss://" + domain + "/api/ws";

		Request request = new Request.Builder()
				.url(url)
				.addHeader("Authorization", "Bearer " + token)
				.addHeader("X-Device-Id", getDeviceId())
				.build();

		ws = client.newWebSocket(request, new WebSocketListener() {
			@Override public void onOpen(WebSocket webSocket, Response response) {
				Log.d(TAG, "WS connected for " + accountId);
				reconnectDelay = RECONNECT_MIN_MS;
				startHeartbeat();
			}

			@Override public void onMessage(WebSocket webSocket, String text) {
				handleMessage(text);
			}

			@Override public void onMessage(WebSocket webSocket, ByteString bytes) {
				// ignore binary
			}

			@Override public void onClosing(WebSocket webSocket, int code, String reason) {
				webSocket.close(1000, null);
				scheduleReconnect();
			}

			@Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
				Log.w(TAG, "WS failure: " + t.getMessage());
				scheduleReconnect();
			}
		});
	}

	public void disconnect() {
		stopped = true;
		stopHeartbeat();
		if (ws != null) {
			try { ws.close(1000, "disconnect"); } catch (Exception ignored) {}
			ws = null;
		}
	}

	public void reconnect() {
		disconnect();
		stopped = false;
		reconnectDelay = RECONNECT_MIN_MS;
		connect();
	}

	private void handleMessage(String text) {
		try {
			JsonObject event = JsonParser.parseString(text).getAsJsonObject();
			String type = event.has("type") ? event.get("type").getAsString() : "";

			if ("sync.ready".equals(type)) {
				syncBoundary = event.has("sync_boundary") ? event.get("sync_boundary").getAsLong() : 0;
				// 开始同步到 boundary
				syncToBoundary();
				return;
			}

			if ("sync.required".equals(type)) {
				syncToBoundary();
				return;
			}

			// 交给 ChatController 处理
			ChatController controller = ChatController.getInstance(accountId);
			controller.applyWsEvent(event);
		} catch (Exception e) {
			Log.w(TAG, "Failed to parse WS message: " + e.getMessage());
		}
	}

	private void syncToBoundary() {
		if (syncing) return;
		syncing = true;
		ChatController controller = ChatController.getInstance(accountId);
		long afterEventId = controller.getStorage().getLastEventId(accountId);
		controller.syncAfter(afterEventId, syncBoundary, () -> {
			syncing = false;
			Log.d(TAG, "Sync complete to boundary " + syncBoundary);
		});
	}

	private void scheduleReconnect() {
		if (stopped) return;
		handler.postDelayed(() -> {
			if (!stopped) connect();
		}, reconnectDelay);
		reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_MAX_MS);
	}

	private void startHeartbeat() {
		stopHeartbeat();
		heartbeatRunnable = () -> {
			if (ws != null) {
				ws.send("ping");
			}
			if (!stopped) {
				handler.postDelayed(heartbeatRunnable, HEARTBEAT_MS);
			}
		};
		handler.postDelayed(heartbeatRunnable, HEARTBEAT_MS);
	}

	private void stopHeartbeat() {
		if (heartbeatRunnable != null) {
			handler.removeCallbacks(heartbeatRunnable);
			heartbeatRunnable = null;
		}
	}

	private String getDeviceId() {
		try {
			android.content.SharedPreferences prefs = org.joinmastodon.android.MastodonApp.context
					.getSharedPreferences("chat_device", android.content.Context.MODE_PRIVATE);
			String id = prefs.getString("device_id", null);
			if (id == null) {
				id = java.util.UUID.randomUUID().toString();
				prefs.edit().putString("device_id", id).apply();
			}
			return id;
		} catch (Exception e) {
			return "unknown";
		}
	}
}
