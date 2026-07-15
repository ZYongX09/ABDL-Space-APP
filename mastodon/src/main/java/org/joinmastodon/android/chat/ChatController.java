package org.joinmastodon.android.chat;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.joinmastodon.android.E;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.requests.chat.GetConversations;
import org.joinmastodon.android.api.requests.chat.GetChatMessages;
import org.joinmastodon.android.api.requests.chat.MarkChatRead;
import org.joinmastodon.android.api.requests.chat.SyncChatEvents;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.chat.model.ChatMessage;
import org.joinmastodon.android.chat.model.Conversation;
import org.joinmastodon.android.chat.model.SendState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;

public class ChatController {
	private static final String TAG = "ChatController";
	private static final Map<String, ChatController> instances = new HashMap<>();
	private final String accountId;
	private final ChatStorage storage;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Gson gson = new Gson();

	public static synchronized ChatController getInstance(String accountId) {
		ChatController c = instances.get(accountId);
		if (c == null) {
			c = new ChatController(accountId);
			instances.put(accountId, c);
		}
		return c;
	}

	public static synchronized void destroy(String accountId) {
		ChatController c = instances.remove(accountId);
		if (c != null) c.handler.removeCallbacksAndMessages(null);
	}

	private ChatController(String accountId) {
		this.accountId = accountId;
		this.storage = ChatStorage.getInstance(MastodonApp.context);
	}

	public ChatStorage getStorage() { return storage; }

	public void loadConversations(boolean forceNetwork, Callback<List<Conversation>> callback) {
		List<Conversation> cached = storage.listConversations(accountId);
		if (!cached.isEmpty() && !forceNetwork) {
			callback.onSuccess(cached);
			return;
		}
		new GetConversations().setCallback(new Callback<Map<String, Object>>() {
			@Override public void onSuccess(Map<String, Object> result) {
				List<Conversation> convos = new ArrayList<>();
				Object convosObj = result.get("conversations");
				if (convosObj instanceof List) {
					List<?> list = (List<?>) convosObj;
					for (Object item : list) {
						if (item instanceof Map) {
							@SuppressWarnings("unchecked")
							Map<String, Object> m = (Map<String, Object>) item;
							Conversation c = new Conversation();
							c.accountId = accountId;
							c.peerId = toLong(m.get("user_id"));
							c.username = str(m.get("username"));
							c.avatar = str(m.get("avatar"));
							c.lastMessage = str(m.get("last_message"));
							c.lastMessageAt = toLong(m.get("last_message_at"));
							c.lastMessageId = toLong(m.get("last_message_id"));
							c.unreadCount = toInt(m.get("unread_count"));
							convos.add(c);
							storage.upsertConversation(accountId, c);
						}
					}
				}
				callback.onSuccess(convos);
			}
			@Override public void onError(ErrorResponse error) { callback.onError(error); }
		}).exec(accountId);
	}

	public void loadMessages(long peerId, long beforeId, int limit, Callback<List<ChatMessage>> callback) {
		new GetChatMessages(peerId, beforeId, limit).setCallback(new Callback<Map<String, Object>>() {
			@Override public void onSuccess(Map<String, Object> result) {
				List<ChatMessage> msgs = new ArrayList<>();
				Object msgsObj = result.get("messages");
				if (msgsObj instanceof List) {
					List<?> list = (List<?>) msgsObj;
					for (Object item : list) {
						if (item instanceof Map) {
							@SuppressWarnings("unchecked")
							Map<String, Object> m = (Map<String, Object>) item;
							ChatMessage msg = new ChatMessage();
							msg.accountId = accountId;
							msg.id = toLong(m.get("id"));
							msg.senderId = toLong(m.get("sender_id"));
							msg.peerId = peerId;
							msg.content = str(m.get("content"));
							msg.clientMsgId = str(m.get("client_msg_id"));
							msg.createdAt = parseTime(m.get("created_at"));
							msg.out = msg.senderId == getCurrentUserId();
							msg.sendState = msg.out && toBool(m.get("read")) ? SendState.READ : (msg.id > 0 ? SendState.SENT : null);
							msgs.add(msg);
							storage.insertMessage(accountId, msg);
						}
					}
				}
				callback.onSuccess(msgs);
			}
			@Override public void onError(ErrorResponse error) { callback.onError(error); }
		}).exec(accountId);
	}

	public void markRead(long peerId, long readUpToId) {
		storage.markReadByServerId(accountId, peerId, readUpToId);
		new MarkChatRead(peerId, readUpToId).setCallback(new Callback<Map<String, Object>>() {
			@Override public void onSuccess(Map<String, Object> result) {}
			@Override public void onError(ErrorResponse error) { Log.w(TAG, "markRead failed: " + error); }
		}).exec(accountId);
	}

	public void applyWsEvent(JsonObject event) {
		String type = event.has("type") ? event.get("type").getAsString() : "";
		long eventId = event.has("event_id") ? event.get("event_id").getAsLong() : 0;
		long localCursor = storage.getLastEventId(accountId);
		if (eventId > 0 && eventId <= localCursor) return;

		switch (type) {
			case "message.new" -> {
				JsonElement msgEl = event.get("message");
				JsonObject msgObj = msgEl != null && msgEl.isJsonObject() ? msgEl.getAsJsonObject() : event;
				long senderId = msgObj.has("sender_id") ? msgObj.get("sender_id").getAsLong() : 0;
				long receiverId = msgObj.has("receiver_id") ? msgObj.get("receiver_id").getAsLong() : 0;
				long myId = getCurrentUserId();
				long peerId = senderId == myId ? receiverId : senderId;
				String clientMsgId = msgObj.has("client_msg_id") && !msgObj.get("client_msg_id").isJsonNull()
						? msgObj.get("client_msg_id").getAsString() : null;
				long serverId = msgObj.has("id") ? msgObj.get("id").getAsLong() : 0;

				if (clientMsgId != null) {
					storage.mapTempToServer(accountId, 0, serverId, eventId);
				}

				ChatMessage msg = new ChatMessage();
				msg.accountId = accountId;
				msg.id = serverId;
				msg.senderId = senderId;
				msg.peerId = peerId;
				msg.content = msgObj.has("content") ? msgObj.get("content").getAsString() : "";
				msg.clientMsgId = clientMsgId;
				msg.createdAt = msgObj.has("created_at") ? parseTime(msgObj.get("created_at").getAsString()) : System.currentTimeMillis();
				msg.out = senderId == myId;
				msg.sendState = SendState.SENT;
				msg.eventId = eventId;

				storage.insertMessage(accountId, msg);
				if (eventId > 0) storage.setLastEventId(accountId, eventId);

				handler.post(() -> E.post(new ChatEvents.NewChatMessageEvent(msg)));
			}
			case "message.read" -> {
				final long readPeerId = event.has("peer_id") ? event.get("peer_id").getAsLong() : 0;
				final long readUpToId = event.has("read_up_to_id") ? event.get("read_up_to_id").getAsLong() : 0;
				if (readUpToId > 0) {
					storage.markReadByServerId(accountId, readPeerId, readUpToId);
					if (eventId > 0) storage.setLastEventId(accountId, eventId);
					handler.post(() -> E.post(new ChatEvents.MessageReadEvent(readPeerId, readUpToId)));
				}
			}
			case "typing" -> {
				final long fromUserId = event.has("from_user_id") ? event.get("from_user_id").getAsLong() : 0;
				if (fromUserId > 0) {
					handler.post(() -> E.post(new ChatEvents.TypingEvent(fromUserId)));
				}
			}
		}
	}

	public void syncAfter(long afterEventId, long throughEventId, Runnable onComplete) {
		new SyncChatEvents(afterEventId, throughEventId, 100).setCallback(new Callback<Map<String, Object>>() {
			@Override public void onSuccess(Map<String, Object> result) {
				JsonArray events = result.containsKey("events") ? gson.toJsonTree(result.get("events")).getAsJsonArray() : new JsonArray();
				for (JsonElement el : events) {
					if (el.isJsonObject()) applyWsEvent(el.getAsJsonObject());
				}
				boolean hasMore = false;
				if (result.containsKey("has_more") && result.get("has_more") instanceof Boolean) {
					hasMore = (Boolean) result.get("has_more");
				}
				long nextEventId = afterEventId + 1;
				if (result.containsKey("next_event_id")) {
					nextEventId = toLong(result.get("next_event_id"));
				}
				if (hasMore && nextEventId > afterEventId) {
					syncAfter(nextEventId, throughEventId, onComplete);
				} else {
					if (onComplete != null) onComplete.run();
				}
			}
			@Override public void onError(ErrorResponse error) {
				Log.w(TAG, "sync failed: " + error);
				if (onComplete != null) onComplete.run();
			}
		}).exec(accountId);
	}

	private long getCurrentUserId() {
		AccountSession session = AccountSessionManager.getInstance().getAccount(accountId);
		return session != null && session.self != null && session.self.id != null ? Long.parseLong(session.self.id) : 0;
	}

	private static String str(Object o) { return o != null ? String.valueOf(o) : ""; }
	private static long toLong(Object o) {
		if (o instanceof Number n) return n.longValue();
		try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return 0; }
	}
	private static int toInt(Object o) {
		if (o instanceof Number n) return n.intValue();
		try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 0; }
	}
	private static boolean toBool(Object o) {
		if (o instanceof Boolean b) return b;
		if (o instanceof Number n) return n.intValue() != 0;
		return Boolean.parseBoolean(String.valueOf(o));
	}
	private static long parseTime(Object o) {
		if (o == null) return System.currentTimeMillis();
		String s = String.valueOf(o);
		try {
			if (s.contains("-")) return java.text.SimpleDateFormat.getDateTimeInstance().parse(s).getTime();
			return Long.parseLong(s) * 1000;
		} catch (Exception e) { return System.currentTimeMillis(); }
	}
}
