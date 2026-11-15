package org.joinmastodon.android.ui.displayitems;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.StatusModerationSupport;
import org.joinmastodon.android.ui.utils.UiUtils;

import me.grishka.appkit.utils.V;

/** The only body row for a blocked post. No media, emoji, HTML links, or image requests. */
public final class ModerationNoticeStatusDisplayItem extends StatusDisplayItem{
	public final String notice;
	public ModerationNoticeStatusDisplayItem(String parentID, Callbacks callbacks, Context context, Status status){
		super(parentID, callbacks, context);
		this.status=status;
		notice=StatusModerationSupport.notice(status);
	}
	@Override public Type getType(){ return Type.MODERATION_NOTICE; }

	public static LinearLayout createNoticeView(Context context){
		LinearLayout row=new LinearLayout(context);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(V.dp(14), V.dp(14), V.dp(14), V.dp(14));
		GradientDrawable background=new GradientDrawable();
		background.setColor(UiUtils.getThemeColor(context, R.attr.colorModerationNoticeSurface));
		background.setCornerRadius(V.dp(20));
		row.setBackground(background);
		TextView icon=new TextView(context);
		icon.setText("!"); icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22); icon.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
		icon.setGravity(Gravity.CENTER); icon.setIncludeFontPadding(false);
		icon.setTextColor(UiUtils.getThemeColor(context, R.attr.colorModerationNoticeOnIcon));
		icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		GradientDrawable circle=new GradientDrawable(); circle.setShape(GradientDrawable.OVAL);
		circle.setColor(UiUtils.getThemeColor(context, R.attr.colorModerationNoticeIcon)); icon.setBackground(circle);
		row.addView(icon, new LinearLayout.LayoutParams(V.dp(36), V.dp(36)));
		TextView text=new TextView(context); text.setId(R.id.moderation_notice_text);
		text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
		text.setTextColor(UiUtils.getThemeColor(context, R.attr.colorModerationNoticeText));
		text.setSingleLine(false); text.setMaxLines(Integer.MAX_VALUE); text.setEllipsize(null);
		LinearLayout.LayoutParams textParams=new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
		textParams.setMarginStart(V.dp(12)); row.addView(text, textParams);
		return row;
	}
	public static void bindNotice(View row, String notice){
		((TextView)row.findViewById(R.id.moderation_notice_text)).setText(notice);
	}

	public static final class Holder extends StatusDisplayItem.Holder<ModerationNoticeStatusDisplayItem>{
		private final LinearLayout noticeView;
		public Holder(Context context, ViewGroup parent){
			super(new FrameLayout(context));
			noticeView=createNoticeView(context);
			((FrameLayout)itemView).addView(noticeView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		}
		@Override public void onBind(ModerationNoticeStatusDisplayItem item){
			itemView.setPaddingRelative(V.dp(item.isQuote && !item.fullWidth ? 64 : 16), V.dp(8), V.dp(16), V.dp(12));
			bindNotice(noticeView, item.notice);
		}
	}
}
