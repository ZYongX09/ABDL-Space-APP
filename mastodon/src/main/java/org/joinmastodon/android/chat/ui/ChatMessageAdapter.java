package org.joinmastodon.android.chat.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.chat.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;

public class ChatMessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
	private static final int TYPE_MESSAGE = 0;
	private static final int TYPE_DATE = 1;

	private final List<Object> items = new ArrayList<>();
	private final List<ChatMessage> messages = new ArrayList<>();

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
		LayoutInflater inflater = LayoutInflater.from(parent.getContext());
		if (viewType == TYPE_DATE) {
			MessageBubbleView.DateDividerView dv = new MessageBubbleView.DateDividerView(parent.getContext());
			dv.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
			return new DateViewHolder(dv);
		}
		return new MessageViewHolder(new MessageBubbleView(parent.getContext()));
	}

	@Override
	public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
		if (holder instanceof DateViewHolder) {
			((DateViewHolder) holder).view.setDate((String) items.get(position));
		} else if (holder instanceof MessageViewHolder) {
			ChatMessage msg = (ChatMessage) items.get(position);
			boolean isOut = msg.out;
			((MessageViewHolder) holder).view.bind(msg, isOut, true);
		}
	}

	@Override
	public int getItemCount() { return items.size(); }

	private static class MessageViewHolder extends RecyclerView.ViewHolder {
		MessageBubbleView view;
		MessageViewHolder(View itemView) {
			super(itemView);
			view = (MessageBubbleView) itemView;
		}
	}

	private static class DateViewHolder extends RecyclerView.ViewHolder {
		MessageBubbleView.DateDividerView view;
		DateViewHolder(View itemView) {
			super(itemView);
			view = (MessageBubbleView.DateDividerView) itemView;
		}
	}

	private String formatDate(long millis) {
		if (millis == 0) return "";
		java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault());
		return sdf.format(new java.util.Date(millis));
	}
}
