package org.joinmastodon.android.chat.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.chat.model.ChatMessage;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MessageBubbleView extends FrameLayout {
	private final TextView bubbleText;
	private final TextView timeView;
	private final TextView statusView;
	private final LinearLayout bubbleContainer;
	private ChatMessage message;

	public MessageBubbleView(Context context) {
		super(context);
		LayoutInflater.from(context).inflate(R.layout.item_chat_message, this, true);
		bubbleText = findViewById(R.id.bubble_text);
		timeView = findViewById(R.id.time);
		statusView = findViewById(R.id.status);
		bubbleContainer = findViewById(R.id.bubble_container);
		setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
	}

	public void bind(ChatMessage msg, boolean isOut, boolean showTail) {
		this.message = msg;
		bubbleText.setText(msg.content);
		timeView.setText(formatTime(msg.createdAt));

		if (isOut) {
			statusView.setVisibility(View.VISIBLE);
			statusView.setText(getStatusText());
		} else {
			statusView.setVisibility(View.GONE);
		}

		// Bubble background
		int bgRes = isOut ? R.drawable.bg_bubble_out : R.drawable.bg_bubble_in;
		bubbleContainer.setBackgroundResource(bgRes);

		// Alignment
		LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bubbleContainer.getLayoutParams();
		lp.gravity = isOut ? android.view.Gravity.END : android.view.Gravity.START;
		bubbleContainer.setLayoutParams(lp);
	}

	public static DateDividerView createDateDivider(Context context) {
		return (DateDividerView) LayoutInflater.from(context).inflate(R.layout.item_chat_message, null);
	}

	public static class DateDividerView extends FrameLayout {
		private final TextView dateText;

		public DateDividerView(Context context) {
			super(context);
			// Simple text-only view for date divider
			dateText = new TextView(context);
			dateText.setTextSize(12);
			dateText.setGravity(android.view.Gravity.CENTER);
			dateText.setTextColor(UiUtils.getThemeColor(context, R.attr.colorM3OnSurfaceVariant));
			dateText.setBackgroundResource(R.drawable.bg_chat_date);
			int pad = (int) (16 * context.getResources().getDisplayMetrics().density);
			setPadding(pad, pad / 2, pad, pad / 2);
			LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
			lp.gravity = android.view.Gravity.CENTER;
			addView(dateText, lp);
		}

		public void setDate(String date) {
			dateText.setText(date);
		}
	}

	private String formatTime(long millis) {
		if (millis == 0) return "";
		SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
		return sdf.format(new Date(millis));
	}

	private String getStatusText() {
		if (message.sendState == null) return "";
		switch (message.sendState) {
			case SENDING: return "\u23F3";
			case SENT: return "\u2714";
			case READ: return "\u2714\u2714";
			case FAILED: return "\u26A0";
			default: return "";
		}
	}
}
