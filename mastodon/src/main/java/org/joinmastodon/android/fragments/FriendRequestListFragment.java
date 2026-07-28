package org.joinmastodon.android.fragments;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.friendrequests.DeleteFriendRequest;
import org.joinmastodon.android.api.requests.friendrequests.GetFriendRequestList;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.FriendRequest;
import org.joinmastodon.android.model.FriendRequestField;
import org.joinmastodon.android.ui.compose.navigation.FriendUniverseLiquidToolbarController;

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
import me.grishka.appkit.views.FragmentRootLinearLayout;
import org.joinmastodon.android.ui.OutlineProviders;
import org.joinmastodon.android.ui.views.FlowLayout;
import org.joinmastodon.android.ui.utils.UiUtils;

import static org.joinmastodon.android.ui.compose.navigation.FriendUniverseToolbarModelKt.friendUniverseMayApplySearch;
import static org.joinmastodon.android.ui.compose.navigation.FriendUniverseToolbarModelKt.friendUniverseTopPaddingDp;
import static org.joinmastodon.android.ui.compose.navigation.FriendUniverseToolbarModelKt.friendUniverseCanLoadMore;

public class FriendRequestListFragment extends LoaderFragment {
	private RecyclerView recyclerView;
	private SwipeRefreshLayout swipeRefreshLayout;
	private FriendRequestAdapter adapter;
	private List<FriendRequest> data = new ArrayList<>();
	private String currentSearch = "";
	private String currentFilter = "";
	private int currentPage = 1;
	private boolean loadingMore = false;
	private boolean hasMore = true;
	private String accountID;
	private ImageButton fab;
	private float totalDy = 0;
	private boolean fabHidden = false;
	private FriendUniverseLiquidToolbarController liquidToolbarController;
	private SharedPreferences prefs;
	private boolean bannerVisible;
	private int searchGeneration;

	// Metadata icon 映射
	private static final String[][] METADATA_ICONS = {
		{"生理性别", "ic_field_gender"}, {"心理性别", "ic_field_gender_identity"},
		{"年龄", "ic_field_age"}, {"生日", "ic_field_birthday"},
		{"城市", "ic_field_city"}, {"QQ", "ic_field_qq"},
		{"微信", "ic_field_wechat"}, {"手机号", "ic_field_phone"},
		{"X(原推特)", "ic_field_twitter"}, {"Telegram", "ic_field_telegram"},
		{"博客", "ic_field_blog"}, {"宝宝新天地", "ic_field_nbw"},
		{"爱好", "ic_field_hobby"}, {"出生地", "ic_field_birthplace"},
		{"工作地", "ic_field_workplace"}, {"现居地", "ic_field_city"},
		{"性取向", "ic_field_orientation"}, {"会玩游戏", "ic_field_game"}
	};

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		accountID = getArguments() != null ? getArguments().getString("account") : null;
	}

	@Override
	public void onAttach(Activity activity) {
		super.onAttach(activity);
		setTitle(getString(R.string.friend_request_list));
		setHasOptionsMenu(true);
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		menu.add(0, 1, 0, getString(R.string.friend_request_search))
			.setIcon(R.drawable.ic_fluent_search_24_regular)
			.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == 1) {
			Toast.makeText(getContext(), "搜索功能即将上线", Toast.LENGTH_SHORT).show();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		FrameLayout wrapper = new FrameLayout(getContext());
		wrapper.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

		FragmentRootLinearLayout root = new FragmentRootLinearLayout(getContext());
		root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		root.setOrientation(LinearLayout.VERTICAL);

		prefs = getContext().getSharedPreferences("friend_request", 0);
		bannerVisible = !prefs.getBoolean("banner_dismissed", false);

		// 下拉刷新
		swipeRefreshLayout = new SwipeRefreshLayout(getContext());
		swipeRefreshLayout.setColorSchemeColors(0xFFA1D9F7);
			swipeRefreshLayout.setOnRefreshListener(() -> {
			startFullReload(false);
		});

		// RecyclerView
		recyclerView = new RecyclerView(getContext());
		recyclerView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
		recyclerView.setClipToPadding(false);
		recyclerView.setPadding(0, V.dp(8), 0, V.dp(72));
		adapter = new FriendRequestAdapter();
		recyclerView.setAdapter(adapter);

		// 滚动监听
		recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
			@Override
			public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
				totalDy += dy;
				if(liquidToolbarController!=null)
					liquidToolbarController.setScrollY(Math.max(0, rv.computeVerticalScrollOffset()));
				// 加载更多
				LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
				if (lm != null && friendUniverseCanLoadMore(dataLoading, loadingMore, hasMore, data.size(), lm.findLastVisibleItemPosition()-(bannerVisible ? 1 : 0))) {
					loadingMore = true;
					loadMore(currentPage+1);
				}
			}

			@Override
			public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
				if (newState == RecyclerView.SCROLL_STATE_IDLE && !fabHidden) {
					fab.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
				}
			}
		});

		FrameLayout content=new FrameLayout(getContext());
		content.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		content.addView(recyclerView);
		swipeRefreshLayout.addView(content);
		root.addView(swipeRefreshLayout, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

		wrapper.addView(root);

		// FAB
		fab = new ImageButton(getContext());
		fab.setImageResource(R.drawable.ic_fluent_add_24_regular);
		FrameLayout.LayoutParams fabParams = new FrameLayout.LayoutParams(V.dp(56), V.dp(56));
		fabParams.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
		fabParams.setMargins(0, 0, V.dp(16), V.dp(16));
		fab.setLayoutParams(fabParams);
		fab.setBackgroundResource(R.drawable.bg_fab);
		fab.setScaleType(ImageView.ScaleType.CENTER);
		fab.setElevation(V.dp(6));
		fab.setContentDescription("发布交友请求");
		fab.setOnClickListener(v -> openCreateRequest());
		wrapper.addView(fab);
		updateLiquidMode();

		return wrapper;
	}

	@Override
	protected void onShown() {
		super.onShown();
		updateLiquidMode();
		if (!loaded && !dataLoading) {
			loadData();
		}
	}

	@Override
	public void onDestroyView() {
		liquidToolbarController=null;
		recyclerView=null;
		swipeRefreshLayout=null;
		adapter=null;
		fab=null;
		prefs=null;
		dataLoading=false;
		loadingMore=false;
		super.onDestroyView();
	}

	@Override
	protected void doLoadData() {
		loadData();
	}

	@Override
	public void onRefresh() {
		startFullReload(false);
	}

	public void loadData() {
		loadData(searchGeneration);
	}

	private void loadData(int requestGeneration) {
		final int requestPage=1;
		final String requestSearch=currentSearch;
		dataLoading = true;
		showProgress();

		new GetFriendRequestList(requestPage, 20, requestSearch, null)
			.setCallback(new Callback<Map<String, Object>>() {
				@Override
				@SuppressWarnings("unchecked")
				public void onSuccess(Map<String, Object> result) {
					if (getActivity() == null) {
						dataLoading=false;
						loadingMore=false;
						return;
					}
					if(!friendUniverseMayApplySearch(requestGeneration, searchGeneration)) return;
					List<Map<String, Object>> requests = (List<Map<String, Object>>) result.get("requests");
					Gson gson = new Gson();
					List<FriendRequest> newItems = gson.fromJson(
						gson.toJson(requests),
						new TypeToken<List<FriendRequest>>(){}.getType()
					);

					data.clear();
					data.addAll(newItems);
					currentPage=requestPage;

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
					if (getActivity() == null) {
						dataLoading=false;
						loadingMore=false;
						return;
					}
					if(!friendUniverseMayApplySearch(requestGeneration, searchGeneration)) return;
					swipeRefreshLayout.setRefreshing(false);
					loadingMore = false;
					dataLoaded();
					error.showToast(getContext());
				}
			})
			.exec(accountID);
	}

	private void loadMore(int requestPage) {
		final int requestGeneration=searchGeneration;
		final String requestSearch=currentSearch;
		new GetFriendRequestList(requestPage, 20, requestSearch, null)
			.setCallback(new Callback<Map<String, Object>>() {
				@Override
				@SuppressWarnings("unchecked")
				public void onSuccess(Map<String, Object> result) {
					if (getActivity() == null) {
						loadingMore=false;
						return;
					}
					if(!friendUniverseMayApplySearch(requestGeneration, searchGeneration)) return;
					List<Map<String, Object>> requests = (List<Map<String, Object>>) result.get("requests");
					Gson gson = new Gson();
					List<FriendRequest> newItems = gson.fromJson(
						gson.toJson(requests),
						new TypeToken<List<FriendRequest>>(){}.getType()
					);

					data.addAll(newItems);
					currentPage=requestPage;
					adapter.notifyItemRangeInserted(data.size() - newItems.size() + (bannerVisible ? 1 : 0), newItems.size());

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
					if(!friendUniverseMayApplySearch(requestGeneration, searchGeneration)) return;
					loadingMore = false;
				}
			})
			.exec(accountID);
	}

	private void updateEmptyState() {
		if(recyclerView!=null) recyclerView.setVisibility(View.VISIBLE);
	}

	public void setLiquidToolbarController(FriendUniverseLiquidToolbarController controller) {
		liquidToolbarController=controller;
		updateLiquidMode();
		if(controller!=null && recyclerView!=null)
			controller.setScrollY(Math.max(0, recyclerView.computeVerticalScrollOffset()));
		if(controller!=null)
			controller.setSearchQuery(currentSearch);
	}

	public void onLiquidSearchChanged(String query) {
		String normalized=query==null ? "" : query.trim();
		if(normalized.equals(currentSearch)) return;
		currentSearch=normalized;
		startFullReload(true);
	}

	private void startFullReload(boolean clearVisibleData) {
		searchGeneration++;
		currentPage=1;
		hasMore=true;
		loadingMore=false;
		if(clearVisibleData){
			data.clear();
			if(adapter!=null) adapter.notifyDataSetChanged();
			updateEmptyState();
		}
		loadData(searchGeneration);
	}

	public void onLiquidPublish() {
		openCreateRequest();
	}

	private void openCreateRequest() {
		Bundle args = new Bundle();
		args.putString("account", accountID);
		Nav.go(getActivity(), FriendRequestCreateFragment.class, args);
	}

	private void updateLiquidMode() {
		boolean liquid=liquidToolbarController!=null;
		View toolbar=getToolbar();
		if(toolbar!=null) toolbar.setVisibility(liquid ? View.GONE : View.VISIBLE);
		if(fab!=null) fab.setVisibility(liquid ? View.GONE : View.VISIBLE);
		if(recyclerView!=null)
			recyclerView.setPadding(0, V.dp(friendUniverseTopPaddingDp(liquid)), 0, V.dp(72));
	}

	private String formatTime(String createdAt) {
		try {
			java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
			sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
			java.util.Date date = sdf.parse(createdAt);
			long diff = System.currentTimeMillis() - date.getTime();
			long seconds = diff / 1000;
			long minutes = seconds / 60;
			long hours = minutes / 60;
			long days = hours / 24;

			if (seconds < 60) return "刚刚";
			if (minutes < 60) return minutes + "分钟前";
			if (hours < 24) return hours + "小时前";
			if (days < 30) return days + "天前";
			if (days < 365) return (days / 30) + "个月前";
			return (days / 365) + "年前";
		} catch (Exception e) {
			return "";
		}
	}

	private int getMetadataIconRes(String fieldKey) {
		for (String[] mapping : METADATA_ICONS) {
			if (mapping[0].equals(fieldKey)) {
				return getResources().getIdentifier(mapping[1], "drawable", getContext().getPackageName());
			}
		}
		return 0;
	}

	private class FriendRequestAdapter extends RecyclerView.Adapter<FriendRequestAdapter.VH> {
		private static final int TYPE_ITEM = 0;
		private static final int TYPE_FOOTER = 1;
		private static final int TYPE_BANNER = 2;
		private static final int TYPE_EMPTY = 3;

		@NonNull
		@Override
		public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			if(viewType == TYPE_EMPTY)
				return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.friend_request_empty_state, parent, false));
			if(viewType == TYPE_BANNER) {
				View banner=LayoutInflater.from(parent.getContext()).inflate(R.layout.friend_request_fraud_banner, parent, false);
				banner.setOnClickListener(v -> {
					prefs.edit().putBoolean("banner_dismissed", true).apply();
					bannerVisible=false;
					notifyItemRemoved(0);
				});
				return new VH(banner);
			}
			if (viewType == TYPE_FOOTER) {
				View v = new View(getContext());
				v.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, V.dp(48)));
				return new VH(v);
			}
			View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_friend_request, parent, false);
			return new VH(v);
		}

		@Override
		public void onBindViewHolder(@NonNull VH holder, int position) {
			if (getItemViewType(position) != TYPE_ITEM) return;
			FriendRequest item = data.get(position-(bannerVisible ? 1 : 0));
			holder.bind(item);
		}

		@Override
		public int getItemCount() {
			return data.size() + 1 + (bannerVisible ? 1 : 0) + (data.isEmpty() ? 1 : 0);
		}

		@Override
		public int getItemViewType(int position) {
			if(bannerVisible && position==0) return TYPE_BANNER;
			int contentPosition=position-(bannerVisible ? 1 : 0);
			if(data.isEmpty() && contentPosition==0) return TYPE_EMPTY;
			return contentPosition >= data.size()+(data.isEmpty() ? 1 : 0) ? TYPE_FOOTER : TYPE_ITEM;
		}

		class VH extends RecyclerView.ViewHolder {
			ImageView avatar;
			TextView username, lookingForChip, requestTitle, requestDescription, basicInfo, publishTime;
			ViewGroup metadataContainer;
			ImageButton menuBtn;

			VH(View itemView) {
				super(itemView);
				if (itemView.findViewById(R.id.avatar) == null) return;
				avatar = itemView.findViewById(R.id.avatar);
				username = itemView.findViewById(R.id.username);
				lookingForChip = itemView.findViewById(R.id.looking_for_chip);
				requestTitle = itemView.findViewById(R.id.request_title);
				requestDescription = itemView.findViewById(R.id.request_description);
				basicInfo = itemView.findViewById(R.id.basic_info);
				metadataContainer = itemView.findViewById(R.id.metadata_container);
				publishTime = itemView.findViewById(R.id.publish_time);
				menuBtn = itemView.findViewById(R.id.menu_btn);
			}

			void bind(FriendRequest item) {
				username.setText(item.user != null ? item.user.username : "");
				lookingForChip.setText(item.looking_for != null ? item.looking_for : "");
				requestTitle.setText(item.title);
				requestTitle.setVisibility(item.title == null || item.title.isBlank() ? View.GONE : View.VISIBLE);
				requestDescription.setText(item.description);
				requestDescription.setVisibility(item.description == null || item.description.isBlank() ? View.GONE : View.VISIBLE);

				// 头像
				avatar.setImageResource(R.drawable.image_placeholder);
				if (item.user != null && item.user.avatar != null) {
					avatar.setOutlineProvider(OutlineProviders.roundedRect(16));
					ViewImageLoader.loadWithoutAnimation(avatar, null, new UrlImageLoaderRequest(item.user.avatar, V.dp(64), V.dp(64)));
				}

				// 基础信息：年龄·性别·城市
				String age = null, gender = null, city = null;
				if (item.fields != null) {
					for (FriendRequestField f : item.fields) {
						if ("年龄".equals(f.field_key)) age = f.field_value;
						else if ("生理性别".equals(f.field_key)) gender = f.field_value;
						else if ("城市".equals(f.field_key)) city = f.field_value;
					}
				}
				List<String> basicParts=new ArrayList<>();
				if(age!=null && !age.isBlank() && !"未知".equals(age)) basicParts.add(age.matches("\\d+") ? age+"岁" : age);
				if(gender!=null && !gender.isBlank() && !"未知".equals(gender)) basicParts.add(gender);
				if(city!=null && !city.isBlank() && !"未知".equals(city)) basicParts.add(city);
				basicInfo.setText(String.join(" · ", basicParts));
				basicInfo.setVisibility(basicParts.isEmpty() ? View.GONE : View.VISIBLE);

				// Metadata icons
				metadataContainer.removeAllViews();
				if (item.fields != null) {
					for (FriendRequestField f : item.fields) {
						if ("年龄".equals(f.field_key) || "生理性别".equals(f.field_key) || "城市".equals(f.field_key)) continue;
						int iconRes = getMetadataIconRes(f.field_key);
						if (iconRes != 0) {
							ImageView icon = new ImageView(getContext());
							ViewGroup.LayoutParams iconParams = new ViewGroup.LayoutParams(V.dp(24), V.dp(24));
							icon.setLayoutParams(iconParams);
							icon.setPadding(0, 0, V.dp(10), 0);
							icon.setImageResource(iconRes);
							icon.setColorFilter(UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnSurfaceVariant));
							icon.setContentDescription(f.field_key);
							metadataContainer.addView(icon);
						}
					}
				}

				// 时间
				if (item.created_at != null) {
					publishTime.setText(formatTime(item.created_at));
					publishTime.setVisibility(View.VISIBLE);
				} else {
					publishTime.setVisibility(View.GONE);
				}

				// Card 按压动画
			 itemView.setOnTouchListener((v, event) -> {
					switch (event.getAction()) {
						case MotionEvent.ACTION_DOWN:
							v.animate().scaleX(0.985f).scaleY(0.985f).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
							v.setTranslationZ(V.dp(4));
							break;
						case MotionEvent.ACTION_UP:
						case MotionEvent.ACTION_CANCEL:
							v.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
							v.setTranslationZ(0);
							break;
					}
					return false;
				});

				// 菜单
				menuBtn.setOnClickListener(v -> {
					PopupMenu popup = new PopupMenu(getContext(), v);
					String myUserId = String.valueOf(AccountSessionManager.getInstance().getAccount(accountID).self.id);
					// 统一去掉小数点再比较
					String itemUserId = item.user_id != null ? item.user_id.replace(".0", "") : "";
					boolean isOwner = itemUserId.equals(myUserId);

					if (isOwner) {
						popup.getMenu().add(0, 1, 0, "编辑");
						popup.getMenu().add(0, 2, 1, "删除");
					}
					// popup.getMenu().add(0, 3, 2, "发布帖子");
					popup.getMenu().add(0, 4, 3, "举报");

					popup.setOnMenuItemClickListener(menuItem -> {
						if (menuItem.getItemId() == 1) {
							Bundle args = new Bundle();
							args.putString("account", accountID);
							args.putString("editRequestId", item.id);
							args.putString("editTitle", item.title);
							args.putString("editLookingFor", item.looking_for);
							args.putString("editDescription", item.description);
							if (item.fields != null) {
								String[] fieldKeys = new String[item.fields.size()];
								String[] fieldValues = new String[item.fields.size()];
								for (int i = 0; i < item.fields.size(); i++) {
									fieldKeys[i] = item.fields.get(i).field_key;
									fieldValues[i] = item.fields.get(i).field_value;
								}
								args.putStringArray("editFieldKeys", fieldKeys);
								args.putStringArray("editFieldValues", fieldValues);
							}
							Nav.go(getActivity(), FriendRequestCreateFragment.class, args);
						} else if (menuItem.getItemId() == 2) {
							// 删除
							new DeleteFriendRequest(item.id)
								.setCallback(new Callback<Map<String, Object>>() {
									@Override
									public void onSuccess(Map<String, Object> result) {
										data.remove(item);
										adapter.notifyDataSetChanged();
										updateEmptyState();
										Toast.makeText(getContext(), "已删除", Toast.LENGTH_SHORT).show();
									}

									@Override
									public void onError(ErrorResponse error) {
										error.showToast(getContext());
									}
								})
								.exec(accountID);
						} else if (menuItem.getItemId() == 3) {
							// 发布帖子 - 跳转到帖子编辑页面，自动填入 [交友]id[/交友]
							Bundle args = new Bundle();
							args.putString("account", accountID);
							args.putString("preFillText", "[交友]" + item.id + "[/交友]");
							Nav.go(getActivity(), ComposeFragment.class, args);
						} else if (menuItem.getItemId() == 4) {
							Bundle args = new Bundle();
							args.putString("account", accountID);
							args.putString("requestId", item.id);
							args.putString("requestTitle", item.title);
							Nav.go(getActivity(), FriendRequestReportFragment.class, args);
						}
						return true;
					});
					popup.show();
				});

				// 点击进入详情
				itemView.setOnClickListener(v -> {
					Bundle args = new Bundle();
					args.putString("account", accountID);
					args.putString("requestId", item.id);
					Nav.go(getActivity(), FriendRequestDetailFragment.class, args);
				});
			}
		}
	}
}
