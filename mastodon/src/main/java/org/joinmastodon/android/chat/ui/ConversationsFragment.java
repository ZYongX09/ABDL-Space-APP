package org.joinmastodon.android.chat.ui;

import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.squareup.otto.Subscribe;

import org.joinmastodon.android.E;
import org.joinmastodon.android.R;
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
	private View pageTitle;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		accountId = getArguments()!=null ? getArguments().getString("account") : null;
		if(accountId==null)
			accountId = AccountSessionManager.getInstance().getLastActiveAccountID();
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View root = inflater.inflate(R.layout.fragment_conversations, container, false);
		swipeRefreshLayout = root.findViewById(R.id.swipe_refresh);
		recyclerView = root.findViewById(R.id.recycler);
		emptyState = root.findViewById(R.id.empty_state);
		pageTitle = root.findViewById(R.id.page_title);
		recyclerView.setLayoutManager(new LinearLayoutManager(getActivity()));
		adapter = new ConversationsAdapter();
		recyclerView.setAdapter(adapter);

		swipeRefreshLayout.setOnRefreshListener(this::loadData);

		loadData();
		return root;
	}

	public void onApplyWindowInsets(WindowInsets insets) {
		if (pageTitle == null) return;
		pageTitle.setPadding(pageTitle.getPaddingLeft(), insets.getSystemWindowInsetTop(), pageTitle.getPaddingRight(), 0);
		ViewGroup.LayoutParams lp = pageTitle.getLayoutParams();
		lp.height = V.dp(64) + insets.getSystemWindowInsetTop();
		pageTitle.setLayoutParams(lp);
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
		controller.loadConversations(true, new me.grishka.appkit.api.Callback<List<Conversation>>() {
			@Override public void onSuccess(List<Conversation> result) {
				data.clear();
				data.addAll(result);
				adapter.notifyDataSetChanged();
				emptyState.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
				recyclerView.setVisibility(data.isEmpty() ? View.GONE : View.VISIBLE);
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
		Bundle args = new Bundle();
		args.putString("account", accountId);
		args.putLong("peer_id", c.peerId);
		args.putString("peer_name", c.username);
		args.putString("peer_avatar", c.avatar != null ? c.avatar : "");
		me.grishka.appkit.Nav.go(getActivity(), ChatFragment.class, args);
	}
}
