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
import org.joinmastodon.android.api.requests.friendrequests.GetFriendRequestComments;
import org.joinmastodon.android.api.requests.friendrequests.GetFriendRequestDetail;
import org.joinmastodon.android.api.requests.friendrequests.PostFriendRequestComment;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.FriendRequest;
import org.joinmastodon.android.model.FriendRequestComment;
import org.joinmastodon.android.model.FriendRequestField;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.fragments.LoaderFragment;

public class FriendRequestDetailFragment extends LoaderFragment {
	private String requestId;
	private String accountID;
	private FriendRequest friendRequest;
	private List<FriendRequestComment> comments = new ArrayList<>();
	private CommentAdapter commentAdapter;
	private EditText commentInput;

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
	protected View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_friend_request_detail, container, false);

		commentInput = view.findViewById(R.id.comment_input);
		ImageButton sendBtn = view.findViewById(R.id.send_btn);
		sendBtn.setOnClickListener(v -> postComment());

		return view;
	}

	@Override
	protected void onShown() {
		super.onShown();
		if (!loaded && !dataLoading) {
			loadData();
		}
	}

	private void loadData() {
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
					loadComments();
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

	private void loadComments() {
		new GetFriendRequestComments(requestId)
			.setCallback(new Callback<Map<String, Object>>() {
				@Override
				@SuppressWarnings("unchecked")
				public void onSuccess(Map<String, Object> result) {
					if (getActivity() == null) return;
					List<Map<String, Object>> commentList = (List<Map<String, Object>>) result.get("comments");
					Gson gson = new Gson();
					comments = gson.fromJson(gson.toJson(commentList), new TypeToken<List<FriendRequestComment>>(){}.getType());
					commentAdapter = new CommentAdapter();
					RecyclerView commentList = getView().findViewById(R.id.comments_list);
					if (commentList != null) {
						commentList.setLayoutManager(new LinearLayoutManager(getContext()));
						commentList.setAdapter(commentAdapter);
					}
					dataLoaded();
				}

				@Override
				public void onError(ErrorResponse error) {
					if (getActivity() == null) return;
					dataLoaded();
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
		LinearLayout fieldsContainer = getView().findViewById(R.id.detail_fields);
		ImageButton menuBtn = getView().findViewById(R.id.detail_menu);

		username.setText(friendRequest.user != null ? friendRequest.user.display_name : "");
		lookingFor.setText(friendRequest.looking_for != null ? "找" + friendRequest.looking_for : "");
		description.setText(friendRequest.description != null ? friendRequest.description : "");

		if (friendRequest.user != null && friendRequest.user.avatar != null) {
			me.grishka.appkit.imageloader.ImageLoader.getInstance().loadAsync(avatar, friendRequest.user.avatar, null);
		}

		// 显示所有字段
		fieldsContainer.removeAllViews();
		if (friendRequest.fields != null) {
			for (FriendRequestField field : friendRequest.fields) {
				TextView tv = new TextView(getContext());
				tv.setText(field.field_key + "：" + field.field_value);
				tv.setTextSize(14);
				tv.setPadding(0, 4, 0, 4);
				fieldsContainer.addView(tv);
			}
		}

		// 菜单
		menuBtn.setOnClickListener(v -> {
			PopupMenu popup = new PopupMenu(getContext(), v);
			String myUserId = AccountSessionManager.getInstance().getAccount(accountID).getUserId();
			boolean isOwner = friendRequest.user_id != null && friendRequest.user_id.equals(myUserId);

			if (isOwner) {
				popup.getMenu().add(0, 1, 0, "删除");
			}
			popup.getMenu().add(0, 2, 1, "举报");

			popup.setOnMenuItemClickListener(menuItem -> {
				if (menuItem.getItemId() == 1) {
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
				} else if (menuItem.getItemId() == 2) {
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

	private void postComment() {
		String content = commentInput.getText().toString().trim();
		if (content.isEmpty()) return;

		new PostFriendRequestComment(requestId, content, null)
			.setCallback(new Callback<Map<String, Object>>() {
				@Override
				public void onSuccess(Map<String, Object> result) {
					commentInput.setText("");
					loadComments();
					Toast.makeText(getContext(), "评论成功", Toast.LENGTH_SHORT).show();
				}

				@Override
				public void onError(ErrorResponse error) {
					error.showToast(getContext());
				}
			})
			.exec(accountID);
	}

	private class CommentAdapter extends RecyclerView.Adapter<CommentAdapter.VH> {
		@NonNull
		@Override
		public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_friend_request_comment, parent, false);
			return new VH(v);
		}

		@Override
		public void onBindViewHolder(@NonNull VH holder, int position) {
			FriendRequestComment comment = comments.get(position);
			holder.username.setText(comment.user != null ? comment.user.display_name : "");
			holder.content.setText(comment.content);

			// 显示回复
			if (comment.replies != null && !comment.replies.isEmpty()) {
				holder.repliesContainer.removeAllViews();
				holder.repliesContainer.setVisibility(View.VISIBLE);
				for (FriendRequestComment reply : comment.replies) {
					View replyView = LayoutInflater.from(getContext()).inflate(R.layout.item_friend_request_comment_reply, holder.repliesContainer, false);
					TextView replyUser = replyView.findViewById(R.id.reply_username);
					TextView replyContent = replyView.findViewById(R.id.reply_content);
					replyUser.setText(reply.user != null ? reply.user.display_name : "");
					replyContent.setText(reply.content);
					holder.repliesContainer.addView(replyView);
				}
			} else {
				holder.repliesContainer.setVisibility(View.GONE);
			}
		}

		@Override
		public int getItemCount() {
			return comments.size();
		}

		class VH extends RecyclerView.ViewHolder {
			TextView username, content;
			LinearLayout repliesContainer;

			VH(View itemView) {
				super(itemView);
				username = itemView.findViewById(R.id.comment_username);
				content = itemView.findViewById(R.id.comment_content);
				repliesContainer = itemView.findViewById(R.id.replies_container);
			}
		}
	}
}
