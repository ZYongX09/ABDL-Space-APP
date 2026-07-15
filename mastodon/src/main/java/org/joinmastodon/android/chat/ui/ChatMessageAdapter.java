package org.joinmastodon.android.chat.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.joinmastodon.android.chat.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;

public class ChatMessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
	private static final int TYPE_MESSAGE = 0;
	private static final int TYPE_DATE = 1;

	private List<Object> items = new ArrayList<>();
	private List<ChatMessage> messages = new ArrayList<>();

	public void setMessages(List<ChatMessage> msgs) {
		this.messages.clear();
		this.messages.addAll(msgs);
		rebuildItems();
		notifyDataSetChanged();
	}

	public void addMessages(List<ChatMessage> msgs) {
		this.messages.addAll(0, msgs);
		rebuildItems();
		notifyDataSetChanged();
	}

	public void addMessage(ChatMessage msg) {
		this.messages.add(msg);
		rebuildItems();
		notifyDataSetChanged();
	}

	public void updateMessage(ChatMessage msg) {
		for (int i = 0; i < messages.size(); i++) {
			ChatMessage existing = messages.get(i);
			if ((existing.tempId != 0 && existing.tempId == msg.tempId)
					|| (existing.id != 0 && existing.id == msg.id)
					|| (existing.clientMsgId != null && existing.clientMsgId.equals(msg.clientMsgId))) {
				messages.set(i, msg);
				rebuildItems();
				notifyDataSetChanged();
				return;
			}
		}
	}

	private void rebuildItems() {
		items.clear();
		String lastDate = null;
		for (int i = messages.size() - 1; i >= 0; i--) {
			ChatMessage msg = messages.get(i);
			String date = formatDate(msg.createdAt);
			if (!date.equals(lastDate)) {
				items.add(date);
				lastDate = date;
			}
			items.add(msg);
		}
	}

	@Override
	public int getItemViewType(int position) {
		return items.get(position) instanceof String ? TYPE_DATE : TYPE_MESSAGE;
	}

	@NonNull @Override
	public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		Context ctx = parent.getContext();
		if (viewType == TYPE_DATE) {
			return new DateViewHolder(new MessageBubbleView.DateDividerView(ctx));
		}
		return new MessageViewHolder(new MessageBubbleView(ctx));
	}

	@Override
	public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
		if (holder instanceof DateViewHolder) {
			((DateViewHolder) holder).dividerView.setDate((String) items.get(position));
		} else if (holder instanceof MessageViewHolder) {
			ChatMessage msg = (ChatMessage) items.get(position);
			boolean showTail = position == 0 || getItemViewType(position - 1) == TYPE_DATE;
			((MessageViewHolder) holder).bubbleView.bind(msg, showTail, 0);
		}
	}

	@Override
	public int getItemCount() { return items.size(); }

	public int getFirstMessagePosition() {
		for (int i = 0; i < items.size(); i++) {
			if (items.get(i) instanceof ChatMessage) return i;
		}
		return 0;
	}

	private static class MessageViewHolder extends RecyclerView.ViewHolder {
		MessageBubbleView bubbleView;
		MessageViewHolder(View itemView) {
			super(itemView);
			bubbleView = (MessageBubbleView) itemView;
		}
	}

	private static class DateViewHolder extends RecyclerView.ViewHolder {
		MessageBubbleView.DateDividerView dividerView;
		DateViewHolder(View itemView) {
			super(itemView);
			dividerView = (MessageBubbleView.DateDividerView) itemView;
		}
	}

	private String formatDate(long millis) {
		if (millis == 0) return "";
		java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault());
		return sdf.format(new java.util.Date(millis));
	}
}
