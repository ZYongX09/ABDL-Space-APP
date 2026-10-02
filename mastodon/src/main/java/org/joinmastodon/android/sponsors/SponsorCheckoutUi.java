package org.joinmastodon.android.sponsors;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.M3AlertDialogBuilder;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.util.function.BooleanSupplier;

import me.grishka.appkit.utils.V;

public final class SponsorCheckoutUi{
	private SponsorCheckoutUi(){}

	public static LinearLayout paymentReminder(LinearLayout parent){
		Context context=parent.getContext();
		LinearLayout card=SponsorUi.card(parent);
		card.setBackground(SponsorUi.rounded(Color.rgb(248, 215, 230), 24));
		TextView title=SponsorUi.text(card, context.getString(R.string.sponsor_ui_pink_title), true);
		title.setTextAppearance(R.style.m3_title_medium);
		title.setTextColor(Color.rgb(122, 31, 77));
		String full=context.getString(R.string.sponsor_ui_pink_hint);
		String key=context.getString(R.string.sponsor_ui_pink_key);
		SpannableStringBuilder text=new SpannableStringBuilder(full);
		for(int start=full.indexOf(key); start>=0; start=full.indexOf(key, start+key.length())){
			text.setSpan(new BackgroundColorSpan(Color.rgb(242, 166, 197)), start, start+key.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			text.setSpan(new ForegroundColorSpan(Color.rgb(122, 31, 77)), start, start+key.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			text.setSpan(new StyleSpan(Typeface.BOLD), start, start+key.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
		TextView body=SponsorUi.text(card, text, false);
		// This card stays baby pink in both themes, so its text must stay dark too.
		body.setTextColor(Color.rgb(85, 39, 61));
		return card;
	}

	public static AlertDialog createQuiz(Context context, BooleanSupplier canContinue, Runnable onCorrect){
		LinearLayout content=SponsorUi.column(context);
		TextView question=SponsorUi.text(content, context.getString(R.string.sponsor_ui_quiz_question), true);
		question.setTextAppearance(R.style.m3_title_medium);
		SponsorUi.label(content, context.getString(R.string.sponsor_ui_quiz_note));
		TextView feedback=SponsorUi.text(content, "", false);
		feedback.setTextColor(UiUtils.getThemeColor(context, R.attr.colorM3Error));
		feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
		feedback.setVisibility(View.GONE);
		ScrollView scroll=new ScrollView(context);
		scroll.addView(content);
		AlertDialog dialog=new M3AlertDialogBuilder(context)
				.setTitle(R.string.sponsor_ui_quiz_title)
				.setView(scroll)
				.setNegativeButton(R.string.sponsor_ui_cancel, null)
				.create();
		boolean[] answered={false};
		for(String option:context.getResources().getStringArray(R.array.sponsor_quiz_options)){
			Button choice=new Button(context, null, 0, R.style.Widget_Mastodon_M3_Button_Tonal);
			choice.setText(option);
			choice.setAllCaps(false);
			choice.setSingleLine(false);
			choice.setMinHeight(V.dp(48));
			choice.setOnClickListener(v->{
				if(answered[0] || !dialog.isShowing() || !canContinue.getAsBoolean()) return;
				if(context.getString(R.string.sponsor_quiz_answer).equals(option)){
					answered[0]=true;
					dialog.dismiss();
					onCorrect.run();
				}else{
					feedback.setText(R.string.sponsor_ui_quiz_wrong);
					feedback.setVisibility(View.VISIBLE);
				}
			});
			LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1, -2);
			params.topMargin=V.dp(6);
			content.addView(choice, params);
		}
		dialog.setCanceledOnTouchOutside(false);
		return dialog;
	}
}
