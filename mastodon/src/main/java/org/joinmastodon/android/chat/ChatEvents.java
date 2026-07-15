package org.joinmastodon.android.chat;

import org.joinmastodon.android.chat.model.ChatMessage;
import org.joinmastodon.android.chat.model.SendState;

public class ChatEvents {
	public static class ConversationsUpdatedEvent {}

	public static class NewChatMessageEvent {
		public final ChatMessage message;
		public NewChatMessageEvent(ChatMessage message) { this.message = message; }
	}

	public static class MessageSendStateEvent {
		public final ChatMessage message;
		public MessageSendStateEvent(ChatMessage message) { this.message = message; }
	}

	public static class MessageReadEvent {
		public final long peerId;
		public final long readUpToId;
		public MessageReadEvent(long peerId, long readUpToId) {
			this.peerId = peerId;
			this.readUpToId = readUpToId;
		}
	}

	public static class TypingEvent {
		public final long fromUserId;
		public TypingEvent(long fromUserId) { this.fromUserId = fromUserId; }
	}
}
