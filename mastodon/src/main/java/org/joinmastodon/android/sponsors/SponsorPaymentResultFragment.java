package org.joinmastodon.android.sponsors;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.MainActivity;
import org.joinmastodon.android.fragments.MastodonToolbarFragment;
import org.joinmastodon.android.fragments.sponsors.SponsorCenterFragment;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.text.DateFormat;
import java.util.Date;

import me.grishka.appkit.Nav;
import me.grishka.appkit.utils.V;

/** Server-confirmed payment result page. Renders only the receipt; SDK status is never evidence. */
public final class SponsorPaymentResultFragment extends MastodonToolbarFragment{
	private static final String EXTRA="receipt";
	private PaymentReceipt receipt;

	public static SponsorPaymentResultFragment newInstance(PaymentReceipt r){
		var f=new SponsorPaymentResultFragment();
		var args=new Bundle();
		args.putSerializable(EXTRA, r);
		f.setArguments(args);
		return f;
	}

	@Override public void onCreate(Bundle saved){
		super.onCreate(saved);
		if(saved!=null && saved.getSerializable(EXTRA) instanceof PaymentReceipt r) receipt=r;
		else if(getArguments()!=null && getArguments().getSerializable(EXTRA) instanceof PaymentReceipt r) receipt=r;
		setTitle(R.string.sponsor_payment_result_title);
	}

	@Override public void onSaveInstanceState(Bundle out){
		super.onSaveInstanceState(out);
		if(receipt!=null) out.putSerializable(EXTRA, receipt);
	}

	@Override public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle saved){
		var ctx=requireContext();
		int blue=ctx.getColor(R.color.blue_primary_500);
		int primary=UiUtils.getThemeColor(ctx, R.attr.colorM3OnSurface);
		int secondary=UiUtils.getThemeColor(ctx, R.attr.colorM3OnSurfaceVariant);

		var scroll=new ScrollView(ctx); scroll.setFillViewport(true);
		scroll.setBackgroundColor(SponsorUi.surface(ctx));
		var col=SponsorUi.column(ctx);
		scroll.addView(col, new LinearLayout.LayoutParams(-1, -2));

		var check=new ImageView(ctx);
		check.setImageResource(R.drawable.ic_fluent_checkmark_24_filled);
		check.setColorFilter(blue);
		var circle=SponsorUi.rounded(blue & 0x1AFFFFFF, 32);
		check.setBackground(circle);
		check.setPadding(V.dp(16), V.dp(16), V.dp(16), V.dp(16));
		var cw=new LinearLayout(ctx); cw.setGravity(android.view.Gravity.CENTER);
		cw.addView(check, new LinearLayout.LayoutParams(V.dp(64), V.dp(64)));
		col.addView(cw, new LinearLayout.LayoutParams(-1, -2));

		var title=new TextView(ctx); title.setText(R.string.payment_result_title);
		title.setTextAppearance(R.style.m3_headline_medium); title.setTextColor(primary);
		title.setGravity(android.view.Gravity.CENTER);
		col.addView(title, new LinearLayout.LayoutParams(-1, -2));

		var subtitle=new TextView(ctx); subtitle.setText(R.string.payment_result_subtitle);
		subtitle.setTextAppearance(R.style.m3_body_large); subtitle.setTextColor(secondary);
		subtitle.setGravity(android.view.Gravity.CENTER);
		col.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));

		var card=SponsorUi.designCard(col);

		var statusRow=new LinearLayout(ctx); statusRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
		var badge=new TextView(ctx); badge.setText(R.string.payment_result_completed);
		badge.setTextAppearance(R.style.m3_label_large); badge.setTextColor(blue); badge.setTypeface(null, android.graphics.Typeface.BOLD);
		var bbg=SponsorUi.rounded(blue & 0x1AFFFFFF, 12);
		badge.setBackground(bbg); badge.setPadding(V.dp(10), V.dp(4), V.dp(10), V.dp(4));
		statusRow.addView(badge);
		var time=new TextView(ctx);
		time.setText(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(receipt.paidAt*1000)));
		time.setTextAppearance(R.style.m3_label_large); time.setTextColor(secondary); time.setGravity(android.view.Gravity.END);
		statusRow.addView(time, new LinearLayout.LayoutParams(0, -2, 1));
		card.addView(statusRow, new LinearLayout.LayoutParams(-1, -2));

		var amount=new TextView(ctx);
		amount.setText("¥"+receipt.amountMinor/100+"."+String.format("%02d", receipt.amountMinor%100)+" CNY");
		amount.setTextAppearance(R.style.m3_headline_medium); amount.setTextColor(primary);
		card.addView(amount, new LinearLayout.LayoutParams(-1, -2));

		if(receipt.productName!=null && !receipt.productName.isBlank()){
			var plan=new TextView(ctx); plan.setText(receipt.productName);
			plan.setTextAppearance(R.style.m3_body_large); plan.setTextColor(secondary);
			card.addView(plan, new LinearLayout.LayoutParams(-1, -2));
		}

		var div=new View(ctx); div.setBackgroundColor(UiUtils.getThemeColor(ctx, R.attr.colorM3OutlineVariant));
		card.addView(div, new LinearLayout.LayoutParams(-1, V.dp(1)));

		addRow(card, R.string.payment_result_method, ctx.getString(R.string.payment_result_method_alipay), secondary, primary);
		if(receipt.tradeNo!=null) addRow(card, R.string.payment_result_trade_no, receipt.tradeNo, secondary, primary);

		SponsorUi.button(col, ctx.getString(R.string.payment_result_center), ()->{
			if(getActivity()!=null){ Nav.finish(this); Nav.go(getActivity(), SponsorCenterFragment.class, getArguments()); }
		});
		SponsorUi.secondary(col, ctx.getString(R.string.payment_result_home), ()->{
			if(getActivity() instanceof MainActivity main){
				var intent=new android.content.Intent(getActivity(), MainActivity.class);
				intent.setFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
				startActivity(intent); getActivity().finish();
			}
		});

		return scroll;
	}

	private void addRow(LinearLayout parent, int labelRes, String value, int secondary, int primary){
		var row=new LinearLayout(parent.getContext()); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
		var label=new TextView(parent.getContext()); label.setText(labelRes);
		label.setTextAppearance(R.style.m3_body_large); label.setTextColor(secondary);
		row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
		var val=new TextView(parent.getContext()); val.setText(value);
		val.setTextAppearance(R.style.m3_body_large); val.setTextColor(primary);
		val.setGravity(android.view.Gravity.END);
		row.addView(val, new LinearLayout.LayoutParams(-2, -2));
		parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
	}
}
