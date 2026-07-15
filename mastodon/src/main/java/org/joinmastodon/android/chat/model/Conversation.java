package org.joinmastodon.android.chat.model;

public class Conversation {
	public String accountId;
	public long peerId;
	public String username;
	public String avatar;
	public String lastMessage;
	public long lastMessageAt;
	public long lastMessageId;
	public int unreadCount;
	public String draft;
	public SendState lastOutState;
	public long readOutboxMaxId;
}
