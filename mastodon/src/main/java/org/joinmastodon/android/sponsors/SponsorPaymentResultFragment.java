package org.joinmastodon.android.sponsors;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.joinmastodon.android.R;
import org.joinmastodon.android.MainActivity;
import org.joinmastodon.android.fragments.sponsors.SponsorCenterFragment;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.text.DateFormat;
import java.util.Date;

import me.grishka.appkit.Nav;
import me.grishka.appkit.fragments.ToolbarFragment;
import me.grishka.appkit.utils.V;

/** Server-confirmed payment result page. Renders only the receipt; SDK status is never evidence. */
public final class SponsorPaymentResultFragment extends ToolbarFragment{
	private static final String EXTRA="receipt";
	private PaymentReceipt receipt;

	public static SponsorPaymentResultFragment newInstance(PaymentReceipt r){
		var f=new SponsorPaymentResultFragment();
		var args=new Bundle();
		args.putSerializable(EXTRA, r);
		f.setArguments(args);
		return f;
	}

	@Override public void onCreate(@Nullable Bundle saved){
		super.onCreate(saved);
		if(saved!=null && saved.getSerializable(EXTRA) instanceof PaymentReceipt r) receipt=r;
		else if(getArguments()!=null && getArguments().getSerializable(EXTRA) instanceof PaymentReceipt r) receipt=r;
		setTitle(R.string.sponsor_payment_result_title);
	}

	@Override public void onSaveInstanceState(@NonNull Bundle out){
		super.onSaveInstanceState(out);
		if(receipt!=null) out.putSerializable(EXTRA, receipt);
	}

	@Override public View onCreateContentView(@Nullable LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle saved){
		var ctx=requireContext();
		int primary=UiUtils.getThemeColor(ctx, android.R.attr.textColorPrimary);
		int secondary=UiUtils.getThemeColor(ctx, android.R.attr.textColorSecondary);
		int blue=UiUtils.getThemeColor(ctx, R.attr.blue_primary_500);
		int surface=UiUtils.getThemeColor(ctx, com.google.android.material.R.attr.colorSurfaceVariant);

		var scroll=new ScrollView(ctx); scroll.setFillViewport(true);
		var col=new LinearLayout(ctx); col.setOrientation(LinearLayout.VERTICAL);
		col.setPadding(V.dp(24), V.dp(32), V.dp(24), V.dp(24));
		scroll.addView(col, new ViewGroup.LayoutParams(-1, -2));

		var check=new ImageView(ctx); check.setImageResource(R.drawable.ic_fluent_checkmark_24_filled);
		check.setColorFilter(blue);
		var circle=new android.graphics.drawable.GradientDrawable();
		circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
		circle.setColor(blue & 0x1AFFFFFF);
		check.setBackground(circle);
		check.setPadding(V.dp(16), V.dp(16), V.dp(16), V.dp(16));
		var cw=new LinearLayout(ctx); cw.setGravity(android.view.Gravity.CENTER);
		cw.addView(check, new LinearLayout.LayoutParams(V.dp(64), V.dp(64)));
		col.addView(cw, new LinearLayout.LayoutParams(-1, -2));

		col.addView(label(ctx, R.string.payment_result_title, 28, true, primary, android.view.Gravity.CENTER), lp(-1, -2, 0, 16, 0, 4));
		col.addView(label(ctx, R.string.payment_result_subtitle, 14, false, secondary, android.view.Gravity.CENTER), lp(-1, -2, 0, 0, 0, 24));

		var card=new LinearLayout(ctx); card.setOrientation(LinearLayout.VERTICAL);
		card.setPadding(V.dp(20), V.dp(20), V.dp(20), V.dp(20));
		var bg=new android.graphics.drawable.GradientDrawable(); bg.setColor(surface); bg.setCornerRadius(V.dp(16));
		card.setBackground(bg);

		if(receipt==null) return scroll;

		var statusRow=new LinearLayout(ctx); statusRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
		var badge=new TextView(ctx); badge.setText(R.string.payment_result_completed);
		badge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12); badge.setTypeface(null, android.graphics.Typeface.BOLD); badge.setTextColor(blue);
		var bbg=new android.graphics.drawable.GradientDrawable(); bbg.setColor(blue & 0x1AFFFFFF); bbg.setCornerRadius(V.dp(12));
		badge.setBackground(bbg); badge.setPadding(V.dp(10), V.dp(4), V.dp(10), V.dp(4));
		statusRow.addView(badge);
		var time=term(ctx, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(receipt.paidAt*1000)), secondary);
		time.setGravity(android.view.Gravity.END);
		statusRow.addView(time, new LinearLayout.LayoutParams(0, -2, 1));
		card.addView(statusRow, lp(-1, -2, 0, 0, 0, 12));

		card.addView(label(ctx, "¥"+receipt.amountMinor/100+"."+String.format("%02d", receipt.amountMinor%100)+" CNY", 32, true, primary, 0), lp(-1, -2, 0, 0, 0, 4));
		if(receipt.productName!=null && !receipt.productName.isBlank())
			card.addView(label(ctx, receipt.productName, 14, false, secondary, 0), lp(-1, -2, 0, 0, 0, 12));

		var div=new View(ctx); div.setBackgroundColor(UiUtils.getThemeColor(ctx, android.R.attr.colorControlHighlight));
		card.addView(div, new LinearLayout.LayoutParams(-1, V.dp(1)));

		card.addView(pair(ctx, R.string.payment_result_method, R.string.payment_result_method_alipay, secondary, primary), lp(-1, -2, 0, 12, 0, 8));
		if(receipt.tradeNo!=null) card.addView(pair(ctx, R.string.payment_result_trade_no, receipt.tradeNo, secondary, primary), lp(-1, -2, 0, 0, 0, 0));
		col.addView(card, lp(-1, -2, 0, 24, 0, 24));

		var centerBtn=button(ctx, R.string.payment_result_center, blue, 0xFFFFFFFF, makeBg(blue, V.dp(24), 0));
		centerBtn.setOnClickListener(v -> { if(getActivity()!=null){ Nav.finish(this); Nav.go(getActivity(), SponsorCenterFragment.class, getArguments()); } });
		col.addView(centerBtn, lp(-1, V.dp(48), 0, 0, 0, 12));

		var homeBtn=button(ctx, R.string.payment_result_home, 0, blue, makeBg(0, V.dp(24), blue));
		homeBtn.setOnClickListener(v -> {
			if(getActivity()!=null){
				var intent=new android.content.Intent(getActivity(), MainActivity.class);
				intent.setFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
				startActivity(intent); getActivity().finish();
			}
		});
		col.addView(homeBtn, lp(-1, V.dp(48), 0, 0, 0, 0));

		return scroll;
	}

	private TextView label(android.content.Context c, int res, int sp, boolean bold, int color, int gravity){
		return label(c, c.getString(res), sp, bold, color, gravity);
	}
	private TextView label(android.content.Context c, String text, int sp, boolean bold, int color, int gravity){
		var t=new TextView(c); t.setText(text); t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
		t.setTypeface(null, bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
		t.setTextColor(color); t.setGravity(gravity); return t;
	}
	private TextView term(android.content.Context c, String text, int color){
		var t=new TextView(c); t.setText(text); t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12); t.setTextColor(color); return t;
	}
	private LinearLayout pair(android.content.Context c, int labelRes, String value, int secondary, int primary){
		var row=new LinearLayout(c); row.setOrientation(LinearLayout.HORIZONTAL);
		row.addView(label(c, c.getString(labelRes), 14, false, secondary, 0), new LinearLayout.LayoutParams(0, -2, 1));
		row.addView(label(c, value, 14, false, primary, android.view.Gravity.END), new LinearLayout.LayoutParams(-2, -2));
		return row;
	}
	private LinearLayout pair(android.content.Context c, int labelRes, int valueRes, int secondary, int primary){
		return pair(c, labelRes, c.getString(valueRes), secondary, primary);
	}
	private Button button(android.content.Context c, int res, int bg, int fg, android.graphics.drawable.Drawable d){
		var b=new Button(c, null, 0); b.setText(res); b.setAllCaps(false); b.setTextColor(fg); b.setBackground(d);
		b.setMinHeight(V.dp(48)); return b;
	}
	private android.graphics.drawable.Drawable makeBg(int color, float radius, int stroke){
		var d=new android.graphics.drawable.GradientDrawable(); d.setColor(color); d.setCornerRadius(radius);
		if(stroke>0) d.setStroke(V.dp(1), stroke); return d;
	}
	private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b){
		var p=new LinearLayout.LayoutParams(w, h); p.setMargins(V.dp(l), V.dp(t), V.dp(r), V.dp(b)); return p;
	}
}
