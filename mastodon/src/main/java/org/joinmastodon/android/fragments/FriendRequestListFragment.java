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
	private View emptyState;
	private ImageButton fab;
	private float totalDy = 0;
	private boolean fabHidden = false;

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

		// 防诈骗 Banner
		SharedPreferences prefs = getContext().getSharedPreferences("friend_request", 0);
		if (!prefs.getBoolean("banner_dismissed", false)) {
			View banner = inflater.inflate(R.layout.friend_request_fraud_banner, root, false);
			banner.setOnClickListener(v -> {
				prefs.edit().putBoolean("banner_dismissed", true).apply();
				((ViewGroup) banner.getParent()).removeView(banner);
			});
			root.addView(banner);
		}

		// 下拉刷新
		swipeRefreshLayout = new SwipeRefreshLayout(getContext());
		swipeRefreshLayout.setColorSchemeResources(android.R.color.holo_blue_dark);
		swipeRefreshLayout.setOnRefreshListener(() -> {
			currentPage = 1;
			data.clear();
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
		recyclerView.setPadding(0, V.dp(8), 0, V.dp(80));
		adapter = new FriendRequestAdapter();
		recyclerView.setAdapter(adapter);

		// 滚动监听
		recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
			@Override
			public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
				totalDy += dy;
				// 加载更多
				LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
				if (lm != null && !loadingMore && hasMore && lm.findLastVisibleItemPosition() >= data.size() - 3) {
					loadingMore = true;
					currentPage++;
					loadMore();
				}
				// FAB 隐藏/显示
				if (dy > V.dp(24) && !fabHidden) {
					fabHidden = true;
					fab.animate().scaleX(0f).scaleY(0f).setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
				} else if (dy < -V.dp(24) && fabHidden) {
					fabHidden = false;
					fab.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
				}
			}

			@Override
			public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
				if (newState == RecyclerView.SCROLL_STATE_IDLE && !fabHidden) {
					fab.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
				}
			}
		});

		swipeRefreshLayout.addView(recyclerView);
		swipeRefreshLayout.addView(emptyState);
		root.addView(swipeRefreshLayout);

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
		fab.setOnClickListener(v -> {
			Bundle args = new Bundle();
			args.putString("account", accountID);
			Nav.go(getActivity(), FriendRequestCreateFragment.class, args);
		});
		wrapper.addView(fab);

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
		data.clear();
		hasMore = true;
		loadData();
	}

	public void loadData() {
		dataLoading = true;
		showProgress();

		new GetFriendRequestList(currentPage, 20, currentSearch, null)
			.setCallback(new Callback<Map<String, Object>>() {
				@Override
				@SuppressWarnings("unchecked")
				public void onSuccess(Map<String, Object> result) {
					if (getActivity() == null) return;
					List<Map<String, Object>> requests = (List<Map<String, Object>>) result.get("requests");
					Gson gson = new Gson();
					List<FriendRequest> newItems = gson.fromJson(
						gson.toJson(requests),
						new TypeToken<List<FriendRequest>>(){}.getType()
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
		new GetFriendRequestList(currentPage, 20, currentSearch, null)
			.setCallback(new Callback<Map<String, Object>>() {
				@Override
				@SuppressWarnings("unchecked")
				public void onSuccess(Map<String, Object> result) {
					if (getActivity() == null) return;
					List<Map<String, Object>> requests = (List<Map<String, Object>>) result.get("requests");
					Gson gson = new Gson();
					List<FriendRequest> newItems = gson.fromJson(
						gson.toJson(requests),
						new TypeToken<List<FriendRequest>>(){}.getType()
					);

					data.addAll(newItems);
					adapter.notifyItemRangeInserted(data.size() - newItems.size(), newItems.size());

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

	private void updateEmptyState() {
		if (emptyState != null) {
			emptyState.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
			recyclerView.setVisibility(data.isEmpty() ? View.GONE : View.VISIBLE);
		}
	}

	private String formatTime(String createdAt) {
		try {
			java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
			sdf.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
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

		@NonNull
		@Override
		public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
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
			if (getItemViewType(position) == TYPE_FOOTER) return;
			FriendRequest item = data.get(position);
			holder.bind(item);
		}

		@Override
		public int getItemCount() {
			return data.size() + 1;
		}

		@Override
		public int getItemViewType(int position) {
			return position >= data.size() ? TYPE_FOOTER : TYPE_ITEM;
		}

		class VH extends RecyclerView.ViewHolder {
			ImageView avatar;
			TextView username, lookingForChip, basicInfo, publishTime;
			LinearLayout metadataContainer;
			ImageButton menuBtn;

			VH(View itemView) {
				super(itemView);
				if (itemView.findViewById(R.id.avatar) == null) return;
				avatar = itemView.findViewById(R.id.avatar);
				username = itemView.findViewById(R.id.username);
				lookingForChip = itemView.findViewById(R.id.looking_for_chip);
				basicInfo = itemView.findViewById(R.id.basic_info);
				metadataContainer = itemView.findViewById(R.id.metadata_container);
				publishTime = itemView.findViewById(R.id.publish_time);
				menuBtn = itemView.findViewById(R.id.menu_btn);
			}

			void bind(FriendRequest item) {
				username.setText(item.user != null ? item.user.username : "");
				lookingForChip.setText(item.looking_for != null ? item.looking_for : "");

				// 头像
				if (item.user != null && item.user.avatar != null) {
					avatar.setOutlineProvider(OutlineProviders.roundedRect(16));
					ViewImageLoader.loadWithoutAnimation(avatar, null, new UrlImageLoaderRequest(item.user.avatar, V.dp(64), V.dp(64)));
				}

				// 基础信息：年龄·性别·城市
				String age = "未知", gender = "未知", city = "未知";
				if (item.fields != null) {
					for (FriendRequestField f : item.fields) {
						if ("年龄".equals(f.field_key)) age = f.field_value;
						else if ("生理性别".equals(f.field_key)) gender = f.field_value;
						else if ("城市".equals(f.field_key)) city = f.field_value;
					}
				}
				basicInfo.setText(age + "岁 · " + gender + " · " + city);

				// Metadata icons
				metadataContainer.removeAllViews();
				int count = 0;
				if (item.fields != null) {
					for (FriendRequestField f : item.fields) {
						if (count >= 6) break;
						if ("年龄".equals(f.field_key) || "生理性别".equals(f.field_key) || "城市".equals(f.field_key)) continue;
						int iconRes = getMetadataIconRes(f.field_key);
						if (iconRes != 0) {
							ImageView icon = new ImageView(getContext());
							icon.setLayoutParams(new LinearLayout.LayoutParams(V.dp(24), V.dp(24)));
							icon.setPadding(0, 0, V.dp(10), 0);
							icon.setImageResource(iconRes);
							icon.setColorFilter(getResources().getColor(android.R.color.darker_gray));
							icon.setContentDescription(f.field_key);
							metadataContainer.addView(icon);
							count++;
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
					boolean isOwner = item.user_id != null && item.user_id.equals(myUserId);

					if (isOwner) {
						popup.getMenu().add(0, 1, 0, "编辑");
						popup.getMenu().add(0, 2, 1, "删除");
					}
					popup.getMenu().add(0, 3, 2, "举报");

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
