package org.joinmastodon.android.fragments.diapers;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.diapers.GetDiaperBrands;
import org.joinmastodon.android.api.requests.diapers.GetDiaperList;
import org.joinmastodon.android.model.Diaper;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.fragments.LoaderFragment;
import me.grishka.appkit.imageloader.ImageLoaderRecyclerAdapter;
import me.grishka.appkit.imageloader.ImageLoaderViewHolder;
import me.grishka.appkit.imageloader.ListImageLoaderWrapper;
import me.grishka.appkit.imageloader.requests.ImageLoaderRequest;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.V;
import me.grishka.appkit.views.UsableRecyclerView;

public class DiaperListFragment extends LoaderFragment {
	private UsableRecyclerView recyclerView;
	private SwipeRefreshLayout swipeRefreshLayout;
	private DiaperAdapter adapter;
	private ListImageLoaderWrapper imgLoader;
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
	private int requestGeneration;

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
		setHasOptionsMenu(true);
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		menu.add(0, 1, 0, getString(R.string.diaper_rankings))
			.setIcon(R.drawable.ic_fluent_trophy_24_regular)
			.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == 1) {
			Bundle args = new Bundle();
			args.putString("account", accountID);
			Nav.go(getActivity(), DiaperRankingsFragment.class, args);
			return true;
		}
		return super.onOptionsItemSelected(item);
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
		swipeRefreshLayout.setColorSchemeColors(0xFFA1D9F7);
		swipeRefreshLayout.setOnRefreshListener(() -> {
			currentPage = 1;
			hasMore = true;
			loadData();
		});

		// 空状态
		emptyState = inflater.inflate(R.layout.diaper_empty_state, swipeRefreshLayout, false);
		emptyState.setVisibility(View.GONE);

		// RecyclerView
		recyclerView = new UsableRecyclerView(getContext());
		recyclerView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
		recyclerView.setClipToPadding(false);
		recyclerView.setPadding(0, V.dp(4), 0, V.dp(16));
		adapter = new DiaperAdapter();
		recyclerView.setAdapter(adapter);
		imgLoader = new ListImageLoaderWrapper(getActivity(), (UsableRecyclerView) recyclerView, (UsableRecyclerView) recyclerView, null);
		imgLoader.setPrefetchAmount(2);

		// 滚动加载更多
		recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
			@Override
			public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
				if (dy <= 0) return;
				LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
				if (lm != null && !loadingMore && hasMore && lm.findLastVisibleItemPosition() >= data.size() - 3) {
					loadingMore = true;
					adapter.notifyDataSetChanged();
					currentPage++;
					loadMore();
				}
			}
		});

		FrameLayout content = new FrameLayout(getContext());
		content.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		content.addView(recyclerView);
		content.addView(emptyState);
		swipeRefreshLayout.addView(content);
		root.addView(swipeRefreshLayout, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

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
		final int generation=++requestGeneration;
		final int requestedPage=currentPage;
		final String requestedSearch=currentSearch;
		final String requestedBrand=currentBrand;
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
		new GetDiaperList(requestedPage, 20, requestedSearch, requestedBrand, currentSort)
			.setCallback(new Callback<Map<String, Object>>() {
				@Override
				@SuppressWarnings("unchecked")
				public void onSuccess(Map<String, Object> result) {
					if (getActivity() == null) return;
					if(generation!=requestGeneration) return;
					List<Map<String, Object>> diaperList = (List<Map<String, Object>>) result.get("diapers");
					if (diaperList == null) diaperList = new ArrayList<>();
					Gson gson = new Gson();
					List<Diaper> newItems = gson.fromJson(
						gson.toJson(diaperList),
						new TypeToken<List<Diaper>>(){}.getType()
					);

					if (requestedPage == 1) {
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
					if(generation!=requestGeneration) return;
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
					adapter.notifyDataSetChanged();
				}

				@Override
				public void onError(ErrorResponse error) {
					currentPage=Math.max(1, currentPage-1);
					loadingMore = false;
					adapter.notifyDataSetChanged();
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
			if (isSelected) {
				chipText.setTextColor(0xFF163247);
			} else {
				chipText.setTextColor(UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnSurface));
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

	private static final int VIEW_TYPE_ITEM = 0;
	private static final int VIEW_TYPE_LOADING = 1;
	private static final int VIEW_TYPE_END = 2;

	private class DiaperAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> implements ImageLoaderRecyclerAdapter {

		@NonNull
		@Override
		public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			if (viewType == VIEW_TYPE_LOADING) {
				View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_loading_footer, parent, false);
				return new LoadingViewHolder(view);
			}
			if (viewType == VIEW_TYPE_END) {
				TextView endText = new TextView(parent.getContext());
				endText.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
				endText.setPadding(V.dp(16), V.dp(16), V.dp(16), V.dp(16));
				endText.setTextSize(12);
				endText.setTextColor(UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnSurfaceVariant));
				endText.setGravity(android.view.Gravity.CENTER);
				return new EndViewHolder(endText);
			}
			View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_diaper_list, parent, false);
			return new ItemViewHolder(view);
		}

		@Override
		public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
			if (holder instanceof ItemViewHolder) {
				ItemViewHolder itemHolder = (ItemViewHolder) holder;
				Diaper diaper = data.get(position);

				// 纸尿裤图片（由 imgLoader 统一管理预取/取消/绑定）
				itemHolder.imageRequest = (diaper.images != null && !diaper.images.isEmpty())
					? new UrlImageLoaderRequest(diaper.images.get(0), V.dp(56), V.dp(56))
					: null;
				itemHolder.diaperImage.setVisibility(itemHolder.imageRequest != null ? View.VISIBLE : View.GONE);
				if (itemHolder.imageRequest == null)
					itemHolder.diaperImage.setImageDrawable(null);

				// 品牌 + 型号
				itemHolder.brandName.setText(diaper.brand + " " + diaper.model);

				// 婴儿标签
				itemHolder.babyBadge.setVisibility(diaper.is_baby_diaper == 1 ? View.VISIBLE : View.GONE);

				// 评分
				if (diaper.avg_score > 0) {
					itemHolder.avgScore.setText(String.format("%.2f", diaper.avg_score));
					itemHolder.avgScore.setVisibility(View.VISIBLE);
				} else {
					itemHolder.avgScore.setText("--");
					itemHolder.avgScore.setVisibility(View.VISIBLE);
				}

				// 评价数
				if (diaper.rating_count > 0) {
					itemHolder.ratingCount.setText(String.format("%d 评价", diaper.rating_count));
					itemHolder.ratingCount.setVisibility(View.VISIBLE);
				} else {
					itemHolder.ratingCount.setVisibility(View.GONE);
				}

				// 吸收量
				if (diaper.absorbency_adult != null && !diaper.absorbency_adult.isEmpty()) {
					itemHolder.absorbency.setText(diaper.absorbency_adult);
					itemHolder.absorbency.setVisibility(View.VISIBLE);
				} else {
					itemHolder.absorbency.setVisibility(View.GONE);
				}

				// 点击进入详情
				itemHolder.itemView.setOnClickListener(v -> {
					Bundle args = new Bundle();
					args.putString("account", accountID);
					args.putInt("diaper_id", diaper.id);
					Nav.go(getActivity(), DiaperDetailFragment.class, args);
				});
			} else if (holder instanceof EndViewHolder) {
				((EndViewHolder) holder).endText.setText(String.format("已显示全部 %d 款", data.size()));
			}
		}

		@Override
		public int getItemCount() {
			int count = data.size();
			if (loadingMore) count++;
			else if (!hasMore && data.size() > 0) count++;
			return count;
		}

		@Override
		public int getItemViewType(int position) {
			if (position >= data.size()) {
				return loadingMore ? VIEW_TYPE_LOADING : VIEW_TYPE_END;
			}
			return VIEW_TYPE_ITEM;
		}

		@Override
		public int getImageCountForItem(int position) {
			return (position >= 0 && position < data.size() && data.get(position).images != null && !data.get(position).images.isEmpty()) ? 1 : 0;
		}

		@Override
		public ImageLoaderRequest getImageRequest(int position, int image) {
			if (position >= 0 && position < data.size()) {
				Diaper diaper = data.get(position);
				if (diaper.images != null && !diaper.images.isEmpty())
					return new UrlImageLoaderRequest(diaper.images.get(0), V.dp(56), V.dp(56));
			}
			return null;
		}

		class ItemViewHolder extends RecyclerView.ViewHolder implements ImageLoaderViewHolder {
			ImageView diaperImage;
			TextView brandName;
			TextView babyBadge;
			TextView avgScore;
			TextView ratingCount;
			TextView absorbency;
			ImageLoaderRequest imageRequest;

			ItemViewHolder(View itemView) {
				super(itemView);
				diaperImage = itemView.findViewById(R.id.diaper_image);
				brandName = itemView.findViewById(R.id.brand_name);
				babyBadge = itemView.findViewById(R.id.baby_badge);
				avgScore = itemView.findViewById(R.id.avg_score);
				ratingCount = itemView.findViewById(R.id.rating_count);
				absorbency = itemView.findViewById(R.id.absorbency);
			}

			@Override
			public void setImage(int image, Drawable drawable) {
				diaperImage.setImageDrawable(drawable);
			}

			@Override
			public void clearImage(int image) {
				diaperImage.setImageDrawable(null);
			}
		}

		class LoadingViewHolder extends RecyclerView.ViewHolder {
			LoadingViewHolder(View itemView) {
				super(itemView);
			}
		}

		class EndViewHolder extends RecyclerView.ViewHolder {
			TextView endText;
			EndViewHolder(View itemView) {
				super(itemView);
				endText = (TextView) itemView;
			}
		}
	}
}
