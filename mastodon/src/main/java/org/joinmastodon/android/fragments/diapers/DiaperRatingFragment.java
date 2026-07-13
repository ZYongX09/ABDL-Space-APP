package org.joinmastodon.android.fragments.diapers;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.api.requests.diapers.SubmitDiaperRating;
import org.joinmastodon.android.fragments.MastodonToolbarFragment;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.Nav;
import me.grishka.appkit.utils.V;

public class DiaperRatingFragment extends MastodonToolbarFragment{
	private static final int ACCENT=0xFFA1D9F7;
	private int diaperId;
	private String accountID;
	private String brand;
	private String model;
	private String productType;
	private double avgScore;
	private int ratingCount;
	private EditText reviewInput;
	private TextView counterText;
	private TextView submitButton;
	private boolean submitting;
	private final Map<String, Integer> scores=new HashMap<>();

	@Override
	public void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		Bundle args=getArguments();
		diaperId=args!=null ? args.getInt("diaper_id", 0) : 0;
		accountID=args!=null ? args.getString("account") : null;
		brand=args!=null ? args.getString("brand", "") : "";
		model=args!=null ? args.getString("model", "") : "";
		productType=args!=null ? args.getString("product_type", "纸尿裤") : "纸尿裤";
		avgScore=args!=null ? args.getDouble("avg_score", 0) : 0;
		ratingCount=args!=null ? args.getInt("rating_count", 0) : 0;
		scores.put("absorption", 8);
		scores.put("comfort", 8);
		scores.put("thickness", 8);
		scores.put("appearance", 8);
		scores.put("value", 8);
	}

	@Override
	public void onAttach(Activity activity){
		super.onAttach(activity);
		setTitle("纸尿裤评分");
	}

	@Override
	public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState){
		ScrollView scrollView=new ScrollView(getContext());
		scrollView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		scrollView.setFillViewport(true);

		LinearLayout root=new LinearLayout(getContext());
		root.setLayoutParams(new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(V.dp(22), V.dp(28), V.dp(22), V.dp(28));

		TextView title=new TextView(getContext());
		title.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		title.setText((brand + " " + model).trim());
		title.setTextSize(26);
		title.setTextColor(getPrimaryTextColor());
		title.setTypeface(null, Typeface.BOLD);
		root.addView(title);

		TextView subtitle=new TextView(getContext());
		subtitle.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		subtitle.setText(productType==null || productType.isEmpty() ? "纸尿裤" : productType);
		subtitle.setTextSize(16);
		subtitle.setTextColor(getSecondaryTextColor());
		subtitle.setPadding(0, V.dp(8), 0, V.dp(18));
		root.addView(subtitle);

		root.addView(createOverviewCard());
		root.addView(createScoreCard());
		root.addView(createReviewCard());

		submitButton=new TextView(getContext());
		LinearLayout.LayoutParams submitParams=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		submitParams.topMargin=V.dp(26);
		submitButton.setLayoutParams(submitParams);
		submitButton.setText("提交评分");
		submitButton.setTextSize(20);
		submitButton.setTypeface(null, Typeface.BOLD);
		submitButton.setTextColor(Color.WHITE);
		submitButton.setGravity(android.view.Gravity.CENTER);
		submitButton.setPadding(0, V.dp(16), 0, V.dp(16));
		submitButton.setBackground(createRoundedDrawable(ACCENT, 0, V.dp(28)));
		submitButton.setOnClickListener(v->submitRating());
		root.addView(submitButton);

		scrollView.addView(root);
		return scrollView;
	}

	private View createOverviewCard(){
		LinearLayout card=createCard();
		card.setOrientation(LinearLayout.VERTICAL);
		card.setPadding(V.dp(18), V.dp(18), V.dp(18), V.dp(18));

		TextView label=new TextView(getContext());
		label.setText("综合评分");
		label.setTextSize(14);
		label.setTextColor(getPrimaryTextColor());
		card.addView(label);

		TextView score=new TextView(getContext());
		score.setText(avgScore>0 ? String.format(Locale.US, "%.1f", avgScore) : "--");
		score.setTextSize(48);
		score.setTextColor(ACCENT);
		score.setTypeface(null, Typeface.BOLD);
		score.setPadding(0, V.dp(8), 0, 0);
		card.addView(score);

		TextView stars=new TextView(getContext());
		stars.setText(buildStars(avgScore));
		stars.setTextSize(24);
		stars.setTextColor(ACCENT);
		card.addView(stars);

		TextView count=new TextView(getContext());
		count.setText(ratingCount + " 人参与评分");
		count.setTextSize(14);
		count.setTextColor(getSecondaryTextColor());
		count.setPadding(0, V.dp(8), 0, 0);
		card.addView(count);

		return card;
	}

	private View createScoreCard(){
		LinearLayout card=createSectionCard("详细评分");
		addScoreRow(card, "◇", "吸收性", "absorption");
		addScoreRow(card, "☁", "舒适度", "comfort");
		addScoreRow(card, "▤", "厚度", "thickness");
		addScoreRow(card, "✧", "外观", "appearance");
		addScoreRow(card, "◇", "性价比", "value");
		return card;
	}

	private View createReviewCard(){
		LinearLayout card=createSectionCard("使用感受");
		reviewInput=new EditText(getContext());
		reviewInput.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, V.dp(130)));
		reviewInput.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
		reviewInput.setHint("分享你的使用体验...\n例如：\n吸收速度不错，夜间使用也比较安心，整体很舒适...");
		reviewInput.setTextSize(15);
		reviewInput.setTextColor(getPrimaryTextColor());
		reviewInput.setHintTextColor(0xFF91A0B5);
		reviewInput.setPadding(V.dp(12), V.dp(12), V.dp(12), V.dp(12));
		reviewInput.setBackground(createRoundedDrawable(0x00FFFFFF, 0xFFD7E2F0, V.dp(10)));
		reviewInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(500)});
		reviewInput.addTextChangedListener(new TextWatcher(){
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after){}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count){
				counterText.setText(s.length() + "/500");
			}

			@Override
			public void afterTextChanged(Editable s){}
		});
		card.addView(reviewInput);

		counterText=new TextView(getContext());
		counterText.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		counterText.setGravity(android.view.Gravity.END);
		counterText.setText("0/500");
		counterText.setTextSize(14);
		counterText.setTextColor(getSecondaryTextColor());
		counterText.setPadding(0, V.dp(8), 0, 0);
		card.addView(counterText);
		return card;
	}

	private LinearLayout createCard(){
		LinearLayout card=new LinearLayout(getContext());
		LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		params.bottomMargin=V.dp(18);
		card.setLayoutParams(params);
		card.setBackgroundResource(R.drawable.bg_diaper_card);
		return card;
	}

	private LinearLayout createSectionCard(String title){
		LinearLayout card=createCard();
		card.setOrientation(LinearLayout.VERTICAL);
		card.setPadding(V.dp(18), V.dp(18), V.dp(18), V.dp(18));

		LinearLayout titleRow=new LinearLayout(getContext());
		titleRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		titleRow.setOrientation(LinearLayout.HORIZONTAL);
		titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
		View indicator=new View(getContext());
		LinearLayout.LayoutParams indicatorParams=new LinearLayout.LayoutParams(V.dp(4), V.dp(20));
		indicatorParams.setMarginEnd(V.dp(10));
		indicator.setLayoutParams(indicatorParams);
		indicator.setBackground(createRoundedDrawable(ACCENT, 0, V.dp(2)));
		titleRow.addView(indicator);

		TextView titleText=new TextView(getContext());
		titleText.setText(title);
		titleText.setTextSize(18);
		titleText.setTextColor(getPrimaryTextColor());
		titleText.setTypeface(null, Typeface.BOLD);
		titleRow.addView(titleText);
		card.addView(titleRow);
		return card;
	}

	private void addScoreRow(LinearLayout container, String icon, String label, String key){
		LinearLayout row=new LinearLayout(getContext());
		LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		rowParams.topMargin=V.dp(18);
		row.setLayoutParams(rowParams);
		row.setGravity(android.view.Gravity.CENTER_VERTICAL);
		row.setOrientation(LinearLayout.HORIZONTAL);

		TextView name=new TextView(getContext());
		name.setLayoutParams(new LinearLayout.LayoutParams(V.dp(112), ViewGroup.LayoutParams.WRAP_CONTENT));
		name.setText(icon + "  " + label);
		name.setTextSize(18);
		name.setTextColor(getPrimaryTextColor());
		row.addView(name);

		SeekBar seekBar=new SeekBar(getContext());
		seekBar.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		seekBar.setMax(9);
		seekBar.setProgress(scores.get(key)-1);
		seekBar.setProgressTintList(ColorStateList.valueOf(ACCENT));
		seekBar.setThumbTintList(ColorStateList.valueOf(ACCENT));
		seekBar.setProgressBackgroundTintList(ColorStateList.valueOf(0xFFE7EDF5));
		row.addView(seekBar);

		TextView value=new TextView(getContext());
		value.setLayoutParams(new LinearLayout.LayoutParams(V.dp(42), ViewGroup.LayoutParams.WRAP_CONTENT));
		value.setGravity(android.view.Gravity.END);
		value.setText(String.format(Locale.US, "%.1f", (float)scores.get(key)));
		value.setTextSize(18);
		value.setTextColor(getPrimaryTextColor());
		value.setTypeface(null, Typeface.BOLD);
		row.addView(value);

		seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser){
				int score=progress+1;
				scores.put(key, score);
				value.setText(String.format(Locale.US, "%.1f", (float)score));
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar){}

			@Override
			public void onStopTrackingTouch(SeekBar seekBar){}
		});
		container.addView(row);
	}

	private GradientDrawable createRoundedDrawable(int color, int strokeColor, int radius){
		GradientDrawable drawable=new GradientDrawable();
		drawable.setColor(color);
		drawable.setCornerRadius(radius);
		if(strokeColor!=0)
			drawable.setStroke(V.dp(1), strokeColor);
		return drawable;
	}

	private String buildStars(double score){
		int full=(int)Math.round(score/2.0);
		if(full<0) full=0;
		if(full>5) full=5;
		StringBuilder sb=new StringBuilder();
		for(int i=0;i<5;i++)
			sb.append(i<full ? '★' : '☆');
		return sb.toString();
	}

	private int getPrimaryTextColor(){
		return getResources().getColor(R.color.diaper_chip_text);
	}

	private int getSecondaryTextColor(){
		boolean dark=(getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
		return dark ? 0xFF9AA7B6 : 0xFF64748B;
	}

	private void submitRating(){
		if(submitting)
			return;
		String review=reviewInput.getText().toString().trim();
		submitting=true;
		submitButton.setEnabled(false);
		submitButton.setAlpha(0.7f);
		submitButton.setText("提交中...");

		new SubmitDiaperRating(
			diaperId,
			scores.get("absorption"),
			scores.get("comfort"),
			scores.get("thickness"),
			scores.get("appearance"),
			scores.get("value"),
			review
		).setCallback(new Callback<Map<String, Object>>(){
			@Override
			public void onSuccess(Map<String, Object> result){
				if(getActivity()==null) return;
				Toast.makeText(getContext(), "评分成功", Toast.LENGTH_SHORT).show();
				setResult(true, null);
				Nav.finish(DiaperRatingFragment.this);
			}

			@Override
			public void onError(ErrorResponse error){
				if(getActivity()==null) return;
				submitting=false;
				submitButton.setEnabled(true);
				submitButton.setAlpha(1f);
				submitButton.setText("提交评分");
				if(error instanceof MastodonErrorResponse mastodonError && mastodonError.httpStatus==409){
					Toast.makeText(getContext(), "你已经评价过这款纸尿裤", Toast.LENGTH_SHORT).show();
				}else{
					error.showToast(getContext());
				}
			}
		}).exec(accountID);
	}
}
