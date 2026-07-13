package org.joinmastodon.android.fragments.diapers;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.diapers.GetDiaperDetail;
import org.joinmastodon.android.api.requests.diapers.GetMyDiaperRating;
import org.joinmastodon.android.model.DiaperReview;
import org.joinmastodon.android.ui.OutlineProviders;
import org.joinmastodon.android.ui.views.FlowLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.fragments.LoaderFragment;
import me.grishka.appkit.imageloader.ViewImageLoader;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.V;

public class DiaperDetailFragment extends LoaderFragment {
	private static final int RATING_RESULT=1;
	private int diaperId;
	private String accountID;
	private Map<String, Object> diaperData;
	private List<DiaperReview> reviews = new ArrayList<>();
	private LinearLayout imagesContainer;
	private LinearLayout sizesContainer;
	private LinearLayout reviewListContainer;
	private TextView reviewsTitleText;
	private TextView sizesTitleText;
	private boolean checkingOwnRating;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		diaperId = getArguments() != null ? getArguments().getInt("diaper_id", 0) : 0;
		accountID = getArguments() != null ? getArguments().getString("account") : null;
	}

	@Override
	public void onAttach(Activity activity) {
		super.onAttach(activity);
		setTitle(getString(R.string.diaper_detail));
	}

	@SuppressWarnings("unchecked")
	@Override
	public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		FrameLayout wrapper = new FrameLayout(getContext());
		wrapper.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

		// ScrollView 包裹整个内容
		android.widget.ScrollView scrollView = new android.widget.ScrollView(getContext());
		scrollView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		scrollView.setFillViewport(true);

		LinearLayout root = new LinearLayout(getContext());
		root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(0, V.dp(4), 0, V.dp(80));

		// 产品图片区域
		imagesContainer = new LinearLayout(getContext());
		imagesContainer.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		imagesContainer.setOrientation(LinearLayout.HORIZONTAL);
		imagesContainer.setPadding(V.dp(12), 0, V.dp(12), 0);
		root.addView(imagesContainer);

		// 产品信息卡片
		View infoCard = inflater.inflate(R.layout.item_diaper_info, root, false);
		root.addView(infoCard);

		// 尺码标题
		sizesTitleText = new TextView(getContext());
		sizesTitleText.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		sizesTitleText.setPadding(V.dp(16), V.dp(16), V.dp(16), V.dp(8));
		sizesTitleText.setText("尺码");
		sizesTitleText.setTextSize(17);
		sizesTitleText.setTextColor(getResources().getColor(R.color.diaper_chip_text));
		sizesTitleText.setTypeface(null, android.graphics.Typeface.BOLD);
		root.addView(sizesTitleText);

		// 尺码列表
		sizesContainer = new LinearLayout(getContext());
		sizesContainer.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		sizesContainer.setOrientation(LinearLayout.VERTICAL);
		root.addView(sizesContainer);

		// 用户评价标题
		reviewsTitleText = new TextView(getContext());
		reviewsTitleText.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		reviewsTitleText.setPadding(V.dp(16), V.dp(20), V.dp(16), V.dp(12));
		reviewsTitleText.setTextSize(16);
		reviewsTitleText.setTextColor(getResources().getColor(R.color.diaper_chip_text));
		reviewsTitleText.setTypeface(null, android.graphics.Typeface.BOLD);
		root.addView(reviewsTitleText);

		// 评价列表
		reviewListContainer = new LinearLayout(getContext());
		reviewListContainer.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		reviewListContainer.setOrientation(LinearLayout.VERTICAL);
		root.addView(reviewListContainer);

		scrollView.addView(root);
		wrapper.addView(scrollView);

		// 底部操作按钮（固定在底部，带背景）
		LinearLayout bottomBarWrapper = new LinearLayout(getContext());
		FrameLayout.LayoutParams bottomWrapperParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		bottomWrapperParams.gravity = android.view.Gravity.BOTTOM;
		bottomBarWrapper.setLayoutParams(bottomWrapperParams);
		bottomBarWrapper.setOrientation(LinearLayout.VERTICAL);
		bottomBarWrapper.setBackgroundResource(R.drawable.bg_diaper_card);
		bottomBarWrapper.setPadding(V.dp(16), V.dp(12), V.dp(16), V.dp(12));

		LinearLayout bottomBar = new LinearLayout(getContext());
		bottomBar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		bottomBar.setOrientation(LinearLayout.HORIZONTAL);
		bottomBar.setGravity(android.view.Gravity.CENTER);

		// 写评分按钮
		TextView rateBtn = new TextView(getContext());
		LinearLayout.LayoutParams rateParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		rateBtn.setLayoutParams(rateParams);
		rateBtn.setText("★ 写评分");
		rateBtn.setTextSize(14);
		rateBtn.setTextColor(getResources().getColor(R.color.diaper_accent_text));
		rateBtn.setGravity(android.view.Gravity.CENTER);
		rateBtn.setBackgroundResource(R.drawable.bg_diaper_chip_selected);
		rateBtn.setPadding(V.dp(12), V.dp(12), V.dp(12), V.dp(12));
		rateBtn.setOnClickListener(v -> {
			openRatingPage();
		});
		bottomBar.addView(rateBtn);

		// 加入对比按钮
		TextView compareBtn = new TextView(getContext());
		LinearLayout.LayoutParams compareParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
		compareParams.setMarginStart(V.dp(10));
		compareBtn.setLayoutParams(compareParams);
		compareBtn.setText("⚖ 加入对比");
		compareBtn.setTextSize(14);
		compareBtn.setTextColor(0xFF999999);
		compareBtn.setGravity(android.view.Gravity.CENTER);
		compareBtn.setBackgroundResource(R.drawable.bg_diaper_chip);
		compareBtn.setPadding(V.dp(12), V.dp(12), V.dp(12), V.dp(12));
		compareBtn.setEnabled(true);
		compareBtn.setAlpha(0.6f);
		compareBtn.setOnClickListener(v -> {
			android.widget.Toast.makeText(getContext(), "即将开放，敬请期待", android.widget.Toast.LENGTH_SHORT).show();
		});
		bottomBar.addView(compareBtn);

		bottomBarWrapper.addView(bottomBar);
		wrapper.addView(bottomBarWrapper);
		return wrapper;
	}

	@Override
	protected void onShown() {
		super.onShown();
		if (!loaded && !dataLoading) {
			loadData();
		}
	}

	@Override
	public void onFragmentResult(int reqCode, boolean success, Bundle result){
		if(reqCode==RATING_RESULT && success && !dataLoading){
			loadData();
		}
	}

	@Override
	protected void doLoadData() {
		loadData();
	}

	@Override
	public void onRefresh() {
		loadData();
	}

	@SuppressWarnings("unchecked")
	public void loadData() {
		dataLoading = true;
		if (!loaded) {
			showProgress();
		}

		new GetDiaperDetail(diaperId)
			.setCallback(new Callback<Map<String, Object>>() {
				@Override
				public void onSuccess(Map<String, Object> result) {
					if (getActivity() == null) return;
					diaperData = (Map<String, Object>) result.get("diaper");
					reviews.clear();
					List<Map<String, Object>> reviewsList = (List<Map<String, Object>>) result.get("reviews");
					if (reviewsList != null) {
						Gson gson = new Gson();
						reviews = gson.fromJson(
							gson.toJson(reviewsList),
							new TypeToken<List<DiaperReview>>(){}.getType()
						);
					}
					buildUI();
					dataLoaded();
				}

				@Override
				public void onError(ErrorResponse error) {
					if (getActivity() == null) return;
					dataLoaded();
					error.showToast(getContext());
				}
			})
			.exec(accountID);
	}

	@SuppressWarnings("unchecked")
	private void buildUI() {
		if (diaperData == null) return;

		// 标题
		String brand = (String) diaperData.getOrDefault("brand", "");
		String model = (String) diaperData.getOrDefault("model", "");
		setTitle(brand + " " + model);

		// 图片
		buildImages();

		// 产品信息
		buildInfoCard();

		// 尺码
		buildSizes();

		// 评价列表
		buildReviews();
	}

	@SuppressWarnings("unchecked")
	private void buildImages() {
		imagesContainer.removeAllViews();
		List<String> images = (List<String>) diaperData.get("images");
		if (images == null || images.isEmpty()) return;

		// 图片卡片容器
		LinearLayout imageCard = new LinearLayout(getContext());
		imageCard.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		imageCard.setOrientation(LinearLayout.VERTICAL);
		imageCard.setBackgroundResource(R.drawable.bg_diaper_card);
		imageCard.setPadding(V.dp(12), V.dp(12), V.dp(12), V.dp(12));
		imageCard.setClipToOutline(true);

		HorizontalScrollView scroll = new HorizontalScrollView(getContext());
		scroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		scroll.setHorizontalScrollBarEnabled(false);
		scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

		LinearLayout imageRow = new LinearLayout(getContext());
		imageRow.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		imageRow.setOrientation(LinearLayout.HORIZONTAL);

		for (String imgUrl : images) {
			ImageView img = new ImageView(getContext());
			int imgWidth = V.dp(180);
			int imgHeight = V.dp(180);
			LinearLayout.LayoutParams imgParams = new LinearLayout.LayoutParams(imgWidth, imgHeight);
			imgParams.setMarginEnd(V.dp(8));
			img.setLayoutParams(imgParams);
			img.setScaleType(ImageView.ScaleType.CENTER_CROP);
			img.setBackgroundResource(R.drawable.bg_diaper_logo);
			img.setOutlineProvider(new ViewOutlineProvider() {
				@Override
				public void getOutline(View view, Outline outline) {
					outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), V.dp(12));
				}
			});
			img.setClipToOutline(true);
			ViewImageLoader.loadWithoutAnimation(img, null,
				new UrlImageLoaderRequest(imgUrl, imgWidth, imgHeight));
			imageRow.addView(img);
		}

		scroll.addView(imageRow);
		imageCard.addView(scroll);
		imagesContainer.addView(imageCard);
	}

	@SuppressWarnings("unchecked")
	private void buildInfoCard() {
		// 品牌Logo
		ImageView brandLogo = getView().findViewById(R.id.brand_logo);
		TextView brandText = getView().findViewById(R.id.brand_text);
		TextView modelText = getView().findViewById(R.id.model_text);

		if (brandLogo != null && brandText != null) {
			String brandLogoUrl = (String) diaperData.get("brand_logo");
			if (brandLogoUrl != null && !brandLogoUrl.isEmpty()) {
				brandLogo.setVisibility(View.VISIBLE);
				brandText.setVisibility(View.GONE);
				ViewImageLoader.loadWithoutAnimation(brandLogo, null,
					new UrlImageLoaderRequest(brandLogoUrl, V.dp(60), V.dp(24)));
			} else {
				brandLogo.setVisibility(View.GONE);
				brandText.setVisibility(View.VISIBLE);
				brandText.setText((String) diaperData.getOrDefault("brand", ""));
			}
		}

		if (modelText != null) {
			modelText.setText((String) diaperData.getOrDefault("model", ""));
		}

		// 类型
		setInfoRow(R.id.row_type, R.id.type_text, "product_type");

		// 厚度
		LinearLayout rowThickness = getView().findViewById(R.id.row_thickness);
		TextView thicknessText = getView().findViewById(R.id.thickness_text);
		if (rowThickness != null && thicknessText != null) {
			rowThickness.setVisibility(View.GONE);
			Object thickness = diaperData.get("thickness");
			if (thickness != null) {
				rowThickness.setVisibility(View.VISIBLE);
				thicknessText.setText(thickness + "mm");
			}
		}

		// 成人实际吸收
		setInfoRow(R.id.row_absorbency_adult, R.id.absorbency_adult_text, "absorbency_adult");

		// 厂家标称吸收
		setInfoRow(R.id.row_absorbency_mfr, R.id.absorbency_mfr_text, "absorbency_mfr");

		// 参考价
		setInfoRow(R.id.row_price, R.id.price_text, "avg_price");

		// 官网
		LinearLayout rowUrl = getView().findViewById(R.id.row_url);
		TextView visitUrlBtn = getView().findViewById(R.id.visit_url_btn);
		if (rowUrl != null && visitUrlBtn != null) {
			rowUrl.setVisibility(View.GONE);
			String officialUrl = (String) diaperData.get("official_url");
			if (officialUrl != null && !officialUrl.isEmpty()) {
				rowUrl.setVisibility(View.VISIBLE);
				visitUrlBtn.setOnClickListener(v -> {
					Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(officialUrl));
					startActivity(intent);
				});
			}
		}
	}

	private void setInfoRow(int rowId, int textId, String key) {
		LinearLayout row = getView().findViewById(rowId);
		TextView text = getView().findViewById(textId);
		if (row != null && text != null) {
			row.setVisibility(View.GONE);
			String value = (String) diaperData.get(key);
			if (value != null && !value.isEmpty()) {
				row.setVisibility(View.VISIBLE);
				text.setText(value);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private void buildSizes() {
		sizesContainer.removeAllViews();
		List<Map<String, Object>> sizes = (List<Map<String, Object>>) diaperData.get("sizes");
		sizesTitleText.setVisibility(sizes == null || sizes.isEmpty() ? View.GONE : View.VISIBLE);
		if (sizes == null || sizes.isEmpty()) {
			return;
		}

		for (Map<String, Object> size : sizes) {
			View sizeView = LayoutInflater.from(getContext()).inflate(R.layout.item_diaper_size, sizesContainer, false);
			TextView label = sizeView.findViewById(R.id.size_label);
			TextView info = sizeView.findViewById(R.id.size_info);

			label.setText((String) size.getOrDefault("label", ""));
			int waistMin = ((Number) size.getOrDefault("waist_min", 0)).intValue();
			int waistMax = ((Number) size.getOrDefault("waist_max", 0)).intValue();
			int hipMin = ((Number) size.getOrDefault("hip_min", 0)).intValue();
			int hipMax = ((Number) size.getOrDefault("hip_max", 0)).intValue();
			info.setText(String.format("腰围 %d-%dcm · 臀围 %d-%dcm", waistMin, waistMax, hipMin, hipMax));

			sizesContainer.addView(sizeView);
		}
	}

	private void buildReviews() {
		reviewListContainer.removeAllViews();

		// 更新评价标题
		if (reviewsTitleText != null) {
			reviewsTitleText.setText(String.format("用户评价 (%d)", reviews.size()));
		}

		if (reviews.isEmpty()) {
			TextView emptyText = new TextView(getContext());
			emptyText.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
			emptyText.setPadding(V.dp(16), V.dp(24), V.dp(16), V.dp(24));
			emptyText.setText("暂无评价");
			emptyText.setTextSize(14);
			emptyText.setGravity(android.view.Gravity.CENTER);
			emptyText.setTextColor(getResources().getColor(R.color.diaper_chip_text));
			reviewListContainer.addView(emptyText);
			return;
		}

		for (DiaperReview review : reviews) {
			View reviewView = LayoutInflater.from(getContext()).inflate(R.layout.item_diaper_review, reviewListContainer, false);
			TextView username = reviewView.findViewById(R.id.username);
			TextView reviewDate = reviewView.findViewById(R.id.review_date);
			TextView reviewText = reviewView.findViewById(R.id.review_text);
			ViewGroup scoresContainer = reviewView.findViewById(R.id.scores_container);

			// 用户名
			if (review.user != null) {
				username.setText(review.user.username);
			}

			// 日期
			if (review.created_at != null && review.created_at.length() >= 10) {
				String date = review.created_at.substring(0, 10);
				reviewDate.setText(date);
			} else {
				reviewDate.setVisibility(View.GONE);
			}

			// 评价文字
			if (review.review != null && !review.review.isEmpty()) {
				reviewText.setVisibility(View.VISIBLE);
				reviewText.setText(review.review);
			}

			// 5维度评分标签
			addScoreTag(scoresContainer, "吸收性", review.absorption_score, "#1976D2");
			addScoreTag(scoresContainer, "舒适度", review.comfort_score, "#4CAF50");
			addScoreTag(scoresContainer, "厚度", review.thickness_score, "#FF9800");
			addScoreTag(scoresContainer, "外观", review.appearance_score, "#9C27B0");
			addScoreTag(scoresContainer, "性价比", review.value_score, "#F44336");

			reviewListContainer.addView(reviewView);
		}
	}

	private void addScoreTag(ViewGroup container, String label, int score, String color) {
		TextView tag = new TextView(getContext());
		ViewGroup.MarginLayoutParams tagParams = new ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		tagParams.setMarginEnd(V.dp(8));
		tagParams.bottomMargin = V.dp(8);
		tag.setLayoutParams(tagParams);
		tag.setPadding(V.dp(12), V.dp(6), V.dp(12), V.dp(6));
		tag.setTextSize(12);
		tag.setText(String.format("%s %d/10", label, score));

		GradientDrawable bg = new GradientDrawable();
		bg.setColor(Color.parseColor("#1A" + color.substring(1)));
		bg.setCornerRadius(V.dp(8));
		tag.setBackground(bg);
		tag.setTextColor(Color.parseColor(color));

		container.addView(tag);
	}

	private void openRatingPage() {
		if (diaperData == null || dataLoading) {
			android.widget.Toast.makeText(getContext(), "请稍候，产品信息加载中", android.widget.Toast.LENGTH_SHORT).show();
			return;
		}
		if(checkingOwnRating)
			return;
		checkingOwnRating=true;
		new GetMyDiaperRating(diaperId)
			.setCallback(new Callback<Map<String, Object>>(){
				@Override
				public void onSuccess(Map<String, Object> result){
					checkingOwnRating=false;
					if(getActivity()==null)
						return;
					if(result.get("rating")!=null){
						android.widget.Toast.makeText(getContext(), "你已经评价过这款纸尿裤", android.widget.Toast.LENGTH_SHORT).show();
						return;
					}
					openRatingForm();
				}

				@Override
				public void onError(ErrorResponse error){
					checkingOwnRating=false;
					if(getActivity()!=null)
						error.showToast(getContext());
				}
			})
			.exec(accountID);
	}

	private void openRatingForm(){
		Bundle args = new Bundle();
		args.putString("account", accountID);
		args.putInt("diaper_id", diaperId);
		args.putString("brand", (String) diaperData.getOrDefault("brand", ""));
		args.putString("model", (String) diaperData.getOrDefault("model", ""));
		args.putString("product_type", (String) diaperData.getOrDefault("product_type", "纸尿裤"));
		Object avgScore = diaperData.get("avg_score");
		if (avgScore instanceof Number) {
			args.putDouble("avg_score", ((Number) avgScore).doubleValue());
		}
		Object ratingCount = diaperData.get("rating_count");
		if (ratingCount instanceof Number) {
			args.putInt("rating_count", ((Number) ratingCount).intValue());
		}
		Nav.goForResult(getActivity(), DiaperRatingFragment.class, args, RATING_RESULT, this);
	}
}
