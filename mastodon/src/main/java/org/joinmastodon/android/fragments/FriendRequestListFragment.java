package org.joinmastodon.android.fragments;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.FrameLayout;

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

public class FriendRequestListFragment extends LoaderFragment {
	private RecyclerView recyclerView;
	private SwipeRefreshLayout swipeRefreshLayout;
	private FriendRequestAdapter adapter;
	private List<FriendRequest> data = new ArrayList<>();
	private EditText searchInput;
	private String currentSearch = "";
	private int currentPage = 1;
	private boolean loadingMore = false;
	private boolean hasMore = true;
	private String accountID;
	private TextView emptyView;

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
			// 搜索功能暂未实现
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

		// 防诈骗提示横幅
		View banner = inflater.inflate(R.layout.friend_request_fraud_banner, root, false);
		root.addView(banner);

		// 下拉刷新
		swipeRefreshLayout = new SwipeRefreshLayout(getContext());
		swipeRefreshLayout.setColorSchemeResources(android.R.color.holo_orange_dark);
		swipeRefreshLayout.setOnRefreshListener(() -> {
			currentPage = 1;
			data.clear();
			hasMore = true;
			loadData();
		});

		// 空状态提示
		emptyView = new TextView(getContext());
		emptyView.setText(R.string.friend_request_no_data);
		emptyView.setTextSize(15);
		emptyView.setGravity(android.view.Gravity.CENTER);
		emptyView.setPadding(0, V.dp(64), 0, 0);
		emptyView.setVisibility(View.GONE);

		// RecyclerView
		recyclerView = new RecyclerView(getContext());
		recyclerView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
		adapter = new FriendRequestAdapter();
		recyclerView.setAdapter(adapter);

		// 滚动加载更多
		recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
			@Override
			public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
				LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
				if (lm != null && !loadingMore && hasMore && lm.findLastVisibleItemPosition() >= data.size() - 3) {
					loadingMore = true;
					currentPage++;
					loadMore();
				}
			}
		});

		swipeRefreshLayout.addView(recyclerView);
		root.addView(swipeRefreshLayout);

		wrapper.addView(root);

		// FAB - 发布交友请求
		ImageButton fab = new ImageButton(getContext());
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
		if (emptyView != null) {
			emptyView.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
			recyclerView.setVisibility(data.isEmpty() ? View.GONE : View.VISIBLE);
		}
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
			return data.size() + 1; // +1 for footer
		}

		@Override
		public int getItemViewType(int position) {
			return position >= data.size() ? TYPE_FOOTER : TYPE_ITEM;
		}

		class VH extends RecyclerView.ViewHolder {
			ImageView avatar;
			TextView username, lookingFor, primaryFields, secondaryIcons;
			ImageButton menuBtn;

			VH(View itemView) {
				super(itemView);
				if (itemView.findViewById(R.id.avatar) == null) return; // footer
				avatar = itemView.findViewById(R.id.avatar);
				username = itemView.findViewById(R.id.username);
				lookingFor = itemView.findViewById(R.id.looking_for);
				primaryFields = itemView.findViewById(R.id.primary_fields);
				secondaryIcons = itemView.findViewById(R.id.secondary_icons);
				menuBtn = itemView.findViewById(R.id.menu_btn);
			}

			void bind(FriendRequest item) {
				username.setText(item.user != null ? item.user.display_name : "");
				lookingFor.setText(item.looking_for != null ? "找" + item.looking_for : "");

				// 加载头像
				if (item.user != null && item.user.avatar != null) {
					ViewImageLoader.loadWithoutAnimation(avatar, null, new UrlImageLoaderRequest(item.user.avatar, V.dp(48), V.dp(48)));
				}

				// 主要信息
				List<String> primaryInfo = new ArrayList<>();
				List<String> secondaryInfo = new ArrayList<>();
				if (item.fields != null) {
					for (FriendRequestField f : item.fields) {
						if (f.is_primary == 1) {
							primaryInfo.add(f.field_key + "：" + f.field_value);
						} else {
							secondaryInfo.add(f.field_key);
						}
					}
				}
				primaryFields.setText(String.join("  ·  ", primaryInfo));
				secondaryIcons.setText(String.join("  ", secondaryInfo));

				// 菜单
				menuBtn.setOnClickListener(v -> {
					PopupMenu popup = new PopupMenu(getContext(), v);
							String myUserId = AccountSessionManager.getInstance().getAccount(accountID).getID();
					boolean isOwner = item.user_id != null && item.user_id.equals(myUserId);

					if (isOwner) {
						popup.getMenu().add(0, 1, 0, "删除");
					}
					popup.getMenu().add(0, 2, 1, "举报");

					popup.setOnMenuItemClickListener(menuItem -> {
						if (menuItem.getItemId() == 1) {
							// 删除
							new DeleteFriendRequest(item.id)
								.setCallback(new Callback<Map<String, Object>>() {
									@Override
									public void onSuccess(Map<String, Object> result) {
										data.remove(item);
										adapter.notifyDataSetChanged();
										Toast.makeText(getContext(), "已删除", Toast.LENGTH_SHORT).show();
									}

									@Override
									public void onError(ErrorResponse error) {
										error.showToast(getContext());
									}
								})
								.exec(accountID);
						} else if (menuItem.getItemId() == 2) {
							// 举报
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

	private static class V {
		static int dp(int dp) {
			return (int) (dp * android.content.res.Resources.getSystem().getDisplayMetrics().density);
		}
	}
}
