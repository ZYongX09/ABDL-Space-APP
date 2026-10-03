package org.joinmastodon.android.chat;

import android.util.Log;

import org.joinmastodon.android.E;
import org.joinmastodon.android.api.requests.chat.SendChatMessage;
import org.joinmastodon.android.api.requests.chat.SendTyping;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.chat.model.ChatMessage;
import org.joinmastodon.android.chat.model.SendState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;

public class MessageSendHelper {
	private static final String TAG = "MessageSendHelper";
	private static final Map<String, MessageSendHelper> instances = new HashMap<>();
	private final String accountId;
	private final ChatStorage storage;
	private long lastTypingSent;

	public static synchronized MessageSendHelper getInstance(String accountId) {
		MessageSendHelper h = instances.get(accountId);
		if (h == null) {
			h = new MessageSendHelper(accountId);
			instances.put(accountId, h);
		}
		return h;
	}

	public static synchronized void destroy(String accountId) {
		instances.remove(accountId);
	}

	private MessageSendHelper(String accountId) {
		this.accountId = accountId;
		this.storage = ChatStorage.getInstance(org.joinmastodon.android.MastodonApp.context);
	}

	public void sendText(long peerId, String content) {
		AccountSession session = AccountSessionManager.getInstance().getAccount(accountId);
		if (session == null || session.self == null) return;

		ChatMessage msg = new ChatMessage();
		msg.accountId = accountId;
		msg.tempId = System.nanoTime();
		msg.clientMsgId = UUID.randomUUID().toString();
		msg.peerId = peerId;
		msg.senderId = session.self.id != null ? Long.parseLong(session.self.id) : 0;
		msg.content = content;
		msg.createdAt = System.currentTimeMillis();
		msg.out = true;
		msg.sendState = SendState.SENDING;

		long localId = storage.insertMessage(accountId, msg);
		msg.tempId = localId;
		E.post(new ChatEvents.NewChatMessageEvent(msg));

		new SendChatMessage(peerId, content, msg.clientMsgId).setCallback(new Callback<Map<String, Object>>() {
			@Override public void onSuccess(Map<String, Object> result) {
				long serverId = toLong(result.get("id"));
				long eventId = result.containsKey("event_id") ? toLong(result.get("event_id")) : 0;
				if (serverId > 0) {
					storage.mapTempToServer(accountId, localId, serverId, eventId);
					msg.id = serverId;
					msg.eventId = eventId;
					msg.sendState = SendState.SENT;
				}
				Object msgObj = result.get("message");
				if (msgObj instanceof Map) {
					@SuppressWarnings("unchecked")
					Map<String, Object> m = (Map<String, Object>) msgObj;
					long sid = toLong(m.get("id"));
					if (sid > 0) {
						msg.id = sid;
						msg.sendState = SendState.SENT;
						storage.mapTempToServer(accountId, localId, sid, eventId);
					}
				}
				E.post(new ChatEvents.MessageSendStateEvent(msg));
				E.post(new ChatEvents.ConversationsUpdatedEvent());
			}
			@Override public void onError(ErrorResponse error) {
				Log.w(TAG, "send failed: " + error);
				msg.sendState = SendState.FAILED;
				storage.updateSendState(accountId, localId, SendState.FAILED);
				E.post(new ChatEvents.MessageSendStateEvent(msg));
			}
		}).exec(accountId);
	}

	public void retry(ChatMessage failed) {
		if (failed.sendState != SendState.FAILED) return;
		failed.sendState = SendState.SENDING;
		storage.updateSendState(accountId, failed.tempId, SendState.SENDING);
		E.post(new ChatEvents.MessageSendStateEvent(failed));

		new SendChatMessage(failed.peerId, failed.content, failed.clientMsgId).setCallback(new Callback<Map<String, Object>>() {
			@Override public void onSuccess(Map<String, Object> result) {
				long serverId = toLong(result.get("id"));
				long eventId = result.containsKey("event_id") ? toLong(result.get("event_id")) : 0;
				if (serverId > 0) {
					storage.mapTempToServer(accountId, failed.tempId, serverId, eventId);
					failed.id = serverId;
					failed.eventId = eventId;
					failed.sendState = SendState.SENT;
				}
				E.post(new ChatEvents.MessageSendStateEvent(failed));
				E.post(new ChatEvents.ConversationsUpdatedEvent());
			}
			@Override public void onError(ErrorResponse error) {
				failed.sendState = SendState.FAILED;
				storage.updateSendState(accountId, failed.tempId, SendState.FAILED);
				E.post(new ChatEvents.MessageSendStateEvent(failed));
			}
		}).exec(accountId);
	}

	public void sendTyping(long peerId) {
		long now = System.currentTimeMillis();
		if (now - lastTypingSent < 3000) return;
		lastTypingSent = now;
		new SendTyping(peerId).setCallback(new Callback<Map<String, Object>>() {
			@Override public void onSuccess(Map<String, Object> result) {}
			@Override public void onError(ErrorResponse error) {}
		}).exec(accountId);
	}

	private static long toLong(Object o) {
		if (o instanceof Number n) return n.longValue();
		try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return 0; }
	}
}
