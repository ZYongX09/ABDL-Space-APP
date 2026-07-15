package org.joinmastodon.android.chat.ui;

import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.squareup.otto.Subscribe;

import org.joinmastodon.android.E;
import org.joinmastodon.android.chat.ChatController;
import org.joinmastodon.android.chat.ChatEvents;
import org.joinmastodon.android.chat.model.Conversation;
import org.joinmastodon.android.api.session.AccountSessionManager;

import java.util.ArrayList;
import java.util.List;

import me.grishka.appkit.utils.V;

public class ConversationsFragment extends Fragment {
	private RecyclerView recyclerView;
	private SwipeRefreshLayout swipeRefreshLayout;
	private ConversationsAdapter adapter;
	private List<Conversation> data = new ArrayList<>();
	private String accountId;
	private View emptyState;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		accountId = AccountSessionManager.getInstance().getLastActiveAccountID();
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View root = new android.widget.FrameLayout(getActivity());
		root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

		swipeRefreshLayout = new SwipeRefreshLayout(getActivity());
		swipeRefreshLayout.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

		recyclerView = new RecyclerView(getActivity());
		recyclerView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		recyclerView.setLayoutManager(new LinearLayoutManager(getActivity()));
		adapter = new ConversationsAdapter();
		recyclerView.setAdapter(adapter);

		swipeRefreshLayout.addView(recyclerView);
		((ViewGroup) root).addView(swipeRefreshLayout);

		swipeRefreshLayout.setOnRefreshListener(this::loadData);

		loadData();
		return root;
	}

	@Override
	public void onResume() {
		super.onResume();
		E.register(this);
	}

	@Override
	public void onPause() {
		super.onPause();
		E.unregister(this);
	}

	private void loadData() {
		if (accountId == null) return;
		ChatController controller = ChatController.getInstance(accountId);
		controller.loadConversations(false, new me.grishka.appkit.api.Callback<List<Conversation>>() {
			@Override public void onSuccess(List<Conversation> result) {
				data.clear();
				data.addAll(result);
				adapter.notifyDataSetChanged();
				swipeRefreshLayout.setRefreshing(false);
			}
			@Override public void onError(me.grishka.appkit.api.ErrorResponse error) {
				swipeRefreshLayout.setRefreshing(false);
			}
		});
	}

	@Subscribe
	public void onConversationsUpdated(ChatEvents.ConversationsUpdatedEvent evt) {
		loadData();
	}

	@Subscribe
	public void onNewMessage(ChatEvents.NewChatMessageEvent evt) {
		loadData();
	}

	private class ConversationsAdapter extends RecyclerView.Adapter<ConversationViewHolder> {
		@NonNull @Override
		public ConversationViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			return new ConversationViewHolder(new ConversationCell(parent.getContext()));
		}

		@Override
		public void onBindViewHolder(@NonNull ConversationViewHolder holder, int position) {
			Conversation c = data.get(position);
			holder.cell.bind(c);
			holder.itemView.setOnClickListener(v -> openChat(c));
		}

		@Override public int getItemCount() { return data.size(); }
	}

	private static class ConversationViewHolder extends RecyclerView.ViewHolder {
		ConversationCell cell;
		ConversationViewHolder(View itemView) {
			super(itemView);
			cell = (ConversationCell) itemView;
		}
	}

	private void openChat(Conversation c) {
		// TODO: Task 11 - 实现 ChatFragment 后取消注释
		// ChatFragment fragment = new ChatFragment();
		// Bundle args = new Bundle();
		// args.putLong("peer_id", c.peerId);
		// args.putString("peer_name", c.username);
		// args.putString("peer_avatar", c.avatar);
		// fragment.setArguments(args);
		// me.grishka.appkit.Nav.showFragment(fragment);
	}
}
