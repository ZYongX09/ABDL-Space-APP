package org.joinmastodon.android.chat;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.joinmastodon.android.chat.model.ChatMessage;
import org.joinmastodon.android.chat.model.Conversation;
import org.joinmastodon.android.chat.model.SendState;

import java.util.ArrayList;
import java.util.List;

public class ChatStorage extends SQLiteOpenHelper {
	private static final String DB_NAME = "chat.db";
	private static final int DB_VERSION = 1;

	private static ChatStorage instance;

	public static synchronized ChatStorage getInstance(Context context) {
		if (instance == null) {
			instance = new ChatStorage(context.getApplicationContext());
		}
		return instance;
	}

	private ChatStorage(Context context) {
		super(context, DB_NAME, null, DB_VERSION);
	}

	@Override
	public void onCreate(SQLiteDatabase db) {
		db.execSQL("CREATE TABLE chat_conversations ("
				+ "account_id TEXT NOT NULL,"
				+ "peer_id INTEGER NOT NULL,"
				+ "username TEXT,"
				+ "avatar TEXT,"
				+ "last_message TEXT,"
				+ "last_message_at INTEGER,"
				+ "last_message_id INTEGER,"
				+ "unread_count INTEGER DEFAULT 0,"
				+ "draft TEXT,"
				+ "last_out_state TEXT,"
				+ "read_outbox_max_id INTEGER DEFAULT 0,"
				+ "PRIMARY KEY(account_id, peer_id))");

		db.execSQL("CREATE TABLE chat_messages ("
				+ "local_id INTEGER PRIMARY KEY AUTOINCREMENT,"
				+ "account_id TEXT NOT NULL,"
				+ "server_id INTEGER,"
				+ "temp_id INTEGER,"
				+ "client_msg_id TEXT,"
				+ "peer_id INTEGER NOT NULL,"
				+ "sender_id INTEGER NOT NULL,"
				+ "content TEXT NOT NULL,"
				+ "created_at INTEGER NOT NULL,"
				+ "is_out INTEGER NOT NULL DEFAULT 0,"
				+ "send_state TEXT,"
				+ "event_id INTEGER)");

		db.execSQL("CREATE UNIQUE INDEX idx_chat_msg_server ON chat_messages(account_id, server_id) WHERE server_id IS NOT NULL AND server_id != 0");
		db.execSQL("CREATE UNIQUE INDEX idx_chat_msg_client ON chat_messages(account_id, client_msg_id) WHERE client_msg_id IS NOT NULL");
		db.execSQL("CREATE INDEX idx_chat_msg_peer ON chat_messages(account_id, peer_id, created_at)");

		db.execSQL("CREATE TABLE chat_drafts ("
				+ "account_id TEXT NOT NULL,"
				+ "peer_id INTEGER NOT NULL,"
				+ "content TEXT,"
				+ "PRIMARY KEY(account_id, peer_id))");

		db.execSQL("CREATE TABLE chat_sync_state ("
				+ "account_id TEXT PRIMARY KEY,"
				+ "last_event_id INTEGER DEFAULT 0)");
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		// v1: initial
	}

	// === Conversations ===

	public void upsertConversation(String accountId, Conversation c) {
		ContentValues cv = new ContentValues();
		cv.put("account_id", accountId);
		cv.put("peer_id", c.peerId);
		cv.put("username", c.username);
		cv.put("avatar", c.avatar);
		cv.put("last_message", c.lastMessage);
		cv.put("last_message_at", c.lastMessageAt);
		cv.put("last_message_id", c.lastMessageId);
		cv.put("unread_count", c.unreadCount);
		cv.put("draft", c.draft);
		cv.put("last_out_state", c.lastOutState != null ? c.lastOutState.name() : null);
		cv.put("read_outbox_max_id", c.readOutboxMaxId);
		getWritableDatabase().insertWithOnConflict("chat_conversations", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
	}

	public List<Conversation> listConversations(String accountId) {
		List<Conversation> result = new ArrayList<>();
		Cursor cursor = getReadableDatabase().query("chat_conversations",
				null, "account_id = ?", new String[]{accountId}, null, null, "last_message_at DESC, last_message_id DESC");
		while (cursor.moveToNext()) {
			result.add(cursorToConversation(cursor));
		}
		cursor.close();
		return result;
	}

	public void setDraft(String accountId, long peerId, String content) {
		ContentValues cv = new ContentValues();
		cv.put("account_id", accountId);
		cv.put("peer_id", peerId);
		cv.put("content", content);
		getWritableDatabase().insertWithOnConflict("chat_drafts", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
	}

	public String getDraft(String accountId, long peerId) {
		Cursor cursor = getReadableDatabase().query("chat_drafts", new String[]{"content"},
				"account_id = ? AND peer_id = ?", new String[]{accountId, String.valueOf(peerId)}, null, null, null);
		String result = null;
		if (cursor.moveToFirst()) {
			result = cursor.getString(0);
		}
		cursor.close();
		return result;
	}

	// === Messages ===

	public long insertMessage(String accountId, ChatMessage msg) {
		ContentValues cv = new ContentValues();
		cv.put("account_id", accountId);
		cv.put("server_id", msg.id);
		cv.put("temp_id", msg.tempId);
		cv.put("client_msg_id", msg.clientMsgId);
		cv.put("peer_id", msg.peerId);
		cv.put("sender_id", msg.senderId);
		cv.put("content", msg.content);
		cv.put("created_at", msg.createdAt);
		cv.put("is_out", msg.out ? 1 : 0);
		cv.put("send_state", msg.sendState != null ? msg.sendState.name() : null);
		cv.put("event_id", msg.eventId);
		return getWritableDatabase().insertWithOnConflict("chat_messages", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
	}

	public List<ChatMessage> getMessages(String accountId, long peerId, int limit) {
		List<ChatMessage> result = new ArrayList<>();
		Cursor cursor = getReadableDatabase().query("chat_messages", null,
				"account_id = ? AND peer_id = ?", new String[]{accountId, String.valueOf(peerId)},
				null, null, "created_at DESC, local_id DESC", String.valueOf(limit));
		while (cursor.moveToNext()) {
			result.add(cursorToMessage(cursor));
		}
		cursor.close();
		// reverse to ASC
		List<ChatMessage> reversed = new ArrayList<>();
		for (int i = result.size() - 1; i >= 0; i--) {
			reversed.add(result.get(i));
		}
		return reversed;
	}

	public void mapTempToServer(String accountId, long tempId, long serverId, long eventId) {
		ContentValues cv = new ContentValues();
		cv.put("server_id", serverId);
		cv.put("event_id", eventId);
		cv.put("send_state", SendState.SENT.name());
		getWritableDatabase().update("chat_messages", cv,
				"account_id = ? AND temp_id = ?", new String[]{accountId, String.valueOf(tempId)});
	}

	public void updateSendState(String accountId, long localId, SendState state) {
		ContentValues cv = new ContentValues();
		cv.put("send_state", state.name());
		getWritableDatabase().update("chat_messages", cv,
				"account_id = ? AND local_id = ?", new String[]{accountId, String.valueOf(localId)});
	}

	public void markReadByServerId(String accountId, long peerId, long readUpToId) {
		ContentValues cv = new ContentValues();
		cv.put("send_state", SendState.READ.name());
		getWritableDatabase().update("chat_messages", cv,
				"account_id = ? AND peer_id = ? AND is_out = 1 AND server_id > 0 AND server_id <= ? AND send_state != ?",
				new String[]{accountId, String.valueOf(peerId), String.valueOf(readUpToId), SendState.READ.name()});
	}

	// === Sync state ===

	public long getLastEventId(String accountId) {
		Cursor cursor = getReadableDatabase().query("chat_sync_state", new String[]{"last_event_id"},
				"account_id = ?", new String[]{accountId}, null, null, null);
		long result = 0;
		if (cursor.moveToFirst()) {
			result = cursor.getLong(0);
		}
		cursor.close();
		return result;
	}

	public void setLastEventId(String accountId, long eventId) {
		ContentValues cv = new ContentValues();
		cv.put("account_id", accountId);
		cv.put("last_event_id", eventId);
		getWritableDatabase().insertWithOnConflict("chat_sync_state", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
	}

	public void applyEventsAndAdvanceCursor(String accountId, List<ChatMessage> newMessages, long newEventId) {
		SQLiteDatabase db = getWritableDatabase();
		db.beginTransaction();
		try {
			for (ChatMessage msg : newMessages) {
				insertMessage(accountId, msg);
			}
			setLastEventId(accountId, newEventId);
			db.setTransactionSuccessful();
		} finally {
			db.endTransaction();
		}
	}

	public void trimMessages(String accountId, long peerId, int keep) {
		getWritableDatabase().execSQL(
				"DELETE FROM chat_messages WHERE account_id = ? AND peer_id = ? AND local_id NOT IN "
						+ "(SELECT local_id FROM chat_messages WHERE account_id = ? AND peer_id = ? AND (send_state = 'SENDING' OR send_state = 'FAILED') "
						+ "UNION ALL "
						+ "SELECT local_id FROM chat_messages WHERE account_id = ? AND peer_id = ? ORDER BY created_at DESC, local_id DESC LIMIT ?)",
				new String[]{accountId, String.valueOf(peerId), accountId, String.valueOf(peerId), accountId, String.valueOf(peerId), String.valueOf(keep)});
	}

	// === Helpers ===

	private Conversation cursorToConversation(Cursor c) {
		Conversation conv = new Conversation();
		conv.accountId = getString(c, "account_id");
		conv.peerId = getLong(c, "peer_id");
		conv.username = getString(c, "username");
		conv.avatar = getString(c, "avatar");
		conv.lastMessage = getString(c, "last_message");
		conv.lastMessageAt = getLong(c, "last_message_at");
		conv.lastMessageId = getLong(c, "last_message_id");
		conv.unreadCount = getInt(c, "unread_count");
		conv.draft = getString(c, "draft");
		String state = getString(c, "last_out_state");
		conv.lastOutState = state != null ? SendState.valueOf(state) : null;
		conv.readOutboxMaxId = getLong(c, "read_outbox_max_id");
		return conv;
	}

	private ChatMessage cursorToMessage(Cursor c) {
		ChatMessage msg = new ChatMessage();
		msg.accountId = getString(c, "account_id");
		msg.id = getLong(c, "server_id");
		msg.tempId = getLong(c, "temp_id");
		msg.clientMsgId = getString(c, "client_msg_id");
		msg.peerId = getLong(c, "peer_id");
		msg.senderId = getLong(c, "sender_id");
		msg.content = getString(c, "content");
		msg.createdAt = getLong(c, "created_at");
		msg.out = getInt(c, "is_out") == 1;
		String state = getString(c, "send_state");
		msg.sendState = state != null ? SendState.valueOf(state) : null;
		msg.eventId = getLong(c, "event_id");
		return msg;
	}

	private String getString(Cursor c, String col) {
		int idx = c.getColumnIndex(col);
		return idx >= 0 ? c.getString(idx) : null;
	}

	private long getLong(Cursor c, String col) {
		int idx = c.getColumnIndex(col);
		return idx >= 0 ? c.getLong(idx) : 0;
	}

	private int getInt(Cursor c, String col) {
		int idx = c.getColumnIndex(col);
		return idx >= 0 ? c.getInt(idx) : 0;
	}
}
