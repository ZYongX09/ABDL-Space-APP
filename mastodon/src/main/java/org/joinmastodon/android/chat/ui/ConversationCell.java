package org.joinmastodon.android.chat.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.chat.model.Conversation;
import org.joinmastodon.android.chat.model.SendState;
import org.joinmastodon.android.ui.utils.UiUtils;

import me.grishka.appkit.imageloader.ViewImageLoader;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.V;

public class ConversationCell extends android.widget.FrameLayout {
	private final TextView nameView;
	private final TextView timeView;
	private final TextView previewView;
	private final TextView badgeView;
	private final ImageView avatarView;
	private Conversation conversation;

	public ConversationCell(Context context) {
		super(context);
		LayoutInflater.from(context).inflate(R.layout.item_conversation, this, true);
		nameView = findViewById(R.id.name);
		timeView = findViewById(R.id.time);
		previewView = findViewById(R.id.preview);
		badgeView = findViewById(R.id.badge);
		avatarView = findViewById(R.id.avatar);
		setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
	}

	public void bind(Conversation c) {
		this.conversation = c;
		nameView.setText(c.username != null ? c.username : "未知用户");
		timeView.setText(formatTime(c.lastMessageAt));
		previewView.setText(getPreviewText());
		boolean unread = c.unreadCount > 0;
		nameView.setTypeface(Typeface.DEFAULT, unread ? Typeface.BOLD : Typeface.NORMAL);
		previewView.setTypeface(Typeface.DEFAULT, unread ? Typeface.BOLD : Typeface.NORMAL);
		timeView.setTextColor(UiUtils.getThemeColor(getContext(), unread ? R.attr.colorM3Primary : R.attr.colorM3OnSurfaceVariant));
		avatarView.setImageResource(R.drawable.image_placeholder);
		if (c.avatar != null && !c.avatar.isEmpty()) {
			ViewImageLoader.load(new ViewImageLoader.Target() {
				@Override
				public void setImageDrawable(Drawable drawable) {
					if (conversation == c) avatarView.setImageDrawable(drawable);
				}

				@Override
				public View getView() {
					return avatarView;
				}
			}, avatarView.getDrawable(), new UrlImageLoaderRequest(c.avatar, V.dp(52), V.dp(52)), false);
		}
		if (c.unreadCount > 0) {
			badgeView.setVisibility(View.VISIBLE);
			badgeView.setText(c.unreadCount > 99 ? "99+" : String.valueOf(c.unreadCount));
		} else {
			badgeView.setVisibility(View.GONE);
		}
	}

	private String getPreviewText() {
		if (conversation.draft != null && !conversation.draft.isEmpty()) return "草稿：" + conversation.draft;
		if (conversation.lastMessage == null || conversation.lastMessage.isEmpty()) return "";
		String prefix = "";
		if (conversation.lastOutState == SendState.SENDING) prefix = "\u23F3 ";
		else if (conversation.lastOutState == SendState.FAILED) prefix = "\u26A0 ";
		else if (conversation.lastOutState == SendState.READ) prefix = "\u2714\u2714 ";
		else if (conversation.lastOutState == SendState.SENT) prefix = "\u2714 ";
		return prefix + conversation.lastMessage;
	}

	private String formatTime(long millis) {
		if (millis == 0) return "";
		long now = System.currentTimeMillis();
		long diff = now - millis;
		if (diff < 60_000) return "刚刚";
		if (diff < 3_600_000) return (diff / 60_000) + "分钟前";
		if (diff < 86_400_000) return (diff / 3_600_000) + "小时前";
		if (diff < 7 * 86_400_000) return (diff / 86_400_000) + "天前";
		java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM/dd", java.util.Locale.getDefault());
		return sdf.format(new java.util.Date(millis));
	}
}
