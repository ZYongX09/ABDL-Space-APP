package org.joinmastodon.android.chat.ui;

import android.app.Fragment;
import android.graphics.Rect;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.squareup.otto.Subscribe;

import org.joinmastodon.android.E;
import org.joinmastodon.android.R;
import org.joinmastodon.android.chat.ChatController;
import org.joinmastodon.android.chat.ChatEvents;
import org.joinmastodon.android.chat.ChatStorage;
import org.joinmastodon.android.chat.MessageSendHelper;
import org.joinmastodon.android.chat.model.ChatMessage;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import me.grishka.appkit.imageloader.ViewImageLoader;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.V;

public class ChatFragment extends Fragment {
	private long peerId;
	private String peerName;
	private String peerAvatar;
	private String accountId;

	private RecyclerView recyclerView;
	private ChatMessageAdapter adapter;
	private EditText inputField;
	private ImageButton sendBtn;
	private LinearLayoutManager layoutManager;

	private boolean autoScroll = true;
	private boolean loadingMore = false;
	private long oldestMessageId = 0;

	private final Handler handler = new Handler(Looper.getMainLooper());
	private Timer draftTimer;

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
		return inflater.inflate(R.layout.fragment_chat, container, false);
	}

	@Override
	public void onViewCreated(View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		View toolbar = view.findViewById(R.id.toolbar);
		View inputBar = view.findViewById(R.id.input_bar);
		view.setOnApplyWindowInsetsListener((v, insets) -> {
			int top = insets.getSystemWindowInsetTop();
			int bottom = insets.getStableInsetBottom();
			toolbar.setPadding(toolbar.getPaddingLeft(), top, toolbar.getPaddingRight(), toolbar.getPaddingBottom());
			ViewGroup.LayoutParams toolbarParams = toolbar.getLayoutParams();
			toolbarParams.height = V.dp(64) + top;
			toolbar.setLayoutParams(toolbarParams);
			inputBar.setPadding(inputBar.getPaddingLeft(), inputBar.getPaddingTop(), inputBar.getPaddingRight(), V.dp(8) + bottom);
			return insets;
		});

		ImageButton backBtn = view.findViewById(R.id.back_btn);
		backBtn.setImageTintList(ColorStateList.valueOf(UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnSurface)));
		backBtn.setOnClickListener(v -> getActivity().onBackPressed());
		TextView peerNameView = view.findViewById(R.id.peer_name);
		peerNameView.setText(peerName == null || peerName.isEmpty() ? "私信" : peerName);
		ImageView peerAvatarView = view.findViewById(R.id.peer_avatar);
		if (peerAvatar != null && !peerAvatar.isEmpty()) {
			ViewImageLoader.loadWithoutAnimation(peerAvatarView, peerAvatarView.getDrawable(), new UrlImageLoaderRequest(peerAvatar, V.dp(40), V.dp(40)));
		}

		// Keyboard inset
		view.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
			@Override
			public void onGlobalLayout() {
				Rect r = new Rect();
				view.getWindowVisibleDisplayFrame(r);
				int heightDiff = view.getRootView().getHeight() - r.bottom;
				if (heightDiff > view.getRootView().getHeight() * 0.15) {
					// Keyboard open, scroll to bottom
					if (adapter != null && adapter.getItemCount() > 0) {
						recyclerView.scrollToPosition(adapter.getItemCount() - 1);
					}
				}
			}
		});

		recyclerView = view.findViewById(R.id.recycler);
		inputField = view.findViewById(R.id.input);
		sendBtn = view.findViewById(R.id.send_btn);

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
			}
		});

		sendBtn.setOnClickListener(v -> sendMessage());

		inputField.addTextChangedListener(new TextWatcher() {
			@Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
			@Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
			@Override public void afterTextChanged(Editable s) {
				if (draftTimer != null) draftTimer.cancel();
				draftTimer = new Timer();
				draftTimer.schedule(new TimerTask() {
					@Override public void run() {
						handler.post(() -> saveDraft());
					}
				}, 400);
				if (s.length() > 0 && accountId != null) {
					MessageSendHelper.getInstance(accountId).sendTyping(peerId);
				}
			}
		});

		loadMessages();

		if (accountId != null) {
			ChatStorage storage = ChatStorage.getInstance(getActivity());
			String draft = storage.getDraft(accountId, peerId);
			if (draft != null && !draft.isEmpty()) {
				inputField.setText(draft);
			}
		}
	}

	@Override
	public void onResume() {
		super.onResume();
		E.register(this);
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
		ChatController.getInstance(accountId).loadMessages(peerId, 0, 50,
				new me.grishka.appkit.api.Callback<List<ChatMessage>>() {
					@Override public void onSuccess(List<ChatMessage> result) {
						adapter.setMessages(result);
						if (!result.isEmpty()) oldestMessageId = result.get(0).id;
						scrollToBottom();
					}
					@Override public void onError(me.grishka.appkit.api.ErrorResponse error) {}
				});
	}

	private void loadMore() {
		if (oldestMessageId <= 0 || accountId == null) return;
		loadingMore = true;
		ChatController.getInstance(accountId).loadMessages(peerId, oldestMessageId, 50,
				new me.grishka.appkit.api.Callback<List<ChatMessage>>() {
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
		MessageSendHelper.getInstance(accountId).sendText(peerId, text);
		inputField.setText("");
		saveDraft();
	}

	private void saveDraft() {
		if (accountId == null) return;
		String text = inputField != null ? inputField.getText().toString() : "";
		ChatStorage.getInstance(getActivity()).setDraft(accountId, peerId, text);
	}

	private void scrollToBottom() {
		if (adapter.getItemCount() > 0) {
			recyclerView.scrollToPosition(adapter.getItemCount() - 1);
			autoScroll = true;
		}
	}

	@Subscribe
	public void onNewMessage(ChatEvents.NewChatMessageEvent evt) {
		if (evt.message.peerId == peerId) {
			adapter.addMessage(evt.message);
			if (autoScroll) scrollToBottom();
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
		// TODO: 显示 typing 指示器
	}
}
