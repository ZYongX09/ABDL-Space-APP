package org.joinmastodon.android.chat.ui;

import android.app.Fragment;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.squareup.otto.Subscribe;

import org.joinmastodon.android.E;
import org.joinmastodon.android.chat.ChatController;
import org.joinmastodon.android.chat.ChatEvents;
import org.joinmastodon.android.chat.ChatStorage;
import org.joinmastodon.android.chat.MessageSendHelper;
import org.joinmastodon.android.chat.model.ChatMessage;
import org.joinmastodon.android.api.session.AccountSessionManager;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class ChatFragment extends Fragment {
	private long peerId;
	private String peerName;
	private String peerAvatar;
	private String accountId;

	private RecyclerView recyclerView;
	private ChatMessageAdapter adapter;
	private EditText inputField;
	private ImageButton sendBtn;
	private TextView titleView;
	private TextView subtitleView;
	private View scrollToBottomBtn;

	private LinearLayoutManager layoutManager;
	private boolean autoScroll = true;
	private boolean loadingMore = false;
	private long oldestMessageId = 0;

	private final Handler handler = new Handler(Looper.getMainLooper());
	private Timer draftTimer;
	private String draftText = "";

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Bundle args = getArguments();
		if (args != null) {
			peerId = args.getLong("peer_id");
			peerName = args.getString("peer_name", "");
			peerAvatar = args.getString("peer_avatar", "");
		}
		accountId = AccountSessionManager.getInstance().getLastActiveAccountID();
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View root = new android.widget.LinearLayout(getActivity());
		((android.widget.LinearLayout) root).setOrientation(android.widget.LinearLayout.VERTICAL);
		root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

		// Toolbar
		View toolbar = createToolbar();
		((android.widget.LinearLayout) root).addView(toolbar);

		// Messages list
		View listContainer = new android.widget.FrameLayout(getActivity());
		listContainer.setLayoutParams(new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

		recyclerView = new RecyclerView(getActivity());
		recyclerView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		layoutManager = new LinearLayoutManager(getActivity());
		layoutManager.setStackFromEnd(true);
		recyclerView.setLayoutManager(layoutManager);
		adapter = new ChatMessageAdapter();
		recyclerView.setAdapter(adapter);

		recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
			@Override
			public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
				if (dy < 0 && !loadingMore) {
					int firstVisible = layoutManager.findFirstVisibleItemPosition();
					if (firstVisible <= 2) {
						loadMore();
					}
				}
				autoScroll = !rv.canScrollVertically(1);
				scrollToBottomBtn.setVisibility(autoScroll ? View.GONE : View.VISIBLE);
			}
		});

		((android.widget.FrameLayout) listContainer).addView(recyclerView);

		// Scroll to bottom button
		scrollToBottomBtn = createScrollToBottomBtn();
		((android.widget.FrameLayout) listContainer).addView(scrollToBottomBtn);
		scrollToBottomBtn.setVisibility(View.GONE);

		((android.widget.LinearLayout) root).addView(listContainer);

		// Input bar
		View inputBar = createInputBar();
		((android.widget.LinearLayout) root).addView(inputBar);

		// Load messages
		loadMessages();

		// Load draft
		if (accountId != null) {
			ChatStorage storage = ChatStorage.getInstance(getActivity());
			draftText = storage.getDraft(accountId, peerId);
			if (draftText != null && !draftText.isEmpty()) {
				inputField.setText(draftText);
			}
		}

		return root;
	}

	@Override
	public void onResume() {
		super.onResume();
		E.register(this);
		// Mark read
		if (accountId != null) {
			ChatController controller = ChatController.getInstance(accountId);
			ChatStorage storage = ChatStorage.getInstance(getActivity());
			List<ChatMessage> msgs = storage.getMessages(accountId, peerId, 1);
			if (!msgs.isEmpty()) {
				ChatMessage last = msgs.get(msgs.size() - 1);
				if (!last.out && last.id > 0) {
					controller.markRead(peerId, last.id);
				}
			}
		}
	}

	@Override
	public void onPause() {
		super.onPause();
		E.unregister(this);
		saveDraft();
	}

	private void loadMessages() {
		if (accountId == null) return;
		ChatController controller = ChatController.getInstance(accountId);
		controller.loadMessages(peerId, 0, 50, new me.grishka.appkit.api.Callback<List<ChatMessage>>() {
			@Override public void onSuccess(List<ChatMessage> result) {
				adapter.setMessages(result);
				if (!result.isEmpty()) {
					oldestMessageId = result.get(0).id;
				}
				scrollToBottom();
			}
			@Override public void onError(me.grishka.appkit.api.ErrorResponse error) {}
		});
	}

	private void loadMore() {
		if (oldestMessageId <= 0 || accountId == null) return;
		loadingMore = true;
		ChatController controller = ChatController.getInstance(accountId);
		controller.loadMessages(peerId, oldestMessageId, 50, new me.grishka.appkit.api.Callback<List<ChatMessage>>() {
			@Override public void onSuccess(List<ChatMessage> result) {
				loadingMore = false;
				if (!result.isEmpty()) {
					adapter.addMessages(result);
					oldestMessageId = result.get(0).id;
				}
			}
			@Override public void onError(me.grishka.appkit.api.ErrorResponse error) {
				loadingMore = false;
			}
		});
	}

	private void sendMessage() {
		String text = inputField.getText().toString().trim();
		if (text.isEmpty() || accountId == null) return;
		MessageSendHelper helper = MessageSendHelper.getInstance(accountId);
		helper.sendText(peerId, text);
		inputField.setText("");
		saveDraft();
	}

	private void saveDraft() {
		if (accountId == null) return;
		String text = inputField != null ? inputField.getText().toString() : "";
		ChatStorage storage = ChatStorage.getInstance(getActivity());
		storage.setDraft(accountId, peerId, text);
	}

	private void scrollToBottom() {
		if (adapter.getItemCount() > 0) {
			recyclerView.scrollToPosition(adapter.getItemCount() - 1);
			autoScroll = true;
			scrollToBottomBtn.setVisibility(View.GONE);
		}
	}

	private View createToolbar() {
		android.widget.LinearLayout toolbar = new android.widget.LinearLayout(getActivity());
		toolbar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
		toolbar.setGravity(android.view.Gravity.CENTER_VERTICAL);
		int dp48 = (int) (48 * getResources().getDisplayMetrics().density);
		toolbar.setLayoutParams(new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp48));
		toolbar.setPadding((int) (16 * getResources().getDisplayMetrics().density), 0, 0, 0);

		ImageButton backBtn = new ImageButton(getActivity());
		backBtn.setImageResource(android.R.drawable.ic_menu_revert);
		backBtn.setBackground(null);
		backBtn.setOnClickListener(v -> getActivity().onBackPressed());
		toolbar.addView(backBtn, new android.widget.LinearLayout.LayoutParams(dp48, dp48));

		android.widget.LinearLayout textContainer = new android.widget.LinearLayout(getActivity());
		textContainer.setOrientation(android.widget.LinearLayout.VERTICAL);
		textContainer.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		titleView = new TextView(getActivity());
		titleView.setText(peerName);
		titleView.setTextSize(16);
		titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
		textContainer.addView(titleView);

		subtitleView = new TextView(getActivity());
		subtitleView.setTextSize(12);
		subtitleView.setTextColor(org.joinmastodon.android.ui.utils.UiUtils.getThemeColor(getActivity(), org.joinmastodon.android.R.attr.colorM3OnSurfaceVariant));
		subtitleView.setVisibility(View.GONE);
		textContainer.addView(subtitleView);

		toolbar.addView(textContainer);
		return toolbar;
	}

	private View createInputBar() {
		android.widget.LinearLayout inputBar = new android.widget.LinearLayout(getActivity());
		inputBar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
		inputBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
		int dp = (int) getResources().getDisplayMetrics().density;
		inputBar.setPadding(12 * dp, 8 * dp, 12 * dp, 8 * dp);
		inputBar.setBackgroundColor(org.joinmastodon.android.ui.utils.UiUtils.getThemeColor(getActivity(), org.joinmastodon.android.R.attr.colorM3Surface));

		inputField = new EditText(getActivity());
		inputField.setHint("输入消息...");
		inputField.setTextSize(15);
		inputField.setMaxLines(5);
		inputField.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		inputField.setBackground(null);
		inputField.addTextChangedListener(new TextWatcher() {
			@Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
			@Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
			@Override public void afterTextChanged(Editable s) {
				// Debounce draft save
				if (draftTimer != null) draftTimer.cancel();
				draftTimer = new Timer();
				draftTimer.schedule(new TimerTask() {
					@Override public void run() {
						handler.post(() -> saveDraft());
					}
				}, 400);
				// Typing indicator
				if (s.length() > 0 && accountId != null) {
					MessageSendHelper.getInstance(accountId).sendTyping(peerId);
				}
			}
		});
		inputBar.addView(inputField);

		sendBtn = new ImageButton(getActivity());
		sendBtn.setImageResource(org.joinmastodon.android.R.drawable.ic_send_24px);
		sendBtn.setBackground(null);
		sendBtn.setOnClickListener(v -> sendMessage());
		inputBar.addView(sendBtn, new android.widget.LinearLayout.LayoutParams(48 * dp, 48 * dp));

		return inputBar;
	}

	private View createScrollToBottomBtn() {
		ImageButton btn = new ImageButton(getActivity());
		btn.setImageResource(android.R.drawable.ic_menu_sort_by_size);
		btn.setBackground(null);
		int dp = (int) getResources().getDisplayMetrics().density;
		android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(48 * dp, 48 * dp);
		lp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
		lp.setMargins(0, 0, 16 * dp, 16 * dp);
		btn.setLayoutParams(lp);
		btn.setOnClickListener(v -> scrollToBottom());
		return btn;
	}

	@Subscribe
	public void onNewMessage(ChatEvents.NewChatMessageEvent evt) {
		if (evt.message.peerId == peerId) {
			adapter.addMessage(evt.message);
			if (autoScroll) scrollToBottom();
			// Mark read
			if (!evt.message.out && evt.message.id > 0 && accountId != null) {
				ChatController.getInstance(accountId).markRead(peerId, evt.message.id);
			}
		}
	}

	@Subscribe
	public void onSendStateChanged(ChatEvents.MessageSendStateEvent evt) {
		if (evt.message.peerId == peerId) {
			adapter.updateMessage(evt.message);
		}
	}

	@Subscribe
	public void onTyping(ChatEvents.TypingEvent evt) {
		if (evt.fromUserId == peerId) {
			subtitleView.setText("正在输入...");
			subtitleView.setVisibility(View.VISIBLE);
			handler.postDelayed(() -> subtitleView.setVisibility(View.GONE), 3000);
		}
	}
}
