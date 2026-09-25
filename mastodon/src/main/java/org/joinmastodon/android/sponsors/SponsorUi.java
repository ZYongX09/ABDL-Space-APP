package org.joinmastodon.android.sponsors;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.model.sponsors.SponsorModels.*;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Currency;

import me.grishka.appkit.utils.V;

/** Small native M3 building blocks. Products, limits and accent colors stay server-owned. */
public final class SponsorUi{
	private SponsorUi(){}
	public static LinearLayout column(Context context){
		LinearLayout layout=new LinearLayout(context);
		layout.setOrientation(LinearLayout.VERTICAL);
		layout.setPadding(V.dp(20), V.dp(12), V.dp(20), V.dp(24));
		return layout;
	}
	public static int surface(Context context){ return UiUtils.getThemeColor(context, R.attr.colorM3Surface); }
	public static int cardColor(Context context){ return UiUtils.alphaBlendColors(surface(context), UiUtils.getThemeColor(context, R.attr.colorM3Primary), .05f); }
	public static GradientDrawable rounded(int color, int radius){
		GradientDrawable shape=new GradientDrawable();
		shape.setColor(color); shape.setCornerRadius(V.dp(radius)); return shape;
	}
	public static TextView text(LinearLayout parent, CharSequence value, boolean heading){
		TextView view=new TextView(parent.getContext());
		view.setTextAppearance(heading ? R.style.m3_title_large : R.style.m3_body_large);
		view.setTextColor(UiUtils.getThemeColor(parent.getContext(), R.attr.colorM3OnSurface));
		view.setText(value);
		view.setPadding(0, V.dp(6), 0, V.dp(6));
		view.setLineSpacing(V.dp(2), 1);
		if(android.os.Build.VERSION.SDK_INT>=28) view.setAccessibilityHeading(heading);
		parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
		return view;
	}
	public static TextView label(LinearLayout parent, CharSequence value){
		TextView view=text(parent, value, false);
		view.setTextAppearance(R.style.m3_label_large);
		view.setTextColor(UiUtils.getThemeColor(parent.getContext(), R.attr.colorM3OnSurfaceVariant));
		return view;
	}
	public static TextView headline(LinearLayout parent, CharSequence value){
		TextView view=text(parent, value, true); view.setTextAppearance(R.style.m3_headline_medium); return view;
	}
	public static LinearLayout card(LinearLayout parent){
		LinearLayout card=column(parent.getContext());
		card.setPadding(V.dp(20), V.dp(16), V.dp(20), V.dp(16));
		card.setBackground(rounded(cardColor(parent.getContext()), 24));
		LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1, -2);
		params.topMargin=V.dp(4); params.bottomMargin=V.dp(12);
		parent.addView(card, params);
		return card;
	}
	/** Tighter 16dp card used by the pixel-oriented supporter screens. */
	public static LinearLayout designCard(LinearLayout parent){
		LinearLayout card=column(parent.getContext());
		card.setPadding(V.dp(18), V.dp(14), V.dp(18), V.dp(14));
		card.setBackground(rounded(cardColor(parent.getContext()), 16));
		LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1, -2);
		params.topMargin=V.dp(4); params.bottomMargin=V.dp(12);
		parent.addView(card, params);
		return card;
	}
	public static Button button(LinearLayout parent, String title, Runnable action){ return action(parent, title, action, R.style.Widget_Mastodon_M3_Button_Filled); }
	public static Button secondary(LinearLayout parent, String title, Runnable action){ return action(parent, title, action, R.style.Widget_Mastodon_M3_Button_Outlined); }
	public static Button textButton(LinearLayout parent, String title, Runnable action){ return action(parent, title, action, R.style.Widget_Mastodon_M3_Button_Text); }
	/** Compact outlined action for a horizontal row, without the full-width/top-margin defaults. */
	public static Button inlineAction(LinearLayout parent, String title, Runnable action){
		Button button=new Button(parent.getContext(), null, 0, R.style.Widget_Mastodon_M3_Button_Outlined);
		button.setText(title); button.setAllCaps(false); button.setSingleLine(true); button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
		button.setPadding(V.dp(10), V.dp(6), V.dp(10), V.dp(6)); button.setMinHeight(V.dp(40)); button.setMinWidth(V.dp(64)); button.setOnClickListener(v->action.run());
		parent.addView(button, new LinearLayout.LayoutParams(-2, V.dp(40))); return button;
	}
	private static Button action(LinearLayout parent, String title, Runnable action, int style){
		Button button=new Button(parent.getContext(), null, 0, style);
		button.setText(title); button.setAllCaps(false); button.setSingleLine(false);
		button.setMaxLines(Integer.MAX_VALUE); button.setEllipsize(null);
		button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
		button.setMinHeight(V.dp(48));
		button.setPadding(V.dp(20), V.dp(12), V.dp(20), V.dp(12));
		button.setOnClickListener(v->action.run());
		LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1, -2); params.topMargin=V.dp(8);
		parent.addView(button, params); return button;
	}
	public static LinearLayout planCard(LinearLayout parent, Plan plan, boolean selected, boolean enabled, Runnable action){
		LinearLayout card=card(parent);
		LinearLayout row=new LinearLayout(parent.getContext()); row.setGravity(Gravity.CENTER_VERTICAL);
		RadioButton radio=new RadioButton(parent.getContext()); radio.setChecked(selected);
		radio.setClickable(false); radio.setFocusable(false); radio.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		radio.setButtonTintList(ColorStateList.valueOf(UiUtils.getThemeColor(parent.getContext(), selected ? R.attr.colorM3Primary : R.attr.colorM3Outline)));
		row.addView(radio, new LinearLayout.LayoutParams(V.dp(40), V.dp(48)));
		TextView name=new TextView(parent.getContext()); name.setTextAppearance(R.style.m3_title_large);
		name.setTextColor(UiUtils.getThemeColor(parent.getContext(), R.attr.colorM3OnSurface)); name.setText(plan.name);
		row.addView(name, new LinearLayout.LayoutParams(0, -2, 1)); card.addView(row);
		headline(card, price(plan)); label(card, duration(parent.getContext(), plan));
		if(plan.description!=null && !plan.description.isBlank()) text(card, plan.description, false);
		if(!plan.enabled) label(card, parent.getContext().getString(R.string.sponsor_ui_plan_disabled));
		selectable(card, selected, enabled, action);
		card.setTag(radio);
		card.setAccessibilityDelegate(new View.AccessibilityDelegate(){
			@Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info){
				super.onInitializeAccessibilityNodeInfo(host, info);
				info.setClassName(RadioButton.class.getName()); info.setCheckable(true); info.setChecked(host.isSelected());
			}
		});
		card.setContentDescription(plan.name+", "+price(plan)+", "+duration(parent.getContext(), plan));
		card.setDescendantFocusability(LinearLayout.FOCUS_BLOCK_DESCENDANTS);
		for(int i=0;i<card.getChildCount();i++) card.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
		return card;
	}

	/** Compact horizontal plan card used by the supporter-center carousel. */
	public static LinearLayout compactPlanCard(LinearLayout parent, Plan plan, boolean selected, boolean enabled, Runnable action){
		Context context=parent.getContext();
		LinearLayout card=new LinearLayout(context);
		card.setOrientation(LinearLayout.VERTICAL);
		card.setGravity(Gravity.CENTER_HORIZONTAL);
		card.setPadding(V.dp(10), V.dp(8), V.dp(10), V.dp(6));
		card.setMinimumHeight(V.dp(116));
		LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(V.dp(132), -2);
		params.rightMargin=V.dp(10);
		parent.addView(card, params);
		TextView name=new TextView(context);
		name.setText(plan.name);
		name.setGravity(Gravity.CENTER);
		name.setSingleLine(true);
		name.setEllipsize(android.text.TextUtils.TruncateAt.END);
		name.setTextAppearance(R.style.m3_title_medium);
		name.setTextColor(UiUtils.getThemeColor(context, R.attr.colorM3OnSurface));
		name.setMinHeight(V.dp(24));
		card.addView(name, new LinearLayout.LayoutParams(-1, -2));
		TextView amount=new TextView(context);
		amount.setText(price(plan));
		amount.setGravity(Gravity.CENTER);
		amount.setSingleLine(true);
		amount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
		amount.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
		amount.setTextColor(UiUtils.getThemeColor(context, R.attr.colorM3OnSurface));
		amount.setMinHeight(V.dp(32));
		card.addView(amount, new LinearLayout.LayoutParams(-1, -2));
		String detail=plan.description==null || plan.description.isBlank() ? duration(context, plan) : plan.description;
		TextView hint=new TextView(context);
		hint.setText(detail);
		hint.setGravity(Gravity.CENTER);
		hint.setMaxLines(2);
		hint.setEllipsize(android.text.TextUtils.TruncateAt.END);
		hint.setTextAppearance(R.style.m3_label_large);
		hint.setTextColor(UiUtils.getThemeColor(context, selected ? R.attr.colorM3Primary : R.attr.colorM3OnSurfaceVariant));
		hint.setMinHeight(V.dp(46));
		card.addView(hint, new LinearLayout.LayoutParams(-1, -2));
		compactSelection(card, selected); card.setEnabled(enabled); card.setAlpha(enabled ? 1f : .55f); card.setFocusable(true); card.setOnClickListener(v->{ if(v.isEnabled()) action.run(); });
		card.setAccessibilityDelegate(new View.AccessibilityDelegate(){
			@Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info){
				super.onInitializeAccessibilityNodeInfo(host, info);
				info.setClassName(RadioButton.class.getName()); info.setCheckable(true); info.setChecked(host.isSelected());
			}
		});
		card.setContentDescription(plan.name+", "+price(plan)+", "+detail); card.setTag(R.id.sponsor_plan_hint, hint);
		card.setDescendantFocusability(LinearLayout.FOCUS_BLOCK_DESCENDANTS);
		name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
		amount.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
		hint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
		return card;
	}
	public static void compactSelection(View view, boolean selected){
		Context context=view.getContext();
		GradientDrawable shape=rounded(selected ? UiUtils.alphaBlendColors(surface(context), UiUtils.getThemeColor(context, R.attr.colorM3Primary), .10f) : cardColor(context), 16);
		shape.setStroke(V.dp(selected ? 2 : 1), UiUtils.getThemeColor(context, selected ? R.attr.colorM3Primary : R.attr.colorM3OutlineVariant));
		view.setBackground(new RippleDrawable(ColorStateList.valueOf(UiUtils.getThemeColor(context, android.R.attr.colorControlHighlight)), shape, null)); view.setSelected(selected);
		Object hint=view.getTag(R.id.sponsor_plan_hint); if(hint instanceof TextView text) text.setTextColor(UiUtils.getThemeColor(context, selected ? R.attr.colorM3Primary : R.attr.colorM3OnSurfaceVariant));
	}
	public static void selectable(View view, boolean selected, boolean enabled, Runnable action){
		selection(view, selected);
		view.setEnabled(enabled);
		view.setAlpha(enabled ? 1f : .55f);
		view.setFocusable(true); view.setOnClickListener(v->{ if(v.isEnabled()) action.run(); });
	}
	public static void selection(View view, boolean selected){
		Context context=view.getContext();
		GradientDrawable shape=rounded(selected ? UiUtils.alphaBlendColors(surface(context), UiUtils.getThemeColor(context, R.attr.colorM3Primary), .10f) : cardColor(context), 24);
		shape.setStroke(V.dp(selected ? 2 : 1), UiUtils.getThemeColor(context, selected ? R.attr.colorM3Primary : R.attr.colorM3OutlineVariant));
		view.setBackground(new RippleDrawable(ColorStateList.valueOf(UiUtils.getThemeColor(context, android.R.attr.colorControlHighlight)), shape, null));
		view.setSelected(selected);
		if(view.getTag() instanceof RadioButton radio){
			radio.setChecked(selected);
			radio.setButtonTintList(ColorStateList.valueOf(UiUtils.getThemeColor(context, selected ? R.attr.colorM3Primary : R.attr.colorM3Outline)));
		}
	}
	public static void quotaMeter(LinearLayout parent, Quota quota){
		Context context=parent.getContext();
		label(parent, context.getString(R.string.sponsor_ui_quota));
		headline(parent, context.getString(R.string.sponsor_ui_remaining, quota.remaining));
		ProgressBar meter=new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
		android.graphics.drawable.LayerDrawable track=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
				rounded(UiUtils.getThemeColor(context, R.attr.colorM3OutlineVariant), 4),
				new android.graphics.drawable.ClipDrawable(rounded(UiUtils.getThemeColor(context, R.attr.colorM3Primary), 4), Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
		});
		track.setId(0, android.R.id.background); track.setId(1, android.R.id.progress);
		meter.setProgressDrawable(track);
		meter.setMax(Math.max(1, quota.limit)); meter.setProgress(Math.min(quota.limit, quota.remaining));
		meter.setProgressTintList(ColorStateList.valueOf(UiUtils.getThemeColor(context, R.attr.colorM3Primary)));
		meter.setProgressBackgroundTintList(ColorStateList.valueOf(UiUtils.getThemeColor(context, R.attr.colorM3OutlineVariant)));
		meter.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1, V.dp(8)); params.topMargin=V.dp(8); params.bottomMargin=V.dp(8);
		parent.addView(meter, params);
		label(parent, context.getString(R.string.sponsor_ui_used, quota.used, quota.limit));
		label(parent, context.getString(R.string.sponsor_ui_reset, date(quota.resetsAt)));
	}
	public static String date(Long seconds){
		if(seconds==null) return "—";
		try{ return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai")).format(Instant.ofEpochSecond(seconds))+"（北京时间）"; }
		catch(RuntimeException ignored){ return "—"; }
	}
	public static String price(Plan plan){
		try{
			BigDecimal amount=BigDecimal.valueOf(plan.priceMinor, 2).stripTrailingZeros();
			if("CNY".equals(plan.currency)) return "¥"+amount.toPlainString();
			NumberFormat format=NumberFormat.getCurrencyInstance(); format.setCurrency(Currency.getInstance(plan.currency));
			return format.format(amount);
		}catch(RuntimeException invalid){ return plan.currency+" "+BigDecimal.valueOf(plan.priceMinor, 2).stripTrailingZeros().toPlainString(); }
	}
	public static String duration(Context context, Plan plan){
		return switch(plan.durationUnit){
			case "day" -> context.getString(R.string.sponsor_ui_days, plan.durationCount);
			case "month" -> context.getString(R.string.sponsor_ui_months, plan.durationCount);
			default -> context.getString(R.string.sponsor_ui_duration_permanent);
		};
	}
	public static String plan(Plan plan){
		String duration=switch(plan.durationUnit){ case "day" -> plan.durationCount+" 天"; case "month" -> plan.durationCount+" 个月"; default -> "永久"; };
		return plan.name+" · "+price(plan)+" / "+duration;
	}
	public static String render(String template, Config config, Quota quota){
		return template.replace("{x}", String.valueOf(config.freeDailyLimit)).replace("{y}", String.valueOf(config.sponsorDailyLimit))
				.replace("{a}", String.valueOf(quota.remaining)).replace("{reset}", date(quota.resetsAt));
	}
	public static String quota(Quota quota){ return "今日原图：已用 "+quota.used+" / "+quota.limit+"，剩余 "+quota.remaining+"\n重置时间："+date(quota.resetsAt); }
}
