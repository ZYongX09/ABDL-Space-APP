package org.joinmastodon.android.fragments;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.friendrequests.DeleteFriendRequest;
import org.joinmastodon.android.api.requests.friendrequests.GetFriendRequestDetail;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.FriendRequest;
import org.joinmastodon.android.model.FriendRequestField;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import androidx.annotation.NonNull;
import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.fragments.LoaderFragment;
import me.grishka.appkit.imageloader.ViewImageLoader;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.V;
import org.joinmastodon.android.ui.OutlineProviders;

public class FriendRequestDetailFragment extends LoaderFragment {
	private String requestId;
	private String accountID;
	private FriendRequest friendRequest;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		requestId = getArguments().getString("requestId");
		accountID = getArguments().getString("account");
	}

	@Override
	public void onAttach(Activity activity) {
		super.onAttach(activity);
		setTitle(getString(R.string.friend_request_detail));
		setHasOptionsMenu(true);
	}

	@Override
	public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_friend_request_detail, container, false);
		return view;
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
		loadData();
	}

	public void loadData() {
		dataLoading = true;
		showProgress();

		new GetFriendRequestDetail(requestId)
			.setCallback(new Callback<Map<String, Object>>() {
				@Override
				@SuppressWarnings("unchecked")
				public void onSuccess(Map<String, Object> result) {
					if (getActivity() == null) return;
					Gson gson = new Gson();
					friendRequest = gson.fromJson(gson.toJson(result), FriendRequest.class);
					updateUI();
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

	private void updateUI() {
		if (friendRequest == null || getView() == null) return;

		ImageView avatar = getView().findViewById(R.id.detail_avatar);
		TextView username = getView().findViewById(R.id.detail_username);
		TextView lookingFor = getView().findViewById(R.id.detail_looking_for);
		TextView description = getView().findViewById(R.id.detail_description);
		LinearLayout fieldsCard = getView().findViewById(R.id.detail_fields_card);
		ImageButton menuBtn = getView().findViewById(R.id.detail_menu);

		username.setText(friendRequest.user != null ? friendRequest.user.display_name : "");
		lookingFor.setText(friendRequest.looking_for != null ? friendRequest.looking_for : "");
		description.setText(friendRequest.description != null ? friendRequest.description : "");

		if (friendRequest.user != null && friendRequest.user.avatar != null) {
			avatar.setOutlineProvider(OutlineProviders.roundedRect(10));
			ViewImageLoader.loadWithoutAnimation(avatar, null, new UrlImageLoaderRequest(friendRequest.user.avatar, V.dp(56), V.dp(56)));
		}

		// 字段信息卡片
		fieldsCard.removeAllViews();
		if (friendRequest.fields != null) {
			// 字段名到图标的映射
			String[][] fieldIcons = {
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

			for (FriendRequestField field : friendRequest.fields) {
				View row = LayoutInflater.from(getContext()).inflate(R.layout.item_friend_request_detail_field, fieldsCard, false);
				ImageView icon = row.findViewById(R.id.field_icon);
				TextView keyText = row.findViewById(R.id.field_key);
				TextView valueText = row.findViewById(R.id.field_value);

				// 设置图标
				int iconRes = 0;
				for (String[] mapping : fieldIcons) {
					if (mapping[0].equals(field.field_key)) {
						iconRes = getResources().getIdentifier(mapping[1], "drawable", getContext().getPackageName());
						break;
					}
				}
				if (iconRes != 0) {
					icon.setImageResource(iconRes);
					icon.setVisibility(View.VISIBLE);
				} else {
					icon.setVisibility(View.GONE);
				}

				keyText.setText(field.field_key);
				valueText.setText(field.field_value);
				fieldsCard.addView(row);
			}
		}

		// 菜单
		menuBtn.setOnClickListener(v -> {
			PopupMenu popup = new PopupMenu(getContext(), v);
			String myUserId = String.valueOf(AccountSessionManager.getInstance().getAccount(accountID).self.id);
			boolean isOwner = friendRequest.user_id != null && friendRequest.user_id.equals(myUserId);

			if (isOwner) {
				popup.getMenu().add(0, 1, 0, "编辑");
				popup.getMenu().add(0, 2, 1, "删除");
			}
			popup.getMenu().add(0, 3, 2, "举报");

			popup.setOnMenuItemClickListener(menuItem -> {
				if (menuItem.getItemId() == 1) {
					// 编辑
					Bundle args = new Bundle();
					args.putString("account", accountID);
					args.putString("editRequestId", friendRequest.id);
					args.putString("editTitle", friendRequest.title);
					args.putString("editLookingFor", friendRequest.looking_for);
					args.putString("editDescription", friendRequest.description);
					Nav.go(getActivity(), FriendRequestCreateFragment.class, args);
				} else if (menuItem.getItemId() == 2) {
					new DeleteFriendRequest(friendRequest.id)
						.setCallback(new Callback<Map<String, Object>>() {
							@Override
							public void onSuccess(Map<String, Object> result) {
								Toast.makeText(getContext(), "已删除", Toast.LENGTH_SHORT).show();
								getActivity().onBackPressed();
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
					args.putString("requestId", friendRequest.id);
					args.putString("requestTitle", friendRequest.title);
					Nav.go(getActivity(), FriendRequestReportFragment.class, args);
				}
				return true;
			});
			popup.show();
		});
	}
}
