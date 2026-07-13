package org.joinmastodon.android.fragments.diapers;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
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
import org.joinmastodon.android.api.requests.diapers.GetRankings;
import org.joinmastodon.android.model.RankingItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.fragments.LoaderFragment;
import me.grishka.appkit.utils.V;

public class DiaperRankingsFragment extends LoaderFragment {
	private RecyclerView recyclerView;
	private RankingsAdapter adapter;
	private List<RankingItem> data = new ArrayList<>();
	private String currentTab = "hot";
	private int currentPage = 0;
	private boolean loadingMore = false;
	private boolean hasMore = true;
	private int totalCount = 0;
	private double baseAdultScore = 0;
	private double baseBabyScore = 0;
	private String accountID;
	private TextView totalCountText;
	private TextView adultScoreText;
	private TextView babyScoreText;
	private View baseScoreCard;
	private LinearLayout tabContainer;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		accountID = getArguments() != null ? getArguments().getString("account") : null;

		if (savedInstanceState != null) {
			currentTab = savedInstanceState.getString("tab", "hot");
		}
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putString("tab", currentTab);
	}

	@Override
	public void onAttach(Activity activity) {
		super.onAttach(activity);
		setTitle(getString(R.string.diaper_rankings));
	}

	@Override
	public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		FrameLayout wrapper = new FrameLayout(getContext());
		wrapper.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

		LinearLayout root = new LinearLayout(getContext());
		root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		root.setOrientation(LinearLayout.VERTICAL);

		// 提示文字
		TextView infoText = new TextView(getContext());
		infoText.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		infoText.setPadding(V.dp(16), V.dp(12), V.dp(16), V.dp(4));
		infoText.setText(R.string.diaper_rankings_info);
		infoText.setTextSize(12);
		infoText.setTextColor(getResources().getColor(R.color.diaper_chip_text));
		root.addView(infoText);

		// 总数
		totalCountText = new TextView(getContext());
		totalCountText.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		totalCountText.setPadding(V.dp(16), V.dp(4), V.dp(16), V.dp(8));
		totalCountText.setTextSize(12);
		totalCountText.setTextColor(getResources().getColor(R.color.diaper_chip_text));
		root.addView(totalCountText);

		// Tab按钮
		tabContainer = new LinearLayout(getContext());
		tabContainer.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		tabContainer.setOrientation(LinearLayout.HORIZONTAL);
		tabContainer.setPadding(V.dp(16), 0, V.dp(16), V.dp(12));
		root.addView(tabContainer);
		buildTabs();

		// 基准分卡片
		baseScoreCard = inflater.inflate(R.layout.item_base_score_card, root, false);
		adultScoreText = baseScoreCard.findViewById(R.id.adult_score);
		babyScoreText = baseScoreCard.findViewById(R.id.baby_score);
		root.addView(baseScoreCard);

		// RecyclerView
		recyclerView = new RecyclerView(getContext());
		recyclerView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
		recyclerView.setClipToPadding(false);
		recyclerView.setPadding(0, V.dp(4), 0, V.dp(80));
		adapter = new RankingsAdapter();
		recyclerView.setAdapter(adapter);

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

		root.addView(recyclerView);
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
		currentPage = 0;
		hasMore = true;
		loadData();
	}

	private void buildTabs() {
		tabContainer.removeAllViews();
		String[][] tabs = {
			{"hot", "热门"},
			{"absorbency", "最强吸收"},
			{"popular", "最受关注"}
		};
		for (String[] tab : tabs) {
			View chipView = LayoutInflater.from(getContext()).inflate(R.layout.item_diaper_chip, tabContainer, false);
			TextView chipText = chipView.findViewById(R.id.chip_text);
			chipText.setText(tab[1]);

			boolean isSelected = tab[0].equals(currentTab);
			chipText.setBackgroundResource(isSelected ? R.drawable.bg_diaper_chip_selected : R.drawable.bg_diaper_chip);
			if (isSelected) {
				chipText.setTextColor(0xFFFFFFFF);
			} else {
				chipText.setTextColor(getResources().getColor(R.color.diaper_chip_text));
			}

			chipText.setOnClickListener(v -> {
				currentTab = tab[0];
				currentPage = 0;
				hasMore = true;
				buildTabs();
				loadData();
			});

			tabContainer.addView(chipView);
		}
	}

	public void loadData() {
		boolean isInitialLoad = !loaded && !dataLoading;
		dataLoading = true;
		if (isInitialLoad) {
			showProgress();
		}

		new GetRankings(currentTab, 20, currentPage * 20)
			.setCallback(new Callback<Map<String, Object>>() {
				@Override
				@SuppressWarnings("unchecked")
				public void onSuccess(Map<String, Object> result) {
					if (getActivity() == null) return;
					List<Map<String, Object>> rankingsList = (List<Map<String, Object>>) result.get("rankings");
					if (rankingsList == null) rankingsList = new ArrayList<>();
					Gson gson = new Gson();
					List<RankingItem> newItems = gson.fromJson(
						gson.toJson(rankingsList),
						new TypeToken<List<RankingItem>>(){}.getType()
					);

					if (currentPage == 0) {
						data.clear();
					}
					data.addAll(newItems);

					// 更新基准分
					Map<String, Object> baseScores = (Map<String, Object>) result.get("base_scores");
					if (baseScores != null) {
						baseAdultScore = ((Number) baseScores.getOrDefault("adult", 0)).doubleValue();
						baseBabyScore = ((Number) baseScores.getOrDefault("baby", 0)).doubleValue();
						adultScoreText.setText(String.format("%.2f", baseAdultScore));
						babyScoreText.setText(String.format("%.2f", baseBabyScore));
						baseScoreCard.setVisibility(View.VISIBLE);
					} else {
						baseScoreCard.setVisibility(View.GONE);
					}

					// 更新总数
					totalCount = ((Number) result.getOrDefault("total", 0)).intValue();
					totalCountText.setText(String.format("共 %d 款纸尿裤", totalCount));

					hasMore = Boolean.TRUE.equals(result.get("hasMore"));

					adapter.notifyDataSetChanged();
					dataLoaded();
					loadingMore = false;
				}

				@Override
				public void onError(ErrorResponse error) {
					if (getActivity() == null) return;
					loadingMore = false;
					dataLoaded();
					error.showToast(getContext());
				}
			})
			.exec(accountID);
	}

	private void loadMore() {
		new GetRankings(currentTab, 20, currentPage * 20)
			.setCallback(new Callback<Map<String, Object>>() {
				@Override
				@SuppressWarnings("unchecked")
				public void onSuccess(Map<String, Object> result) {
					if (getActivity() == null) return;
					List<Map<String, Object>> rankingsList = (List<Map<String, Object>>) result.get("rankings");
					if (rankingsList == null) rankingsList = new ArrayList<>();
					Gson gson = new Gson();
					List<RankingItem> newItems = gson.fromJson(
						gson.toJson(rankingsList),
						new TypeToken<List<RankingItem>>(){}.getType()
					);

					int startPos = data.size();
					data.addAll(newItems);
					adapter.notifyItemRangeInserted(startPos, newItems.size());

					hasMore = Boolean.TRUE.equals(result.get("hasMore"));
					loadingMore = false;
					adapter.notifyDataSetChanged();
				}

				@Override
				public void onError(ErrorResponse error) {
					loadingMore = false;
					adapter.notifyDataSetChanged();
				}
			})
			.exec(accountID);
	}

	private static final int VIEW_TYPE_ITEM = 0;
	private static final int VIEW_TYPE_LOADING = 1;

	private class RankingsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

		@NonNull
		@Override
		public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			if (viewType == VIEW_TYPE_LOADING) {
				View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_loading_footer, parent, false);
				return new LoadingViewHolder(view);
			}
			View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_ranking_list, parent, false);
			return new ItemViewHolder(view);
		}

		@Override
		public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
			if (holder instanceof ItemViewHolder) {
				ItemViewHolder itemHolder = (ItemViewHolder) holder;
				RankingItem item = data.get(position);

				// 排名序号
				int rank = position + 1;
				itemHolder.rankNumber.setText(String.valueOf(rank));
				if (rank <= 3) {
					itemHolder.rankNumber.setTextColor(0xFFFFB300);
				} else {
					itemHolder.rankNumber.setTextColor(getResources().getColor(R.color.diaper_chip_text));
				}

				// 品牌 + 型号
				itemHolder.brandName.setText(item.brand + " " + item.model);

				// 儿童款标签
				itemHolder.babyBadge.setVisibility(item.is_baby_diaper ? View.VISIBLE : View.GONE);

				// 评分
				if (item.avg_score > 0) {
					itemHolder.avgScore.setText(String.format("%.2f", item.avg_score));
					itemHolder.avgScore.setVisibility(View.VISIBLE);
				} else {
					itemHolder.avgScore.setText("--");
					itemHolder.avgScore.setVisibility(View.VISIBLE);
				}

				// 点击进入详情
				itemHolder.itemView.setOnClickListener(v -> {
					Bundle args = new Bundle();
					args.putString("account", accountID);
					args.putInt("diaper_id", item.id);
					Nav.go(getActivity(), DiaperDetailFragment.class, args);
				});
			}
		}

		@Override
		public int getItemCount() {
			return data.size() + (loadingMore ? 1 : 0);
		}

		@Override
		public int getItemViewType(int position) {
			return position >= data.size() ? VIEW_TYPE_LOADING : VIEW_TYPE_ITEM;
		}

		class ItemViewHolder extends RecyclerView.ViewHolder {
			TextView rankNumber;
			TextView brandName;
			TextView babyBadge;
			TextView avgScore;

			ItemViewHolder(View itemView) {
				super(itemView);
				rankNumber = itemView.findViewById(R.id.rank_number);
				brandName = itemView.findViewById(R.id.brand_name);
				babyBadge = itemView.findViewById(R.id.baby_badge);
				avgScore = itemView.findViewById(R.id.avg_score);
			}
		}

		class LoadingViewHolder extends RecyclerView.ViewHolder {
			LoadingViewHolder(View itemView) {
				super(itemView);
			}
		}
	}
}
