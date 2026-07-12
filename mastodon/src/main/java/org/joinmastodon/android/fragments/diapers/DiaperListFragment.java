package org.joinmastodon.android.fragments.diapers;

import android.app.Activity;
import android.graphics.Outline;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.diapers.GetDiaperBrands;
import org.joinmastodon.android.api.requests.diapers.GetDiaperList;
import org.joinmastodon.android.model.Diaper;
import org.joinmastodon.android.ui.OutlineProviders;

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

public class DiaperListFragment extends LoaderFragment {
	private RecyclerView recyclerView;
	private SwipeRefreshLayout swipeRefreshLayout;
	private DiaperAdapter adapter;
	private List<Diaper> data = new ArrayList<>();
	private String currentSearch = "";
	private String currentBrand = "";
	private String currentSort = "id";
	private int currentPage = 1;
	private boolean loadingMore = false;
	private boolean hasMore = true;
	private List<String> brands = new ArrayList<>();
	private LinearLayout chipsContainer;
	private View emptyState;
	private String accountID;
	private EditText searchInput;
	private final Handler searchHandler = new Handler(Looper.getMainLooper());
	private Runnable searchRunnable;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		accountID = getArguments() != null ? getArguments().getString("account") : null;

		if (savedInstanceState != null) {
			currentSearch = savedInstanceState.getString("search", "");
			currentBrand = savedInstanceState.getString("brand", "");
			currentSort = savedInstanceState.getString("sort", "id");
		}
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putString("search", currentSearch);
		outState.putString("brand", currentBrand);
		outState.putString("sort", currentSort);
	}

	@Override
	public void onAttach(Activity activity) {
		super.onAttach(activity);
		setTitle(getString(R.string.diaper_list));
	}

	@Override
	public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		FrameLayout wrapper = new FrameLayout(getContext());
		wrapper.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

		LinearLayout root = new LinearLayout(getContext());
		root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		root.setOrientation(LinearLayout.VERTICAL);

		// 搜索框
		View searchView = inflater.inflate(R.layout.diaper_search_bar, root, false);
		searchInput = searchView.findViewById(R.id.search_input);
		if (searchInput != null) {
			if (!currentSearch.isEmpty()) {
				searchInput.setText(currentSearch);
			}
			searchInput.addTextChangedListener(new android.text.TextWatcher() {
				@Override
				public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
				@Override
				public void onTextChanged(CharSequence s, int start, int before, int count) {}
				@Override
				public void afterTextChanged(android.text.Editable s) {
					// 防抖：300ms延迟
					if (searchRunnable != null) searchHandler.removeCallbacks(searchRunnable);
					searchRunnable = () -> {
						String newSearch = s.toString();
						if (!newSearch.equals(currentSearch)) {
							currentSearch = newSearch;
							currentPage = 1;
							hasMore = true;
							loadData();
						}
					};
					searchHandler.postDelayed(searchRunnable, 300);
				}
			});
		}
		root.addView(searchView);

		// 品牌筛选 chips (HorizontalScrollView)
		HorizontalScrollView chipsScroll = new HorizontalScrollView(getContext());
		chipsScroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		chipsScroll.setHorizontalScrollBarEnabled(false);
		chipsScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

		chipsContainer = new LinearLayout(getContext());
		chipsContainer.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		chipsContainer.setOrientation(LinearLayout.HORIZONTAL);
		chipsContainer.setPadding(V.dp(16), V.dp(8), V.dp(16), V.dp(8));
		chipsScroll.addView(chipsContainer);
		root.addView(chipsScroll);

		// 下拉刷新
		swipeRefreshLayout = new SwipeRefreshLayout(getContext());
		swipeRefreshLayout.setColorSchemeResources(android.R.color.holo_blue_dark);
		swipeRefreshLayout.setOnRefreshListener(() -> {
			currentPage = 1;
			hasMore = true;
			loadData();
		});

		// 空状态
		emptyState = inflater.inflate(R.layout.friend_request_empty_state, swipeRefreshLayout, false);
		emptyState.setVisibility(View.GONE);

		// RecyclerView
		recyclerView = new RecyclerView(getContext());
		recyclerView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
		recyclerView.setClipToPadding(false);
		recyclerView.setPadding(0, V.dp(4), 0, V.dp(80));
		adapter = new DiaperAdapter();
		recyclerView.setAdapter(adapter);

		// 滚动加载更多
		recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
			@Override
			public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
				if (dy <= 0) return; // 向上滚动不触发加载
				LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
				if (lm != null && !loadingMore && hasMore && lm.findLastVisibleItemPosition() >= data.size() - 3) {
					loadingMore = true;
					currentPage++;
					loadMore();
				}
			}
		});

		swipeRefreshLayout.addView(recyclerView);
		swipeRefreshLayout.addView(emptyState);
		root.addView(swipeRefreshLayout);

		wrapper.addView(root);
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
	protected void doLoadData() {
		loadData();
	}

	@Override
	public void onRefresh() {
		currentPage = 1;
		hasMore = true;
		loadData();
	}

	public void loadData() {
		boolean isInitialLoad = !loaded && !dataLoading;
		dataLoading = true;
		if (isInitialLoad) {
			showProgress();
		}

		// 加载品牌列表（仅首次）
		if (brands.isEmpty()) {
			new GetDiaperBrands()
				.setCallback(new Callback<Map<String, Object>>() {
					@Override
					@SuppressWarnings("unchecked")
					public void onSuccess(Map<String, Object> result) {
						if (getActivity() == null) return;
						List<String> brandList = (List<String>) result.get("brands");
						if (brandList != null) {
							brands.clear();
							brands.add("");
							brands.addAll(brandList);
							buildChips();
						}
					}

					@Override
					public void onError(ErrorResponse error) {}
				})
				.exec(accountID);
		}

		// 加载纸尿裤列表
		new GetDiaperList(currentPage, 20, currentSearch, currentBrand, currentSort)
			.setCallback(new Callback<Map<String, Object>>() {
				@Override
				@SuppressWarnings("unchecked")
				public void onSuccess(Map<String, Object> result) {
					if (getActivity() == null) return;
					List<Map<String, Object>> diaperList = (List<Map<String, Object>>) result.get("diapers");
					if (diaperList == null) diaperList = new ArrayList<>();
					Gson gson = new Gson();
					List<Diaper> newItems = gson.fromJson(
						gson.toJson(diaperList),
						new TypeToken<List<Diaper>>(){}.getType()
					);

					if (currentPage == 1) {
						data.clear();
					}
					data.addAll(newItems);

					Map<String, Object> pagination = (Map<String, Object>) result.get("pagination");
					if (pagination != null) {
						long total = ((Number) pagination.get("total")).longValue();
						hasMore = data.size() < total;
					} else {
						hasMore = false;
					}

					adapter.notifyDataSetChanged();
					updateEmptyState();
					dataLoaded();
					swipeRefreshLayout.setRefreshing(false);
					loadingMore = false;
				}

				@Override
				public void onError(ErrorResponse error) {
					if (getActivity() == null) return;
					swipeRefreshLayout.setRefreshing(false);
					loadingMore = false;
					dataLoaded();
					error.showToast(getContext());
				}
			})
			.exec(accountID);
	}

	private void loadMore() {
		new GetDiaperList(currentPage, 20, currentSearch, currentBrand, currentSort)
			.setCallback(new Callback<Map<String, Object>>() {
				@Override
				@SuppressWarnings("unchecked")
				public void onSuccess(Map<String, Object> result) {
					if (getActivity() == null) return;
					List<Map<String, Object>> diaperList = (List<Map<String, Object>>) result.get("diapers");
					if (diaperList == null) diaperList = new ArrayList<>();
					Gson gson = new Gson();
					List<Diaper> newItems = gson.fromJson(
						gson.toJson(diaperList),
						new TypeToken<List<Diaper>>(){}.getType()
					);

					int startPos = data.size();
					data.addAll(newItems);
					adapter.notifyItemRangeInserted(startPos, newItems.size());

					Map<String, Object> pagination = (Map<String, Object>) result.get("pagination");
					if (pagination != null) {
						long total = ((Number) pagination.get("total")).longValue();
						hasMore = data.size() < total;
					} else {
						hasMore = false;
					}
					loadingMore = false;
				}

				@Override
				public void onError(ErrorResponse error) {
					loadingMore = false;
				}
			})
			.exec(accountID);
	}

	private void buildChips() {
		if (chipsContainer == null) return;
		chipsContainer.removeAllViews();
		for (int i = 0; i < brands.size(); i++) {
			String brand = brands.get(i);
			View chipView = LayoutInflater.from(getContext()).inflate(R.layout.item_diaper_chip, chipsContainer, false);
			TextView chipText = chipView.findViewById(R.id.chip_text);
			chipText.setText(brand.isEmpty() ? getString(R.string.diaper_all_brands) : brand);

			boolean isSelected = brand.equals(currentBrand);
			chipText.setBackgroundResource(isSelected ? R.drawable.bg_diaper_chip_selected : R.drawable.bg_diaper_chip);
			// 使用主题颜色，适配暗色模式
			if (isSelected) {
				chipText.setTextColor(0xFFFFFFFF);
			} else {
				chipText.setTextColor(getResources().getColor(R.color.diaper_chip_text));
			}

			chipText.setOnClickListener(v -> {
				currentBrand = brand;
				currentPage = 1;
				hasMore = true;
				buildChips();
				loadData();
			});

			chipsContainer.addView(chipView);
		}
	}

	private void updateEmptyState() {
		if (data.isEmpty()) {
			emptyState.setVisibility(View.VISIBLE);
			recyclerView.setVisibility(View.GONE);
		} else {
			emptyState.setVisibility(View.GONE);
			recyclerView.setVisibility(View.VISIBLE);
		}
	}

	private class DiaperAdapter extends RecyclerView.Adapter<DiaperAdapter.ViewHolder> {

		@NonNull
		@Override
		public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_diaper_list, parent, false);
			return new ViewHolder(view);
		}

		@Override
		public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
			Diaper diaper = data.get(position);

			// 品牌Logo
			if (diaper.brand_logo != null && !diaper.brand_logo.isEmpty()) {
				int logoSize = V.dp(48);
				ViewImageLoader.loadWithoutAnimation(holder.brandLogo, null,
					new UrlImageLoaderRequest(diaper.brand_logo, logoSize, logoSize));
				holder.brandLogo.setVisibility(View.VISIBLE);
			} else {
				holder.brandLogo.setVisibility(View.GONE);
			}

			// 品牌 + 型号
			holder.brandName.setText(diaper.brand + " " + diaper.model);

			// 婴儿标签
			if (diaper.is_baby_diaper == 1) {
				holder.babyBadge.setVisibility(View.VISIBLE);
			} else {
				holder.babyBadge.setVisibility(View.GONE);
			}

			// 评分
			if (diaper.avg_score > 0) {
				holder.avgScore.setText(String.format("%.2f", diaper.avg_score));
				holder.avgScore.setVisibility(View.VISIBLE);
			} else {
				holder.avgScore.setText("--");
				holder.avgScore.setVisibility(View.VISIBLE);
			}

			// 评价数
			if (diaper.rating_count > 0) {
				holder.ratingCount.setText(String.format("%d 评价", diaper.rating_count));
				holder.ratingCount.setVisibility(View.VISIBLE);
			} else {
				holder.ratingCount.setVisibility(View.GONE);
			}

			// 吸收量
			if (diaper.absorbency_adult != null && !diaper.absorbency_adult.isEmpty()) {
				holder.absorbency.setText(diaper.absorbency_adult);
				holder.absorbency.setVisibility(View.VISIBLE);
			} else {
				holder.absorbency.setVisibility(View.GONE);
			}

			// 点击进入详情
			holder.itemView.setOnClickListener(v -> {
				Bundle args = new Bundle();
				args.putString("account", accountID);
				args.putInt("diaper_id", diaper.id);
				Nav.go(getActivity(), DiaperDetailFragment.class, args);
			});
		}

		@Override
		public int getItemCount() {
			return data.size();
		}

		class ViewHolder extends RecyclerView.ViewHolder {
			ImageView brandLogo;
			TextView brandName;
			TextView babyBadge;
			TextView avgScore;
			TextView ratingCount;
			TextView absorbency;

			ViewHolder(View itemView) {
				super(itemView);
				brandLogo = itemView.findViewById(R.id.brand_logo);
				brandName = itemView.findViewById(R.id.brand_name);
				babyBadge = itemView.findViewById(R.id.baby_badge);
				avgScore = itemView.findViewById(R.id.avg_score);
				ratingCount = itemView.findViewById(R.id.rating_count);
				absorbency = itemView.findViewById(R.id.absorbency);
			}
		}
	}
}
