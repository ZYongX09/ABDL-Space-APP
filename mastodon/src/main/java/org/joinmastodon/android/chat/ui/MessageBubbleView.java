package org.joinmastodon.android.chat.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import org.joinmastodon.android.R;
import org.joinmastodon.android.chat.model.ChatMessage;
import org.joinmastodon.android.chat.model.SendState;
import org.joinmastodon.android.ui.utils.UiUtils;

public class MessageBubbleView extends View {
	private ChatMessage message;
	private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
	private final TextPaint timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
	private final TextPaint statusPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
	private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint dividerPaint = new Paint();
	private final RectF bubbleRect = new RectF();

	private StaticLayout textLayout;
	private StaticLayout timeLayout;

	private int cornerRadius;
	private static final float BUBBLE_MAX_WIDTH_RATIO = 0.75f;
	private static final int DATE_ITEM_TYPE = 1;
	private static final int MESSAGE_ITEM_TYPE = 0;

	public MessageBubbleView(Context context) {
		super(context);

		float dp = context.getResources().getDisplayMetrics().density;
		float sp = context.getResources().getDisplayMetrics().scaledDensity;

		textPaint.setTextSize(15 * sp);
		textPaint.setColor(UiUtils.getThemeColor(context, R.attr.colorM3OnSurface));

		timePaint.setTextSize(11 * sp);
		timePaint.setColor(UiUtils.getThemeColor(context, R.attr.colorM3OnSurfaceVariant));
		timePaint.setTextAlign(Paint.Align.RIGHT);

		statusPaint.setTextSize(11 * sp);
		statusPaint.setColor(UiUtils.getThemeColor(context, R.attr.colorM3OnSurfaceVariant));

		dividerPaint.setColor(UiUtils.getThemeColor(context, R.attr.colorM3OutlineVariant));
		dividerPaint.setStrokeWidth(1 * dp);

		cornerRadius = (int) (16 * dp);
	}

	public void bind(ChatMessage msg, boolean showTail, int positionType) {
		this.message = msg;
		textLayout = null;
		timeLayout = null;
		invalidate();
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		if (message == null) {
			setMeasuredDimension(0, 0);
			return;
		}

		int maxWidth = MeasureSpec.getSize(widthMeasureSpec);
		float dp = getResources().getDisplayMetrics().density;
		float sp = getResources().getDisplayMetrics().scaledDensity;
		int bubbleMaxWidth = (int) (maxWidth * BUBBLE_MAX_WIDTH_RATIO) - (int) (24 * dp);
		int paddingH = (int) (12 * dp);
		int paddingV = (int) (8 * dp);

		String text = message.content != null ? message.content : "";
		textLayout = StaticLayout.Builder.obtain(text, 0, text.length(), textPaint, bubbleMaxWidth - paddingH * 2)
				.build();

		String timeText = formatTime(message.createdAt);
		String statusText = getStatusText();
		String metaText = timeText + (statusText.isEmpty() ? "" : " " + statusText);
		timeLayout = StaticLayout.Builder.obtain(metaText, 0, metaText.length(), timePaint, bubbleMaxWidth - paddingH * 2)
				.build();

		int textHeight = textLayout.getHeight();
		int timeHeight = timeLayout.getHeight();
		int totalHeight = paddingV + textHeight + 4 + timeHeight + paddingV;
		int totalWidth = Math.min(bubbleMaxWidth, Math.max(textLayout.getWidth(), timeLayout.getWidth()) + paddingH * 2);

		setMeasuredDimension(totalWidth + (int) (16 * dp), totalHeight);
	}

	@Override
	protected void onDraw(Canvas canvas) {
		if (message == null || textLayout == null) return;

		float dp = getResources().getDisplayMetrics().density;
		int w = getWidth();
		int h = getHeight();

		boolean isOut = message.out;
		int bubbleLeft = isOut ? (int) (8 * dp) : 0;
		int bubbleRight = isOut ? w : w - (int) (8 * dp);
		int paddingH = (int) (12 * dp);
		int paddingV = (int) (8 * dp);

		// Bubble background
		bubbleRect.set(bubbleLeft, 0, bubbleRight, h);
		bubblePaint.setColor(isOut ? 0xFF4F7CFF : UiUtils.getThemeColor(getContext(), R.attr.colorM3SurfaceVariant));
		canvas.drawRoundRect(bubbleRect, cornerRadius, cornerRadius, bubblePaint);

		// Text
		int textX = bubbleLeft + paddingH;
		int textY = paddingV;
		canvas.save();
		canvas.translate(textX, textY);
		textPaint.setColor(isOut ? 0xFFFFFFFF : UiUtils.getThemeColor(getContext(), R.attr.colorM3OnSurface));
		textLayout.draw(canvas);
		canvas.restore();

		// Time + status
		int timeY = textY + textLayout.getHeight() + 4;
		canvas.save();
		canvas.translate(textX, timeY);
		timePaint.setColor(isOut ? 0xAAFFFFFF : UiUtils.getThemeColor(getContext(), R.attr.colorM3OnSurfaceVariant));
		timeLayout.draw(canvas);
		canvas.restore();
	}

	private String formatTime(long millis) {
		if (millis == 0) return "";
		java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
		return sdf.format(new java.util.Date(millis));
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

	public static class DateDividerView extends View {
		private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
		private final Paint linePaint = new Paint();
		private String dateText;

		public DateDividerView(Context context) {
			super(context);
			float dp = context.getResources().getDisplayMetrics().density;
			float sp = context.getResources().getDisplayMetrics().scaledDensity;
			textPaint.setTextSize(12 * sp);
			textPaint.setColor(UiUtils.getThemeColor(context, R.attr.colorM3OnSurfaceVariant));
			textPaint.setTextAlign(Paint.Align.CENTER);
			linePaint.setColor(UiUtils.getThemeColor(context, R.attr.colorM3OutlineVariant));
			linePaint.setStrokeWidth(1 * dp);
		}

		public void setDate(String date) {
			this.dateText = date;
			invalidate();
		}

		@Override
		protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
			setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (int) (40 * getResources().getDisplayMetrics().density));
		}

		@Override
		protected void onDraw(Canvas canvas) {
			int w = getWidth();
			int h = getHeight();
			float dp = getResources().getDisplayMetrics().density;
			float textWidth = textPaint.measureText(dateText != null ? dateText : "");
			float cx = w / 2f;
			float cy = h / 2f;
			canvas.drawLine(cx - textWidth / 2 - 16 * dp, cy, cx - textWidth / 2 - 8 * dp, cy, linePaint);
			canvas.drawLine(cx + textWidth / 2 + 8 * dp, cy, cx + textWidth / 2 + 16 * dp, cy, linePaint);
			canvas.drawText(dateText != null ? dateText : "", cx, cy + 5 * dp, textPaint);
		}
	}
}
