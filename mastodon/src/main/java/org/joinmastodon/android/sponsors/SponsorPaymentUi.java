package org.joinmastodon.android.sponsors;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.model.sponsors.SponsorModels.Plan;
import org.joinmastodon.android.model.sponsors.SponsorPaymentModels.Order;
import org.joinmastodon.android.ui.utils.UiUtils;

import me.grishka.appkit.utils.V;

/** Real native view hierarchy shared by the screen and offline rendering fixtures. */
public final class SponsorPaymentUi{
	public interface Actions{
		void select(String channel);
		void pay();
		void retry();
		void switchChannel();
	}
	public final LinearLayout root, footer, content;
	public final ScrollView scroll;
	public final TextView planName, price, duration, status;
	public final Button pay, retry, switchChannel;
	public final ChannelRow alipay, afdian;
	private final Context context;

	public SponsorPaymentUi(Context context, Actions actions){
		this.context=context;
		root=new LinearLayout(context); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(SponsorUi.surface(context));
		scroll=new ScrollView(context); scroll.setFillViewport(true); scroll.setClipToPadding(false);
		content=SponsorUi.column(context); content.setPadding(V.dp(16), V.dp(12), V.dp(16), V.dp(20));
		scroll.addView(content, new ScrollView.LayoutParams(-1, -2)); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
		LinearLayout summary=SponsorUi.designCard(content);
		planName=SponsorUi.text(summary, "", true);
		price=SponsorUi.headline(summary, "");
		duration=SponsorUi.label(summary, "");
		SponsorUi.text(content, context.getString(R.string.sponsor_payment_choose_channel), true);
		alipay=new ChannelRow(content, "alipay", R.drawable.ic_sponsor_alipay, R.string.sponsor_payment_alipay,
				R.string.sponsor_payment_recommended, true, ()->actions.select("alipay"));
		afdian=new ChannelRow(content, "afdian", R.drawable.ic_sponsor_afdian, R.string.sponsor_payment_afdian,
				R.string.sponsor_payment_wechat, false, ()->actions.select("afdian"));
		status=SponsorUi.text(content, context.getString(R.string.sponsor_payment_checking), false);
		status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
		switchChannel=SponsorUi.secondary(content, context.getString(R.string.sponsor_payment_close_switch), actions::switchChannel);
		retry=SponsorUi.textButton(content, context.getString(R.string.sponsor_payment_refresh), actions::retry);
		SponsorUi.label(content, context.getString(R.string.sponsor_payment_channel_difference));
		SponsorUi.label(content, context.getString(R.string.sponsor_payment_server_confirmation));
		footer=new LinearLayout(context); footer.setOrientation(LinearLayout.VERTICAL);
		footer.setPadding(V.dp(16), V.dp(4), V.dp(16), V.dp(12)); footer.setBackgroundColor(SponsorUi.surface(context));
		pay=SponsorUi.button(footer, context.getString(R.string.sponsor_payment_continue), actions::pay);
		pay.setMinHeight(V.dp(52)); pay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
		root.addView(footer, new LinearLayout.LayoutParams(-1, -2));
		switchChannel.setVisibility(View.GONE); pay.setEnabled(false);
	}
	public void insets(int left, int right, int bottom){
		content.setPadding(V.dp(16)+left, V.dp(12), V.dp(16)+right, V.dp(20));
		footer.setPadding(V.dp(16)+left, V.dp(4), V.dp(16)+right, V.dp(12)+bottom);
	}
	public void render(SponsorPaymentCoordinator state){
		Plan plan=state.plan();
		planName.setText(plan==null ? context.getString(R.string.sponsor_payment_checking) : plan.name);
		price.setText(plan==null ? "" : SponsorUi.price(plan)); duration.setText(plan==null ? "" : SponsorUi.duration(context, plan));
		alipay.view.setVisibility(state.showAlipay() ? View.VISIBLE : View.GONE);
		alipay.update("alipay".equals(state.selected()), state.canSelect("alipay"), state.disabledReason("alipay"));
		afdian.update("afdian".equals(state.selected()), state.canSelect("afdian"), state.disabledReason("afdian"));
		Order order=state.order();
		String text;
		if(state.confirmed()) text=context.getString(R.string.sponsor_payment_granted);
		else if(state.sdkActive()) text=context.getString(R.string.sponsor_payment_sdk_active);
		else if(state.busy()) text=message(context, state.problem());
		else if(order!=null && "MANUAL_REVIEW".equals(order.fulfillmentState)) text=context.getString(R.string.sponsor_payment_manual_review);
		else if(order!=null && "REFUND_REVIEW".equals(order.fulfillmentState)) text=context.getString(R.string.sponsor_payment_refund_review);
		else if(order!=null && "GRANTED".equals(order.fulfillmentState)) text=context.getString(R.string.sponsor_payment_membership_pending);
		else if(order!=null && "PAID".equals(order.paymentState)) text=context.getString(R.string.sponsor_payment_settlement_pending);
		else if(order!=null && "UNKNOWN".equals(order.paymentState)) text=context.getString(R.string.sponsor_payment_unknown);
		else if(order!=null && "PENDING_PAYMENT".equals(order.paymentState)) text=context.getString(R.string.sponsor_payment_pending);
		else if(state.problem()!=null) text=message(context, state.problem());
		else if(order!=null && "CLOSED".equals(order.paymentState)) text=context.getString(R.string.sponsor_payment_closed);
		else text=context.getString(R.string.sponsor_payment_choose_hint);
		status.setText(text);
		switchChannel.setVisibility(state.canSwitch() ? View.VISIBLE : View.GONE);
		retry.setEnabled(!state.busy()); retry.setText(state.blocking() ? R.string.sponsor_payment_check_order : R.string.sponsor_payment_refresh);
		pay.setEnabled(state.canPay());
		pay.setText(state.confirmed() ? R.string.sponsor_payment_granted : state.busy() ? R.string.sponsor_payment_processing : R.string.sponsor_payment_continue);
	}
	public static String message(Context context, String reason){
		int resource=switch(reason==null ? "checking" : reason){
			case "checking" -> R.string.sponsor_payment_checking;
			case "creating" -> R.string.sponsor_payment_creating;
			case "checking_order" -> R.string.sponsor_payment_checking_order;
			case "closing" -> R.string.sponsor_payment_closing;
			case "confirming_membership", "membership_refresh_pending" -> R.string.sponsor_payment_membership_pending;
			case "disabled", "sponsors_disabled", "payment_unavailable" -> R.string.sponsor_payment_disabled;
			case "limits_unverified" -> R.string.sponsor_payment_limits_unverified;
			case "not_configured" -> R.string.sponsor_payment_not_configured;
			case "amount_unsupported" -> R.string.sponsor_payment_amount_unsupported;
			case "wallet_unavailable" -> R.string.sponsor_payment_wallet_unavailable;
			case "legacy_backend", "not_supported" -> R.string.sponsor_payment_legacy_backend;
			case "plan_changed", "config_changed" -> R.string.sponsor_payment_plan_changed;
			case "already_permanent" -> R.string.sponsor_payment_already_permanent;
			case "session_changed", "unauthorized" -> R.string.sponsor_payment_session_changed;
			case "payment_result_unknown", "payment_pending", "idempotency_conflict" -> R.string.sponsor_payment_unknown;
			case "settlement_pending" -> R.string.sponsor_payment_settlement_pending;
			case "storage_failed" -> R.string.sponsor_payment_storage_failed;
			default -> R.string.sponsor_payment_unavailable;
		};
		return context.getString(resource);
	}
	public static final class ChannelRow{
		public final LinearLayout view;
		public final RadioButton radio;
		public final TextView name, reason;
		public final ImageView logo;
		private final String label, badge;
		private final boolean recommended;
		ChannelRow(LinearLayout parent, String id, int icon, int title, int badgeText, boolean recommended, Runnable action){
			Context context=parent.getContext();
			this.recommended=recommended; label=context.getString(title); badge=context.getString(badgeText);
			view=SponsorUi.designCard(parent); view.setTag(id); view.setPadding(V.dp(12), V.dp(12), V.dp(12), V.dp(12));
			LinearLayout row=new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL); view.addView(row, new LinearLayout.LayoutParams(-1, -2));
			logo=new ImageView(context); logo.setImageResource(icon); logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
			// Brand vectors retain their own colors. No tint or colorFilter is applied.
			row.addView(logo, new LinearLayout.LayoutParams(V.dp(36), V.dp(36)));
			name=new TextView(context); name.setTextAppearance(R.style.m3_title_medium);
			name.setTextColor(UiUtils.getThemeColor(context, R.attr.colorM3OnSurface));
			name.setPadding(V.dp(10), V.dp(4), V.dp(6), V.dp(4)); name.setSingleLine(false);
			SpannableStringBuilder text=new SpannableStringBuilder(label).append("  ");
			int start=text.length(); text.append(badge);
			text.setSpan(new BadgeSpan(recommended), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); name.setText(text);
			row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
			radio=new RadioButton(context); radio.setClickable(false); radio.setFocusable(false); radio.setMinimumWidth(0);
			row.addView(radio, new LinearLayout.LayoutParams(V.dp(36), V.dp(48)));
			reason=SponsorUi.label(view, ""); reason.setPadding(V.dp(46), V.dp(4), 0, 0);
			view.setMinimumHeight(V.dp(72)); view.setFocusable(true); view.setDescendantFocusability(LinearLayout.FOCUS_BLOCK_DESCENDANTS);
			view.setOnClickListener(v->{ if(v.isEnabled()) action.run(); });
			for(int i=0;i<view.getChildCount();i++) view.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
			view.setAccessibilityDelegate(new View.AccessibilityDelegate(){
				@Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info){
					super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(RadioButton.class.getName());
					info.setCheckable(true); info.setChecked(host.isSelected());
				}
			});
		}
		public void update(boolean selected, boolean enabled, String code){
			Context context=view.getContext();
			SponsorUi.selection(view, selected); view.setEnabled(enabled); radio.setChecked(selected);
			radio.setButtonTintList(android.content.res.ColorStateList.valueOf(UiUtils.getThemeColor(context, selected ? R.attr.colorM3Primary : R.attr.colorM3Outline)));
			String unavailable=code==null ? "" : message(context, code);
			reason.setText(unavailable); reason.setVisibility(code==null ? View.GONE : View.VISIBLE);
			view.setContentDescription(label+", "+badge+(unavailable.isEmpty() ? "" : ", "+unavailable));
		}
	}
	/** Inline badge wraps together with the channel name even at 320dp / 1.6x fonts. */
	private static final class BadgeSpan extends ReplacementSpan{
		private final boolean solid;
		BadgeSpan(boolean solid){ this.solid=solid; }
		@Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt metrics){
			Paint badge=badgePaint(paint);
			if(metrics!=null){
				Paint.FontMetricsInt original=paint.getFontMetricsInt();
				metrics.top=original.top; metrics.ascent=original.ascent; metrics.descent=original.descent; metrics.bottom=original.bottom; metrics.leading=original.leading;
			}
			return (int)Math.ceil(badge.measureText(text, start, end)+V.dp(12));
		}
		private Paint badgePaint(Paint original){ Paint paint=new Paint(original); paint.setTextSize(original.getTextSize()*.68f); paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD)); return paint; }
		@Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint original){
			Paint paint=badgePaint(original); float width=paint.measureText(text, start, end)+V.dp(12);
			float height=paint.descent()-paint.ascent()+V.dp(6), center=y+(original.ascent()+original.descent())/2f;
			int accent=Color.rgb(185, 38, 50);
			paint.setColor(accent); paint.setStyle(solid ? Paint.Style.FILL : Paint.Style.STROKE); paint.setStrokeWidth(V.dp(1));
			canvas.drawRoundRect(x, center-height/2f, x+width, center+height/2f, V.dp(4), V.dp(4), paint);
			paint.setStyle(Paint.Style.FILL); paint.setColor(solid ? Color.WHITE : original.getColor());
			canvas.drawText(text, start, end, x+V.dp(6), center-(paint.ascent()+paint.descent())/2f, paint);
		}
	}
}
