package org.joinmastodon.android.fragments;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.icu.text.BreakIterator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.twitter.twittertext.TwitterTextEmojiRegex;

import org.joinmastodon.android.E;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.api.requests.accounts.GetOwnAccount;
import org.joinmastodon.android.api.requests.nbw.RecommendNBWForum;
import org.joinmastodon.android.api.requests.statuses.CreateStatus;
import org.joinmastodon.android.api.requests.statuses.EditStatus;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.events.StatusCountersUpdatedEvent;
import org.joinmastodon.android.events.StatusCreatedEvent;
import org.joinmastodon.android.events.StatusUpdatedEvent;
import org.joinmastodon.android.fragments.account_list.AccountSearchFragment;
import org.joinmastodon.android.fragments.settings.NBWPostRegisterActivity;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Emoji;
import org.joinmastodon.android.model.EmojiCategory;
import org.joinmastodon.android.model.Instance;
import org.joinmastodon.android.model.Mention;
import org.joinmastodon.android.model.Preferences;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.StatusPrivacy;
import org.joinmastodon.android.model.StatusQuotePolicy;
import org.joinmastodon.android.model.viewmodel.CheckableListItem;
import org.joinmastodon.android.model.viewmodel.ListItem;
import org.joinmastodon.android.ui.CustomEmojiPopupKeyboard;
import org.joinmastodon.android.ui.ExtendedPopupMenu;
import org.joinmastodon.android.ui.M3AlertDialogBuilder;
import org.joinmastodon.android.ui.OutlineProviders;
import org.joinmastodon.android.ui.PopupKeyboard;
import org.joinmastodon.android.ui.displayitems.InlineStatusStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.StatusDisplayItem;
import org.joinmastodon.android.ui.drawables.SpoilerStripesDrawable;
import org.joinmastodon.android.ui.sheets.ListItemsSheet;
import org.joinmastodon.android.ui.text.ComposeAutocompleteSpan;
import org.joinmastodon.android.ui.text.ComposeHashtagOrMentionSpan;
import org.joinmastodon.android.ui.text.HtmlParser;
import org.joinmastodon.android.ui.utils.SimpleTextWatcher;
import org.joinmastodon.android.ui.views.CrisisWarningViewController;
import org.joinmastodon.android.ui.utils.LocationUtils;
import org.joinmastodon.android.ui.sheets.LocationPermissionSheet;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.joinmastodon.android.ui.viewcontrollers.ComposeAutocompleteViewController;
import org.joinmastodon.android.ui.viewcontrollers.ComposeLanguageAlertViewController;
import org.joinmastodon.android.ui.viewcontrollers.ComposeMediaViewController;
import org.joinmastodon.android.ui.media.MediaPickerConfig;
import org.joinmastodon.android.ui.media.MediaCameraContract;
import org.joinmastodon.android.ui.media.MediaStoreLoader;
import org.joinmastodon.android.ui.sheets.MediaPickerSheet;
import org.joinmastodon.android.ui.viewcontrollers.ComposePollViewController;
import org.joinmastodon.android.ui.views.ComposeEditText;
import org.joinmastodon.android.ui.views.CustomScrollView;
import org.joinmastodon.android.ui.views.NestedRecyclerScrollView;
import org.joinmastodon.android.ui.views.SizeListenerLinearLayout;
import org.joinmastodon.android.ui.views.StatusView;
import org.joinmastodon.android.ui.views.TopBarsScrollAwayLinearLayout;
import org.joinmastodon.android.utils.ViewImageLoaderHolderTarget;
import org.parceler.Parcels;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import androidx.annotation.NonNull;
import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.fragments.CustomTransitionsFragment;
import me.grishka.appkit.imageloader.ViewImageLoader;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.CubicBezierInterpolator;
import me.grishka.appkit.utils.V;

public class ComposeFragment extends MastodonToolbarFragment implements ComposeEditText.SelectionListener, CustomTransitionsFragment{

	private static final int MEDIA_RESULT=717;
	private static final int MEDIA_PERMISSION_RESULT=718;
	private static final int CAMERA_CAPTURE_RESULT=719;
	public static final int IMAGE_DESCRIPTION_RESULT=363;
	private static final int AUTOCOMPLETE_ACCOUNT_RESULT=779;
	private static final String TAG="ComposeFragment";

	private static final Pattern MENTION_PATTERN=Pattern.compile("(^|[^\\/\\w])@(([a-z0-9_]+)@[a-z0-9\\.\\-]+[a-z0-9]+)", Pattern.CASE_INSENSITIVE);

	// from https://github.com/mastodon/mastodon-ios/blob/main/Mastodon/Helper/MastodonRegex.swift
	private static final Pattern AUTO_COMPLETE_PATTERN=Pattern.compile("(?<!\\w)(?:@([a-z0-9_]+)(@[a-z0-9_\\.\\-]*)?|#([^\\s.]+)|:([a-z0-9_]+))", Pattern.CASE_INSENSITIVE);
	private static final Pattern HIGHLIGHT_PATTERN=Pattern.compile("(?<!\\w)(?:@([a-zA-Z0-9_]+)(@[a-zA-Z0-9_.-]+)?|#([^\\s.]+))");

	@SuppressLint("NewApi") // this class actually exists on 6.0
	private final BreakIterator breakIterator=BreakIterator.getCharacterInstance();

	public TopBarsScrollAwayLinearLayout mainLayout;
	private SizeListenerLinearLayout contentView;
	private TextView selfName, selfUsername;
	private ImageView selfAvatar;
	private Account self;
	private String instanceDomain;

	private ComposeEditText mainEditText;
	private TextView charCounter;
	private String accountID;
	private int charCount, charLimit, trimmedCharCount;

	private ImageButton mediaBtn, pollBtn, emojiBtn, spoilerBtn, languageBtn;
	private FrameLayout replyWrap;
	private LinearLayout visibilityBtn;
	private TextView visibilityText1, visibilityText2, visibilityCurrentText;
	private LinearLayout bottomBar;
	private View autocompleteDivider;
	private FrameLayout quotedPostWrap;

	private List<EmojiCategory> customEmojis;
	private CustomEmojiPopupKeyboard emojiKeyboard;
	private Status replyTo;
	private Status quotedStatus;
	private String initialText;
	private String uuid;
	private EditText spoilerEdit;
	private View spoilerWrap;
	private boolean hasSpoiler;
	private ProgressBar sendProgress;
	private View sendingOverlay;
	private WindowManager wm;
	private StatusPrivacy statusVisibility=StatusPrivacy.PUBLIC;
	private StatusQuotePolicy statusQuotePolicy=StatusQuotePolicy.PUBLIC;
	private int selectedNBWForumId;
	private int resolvedNBWForumId=27;
	private String aiRecommendedForumName;
	private TextView newBabyWorldCardText, newBabyWorldCardAction;
	private View newBabyWorldCard;
	private View crisisCard;
	private CrisisWarningViewController crisisWarningController;
	private boolean crisisWarningDismissed;
	private static final String[] CRISIS_KEYWORDS={"自杀", "自残", "死亡", "抑郁", "双向", "双相", "ADHD", "PTSD", "童年创伤", "不想活", "不想活了", "不想再活", "活不下去", "活着没意思", "活着没有意义", "想死", "去死", "求死", "寻死", "轻生", "结束生命", "结束自己的生命", "了结自己", "离开这个世界", "永远消失", "绝望", "没必要活", "没有活下去的理由", "不值得活", "死了算了", "一死了之", "结束一切", "解脱了", "撑不下去了", "不想醒来", "希望永远睡过去", "割腕", "割脉", "拿刀割自己", "划伤自己", "跳楼", "跳河", "撞车", "卧轨", "吞药", "服毒", "上吊", "自我伤害", "伤害自己", "伤害我自己"};
	private int newBabyWorldBindingState;
	private int newBabyWorldBindingRequestGeneration;
	private int aiRecommendationRequestGeneration;
	private boolean aiRecommendationInFlight;
	private static final Set<String> dismissedNewBabyWorldCards=new HashSet<>();
	private static final int BINDING_CHECKING=0;
	private static final int BINDING_BOUND=1;
	private static final int BINDING_UNBOUND=-1;
	private static final int BINDING_CHECK_FAILED=-2;
	private ComposeAutocompleteSpan currentAutocompleteSpan;
	private FrameLayout mainEditTextWrap;
	private ComposeLanguageAlertViewController.SelectedOption postLang;

	// 位置选择
	private static final int LOCATION_PROVINCE=0;
	private static final int LOCATION_CITY=1;
	private static final int LOCATION_DISTRICT=2;
	private static final int LOCATION_NONE=3;
	private int selectedLocationLevel=LOCATION_PROVINCE; // 默认展示省
	private LinearLayout locationBtn;
	private TextView locationText;
	private LocationUtils.ResolvedLocation currentLocation;

	private ComposeAutocompleteViewController autocompleteViewController;
	private ComposePollViewController pollViewController=new ComposePollViewController(this);
	private ComposeMediaViewController mediaViewController=new ComposeMediaViewController(this);
	public Instance instance;

	public Status editingStatus;
	private boolean creatingView;
	private boolean ignoreSelectionChanges=false;
	private MenuItem publishButton;
	private boolean wasDetached;

	private BackgroundColorSpan overLimitBG;
	private ForegroundColorSpan overLimitFG;

	private Runnable emojiKeyboardHider;
	private Runnable sendingBackButtonBlocker=()->{};
	private Runnable discardConfirmationCallback=this::confirmDiscardDraftBackCallback;
	private boolean prevHadDraft;
	private boolean keyboardVisible;
	private MediaPickerConfig pendingMediaPickerConfig;

	public ComposeFragment(){
		super(R.layout.toolbar_fragment_with_progressbar);
	}

	@Override
	public void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		setRetainInstance(true);

		accountID=getArguments().getString("account");
		AccountSession session=AccountSessionManager.getInstance().getAccount(accountID);
		self=session.self;
		instanceDomain=session.domain;
		customEmojis=AccountSessionManager.getInstance().getCustomEmojis(instanceDomain);
		instance=AccountSessionManager.getInstance().getInstanceInfo(instanceDomain);
		if(getArguments().containsKey("editStatus")){
			editingStatus=Parcels.unwrap(getArguments().getParcelable("editStatus"));
		}
		if(instance==null){
			Nav.finish(this);
			return;
		}
		if(customEmojis.isEmpty()){
			AccountSessionManager.getInstance().updateInstanceInfo(instanceDomain);
		}

		if(instance.maxTootChars>0)
			charLimit=instance.maxTootChars;
		else if(instance.configuration!=null && instance.configuration.statuses!=null && instance.configuration.statuses.maxCharacters>0)
			charLimit=instance.configuration.statuses.maxCharacters;
		else
			charLimit=500;

		setTitle(editingStatus==null ? R.string.new_post : R.string.edit_post);
		if(savedInstanceState!=null){
			postLang=Parcels.unwrap(savedInstanceState.getParcelable("postLang"));
		}

		if(getArguments().containsKey("quote"))
			quotedStatus=Parcels.unwrap(getArguments().getParcelable("quote"));
		if(getArguments().containsKey("replyTo"))
			replyTo=Parcels.unwrap(getArguments().getParcelable("replyTo"));
	}

	@Override
	public void onDestroy(){
		newBabyWorldBindingRequestGeneration++;
		aiRecommendationRequestGeneration++;
		super.onDestroy();
		mediaViewController.cancelAllUploads();
		removeBackCallback(emojiKeyboardHider);
		removeBackCallback(sendingBackButtonBlocker);
		removeBackCallback(discardConfirmationCallback);
	}

	@Override
	public void onAttach(Activity activity){
		super.onAttach(activity);
		setHasOptionsMenu(true);
		wm=activity.getSystemService(WindowManager.class);

		overLimitBG=new BackgroundColorSpan(UiUtils.getThemeColor(activity, R.attr.colorM3ErrorContainer));
		overLimitFG=new ForegroundColorSpan(UiUtils.getThemeColor(activity, R.attr.colorM3Error));
	}

	@Override
	public void onDetach(){
		wasDetached=true;
		super.onDetach();
	}

	@Override
	public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState){
		creatingView=true;
		emojiKeyboard=new CustomEmojiPopupKeyboard(getActivity(), customEmojis, instanceDomain);
		emojiKeyboard.setListener(new CustomEmojiPopupKeyboard.Listener(){
			@Override
			public void onEmojiSelected(Emoji emoji){
				onCustomEmojiClick(emoji);
			}

			@Override
			public void onBackspace(){
				getActivity().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
				getActivity().dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL));
			}
		});
		emojiKeyboardHider=emojiKeyboard::hide;

		View view=inflater.inflate(R.layout.fragment_compose, container, false);
		mainLayout=view.findViewById(R.id.compose_main_ll);
		newBabyWorldCard=view.findViewById(R.id.newbabyworld_card);
		crisisCard=view.findViewById(R.id.compose_crisis_card);
		crisisWarningController=new CrisisWarningViewController(view);
		newBabyWorldCardText=view.findViewById(R.id.newbabyworld_card_text);
		newBabyWorldCardAction=view.findViewById(R.id.newbabyworld_card_action);
		newBabyWorldCardAction.setOnClickListener(v->onNewBabyWorldCardAction());
		mainEditText=view.findViewById(R.id.toot_text);
		mainEditTextWrap=view.findViewById(R.id.toot_text_wrap);
		charCounter=view.findViewById(R.id.char_counter);
		charCounter.setText(String.valueOf(charLimit));

		selfName=view.findViewById(R.id.name);
		selfUsername=view.findViewById(R.id.username);
		selfAvatar=view.findViewById(R.id.avatar);
		HtmlParser.setTextWithCustomEmoji(selfName, self.displayName, self.emojis);
		selfUsername.setText('@'+self.username+'@'+instanceDomain);
		if(self.avatar!=null)
			ViewImageLoader.load(selfAvatar, null, new UrlImageLoaderRequest(self.avatar));
		selfAvatar.setOutlineProvider(OutlineProviders.roundedRect(12));
		selfAvatar.setClipToOutline(true);
		bottomBar=view.findViewById(R.id.bottom_bar);

		mediaBtn=view.findViewById(R.id.btn_media);
		pollBtn=view.findViewById(R.id.btn_poll);
		emojiBtn=view.findViewById(R.id.btn_emoji);
		spoilerBtn=view.findViewById(R.id.btn_spoiler);
		visibilityBtn=view.findViewById(R.id.btn_visibility);
		visibilityText1=view.findViewById(R.id.visibility_text1);
		visibilityText2=view.findViewById(R.id.visibility_text2);
		visibilityCurrentText=visibilityText1;
		languageBtn=view.findViewById(R.id.btn_language);
		replyWrap=view.findViewById(R.id.reply_wrap);
		quotedPostWrap=view.findViewById(R.id.quoted_post_wrap);

		// 位置选择器初始化
		locationBtn=view.findViewById(R.id.btn_location);
		locationText=view.findViewById(R.id.location_text);
		currentLocation=LocationUtils.getCachedLocation(getActivity());
		if(replyTo==null){
			// 非回复帖才显示位置选择器
			locationBtn.setVisibility(View.VISIBLE);
			updateLocationButton();
			locationBtn.setOnClickListener(this::onLocationClick);
			// 进入发帖页时若缓存为空但有定位权限，主动触发一次解析（异步，结果回调后刷新按钮）
			if(currentLocation==null && LocationUtils.hasLocationPermission(getActivity())){
				LocationUtils.fetchAndResolve(getActivity(), loc->{
					if(loc!=null){
						currentLocation=loc;
						new android.os.Handler(android.os.Looper.getMainLooper()).post(()->updateLocationButton());
					}
				});
			}
		}

		mediaBtn.setOnClickListener(v->openLocalMediaPicker());
		if(UiUtils.isPhotoPickerAvailable()){
			mediaBtn.setOnLongClickListener(v->{
				openFilePicker(true);
				return true;
			});
		}
		pollBtn.setOnClickListener(v->togglePoll());
		emojiBtn.setOnClickListener(v->emojiKeyboard.toggleKeyboardPopup(mainEditText));
		spoilerBtn.setOnClickListener(v->toggleSpoiler());
		languageBtn.setOnClickListener(v->showLanguageAlert());
		visibilityBtn.setOnClickListener(this::onVisibilityClick);
		if(!instance.supportsQuotePostAuthoring()){
			visibilityBtn.setAccessibilityDelegate(new View.AccessibilityDelegate(){
				@Override
				public void onInitializeAccessibilityNodeInfo(@NonNull View host, @NonNull AccessibilityNodeInfo info){
					super.onInitializeAccessibilityNodeInfo(host, info);
					info.setClassName("android.widget.Spinner");
				}
			});
		}
		Drawable arrow=getResources().getDrawable(R.drawable.ic_baseline_arrow_drop_down_18, getActivity().getTheme()).mutate();
		arrow.setTint(UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnSurface));
		emojiKeyboard.setOnIconChangedListener(new PopupKeyboard.OnIconChangeListener(){
			@Override
			public void onIconChanged(int icon){
				emojiBtn.setSelected(icon!=PopupKeyboard.ICON_HIDDEN);
				updateNavigationBarColor(icon!=PopupKeyboard.ICON_HIDDEN);
				if(icon!=PopupKeyboard.ICON_HIDDEN)
					addBackCallback(emojiKeyboardHider);
				else
					removeBackCallback(emojiKeyboardHider);
				if(autocompleteViewController.getMode()==ComposeAutocompleteViewController.Mode.EMOJIS){
					contentView.layout(contentView.getLeft(), contentView.getTop(), contentView.getRight(), contentView.getBottom());
					if(icon==PopupKeyboard.ICON_HIDDEN)
						showAutocomplete();
					else
						hideAutocomplete();
				}
			}
		});

		contentView=(SizeListenerLinearLayout) view;
		contentView.addView(emojiKeyboard.getView());

		spoilerEdit=view.findViewById(R.id.content_warning);
		spoilerWrap=view.findViewById(R.id.content_warning_wrap);
		LayerDrawable spoilerBg=(LayerDrawable) spoilerWrap.getBackground().mutate();
		spoilerBg.setDrawableByLayerId(R.id.left_drawable, new SpoilerStripesDrawable(false));
		spoilerBg.setDrawableByLayerId(R.id.right_drawable, new SpoilerStripesDrawable(false));
		spoilerWrap.setBackground(spoilerBg);
		spoilerWrap.setClipToOutline(true);
		spoilerWrap.setOutlineProvider(OutlineProviders.roundedRect(8));
		if((savedInstanceState!=null && savedInstanceState.getBoolean("hasSpoiler", false)) || hasSpoiler){
			hasSpoiler=true;
			spoilerWrap.setVisibility(View.VISIBLE);
			spoilerBtn.setSelected(true);
		}else if(editingStatus!=null && !TextUtils.isEmpty(editingStatus.spoilerText)){
			hasSpoiler=true;
			spoilerWrap.setVisibility(View.VISIBLE);
			spoilerEdit.setText(getArguments().getString("sourceSpoiler", editingStatus.spoilerText));
			spoilerBtn.setSelected(true);
		}

		statusVisibility=StatusPrivacy.PUBLIC;
		updateNBWForumButton(false);
		refreshNewBabyWorldBinding();

		autocompleteViewController=new ComposeAutocompleteViewController(getActivity(), accountID);
		autocompleteViewController.setCompletionSelectedListener(new ComposeAutocompleteViewController.AutocompleteListener(){
			@Override
			public void onCompletionSelected(String completion){
				onAutocompleteOptionSelected(completion);
			}

			@Override
			public void onSetEmojiPanelOpen(boolean open){
				if(open!=emojiKeyboard.isVisible())
					emojiKeyboard.toggleKeyboardPopup(mainEditText);
			}

			@Override
			public void onLaunchAccountSearch(){
				Bundle args=new Bundle();
				args.putString("account", accountID);
				Nav.goForResult(getActivity(), AccountSearchFragment.class, args, AUTOCOMPLETE_ACCOUNT_RESULT, ComposeFragment.this);
			}
		});
		View autocompleteView=autocompleteViewController.getView();
		autocompleteView.setVisibility(View.INVISIBLE);
		bottomBar.addView(autocompleteView, 0, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, V.dp(56)));
		autocompleteDivider=view.findViewById(R.id.bottom_bar_autocomplete_divider);

		pollViewController.setView(view, savedInstanceState);
		mediaViewController.setView(view, savedInstanceState);

		NestedRecyclerScrollView outerScroller=view.findViewById(R.id.outer_scroller);
		CustomScrollView innerScroller=view.findViewById(R.id.inner_scroller);
		outerScroller.setScrollableChildSupplier(()->innerScroller);

		view.findViewById(R.id.quote_post_remove).setOnClickListener(v->{
			removeQuote();
		});

		creatingView=false;

		return view;
	}

	private void removeQuote(){
		if(quotedStatus!=null){
			quotedStatus=null;
			quotedPostWrap.setVisibility(View.GONE);
			updateMediaPollStates();
			mainEditText.setHint(R.string.compose_hint);
		}
	}

	@Override
	public void onSaveInstanceState(Bundle outState){
		super.onSaveInstanceState(outState);
		pollViewController.onSaveInstanceState(outState);
		mediaViewController.onSaveInstanceState(outState);
		outState.putBoolean("hasSpoiler", hasSpoiler);
		outState.putInt("nbwForumId", selectedNBWForumId);
		outState.putParcelable("postLang", Parcels.wrap(postLang));
		if(currentAutocompleteSpan!=null){
			Editable e=mainEditText.getText();
			outState.putInt("autocompleteStart", e.getSpanStart(currentAutocompleteSpan));
			outState.putInt("autocompleteEnd", e.getSpanEnd(currentAutocompleteSpan));
		}
	}

	@Override
	public void onResume(){
		super.onResume();
		if(!creatingView)
			refreshNewBabyWorldBinding();
		// 位置权限刚授予后回到本页：刷新成 GPS 精确定位（替代 IP 属地缓存）
		if(locationBtn!=null && locationBtn.getVisibility()==View.VISIBLE
				&& LocationUtils.hasLocationPermission(getActivity())){
			boolean needsGpsRefresh=currentLocation==null
					|| currentLocation.district()==null; // IP 属地无 district
			LocationUtils.ResolvedLocation cached=LocationUtils.getCachedLocation(getActivity());
			if(needsGpsRefresh && (cached==null || cached.district()==null)){
				LocationUtils.fetchAndResolve(getActivity(), loc->{
					if(loc!=null){
						currentLocation=loc;
						new android.os.Handler(android.os.Looper.getMainLooper()).post(()->updateLocationButton());
					}
				});
			}else if(cached!=null && (currentLocation==null || currentLocation.district()==null)){
				currentLocation=cached;
				updateLocationButton();
			}
		}
	}

	private void refreshNewBabyWorldBinding(){
		// 回复帖不涉及宝宝新天地同步，不做绑定状态检测
		if(replyTo!=null)
			return;
		final int requestGeneration=++newBabyWorldBindingRequestGeneration;
		newBabyWorldBindingState=BINDING_CHECKING;
		updateNewBabyWorldCard();
		updatePublishButtonState();
		new GetOwnAccount().setCallback(new Callback<>(){
			@Override
			public void onSuccess(Account account){
				if(getActivity()==null || requestGeneration!=newBabyWorldBindingRequestGeneration)
					return;
				AccountSessionManager.getInstance().updateAccountInfo(accountID, account);
				self=account;
				newBabyWorldBindingState=TextUtils.isEmpty(account.nbwUsername) ? BINDING_UNBOUND : BINDING_BOUND;
				getActivity().runOnUiThread(()->{
					updateNewBabyWorldCard();
					updatePublishButtonState();
				});
			}

			@Override
			public void onError(ErrorResponse error){
				if(getActivity()==null || requestGeneration!=newBabyWorldBindingRequestGeneration)
					return;
				newBabyWorldBindingState=BINDING_CHECK_FAILED;
				getActivity().runOnUiThread(()->{
					updateNewBabyWorldCard();
					updatePublishButtonState();
				});
			}
		}).exec(accountID);
	}

	private void updateNewBabyWorldCard(){
		if(newBabyWorldCard==null)
			return;
		// 回复帖不显示宝宝新天地绑定板块
		if(replyTo!=null){
			newBabyWorldCard.setVisibility(View.GONE);
			return;
		}
		if(crisisWarningController!=null && crisisWarningController.isVisible()){
			newBabyWorldCard.setVisibility(View.GONE);
			return;
		}
		if(newBabyWorldBindingState==BINDING_BOUND && dismissedNewBabyWorldCards.contains(accountID)){
			newBabyWorldCard.setVisibility(View.GONE);
			return;
		}
		newBabyWorldCard.setVisibility(View.VISIBLE);
		if(newBabyWorldBindingState==BINDING_BOUND){
			newBabyWorldCardText.setText(getString(R.string.compose_newbabyworld_bound, self.nbwUsername));
			newBabyWorldCardAction.setText(R.string.compose_newbabyworld_close);
		}else if(newBabyWorldBindingState==BINDING_UNBOUND){
			newBabyWorldCardText.setText(R.string.compose_newbabyworld_unbound);
			newBabyWorldCardAction.setText(R.string.compose_newbabyworld_bind);
		}else if(newBabyWorldBindingState==BINDING_CHECK_FAILED){
			newBabyWorldCardText.setText(R.string.compose_newbabyworld_check_failed);
			newBabyWorldCardAction.setText(R.string.compose_newbabyworld_retry);
		}else{
			newBabyWorldCardText.setText(R.string.compose_newbabyworld_checking);
			newBabyWorldCardAction.setText(R.string.compose_newbabyworld_retry);
		}
	}

	private boolean containsCrisisKeyword(String text){
		if(TextUtils.isEmpty(text))
			return false;
		String normalized=text.toLowerCase(Locale.ROOT);
		for(String keyword:CRISIS_KEYWORDS){
			if(normalized.contains(keyword.toLowerCase(Locale.ROOT)))
				return true;
		}
		return false;
	}

	private void updateCrisisWarning(CharSequence text){
		if(crisisCard==null || crisisWarningDismissed)
			return;
		boolean show=containsCrisisKeyword(text==null ? "" : text.toString());
		if(show)
			crisisWarningController.show();
		else
			crisisWarningController.hide();
		if(show)
			newBabyWorldCard.setVisibility(View.GONE);
		else
			updateNewBabyWorldCard();
	}

	private void dismissCrisisWarning(){
		crisisWarningDismissed=true;
		crisisWarningController.hide();
		updateNewBabyWorldCard();
	}

	private void onNewBabyWorldCardAction(){
		if(newBabyWorldBindingState==BINDING_BOUND){
			dismissedNewBabyWorldCards.add(accountID);
			updateNewBabyWorldCard();
		}else if(newBabyWorldBindingState==BINDING_UNBOUND){
			Intent intent=new Intent(getActivity(), NBWPostRegisterActivity.class);
			intent.putExtra("show_back", true);
			startActivity(intent);
		}else{
			refreshNewBabyWorldBinding();
		}
	}

	@Override
	protected void onHidden(){
		super.onHidden();
		if(prevHadDraft){
			prevHadDraft=false;
			removeBackCallback(discardConfirmationCallback);
		}
	}

	@Override
	protected void onShown(){
		super.onShown();
		updateDraftState();
	}

	@Override
	public void onViewCreated(View view, Bundle savedInstanceState){
		super.onViewCreated(view, savedInstanceState);
		statusVisibility=StatusPrivacy.PUBLIC;
		if(savedInstanceState!=null)
			selectedNBWForumId=savedInstanceState.getInt("nbwForumId", 0);
		updateNBWForumButton(false);
		contentView.setSizeListener(emojiKeyboard::onContentViewSizeChanged);
		InputMethodManager imm=getActivity().getSystemService(InputMethodManager.class);
		mainEditText.requestFocus();
		view.postDelayed(()->{
			imm.showSoftInput(mainEditText, 0);
		}, 100);
		sendProgress=view.findViewById(R.id.progress);
		sendProgress.setVisibility(View.GONE);

		mainEditText.setSelectionListener(this);
		mainEditText.addTextChangedListener(new TextWatcher(){
			private int lastChangeStart, lastChangeCount;

			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after){

			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count){
				if(s.length()==0)
					return;
				lastChangeStart=start;
				lastChangeCount=count;
			}

			@Override
			public void afterTextChanged(Editable s){
				updateCrisisWarning(s);
				if(s.length()==0){
					updateCharCounter();
					return;
				}
				int start=lastChangeStart;
				int count=lastChangeCount;
				// offset one char back to catch an already typed '@' or '#' or ':'
				int realStart=start;
				start=Math.max(0, start-1);
				CharSequence changedText=s.subSequence(start, realStart+count);
				String raw=changedText.toString();
				Editable editable=(Editable) s;
				// 1. find mentions, hashtags, and emoji shortcodes in any freshly inserted text, and put spans over them
				if(raw.contains("@") || raw.contains("#") || raw.contains(":")){
					Matcher matcher=AUTO_COMPLETE_PATTERN.matcher(changedText);
					while(matcher.find()){
						if(editable.getSpans(start+matcher.start(), start+matcher.end(), ComposeAutocompleteSpan.class).length>0)
							continue;
						ComposeAutocompleteSpan span;
						if(TextUtils.isEmpty(matcher.group(4))){ // not an emoji
							span=new ComposeHashtagOrMentionSpan();
						}else{
							span=new ComposeAutocompleteSpan();
						}
						editable.setSpan(span, start+matcher.start(), start+matcher.end(), Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
					}
				}
				// 2. go over existing spans in the affected range, adjust end offsets and remove no longer valid spans
				ComposeAutocompleteSpan[] spans=editable.getSpans(realStart, realStart+count, ComposeAutocompleteSpan.class);
				for(ComposeAutocompleteSpan span:spans){
					int spanStart=editable.getSpanStart(span);
					int spanEnd=editable.getSpanEnd(span);
					if(spanStart==spanEnd){ // empty, remove
						editable.removeSpan(span);
						continue;
					}
					char firstChar=editable.charAt(spanStart);
					String spanText=s.subSequence(spanStart, spanEnd).toString();
					if(firstChar=='@' || firstChar=='#' || firstChar==':'){
						Matcher matcher=AUTO_COMPLETE_PATTERN.matcher(spanText);
						char prevChar=spanStart>0 ? editable.charAt(spanStart-1) : ' ';
						if(!matcher.find() || !Character.isWhitespace(prevChar)){ // invalid mention, remove
							editable.removeSpan(span);
						}else if(matcher.end()+spanStart<spanEnd){ // mention with something at the end, move the end offset
							editable.setSpan(span, spanStart, spanStart+matcher.end(), Spanned.SPAN_EXCLUSIVE_INCLUSIVE);
						}
					}else{
						editable.removeSpan(span);
					}
				}

				updateCharCounter();
				updateDraftState();
			}
		});
		updateCrisisWarning(mainEditText.getText());
		spoilerEdit.addTextChangedListener(new SimpleTextWatcher(e->updateCharCounter()));
		if(replyTo!=null){
			InlineStatusStatusDisplayItem item=new InlineStatusStatusDisplayItem("reply", new StatusDisplayItem.NoOpCallbacks(getActivity()), getActivity(), replyTo, accountID, R.drawable.ic_reply_wght700_20px, getString(R.string.in_reply_to, replyTo.account.displayName));
			item.fullWidth=true;
			InlineStatusStatusDisplayItem.Holder holder=new InlineStatusStatusDisplayItem.Holder(getActivity(), replyWrap);
			replyWrap.addView(holder.itemView);
			holder.bind(item);
			int imgCount=item.getImageCount();
			for(int i=0;i<imgCount;i++){
				ViewImageLoader.load(new ViewImageLoaderHolderTarget(holder, i), null, item.getImageRequest(i), false, true);
			}
//			mainLayout.setTopBarsCount(1);

			ArrayList<String> mentions=new ArrayList<>();
			String ownID=AccountSessionManager.getInstance().getAccount(accountID).self.id;
			if(!replyTo.account.id.equals(ownID))
				mentions.add('@'+replyTo.account.acct);
			for(Mention mention:replyTo.mentions){
				if(mention.id.equals(ownID))
					continue;
				String m='@'+mention.acct;
				if(!mentions.contains(m))
					mentions.add(m);
			}
			initialText=mentions.isEmpty() ? "" : TextUtils.join(" ", mentions)+" ";
			if(savedInstanceState==null){
				mainEditText.setText(initialText);
				ignoreSelectionChanges=true;
				mainEditText.setSelection(mainEditText.length());
				ignoreSelectionChanges=false;
				if(!TextUtils.isEmpty(replyTo.spoilerText) && AccountSessionManager.getInstance().isSelf(accountID, replyTo.account)){
					hasSpoiler=true;
					spoilerWrap.setVisibility(View.VISIBLE);
					spoilerEdit.setText(replyTo.spoilerText);
					spoilerBtn.setSelected(true);
				}
			}
		}else{
			replyWrap.setVisibility(View.GONE);
		}

		if(quotedStatus!=null){
			quotedPostWrap.setVisibility(View.VISIBLE);
			StatusView sv=new StatusView(getActivity(), this);
			sv.addItems(StatusDisplayItem.buildItems(null, getActivity(), quotedStatus, accountID, quotedStatus, Map.of(), StatusDisplayItem.FLAG_NO_FOOTER | StatusDisplayItem.FLAG_IS_QUOTE | StatusDisplayItem.FLAG_FULL_WIDTH));
			LinearLayout.LayoutParams headerLP=(LinearLayout.LayoutParams) sv.getChildAt(0).getLayoutParams();
			headerLP.setMarginEnd(V.dp(48-16));
			headerLP.setMarginStart(V.dp(-4));
			quotedPostWrap.addView(sv);
			mainEditText.setHint(R.string.compose_hint_quote);
			if(savedInstanceState==null && quotedStatus.visibility==StatusPrivacy.UNLISTED && !GlobalUserPreferences.alertSeen("quoteUnlisted")){
				view.postDelayed(()->{
					new M3AlertDialogBuilder(getActivity())
							.setTitle(R.string.unlisted_quote_alert_title)
							.setMessage(R.string.unlisted_quote_alert)
							.setPositiveButton(R.string.dismiss, (dlg, which)->{})
							.setNegativeButton(R.string.dont_remind_again, (dlg, which)->GlobalUserPreferences.setAlertSeen("quoteUnlisted"))
							.show();
				}, 200);
			}
		}

		if(savedInstanceState==null){
			if(editingStatus!=null){
				initialText=getArguments().getString("sourceText", "");
				mainEditText.setText(initialText);
				ignoreSelectionChanges=true;
				mainEditText.setSelection(mainEditText.length());
				ignoreSelectionChanges=false;
				mediaViewController.onViewCreated(savedInstanceState);
			}else{
				String prefilledText=getArguments().getString("prefilledText");
				if(!TextUtils.isEmpty(prefilledText)){
					mainEditText.setText(prefilledText);
					ignoreSelectionChanges=true;
					mainEditText.setSelection(mainEditText.length());
					ignoreSelectionChanges=false;
					initialText=prefilledText;
				}
				ArrayList<Uri> mediaUris=getArguments().getParcelableArrayList("mediaAttachments");
				if(mediaUris!=null && !mediaUris.isEmpty()){
					for(Uri uri:mediaUris){
						mediaViewController.addMediaAttachment(uri, null);
					}
				}
			}
		}

		if(editingStatus!=null){
			updateCharCounter();
			visibilityBtn.setEnabled(false);
			visibilityBtn.setAlpha(0.5f);
		}
		updateMediaPollStates();
	}

	@Override
	public void onViewStateRestored(Bundle savedInstanceState){
		super.onViewStateRestored(savedInstanceState);
		if(savedInstanceState!=null && savedInstanceState.containsKey("autocompleteStart")){
			int start=savedInstanceState.getInt("autocompleteStart"), end=savedInstanceState.getInt("autocompleteEnd");
			currentAutocompleteSpan=new ComposeAutocompleteSpan();
			mainEditText.getText().setSpan(currentAutocompleteSpan, start, end, Editable.SPAN_EXCLUSIVE_INCLUSIVE);
			startAutocomplete(currentAutocompleteSpan);
		}
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater){
		inflater.inflate(editingStatus==null ? R.menu.compose : R.menu.compose_edit, menu);
		publishButton=menu.findItem(R.id.publish);
		updatePublishButtonState();
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item){
		if(item.getItemId()==R.id.publish){
			if(newBabyWorldBindingState!=BINDING_BOUND){
				refreshNewBabyWorldBinding();
				return true;
			}
			if(GlobalUserPreferences.altTextReminders && editingStatus==null)
				checkAltTextsAndPublish();
			else
				publish();
		}
		return true;
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig){
		super.onConfigurationChanged(newConfig);
		emojiKeyboard.onConfigurationChanged();
	}

	@SuppressLint("NewApi")
	private void updateCharCounter(){
		Editable text=mainEditText.getText();

		String countableText=TwitterTextEmojiRegex.VALID_EMOJI_PATTERN.matcher(
				MENTION_PATTERN.matcher(
						HtmlParser.URL_PATTERN.matcher(text).replaceAll("$2xxxxxxxxxxxxxxxxxxxxxxx")
				).replaceAll("$1@$3")
		).replaceAll("x");
		charCount=0;
		breakIterator.setText(countableText);
		while(breakIterator.next()!=BreakIterator.DONE){
			charCount++;
		}

		if(hasSpoiler){
			charCount+=spoilerEdit.length();
		}
		charCounter.setText(String.valueOf(charLimit-charCount));

		text.removeSpan(overLimitBG);
		text.removeSpan(overLimitFG);
		if(charCount>charLimit){
			charCounter.setTextColor(UiUtils.getThemeColor(getActivity(), R.attr.colorM3Error));
			int start=text.length()-(charCount-charLimit);
			int end=text.length();
			text.setSpan(overLimitFG, start, end, 0);
			text.setSpan(overLimitBG, start, end, 0);
		}else{
			charCounter.setTextColor(UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnSurface));
		}

		trimmedCharCount=text.toString().trim().length();
		updatePublishButtonState();
	}

	public void updatePublishButtonState(){
		uuid=null;
		if(publishButton==null)
			return;
		publishButton.setEnabled(newBabyWorldBindingState==BINDING_BOUND && !aiRecommendationInFlight && (trimmedCharCount>0 || !mediaViewController.isEmpty()) && charCount<=charLimit && mediaViewController.getNonDoneAttachmentCount()==0 && (pollViewController.isEmpty() || pollViewController.getNonEmptyOptionsCount()>1));
		updateDraftState();
	}

	private void onCustomEmojiClick(Emoji emoji){
		if(getActivity().getCurrentFocus() instanceof EditText edit){
			if(edit==mainEditText && currentAutocompleteSpan!=null && autocompleteViewController.getMode()==ComposeAutocompleteViewController.Mode.EMOJIS){
				Editable text=mainEditText.getText();
				int start=text.getSpanStart(currentAutocompleteSpan);
				int end=text.getSpanEnd(currentAutocompleteSpan);
				finishAutocomplete();
				text.replace(start, end, ':'+emoji.shortcode+':');
				return;
			}
			int start=edit.getSelectionStart();
			String prefix=start>0 && !Character.isWhitespace(edit.getText().charAt(start-1)) ? " :" : ":";
			edit.getText().replace(start, edit.getSelectionEnd(), prefix+emoji.shortcode+':');
		}
	}

	@Override
	protected void updateToolbar(){
		super.updateToolbar();
		int color=UiUtils.alphaBlendThemeColors(getActivity(), R.attr.colorM3Background, R.attr.colorM3Primary, 0.11f);
		getToolbar().setBackgroundColor(color);
		setStatusBarColor(color);
		bottomBar.setBackgroundColor(color);
		updateNavigationBarColor(emojiKeyboard.isVisible());
	}

	private void updateNavigationBarColor(boolean emojiKeyboardVisible){
		int color=UiUtils.alphaBlendThemeColors(getActivity(), R.attr.colorM3Background, R.attr.colorM3Primary, emojiKeyboardVisible ? 0.08f : 0.11f);
		setNavigationBarColor(color);
	}

	@Override
	protected int getNavigationIconDrawableResource(){
		return R.drawable.ic_baseline_close_24;
	}

	@Override
	public boolean wantsCustomNavigationIcon(){
		return true;
	}

	private void checkAltTextsAndPublish(){
		int count=mediaViewController.getMissingAltTextAttachmentCount();
		if(count==0){
			publish();
		}else{
			String msg=getResources().getQuantityString(mediaViewController.areAllAttachmentsImages() ? R.plurals.alt_text_reminder_x_images : R.plurals.alt_text_reminder_x_attachments,
					count, switch(count){
						case 1 -> getString(R.string.count_one);
						case 2 -> getString(R.string.count_two);
						case 3 -> getString(R.string.count_three);
						case 4 -> getString(R.string.count_four);
						default -> String.valueOf(count);
					});
			new M3AlertDialogBuilder(getActivity())
					.setTitle(R.string.alt_text_reminder_title)
					.setMessage(msg)
					.setPositiveButton(R.string.alt_text_reminder_post_anyway, (dlg, item)->publish())
					.setNegativeButton(R.string.cancel, null)
					.show();
		}
	}

	private void publish(){
		// 回复帖禁用宝宝新天地同步：不检测绑定、不调用 AI 推荐板块
		if(replyTo!=null){
			publishResolved();
			return;
		}
		// 禁止同步宝宝新天地时跳过绑定检查
		if(selectedNBWForumId!=-1 && newBabyWorldBindingState!=BINDING_BOUND){
			refreshNewBabyWorldBinding();
			return;
		}
		if(selectedNBWForumId==-1){
			// 禁止同步宝宝新天地
			resolvedNBWForumId=-1;
			publishResolved();
			return;
		}
		if(selectedNBWForumId!=0){
			resolvedNBWForumId=selectedNBWForumId;
			publishResolved();
			return;
		}
		if(aiRecommendationInFlight)
			return;
		aiRecommendationInFlight=true;
		updatePublishButtonState();
		final int requestGeneration=++aiRecommendationRequestGeneration;
		String content=mainEditText.getText().toString().trim();
		new RecommendNBWForum(content).setCallback(new Callback<>(){
			@Override
			public void onSuccess(RecommendNBWForum.Response result){
				if(getActivity()==null || requestGeneration!=aiRecommendationRequestGeneration)
					return;
				aiRecommendationInFlight=false;
				updatePublishButtonState();
				if(result.fallback || !isValidNBWForumId(result.fid)){
					confirmDefaultNBWForumPublish();
					return;
				}
				resolvedNBWForumId=result.fid;
				aiRecommendedForumName=result.forumName;
				selectedNBWForumId=0;
				updateNBWForumButton(true);
				publishResolved();
			}

			@Override
			public void onError(ErrorResponse error){
				if(getActivity()==null || requestGeneration!=aiRecommendationRequestGeneration)
					return;
				aiRecommendationInFlight=false;
				updatePublishButtonState();
				confirmDefaultNBWForumPublish();
			}
		}).exec(accountID);
	}

	private void confirmDefaultNBWForumPublish(){
		new M3AlertDialogBuilder(getActivity())
				.setTitle(R.string.compose_ai_recommend_failed_title)
				.setMessage(R.string.compose_ai_recommend_failed_message)
				.setPositiveButton(R.string.compose_ai_recommend_publish_default, (dialog, which)->{
					selectedNBWForumId=27;
					resolvedNBWForumId=27;
					aiRecommendedForumName=null;
					updateNBWForumButton(true);
					publishResolved();
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private boolean isValidNBWForumId(int fid){
		return fid==28 || fid==27 || fid==26 || fid==3;
	}

	private void publishResolved(){
		if(getActivity()==null)
			return;
		// 禁止同步宝宝新天地时跳过绑定检查（回复帖已禁用全部宝宝新天地功能）
		if(replyTo==null && resolvedNBWForumId!=-1 && newBabyWorldBindingState!=BINDING_BOUND){
			refreshNewBabyWorldBinding();
			return;
		}
		Runnable publishAction=()->{
			sendingOverlay=new View(getActivity());
			WindowManager.LayoutParams overlayParams=new WindowManager.LayoutParams();
			overlayParams.type=WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
			overlayParams.flags=WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
			overlayParams.width=overlayParams.height=WindowManager.LayoutParams.MATCH_PARENT;
			overlayParams.format=PixelFormat.TRANSLUCENT;
			overlayParams.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED;
			overlayParams.token=mainEditText.getWindowToken();
			wm.addView(sendingOverlay, overlayParams);
			addBackCallback(sendingBackButtonBlocker);

			publishButton.setEnabled(false);
			V.setVisibilityAnimated(sendProgress, View.VISIBLE);

			mediaViewController.saveAltTextsBeforePublishing(this::actuallyPublish, this::handlePublishError);
		};
		if(quotedStatus!=null && !AccountSessionManager.getInstance().isSelf(accountID, quotedStatus.account) && statusVisibility==StatusPrivacy.PRIVATE && !GlobalUserPreferences.alertSeen("quoteFollowersOnly")){
			new M3AlertDialogBuilder(getActivity())
					.setCheckboxText(R.string.quote_followers_only_dont_show_again)
					.setTitle(R.string.quote_followers_only_confirm_title)
					.setMessage(R.string.quote_followers_only_confirm)
					.setPositiveButton(R.string.quote_followers_only_publish, (dlg, which)->{
						CheckBox cb=((AlertDialog)dlg).findViewById(R.id.checkbox);
						if(cb!=null && cb.isChecked())
							GlobalUserPreferences.setAlertSeen("quoteFollowersOnly");
						publishAction.run();
					})
					.setNegativeButton(R.string.quote_followers_only_cancel, null)
					.show();
		}else{
			publishAction.run();
		}
	}

	private void actuallyPublish(){
		String text=mainEditText.getText().toString();
		CreateStatus.Request req=new CreateStatus.Request();
		req.status=text;
		req.mentalCrisis=containsCrisisKeyword(text);
		req.visibility=StatusPrivacy.PUBLIC;
		// 回复帖不同步宝宝新天地，不携带版块字段
		req.nbwFid=(replyTo!=null || resolvedNBWForumId==-1) ? null : resolvedNBWForumId;
		// 位置信息（回复帖不携带）
		if(replyTo==null){
			LocationUtils.ResolvedLocation geo=getGeoForPost();
			if(geo!=null){
				req.geoProvince=geo.province();
				req.geoCity=geo.city();
				req.geoDistrict=geo.district();
			}
		}
		if(!mediaViewController.isEmpty()){
			req.mediaIds=mediaViewController.getAttachmentIDs();
			req.mediaAttributes=mediaViewController.getAttachmentAttributes();
		}
		if(replyTo!=null){
			req.inReplyToId=replyTo.id;
		}
		if(!pollViewController.isEmpty()){
			req.poll=pollViewController.getPollForRequest();
		}
		if(hasSpoiler){
			if(spoilerEdit.length()>0)
				req.spoilerText=spoilerEdit.getText().toString();
			else
				req.sensitive=true;
		}

		// NSFW 检测：如果任何附件检测到敏感内容，自动启用 sensitive
		if(!req.sensitive && mediaViewController.hasNsfwAttachments()){
			req.sensitive=true;
			if(!hasSpoiler && spoilerEdit.length()==0){
				spoilerEdit.setText("包含敏感图片");
				hasSpoiler=true;
				spoilerEdit.setVisibility(View.VISIBLE);
			}
		}
		if(postLang!=null){
			req.language=postLang.locale.toLanguageTag();
		}
		if(statusQuotePolicy!=null && instance.supportsQuotePostAuthoring())
			req.quoteApprovalPolicy=statusQuotePolicy;
		if(quotedStatus!=null)
			req.quotedStatusId=quotedStatus.id;
		if(uuid==null)
			uuid=UUID.randomUUID().toString();

		Callback<Status> resCallback=new Callback<>(){
			@Override
			public void onSuccess(Status result){
				wm.removeView(sendingOverlay);
				sendingOverlay=null;
				removeBackCallback(sendingBackButtonBlocker);
				removeBackCallback(discardConfirmationCallback);
				removeBackCallback(emojiKeyboardHider);
				if(editingStatus==null){
					E.post(new StatusCreatedEvent(result, accountID));
					if(replyTo!=null){
						replyTo.repliesCount++;
						E.post(new StatusCountersUpdatedEvent(replyTo, StatusCountersUpdatedEvent.CounterType.REPLIES));
					}
				}else{
					E.post(new StatusUpdatedEvent(result));
				}
				Nav.finish(ComposeFragment.this);
			}

			@Override
			public void onError(ErrorResponse error){
				handlePublishError(error);
			}
		};

		if(editingStatus!=null){
			new EditStatus(req, editingStatus.id)
					.setCallback(resCallback)
					.exec(accountID);
		}else{
			new CreateStatus(req, uuid)
					.setCallback(resCallback)
					.exec(accountID);
		}
	}

	private void handlePublishError(ErrorResponse error){
		if(sendingOverlay.isAttachedToWindow())
			wm.removeView(sendingOverlay);
		sendingOverlay=null;
		removeBackCallback(sendingBackButtonBlocker);
		V.setVisibilityAnimated(sendProgress, View.GONE);
		publishButton.setEnabled(true);
		if(error instanceof MastodonErrorResponse me){
			String message=me.error;
			String mediaDebugInfo=mediaViewController.getUploadedAttachmentDebugInfo();
			if(!TextUtils.isEmpty(mediaDebugInfo))
				message+="\n\n上传图片返回 URL：\n"+mediaDebugInfo;
			new M3AlertDialogBuilder(getActivity())
					.setTitle(R.string.post_failed)
					.setMessage(message)
					.setPositiveButton(R.string.retry, (dlg, btn)->publish())
					.setNegativeButton(R.string.cancel, null)
					.show();
		}else{
			error.showToast(getActivity());
		}
	}

	private boolean hasDraft(){
		if(editingStatus!=null){
			if(!mainEditText.getText().toString().equals(initialText))
				return true;
			List<String> existingMediaIDs=editingStatus.mediaAttachments.stream().map(a->a.id).collect(Collectors.toList());
			if(!existingMediaIDs.equals(mediaViewController.getAttachmentIDs()))
				return true;
			return pollViewController.isPollChanged();
		}
		boolean pollFieldsHaveContent=pollViewController.getNonEmptyOptionsCount()>0;
		return (mainEditText.length()>0 && !mainEditText.getText().toString().equals(initialText)) || !mediaViewController.isEmpty() || pollFieldsHaveContent;
	}

	private void updateDraftState(){
		boolean hasDraft=hasDraft();
		if(hasDraft!=prevHadDraft){
			prevHadDraft=hasDraft;
			if(hasDraft){
				addBackCallback(discardConfirmationCallback);
			}else{
				removeBackCallback(discardConfirmationCallback);
			}
		}
	}

	@Override
	public void onToolbarNavigationClick(){
		if(hasDraft()){
			confirmDiscardDraftAndFinish();
		}else{
			super.onToolbarNavigationClick();
		}
	}

	@Override
	public void onFragmentResult(int reqCode, boolean success, Bundle result){
		if(reqCode==IMAGE_DESCRIPTION_RESULT && success){
			String attID=result.getString("attachment");
			String text=result.getString("text");
			mediaViewController.setAltTextByID(attID, text);
		}else if(reqCode==AUTOCOMPLETE_ACCOUNT_RESULT && success){
			Account acc=Parcels.unwrap(result.getParcelable("selectedAccount"));
			if(currentAutocompleteSpan==null)
				return;
			Editable e=mainEditText.getText();
			int start=e.getSpanStart(currentAutocompleteSpan);
			int end=e.getSpanEnd(currentAutocompleteSpan);
			e.removeSpan(currentAutocompleteSpan);
			e.replace(start, end, '@'+acc.acct+' ');
			finishAutocomplete();
		}
	}

	@Override
	public void onApplyWindowInsets(WindowInsets insets){
		if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.R){
			Insets imeInsets=insets.getInsets(WindowInsets.Type.ime());
			keyboardVisible=imeInsets.left>0 || imeInsets.top>0 || imeInsets.right>0 || imeInsets.bottom>0;
		}
		super.onApplyWindowInsets(insets);
	}

	private void confirmDiscardDraftBackCallback(){
		// If the back callback was set with the keyboard active, it will be invoked first.
		// So, dismiss the keyboard first, because of course that's what the user expects.
		if(keyboardVisible){
			getActivity().getSystemService(InputMethodManager.class).hideSoftInputFromWindow(contentView.getWindowToken(), 0);
			return;
		}
		confirmDiscardDraftAndFinish();
	}

	private void confirmDiscardDraftAndFinish(){
		new M3AlertDialogBuilder(getActivity())
				.setTitle(editingStatus==null ? R.string.discard_draft : R.string.discard_changes)
				.setPositiveButton(R.string.discard, (dialog, which)->{
					removeBackCallback(discardConfirmationCallback);
					Nav.finish(this);
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}


	/**
	 * Builds the correct intent for the device version to select media.
	 *
	 * <p>For Device version > T or R_SDK_v2, use the android platform photopicker via
	 * {@link MediaStore#ACTION_PICK_IMAGES}
	 *
	 * <p>For earlier versions use the built in docs ui via {@link Intent#ACTION_GET_CONTENT}
	 */
	private void openFilePicker(boolean forceGetContent){
		Intent intent;
		boolean usePhotoPicker=!forceGetContent && UiUtils.isPhotoPickerAvailable();
		if(usePhotoPicker){
			intent=new Intent(MediaStore.ACTION_PICK_IMAGES);
			if(mediaViewController.getMaxAttachments()-mediaViewController.getMediaAttachmentsCount()>1)
				intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, mediaViewController.getMaxAttachments()-mediaViewController.getMediaAttachmentsCount());
		}else{
			intent=new Intent(Intent.ACTION_GET_CONTENT);
			intent.addCategory(Intent.CATEGORY_OPENABLE);
			intent.setType("*/*");
		}
		if(!usePhotoPicker && instance.configuration!=null &&
				instance.configuration.mediaAttachments!=null &&
				instance.configuration.mediaAttachments.supportedMimeTypes!=null &&
				!instance.configuration.mediaAttachments.supportedMimeTypes.isEmpty()){
			intent.putExtra(Intent.EXTRA_MIME_TYPES,
					instance.configuration.mediaAttachments.supportedMimeTypes.toArray(
							new String[0]));
		}else{
			if(!usePhotoPicker){
				// If photo picker is being used these are the default mimetypes.
				intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
			}
		}
		intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
		startActivityForResult(intent, MEDIA_RESULT);
	}

	private void openLocalMediaPicker(){
		MediaPickerConfig config=new MediaPickerConfig();
		config.maxCount=mediaViewController.getMaxAttachments()-mediaViewController.getMediaAttachmentsCount();
		ArrayList<String> missing=new ArrayList<>();
		if(Build.VERSION.SDK_INT>=33){
			if(config.allowImages && getActivity().checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)!=PackageManager.PERMISSION_GRANTED)
				missing.add(Manifest.permission.READ_MEDIA_IMAGES);
			if(config.allowVideos && getActivity().checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)!=PackageManager.PERMISSION_GRANTED)
				missing.add(Manifest.permission.READ_MEDIA_VIDEO);
		}else if(!new MediaStoreLoader(getActivity()).hasPermission(config)){
			missing.add(Manifest.permission.READ_EXTERNAL_STORAGE);
		}
		if(getActivity().checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
			missing.add(Manifest.permission.CAMERA);
		if(!missing.isEmpty()){
			pendingMediaPickerConfig=config;
			requestPermissions(missing.toArray(new String[0]), MEDIA_PERMISSION_RESULT);
			return;
		}
		showLocalMediaPicker(config);
	}

	private void showLocalMediaPicker(MediaPickerConfig config){
		new MediaPickerSheet(getActivity(), config, new MediaPickerSheet.Listener(){
			@Override public void onMediaSelected(ArrayList<Uri> uris){
				for(Uri uri:uris)
					mediaViewController.addMediaAttachment(uri, null);
			}
			@Override public void onCameraRequested(){ openCameraForAttachment(); }
		}).show();
	}

	private void openCameraForAttachment(){
		startActivityForResult(MediaCameraContract.createIntent(getActivity(), true), CAMERA_CAPTURE_RESULT);
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults){
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if(requestCode==MEDIA_PERMISSION_RESULT){
			MediaPickerConfig config=pendingMediaPickerConfig;
			pendingMediaPickerConfig=null;
			if(config!=null && new MediaStoreLoader(getActivity()).hasPermission(config))
				showLocalMediaPicker(config);
		}
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data){
		if(requestCode==CAMERA_CAPTURE_RESULT && resultCode==Activity.RESULT_OK && MediaCameraContract.getUri(data)!=null){
			mediaViewController.addMediaAttachment(MediaCameraContract.getUri(data), null);
		}else if(requestCode==MEDIA_RESULT && resultCode==Activity.RESULT_OK){
			Uri single=data.getData();
			if(single!=null){
				mediaViewController.addMediaAttachment(single, null);
			}else{
				ClipData clipData=data.getClipData();
				for(int i=0;i<clipData.getItemCount();i++){
					mediaViewController.addMediaAttachment(clipData.getItemAt(i).getUri(), null);
				}
			}
		}
	}


	public void updateMediaPollStates(){
		pollBtn.setSelected(pollViewController.isShown());
		mediaBtn.setEnabled(!pollViewController.isShown() && mediaViewController.canAddMoreAttachments() && quotedStatus==null);
		pollBtn.setEnabled(mediaViewController.isEmpty() && quotedStatus==null);
	}

	private void togglePoll(){
		pollViewController.toggle();
		updatePublishButtonState();
		updateMediaPollStates();
	}

	private void toggleSpoiler(){
		hasSpoiler=!hasSpoiler;
		if(hasSpoiler){
			spoilerWrap.setVisibility(View.VISIBLE);
			spoilerBtn.setSelected(true);
			spoilerEdit.requestFocus();
		}else{
			spoilerWrap.setVisibility(View.GONE);
			spoilerEdit.setText("");
			spoilerBtn.setSelected(false);
			mainEditText.requestFocus();
			updateCharCounter();
		}
	}

	private void updateLocationButton(){
		if(locationText==null) return;
		String province=currentLocation!=null ? currentLocation.province() : getString(R.string.location_unknown);
		switch(selectedLocationLevel){
			case LOCATION_PROVINCE -> locationText.setText(getString(R.string.location_province, province));
			case LOCATION_CITY -> {
				String city=currentLocation!=null ? currentLocation.city() : null;
				locationText.setText(getString(R.string.location_city, city!=null ? city : getString(R.string.location_unknown)));
			}
			case LOCATION_DISTRICT -> {
				String district=currentLocation!=null ? currentLocation.district() : null;
				locationText.setText(getString(R.string.location_district, district!=null ? district : getString(R.string.location_unknown)));
			}
			case LOCATION_NONE -> locationText.setText(R.string.location_none);
		}
	}

	private void onLocationClick(View v){
		ArrayList<ListItem<Integer>> items=new ArrayList<>();
		ExtendedPopupMenu menu=new ExtendedPopupMenu(getActivity(), items);
		String province=currentLocation!=null ? currentLocation.province() : getString(R.string.location_unknown);
		String city=(currentLocation!=null && currentLocation.city()!=null) ? currentLocation.city() : getString(R.string.location_unknown);
		String district=(currentLocation!=null && currentLocation.district()!=null) ? currentLocation.district() : getString(R.string.location_unknown);
		boolean hasPermission=LocationUtils.hasLocationPermission(getActivity());
		Consumer<ListItem<Integer>> onClick=item->{
			selectedLocationLevel=item.parentObject;
			menu.dismiss();
			// 点击市/区但没有定位权限 → 弹出位置权限引导（同首次登录），用户同意后申请系统权限
			if(selectedLocationLevel!=LOCATION_NONE && selectedLocationLevel!=LOCATION_PROVINCE && !hasPermission){
				selectedLocationLevel=LOCATION_PROVINCE;
				updateLocationButton();
				new LocationPermissionSheet(getActivity(), getActivity(), null).show();
				return;
			}
			updateLocationButton();
			// 选择非省时如果没有位置数据，尝试获取
			if(selectedLocationLevel!=LOCATION_NONE && selectedLocationLevel!=LOCATION_PROVINCE && currentLocation==null){
				LocationUtils.fetchAndResolve(getActivity(), loc->{
					if(loc!=null){
						currentLocation=loc;
						updateLocationButton();
					}
				});
			}
		};
		// 用 String 构造器避免 %s 未格式化
		items.add(new ListItem<>(getString(R.string.location_province, province), getString(R.string.location_province_desc), R.drawable.ic_fluent_location_12_filled, onClick, LOCATION_PROVINCE));
		ListItem<Integer> cityItem=new ListItem<>(getString(R.string.location_city, city), hasPermission ? getString(R.string.location_city_desc) : getString(R.string.location_disabled_no_permission), R.drawable.ic_fluent_location_12_filled, onClick, LOCATION_CITY);
		cityItem.isEnabled=hasPermission || (currentLocation!=null && currentLocation.city()!=null);
		items.add(cityItem);
		ListItem<Integer> districtItem=new ListItem<>(getString(R.string.location_district, district), hasPermission ? getString(R.string.location_district_desc) : getString(R.string.location_disabled_no_permission), R.drawable.ic_fluent_location_12_filled, onClick, LOCATION_DISTRICT);
		districtItem.isEnabled=hasPermission || (currentLocation!=null && currentLocation.district()!=null);
		items.add(districtItem);
		items.add(new ListItem<>(getString(R.string.location_none), getString(R.string.location_none_desc), R.drawable.ic_fluent_location_12_regular, onClick, LOCATION_NONE));
		menu.showAsDropDown(v);
	}

	/** 获取发帖时要发送的位置数据（根据用户选择级别） */
	private LocationUtils.ResolvedLocation getGeoForPost(){
		if(selectedLocationLevel==LOCATION_NONE) return null;
		if(currentLocation==null) return null;
		return switch(selectedLocationLevel){
			case LOCATION_PROVINCE -> new LocationUtils.ResolvedLocation(currentLocation.province(), null, null);
			case LOCATION_CITY -> new LocationUtils.ResolvedLocation(currentLocation.province(), currentLocation.city(), null);
			case LOCATION_DISTRICT -> currentLocation;
			default -> null;
		};
	}

	private void onVisibilityClick(View v){
		ArrayList<ListItem<Integer>> items=new ArrayList<>();
		ExtendedPopupMenu menu=new ExtendedPopupMenu(getActivity(), items);
		Consumer<ListItem<Integer>> onClick=item->{
			selectedNBWForumId=item.parentObject;
			aiRecommendedForumName=null;
			updateNBWForumButton(true);
			menu.dismiss();
		};
		items.add(new ListItem<>(R.string.nbw_forum_ai, R.string.nbw_forum_ai_subtitle, R.drawable.ic_fluent_wand_24_regular, 0, onClick));
		items.add(new ListItem<>(R.string.nbw_forum_selfie, 0, R.drawable.ic_fluent_camera_24_regular, 28, onClick));
		items.add(new ListItem<>(R.string.nbw_forum_share, 0, R.drawable.ic_fluent_share_24_regular, 27, onClick));
		items.add(new ListItem<>(R.string.nbw_forum_novel, 0, R.drawable.ic_fluent_book_24_regular, 26, onClick));
		items.add(new ListItem<>(R.string.nbw_forum_friends, 0, R.drawable.ic_fluent_group_24_regular, 3, onClick));
		items.add(new ListItem<>(R.string.nbw_forum_none, R.string.nbw_forum_none_subtitle, R.drawable.ic_nbw, -1, onClick));
		menu.showAsDropDown(v);
	}

	private void updateNBWForumButton(boolean animated){
		if(getActivity()==null)
			return;
		// 回复帖不显示宝宝新天地板块选择器
		if(replyTo!=null){
			visibilityBtn.setVisibility(View.GONE);
			return;
		}
		visibilityBtn.setVisibility(View.VISIBLE);
		TextView visibilityText;
		if(!animated){
			visibilityText=visibilityCurrentText;
		}else{
			TransitionManager.beginDelayedTransition(visibilityBtn, new TransitionSet()
					.addTransition(new Fade(Fade.IN | Fade.OUT))
					.addTransition(new ChangeBounds().excludeTarget(TextView.class, true))
					.setDuration(250)
					.setInterpolator(CubicBezierInterpolator.DEFAULT)
			);
			visibilityText=visibilityCurrentText==visibilityText1 ? visibilityText2 : visibilityText1;
			visibilityText.setVisibility(View.VISIBLE);
			visibilityCurrentText.setVisibility(View.GONE);
			visibilityCurrentText=visibilityText;
		}
		if(selectedNBWForumId==0 && !TextUtils.isEmpty(aiRecommendedForumName))
			visibilityText.setText(getString(R.string.compose_ai_recommend_result, aiRecommendedForumName));
		else
			visibilityText.setText(switch(selectedNBWForumId){
				case 28 -> R.string.nbw_forum_selfie;
				case 27 -> R.string.nbw_forum_share;
				case 26 -> R.string.nbw_forum_novel;
				case 3 -> R.string.nbw_forum_friends;
				case -1 -> R.string.nbw_forum_none;
				default -> R.string.nbw_forum_ai;
			});
		Drawable icon=getResources().getDrawable(switch(selectedNBWForumId){
			case 28 -> R.drawable.ic_fluent_camera_24_regular;
			case 27 -> R.drawable.ic_fluent_share_24_regular;
			case 26 -> R.drawable.ic_fluent_book_24_regular;
			case 3 -> R.drawable.ic_fluent_group_24_regular;
			case -1 -> R.drawable.ic_nbw;
			default -> R.drawable.ic_fluent_wand_24_regular;
		}, getActivity().getTheme()).mutate();
		icon.setBounds(0, 0, V.dp(18), V.dp(18));
		icon.setTint(UiUtils.getThemeColor(getActivity(), R.attr.colorM3OnSurfaceVariant));
		visibilityText.setCompoundDrawablesRelative(icon, null, null, null);
	}

	@Override
	public void onSelectionChanged(int start, int end){
		if(ignoreSelectionChanges)
			return;
		if(start==end && mainEditText.length()>0){
			ComposeAutocompleteSpan[] spans=mainEditText.getText().getSpans(start, end, ComposeAutocompleteSpan.class);
			if(spans.length>0){
				assert spans.length==1;
				ComposeAutocompleteSpan span=spans[0];
				if(currentAutocompleteSpan==null && end==mainEditText.getText().getSpanEnd(span)){
					startAutocomplete(span);
				}else if(currentAutocompleteSpan!=null){
					Editable e=mainEditText.getText();
					String spanText=e.toString().substring(e.getSpanStart(span), e.getSpanEnd(span));
					autocompleteViewController.setText(spanText);
				}
			}else if(currentAutocompleteSpan!=null){
				finishAutocomplete();
			}
		}else if(currentAutocompleteSpan!=null){
			finishAutocomplete();
		}
	}

	@Override
	public String[] onGetAllowedMediaMimeTypes(){
		if(instance!=null && instance.configuration!=null && instance.configuration.mediaAttachments!=null && instance.configuration.mediaAttachments.supportedMimeTypes!=null)
			return instance.configuration.mediaAttachments.supportedMimeTypes.toArray(new String[0]);
		return new String[]{"image/jpeg", "image/gif", "image/png", "video/mp4"};
	}

	private String sanitizeMediaDescription(String description){
		if(description == null){
			return null;
		}

		// The Gboard android keyboard attaches this text whenever the user
		// pastes something from the keyboard's suggestion bar.
		// Due to different end user locales, the exact text may vary, but at
		// least in version 13.4.08, all of the translations contained the
		// string "Gboard".
		if (description.contains("Gboard")){
			return null;
		}

		return description;
	}

	@Override
	public boolean onAddMediaAttachmentFromEditText(Uri uri, String description){
		description = sanitizeMediaDescription(description);
		return mediaViewController.addMediaAttachment(uri, description);
	}

	private void startAutocomplete(ComposeAutocompleteSpan span){
		currentAutocompleteSpan=span;
		Editable e=mainEditText.getText();
		String spanText=e.toString().substring(e.getSpanStart(span), e.getSpanEnd(span));
		autocompleteViewController.setText(spanText);
		showAutocomplete();
	}

	private void finishAutocomplete(){
		if(currentAutocompleteSpan==null)
			return;
		autocompleteViewController.setText(null);
		currentAutocompleteSpan=null;
		hideAutocomplete();
	}

	private void showAutocomplete(){
		UiUtils.beginLayoutTransition(bottomBar);
		View autocompleteView=autocompleteViewController.getView();
		bottomBar.getLayoutParams().height=ViewGroup.LayoutParams.WRAP_CONTENT;
		bottomBar.requestLayout();
		autocompleteView.setVisibility(View.VISIBLE);
		autocompleteDivider.setVisibility(View.VISIBLE);
	}

	private void hideAutocomplete(){
		UiUtils.beginLayoutTransition(bottomBar);
		bottomBar.getLayoutParams().height=V.dp(48);
		bottomBar.requestLayout();
		autocompleteViewController.getView().setVisibility(View.INVISIBLE);
		autocompleteDivider.setVisibility(View.INVISIBLE);
	}

	private void onAutocompleteOptionSelected(String text){
		Editable e=mainEditText.getText();
		int start=e.getSpanStart(currentAutocompleteSpan);
		int end=e.getSpanEnd(currentAutocompleteSpan);
		if(start==-1 || end==-1)
			return;
		e.replace(start, end, text+" ");
		finishAutocomplete();
		InputConnection conn=mainEditText.getCurrentInputConnection();
		if(conn!=null)
			conn.finishComposingText();
	}

	@Override
	public CharSequence getTitle(){
		return getString(R.string.new_post);
	}

	@Override
	public boolean wantsLightStatusBar(){
		return !UiUtils.isDarkTheme();
	}

	@Override
	public boolean wantsLightNavigationBar(){
		return !UiUtils.isDarkTheme();
	}

	public boolean getWasDetached(){
		return wasDetached;
	}

	public boolean isCreatingView(){
		return creatingView;
	}

	public String getAccountID(){
		return accountID;
	}

	public void addFakeMediaAttachment(Uri uri, String description){
		mediaViewController.addFakeMediaAttachment(uri, description);
	}

	private void showLanguageAlert(){
		Preferences prefs=AccountSessionManager.getInstance().getAccount(accountID).preferences;
		ComposeLanguageAlertViewController vc=new ComposeLanguageAlertViewController(getActivity(), prefs!=null ? prefs.postingDefaultLanguage : null, postLang, mainEditText.getText().toString());
		final AlertDialog dlg=new M3AlertDialogBuilder(getActivity())
				.setTitle(R.string.language)
				.setView(vc.getView())
				.setPositiveButton(R.string.cancel, null)
				.show();
		vc.setSelectionListener(opt->{
			setPostLanguage(opt);
			dlg.dismiss();
		});
	}

	private void setPostLanguage(ComposeLanguageAlertViewController.SelectedOption language){
		postLang=language;
	}

	@Override
	public Animator onCreateEnterTransition(View prev, View container){
		AnimatorSet anim=new AnimatorSet();
		if(getArguments().getBoolean("fromThreadFragment")){
			anim.playTogether(
					ObjectAnimator.ofFloat(container, View.ALPHA, 0f, 1f),
					ObjectAnimator.ofFloat(container, View.TRANSLATION_Y, V.dp(200), 0)
			);
		}else{
			anim.playTogether(
					ObjectAnimator.ofFloat(container, View.ALPHA, 0f, 1f),
					ObjectAnimator.ofFloat(container, View.TRANSLATION_X, V.dp(100), 0)
			);
		}
		anim.setDuration(300);
		anim.setInterpolator(CubicBezierInterpolator.DEFAULT);
		return anim;
	}

	@Override
	public Animator onCreateExitTransition(View prev, View container){
		AnimatorSet anim=new AnimatorSet();
		anim.playTogether(
				ObjectAnimator.ofFloat(container, View.TRANSLATION_X, V.dp(100)),
				ObjectAnimator.ofFloat(container, View.ALPHA, 0)
		);
		anim.setDuration(200);
		anim.setInterpolator(CubicBezierInterpolator.DEFAULT);
		return anim;
	}
}
