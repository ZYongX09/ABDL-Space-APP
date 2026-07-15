package org.joinmastodon.android.chat.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

import org.joinmastodon.android.R;
import org.joinmastodon.android.chat.model.Conversation;
import org.joinmastodon.android.chat.model.SendState;
import org.joinmastodon.android.ui.utils.UiUtils;

import me.grishka.appkit.utils.V;

public class ConversationCell extends View {
	private Conversation conversation;
	private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
	private final TextPaint previewPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
	private final TextPaint timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
	private final TextPaint badgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
	private final Paint avatarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint dividerPaint = new Paint();
	private final RectF badgeRect = new RectF();
	private final float[] avatarRadii = new float[8];

	private StaticLayout nameLayout;
	private StaticLayout previewLayout;
	private StaticLayout timeLayout;
	private StaticLayout badgeLayout;

	private String avatarUrl;

	public ConversationCell(Context context) {
		super(context);

		float dp = context.getResources().getDisplayMetrics().density;
		float sp = context.getResources().getDisplayMetrics().scaledDensity;

		namePaint.setTextSize(16 * sp);
		namePaint.setColor(UiUtils.getThemeColor(context, R.attr.colorM3OnSurface));
		namePaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

		previewPaint.setTextSize(14 * sp);
		previewPaint.setColor(UiUtils.getThemeColor(context, R.attr.colorM3OnSurfaceVariant));
		previewPaint.setTypeface(android.graphics.Typeface.DEFAULT);

		timePaint.setTextSize(12 * sp);
		timePaint.setColor(UiUtils.getThemeColor(context, R.attr.colorM3OnSurfaceVariant));

		badgePaint.setTextSize(12 * sp);
		badgePaint.setColor(0xFFFFFFFF);
		badgePaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
		badgePaint.setTextAlign(Paint.Align.CENTER);

		dividerPaint.setColor(UiUtils.getThemeColor(context, R.attr.colorM3OutlineVariant));
		dividerPaint.setStrokeWidth(1 * dp);

		for (int i = 0; i < 8; i++) avatarRadii[i] = 27 * dp;

		setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (72 * dp)));
	}

	public void bind(Conversation c) {
		this.conversation = c;
		this.avatarUrl = c.avatar;
		invalidate();
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		int w = MeasureSpec.getSize(widthMeasureSpec);
		int h = (int) (72 * getResources().getDisplayMetrics().density);
		setMeasuredDimension(w, h);
	}

	@Override
	protected void onDraw(Canvas canvas) {
		if (conversation == null) return;

		float dp = getResources().getDisplayMetrics().density;
		int w = getWidth();
		int h = getHeight();

		float avatarSize = 54 * dp;
		float avatarLeft = 16 * dp;
		float avatarTop = (h - avatarSize) / 2f;
		float avatarRight = avatarLeft + avatarSize;

		float textLeft = avatarRight + 12 * dp;
		float textRight = w - 16 * dp;
		float textMaxWidth = textRight - textLeft;

		// Avatar placeholder
		avatarPaint.setColor(UiUtils.getThemeColor(getContext(), R.attr.colorM3SurfaceVariant));
		RectF avatarRect = new RectF(avatarLeft, avatarTop, avatarRight, avatarTop + avatarSize);
		canvas.drawRoundRect(avatarRect, 27 * dp, 27 * dp, avatarPaint);

		// Name
		String name = conversation.username != null ? conversation.username : "";
		nameLayout = makeLayout(namePaint, name, (int) textMaxWidth, 1);

		// Time
		String time = formatTime(conversation.lastMessageAt);
		timeLayout = makeLayout(timePaint, time, (int) textMaxWidth, 1);

		// Name & time on first line
		float line1Y = 20 * dp;
		canvas.save();
		canvas.translate(textLeft, line1Y);
		if (nameLayout != null) nameLayout.draw(canvas);
		canvas.restore();

		if (timeLayout != null) {
			float timeX = textRight - timeLayout.getWidth();
			canvas.save();
			canvas.translate(timeX, line1Y);
			timeLayout.draw(canvas);
			canvas.restore();
		}

		// Preview + badge on second line
		String preview = getPreviewText();
		float previewRight = textRight;
		int badge = conversation.unreadCount;
		if (badge > 0) {
			String badgeText = badge > 99 ? "99+" : String.valueOf(badge);
			float badgeWidth = Math.max(24 * dp, badgePaint.measureText(badgeText) + 12 * dp);
			float badgeHeight = 20 * dp;
			float badgeX = textRight - badgeWidth;
			float badgeY = 42 * dp;
			badgeRect.set(badgeX, badgeY, badgeX + badgeWidth, badgeY + badgeHeight);
			avatarPaint.setColor(0xFF4F7CFF);
			canvas.drawRoundRect(badgeRect, badgeHeight / 2, badgeHeight / 2, avatarPaint);
			canvas.drawText(badgeText, badgeRect.centerX(), badgeRect.centerY() + 5 * dp, badgePaint);
			previewRight = badgeX - 8 * dp;
		}

		float previewWidth = previewRight - textLeft;
		if (previewWidth > 0) {
			previewLayout = makeLayout(previewPaint, preview, (int) previewWidth, 1);
			if (previewLayout != null) {
				canvas.save();
				canvas.translate(textLeft, 42 * dp);
				previewLayout.draw(canvas);
				canvas.restore();
			}
		}

		// Divider
		canvas.drawLine(textLeft, h - 1, w, h - 1, dividerPaint);
	}

	private String getPreviewText() {
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

	private StaticLayout makeLayout(TextPaint paint, String text, int width, int maxLines) {
		if (text == null || text.isEmpty() || width <= 0) return null;
		return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
				.setMaxLines(maxLines)
				.setEllipsize(TextUtils.TruncateAt.END)
				.build();
	}

	private static class RecyclerView extends android.view.ViewGroup {
		public RecyclerView(Context context) { super(context); }
		@Override protected void onLayout(boolean changed, int l, int t, int r, int b) {}
	}
}
