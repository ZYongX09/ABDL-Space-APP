package org.joinmastodon.android.chat.model;

public class ChatMessage {
	public String accountId;
	public long id;           // server id, 0 if pending
	public long tempId;       // local
	public String clientMsgId;
	public long peerId;
	public long senderId;
	public String content;
	public long createdAt;    // millis
	public boolean out;
	public SendState sendState;
	public long eventId;
}
