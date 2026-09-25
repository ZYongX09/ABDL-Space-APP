package org.joinmastodon.android.fragments;

import static org.joinmastodon.android.GlobalUserPreferences.reduceMotion;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.app.assist.AssistContent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.WindowInsets;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.squareup.otto.Subscribe;

import org.joinmastodon.android.E;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.MainActivity;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.announcements.GetAnnouncements;
import org.joinmastodon.android.api.requests.lists.GetLists;
import org.joinmastodon.android.api.requests.tags.GetFollowedTags;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.events.HashtagUpdatedEvent;
import org.joinmastodon.android.events.ListCreatedEvent;
import org.joinmastodon.android.events.ListDeletedEvent;
import org.joinmastodon.android.events.ListUpdatedEvent;
import org.joinmastodon.android.events.SelfUpdateStateChangedEvent;
import org.joinmastodon.android.fragments.discover.SearchQueryFragment;
import org.joinmastodon.android.fragments.settings.SettingsMainFragment;
import org.joinmastodon.android.googleservices.barcodescanner.Barcode;
import org.joinmastodon.android.googleservices.barcodescanner.BarcodeScanner;
import org.joinmastodon.android.model.Announcement;
import org.joinmastodon.android.model.Hashtag;
import org.joinmastodon.android.model.HeaderPaginationList;
import org.joinmastodon.android.model.FollowList;
import org.joinmastodon.android.model.StatusPrivacy;
import org.joinmastodon.android.model.TimelineDefinition;
import org.joinmastodon.android.model.viewmodel.ListItem;
import org.joinmastodon.android.novel.editor.NovelEditorActivity;
import org.joinmastodon.android.ui.ExtendedPopupMenu;
import org.joinmastodon.android.ui.SimpleViewHolder;
import org.joinmastodon.android.ui.compose.navigation.HomeLiquidToolbarController;
import org.joinmastodon.android.ui.compose.navigation.HomeToolbarMenuItem;
import org.joinmastodon.android.ui.compose.navigation.HomeToolbarTimeline;
import org.joinmastodon.android.ui.utils.LocationUtils;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.joinmastodon.android.updater.GithubSelfUpdater;
import org.joinmastodon.android.utils.ElevationOnScrollListener;
import org.joinmastodon.android.utils.ProvidesAssistContent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.fragments.BaseRecyclerFragment;
import me.grishka.appkit.utils.CubicBezierInterpolator;
import me.grishka.appkit.utils.V;
import me.grishka.appkit.views.FragmentRootLinearLayout;

import static org.joinmastodon.android.ui.compose.navigation.HomeLiquidToolbarModelKt.homeTimelineTopPaddingDp;

public class HomeTabFragment extends MastodonToolbarFragment implements ScrollableToTop, HasFab, ProvidesAssistContent, HasElevationOnScrollListener {
	private static final int ANNOUNCEMENTS_RESULT = 654;
	private static final int SCAN_RESULT = 456;

	private String accountID;
	private MenuItem announcements, announcementsAction, settings, settingsAction;
	//	private ImageView toolbarLogo;
	private Button toolbarShowNewPostsBtn;
	private boolean newPostsBtnShown;
	private AnimatorSet currentNewPostsAnim;
	private ViewPager2 pager;
	private View switcher;
	private FrameLayout toolbarFrame;
	private int lastTopInset;
	private int liquidNavBottomInset;
	private ImageView timelineIcon;
	private ImageView collapsedChevron;
	private TextView timelineTitle;
	private PopupMenu switcherPopup;
	private final Map<Integer, FollowList> listItems = new HashMap<>();
	private final Map<Integer, Hashtag> hashtagsItems = new HashMap<>();
	private List<TimelineDefinition> timelinesList;
	private int count;
	private Fragment[] fragments;
	private FrameLayout[] tabViews;
	private TimelineDefinition[] timelines;
	private final Map<Integer, TimelineDefinition> timelinesByMenuItem = new HashMap<>();
	private SubMenu hashtagsMenu, listsMenu;
	private PopupMenu overflowPopup;
	private View overflowActionView = null;
	private ImageButton searchActionView = null;
	private boolean announcementsBadged, settingsBadged;
	private ImageButton fab;
	private int fabBottomInset;
	private Intent scannerIntent;
	private ElevationOnScrollListener elevationOnScrollListener;
	private HomeLiquidToolbarController liquidToolbarController;

	// TODO: rename this
	private Runnable returnToBeginningOfPager=this::returnToBeginningOfPager;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		E.register(this);
		accountID = getArguments().getString("account");
		timelinesList=AccountSessionManager.get(accountID).getLocalPreferences().timelines;
		if(timelinesList==null || timelinesList.isEmpty()) timelinesList=new java.util.ArrayList<>(List.of(TimelineDefinition.HOME_TIMELINE));
		// 同城时间线：注入或更新省份时间线（当用户省份已知时）
		try{
			String cachedProvince=LocationUtils.getCachedProvince(getActivity());
			if(cachedProvince!=null){
				boolean needPersist=false;
				java.util.ArrayList<TimelineDefinition> newList=new java.util.ArrayList<>(timelinesList);
				// 已有 GEO 时间线 → 校准省份（位置漂移后让持久化定义与 tab 标题同步）
				for(int i=0;i<newList.size();i++){
					TimelineDefinition t=newList.get(i);
					if(t.getType()==TimelineDefinition.TimelineType.GEO){
						String oldProvince=t.getProvince();
						if(oldProvince==null || !oldProvince.equals(cachedProvince)){
							newList.set(i, TimelineDefinition.ofGeo(cachedProvince));
							needPersist=true;
						}
					}
				}
				// 尚无 GEO 时间线 → 注入并排在第二位（HOME 之后）
				if(newList.stream().noneMatch(t->t.getType()==TimelineDefinition.TimelineType.GEO)){
					TimelineDefinition geoTl=TimelineDefinition.ofGeo(cachedProvince);
					int insertIdx=Math.min(1, newList.size());
					newList.add(insertIdx, geoTl);
					needPersist=true;
				}
				if(needPersist){
					timelinesList=newList;
					AccountSessionManager.get(accountID).getLocalPreferences().timelines=newList;
					AccountSessionManager.get(accountID).getLocalPreferences().save();
				}
			}
			// 省份未知时双保险：GPS 定位 + IP 属地同时触发（先到先用，下次启动生效）
			if(cachedProvince==null){
				if(LocationUtils.hasLocationPermission(getActivity())){
					LocationUtils.fetchAndResolve(getActivity(), null);
				}
				// 无论是否有定位权限，都额外尝试 IP 属地兜底（GPS 失败/室内无信号时仍有保障）
				LocationUtils.fetchProvinceFromIP(getActivity(), province->{
					if(province!=null){
						// 省份刚缓存，重建 Activity 以加载同城时间线
						new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{
							if(getActivity()!=null) getActivity().recreate();
						});
					}
				});
			}
			}catch(Exception e){
				// 旧版本升级时 preferences 可能不完整，忽略
			}
			// 「关注」时间线固定第二位（HOME 之后）
			if(timelinesList.stream().noneMatch(t->t.getType()==TimelineDefinition.TimelineType.FOLLOWING)){
				java.util.ArrayList<TimelineDefinition> newList=new java.util.ArrayList<>(timelinesList);
				newList.add(Math.min(1, newList.size()), TimelineDefinition.FOLLOWING_TIMELINE.copy());
				timelinesList=newList;
				AccountSessionManager.get(accountID).getLocalPreferences().timelines=newList;
				AccountSessionManager.get(accountID).getLocalPreferences().save();
			}
			// 热门时间线固定排在第三位（HOME、同城之后）；没有同城时仍占第三位。
			if(timelinesList.stream().noneMatch(t->t.getType()==TimelineDefinition.TimelineType.POPULAR)){
				java.util.ArrayList<TimelineDefinition> newList=new java.util.ArrayList<>(timelinesList);
				newList.add(Math.min(2, newList.size()), TimelineDefinition.ofPopular());
				timelinesList=newList;
				AccountSessionManager.get(accountID).getLocalPreferences().timelines=newList;
				AccountSessionManager.get(accountID).getLocalPreferences().save();
			}
			// 跨站与交友内容已并入主页（/api/v1/timelines/all）：移除旧版独立时间线并重存。
			boolean hasAggregatedTimeline=timelinesList.stream().anyMatch(t->
				t.getType()==TimelineDefinition.TimelineType.FEDERATED ||
				t.getType()==TimelineDefinition.TimelineType.FRIEND_UNIVERSE);
			if(hasAggregatedTimeline){
				java.util.ArrayList<TimelineDefinition> newList=new java.util.ArrayList<>();
				for(TimelineDefinition t:timelinesList){
					if(t.getType()!=TimelineDefinition.TimelineType.FEDERATED &&
						t.getType()!=TimelineDefinition.TimelineType.FRIEND_UNIVERSE)
						newList.add(t);
				}
				if(newList.isEmpty()) newList.add(TimelineDefinition.HOME_TIMELINE.copy());
				timelinesList=newList;
				AccountSessionManager.get(accountID).getLocalPreferences().timelines=newList;
				AccountSessionManager.get(accountID).getLocalPreferences().save();
			}
			count=timelinesList.size();
		fragments=new Fragment[count];
		tabViews=new FrameLayout[count];
		timelines=new TimelineDefinition[count];
		if(GlobalUserPreferences.toolbarMarquee){
			setTitleMarqueeEnabled(false);
			setSubtitleMarqueeEnabled(false);
		}
	}

	private void returnToBeginningOfPager() {
		pager.setCurrentItem(0);
	}

	@Override
	public void onApplyWindowInsets(WindowInsets insets){
		// 时间线内的交友宇宙 fragment 不在 view 树上自动收到 insets，显式转发
		int top=insets.getSystemWindowInsetTop();
		lastTopInset=top;
		if(fragments!=null){
			for(Fragment f:fragments){
				if(f instanceof FriendRequestListFragment frf)
					frf.setTopInset(top);
			}
		}
		// 液态模式下标准 toolbar 隐藏、玻璃悬浮其上，列表顶部安全区需要含状态栏
		updateLiquidToolbarMode();
		super.onApplyWindowInsets(insets);
	}

	@Override
	public void onAttach(Activity activity) {
		super.onAttach(activity);
		setHasOptionsMenu(true);
	}

	@Override
	public View onCreateContentView(LayoutInflater inflater, ViewGroup container, Bundle bundle) {
		FragmentRootLinearLayout rootView = new FragmentRootLinearLayout(getContext());
		rootView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		FrameLayout view = new FrameLayout(getContext());
		view.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		rootView.addView(view);
		inflater.inflate(R.layout.compose_fab, view);
		fab = view.findViewById(R.id.fab);
		fab.setOnClickListener(this::onFabClick);
		fab.setOnLongClickListener(this::onFabLongClick);
		pager = new ViewPager2(getContext());
		toolbarFrame = (FrameLayout) LayoutInflater.from(getContext()).inflate(R.layout.home_toolbar, getToolbar(), false);

		Bundle args = new Bundle();
		args.putString("account", accountID);
		args.putBoolean("__is_tab", true);
		args.putBoolean("__disable_fab", true);
		args.putBoolean("onlyPosts", true);
		FragmentTransaction transaction = null;
		for (int i = 0; i < count; i++) {
			int containerId=i + 1;
			timelines[i]=timelinesList.get(i);
			Fragment expectedFragment=timelines[i].getFragment();
			Class<?> expectedClass=expectedFragment.getClass();
			Fragment restoredFragment=getChildFragmentManager().findFragmentById(containerId);
			if(restoredFragment!=null && expectedClass.isInstance(restoredFragment)){
				fragments[i]=restoredFragment;
			}else{
				if(restoredFragment!=null){
					if(transaction==null)
						transaction=getChildFragmentManager().beginTransaction();
					transaction.remove(restoredFragment);
				}
				fragments[i]=expectedFragment;
				fragments[i].setArguments(timelines[i].populateArguments(new Bundle(args)));
				if(transaction==null)
					transaction=getChildFragmentManager().beginTransaction();
				transaction.add(containerId, fragments[i]);
			}

			FrameLayout tabView = new FrameLayout(getActivity());
			tabView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
			tabView.setVisibility(View.GONE);
			tabView.setId(containerId);
			view.addView(tabView);
			tabViews[i] = tabView;
		}
		if(transaction!=null)
			transaction.commit();

		view.addView(pager, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

		overflowActionView = UiUtils.makeOverflowActionView(getContext());
		overflowPopup = new PopupMenu(getContext(), overflowActionView);
		overflowPopup.setOnMenuItemClickListener(this::onOptionsItemSelected);
		overflowActionView.setOnClickListener(l -> overflowPopup.show());
		overflowActionView.setOnTouchListener(overflowPopup.getDragToOpenListener());

		// 非液态模式：搜索按钮紧邻更多按钮（液态模式由玻璃工具栏接管）
		searchActionView = makeSearchActionView();
		setFabBottomInset(0);

		return rootView;
	}

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public void onViewCreated(View view, Bundle savedInstanceState){
		super.onViewCreated(view, savedInstanceState);

		timelineIcon = toolbarFrame.findViewById(R.id.timeline_icon);
		timelineTitle = toolbarFrame.findViewById(R.id.timeline_title);
		collapsedChevron = toolbarFrame.findViewById(R.id.collapsed_chevron);
		switcher = toolbarFrame.findViewById(R.id.switcher_btn);
		switcherPopup = new PopupMenu(getContext(), switcher);
		switcherPopup.setOnMenuItemClickListener(this::onSwitcherItemSelected);
		UiUtils.enablePopupMenuIcons(getContext(), switcherPopup);
		switcher.setOnClickListener(v->switcherPopup.show());
		switcher.setOnTouchListener(switcherPopup.getDragToOpenListener());
		updateSwitcherMenu();

		UiUtils.reduceSwipeSensitivity(pager);
		pager.setUserInputEnabled(!GlobalUserPreferences.disableSwipe);
		pager.setAdapter(new HomePagerAdapter());
		pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
			@Override
			public void onPageSelected(int position){
				if(position!=0) {
					addBackCallback(returnToBeginningOfPager);
				} else {
					removeBackCallback(returnToBeginningOfPager);
				}

				if (!reduceMotion) {
					// setting this here because page transformer appears to fire too late so the
					// animation can appear bumpy, especially when navigating to a further-away tab
					switcher.setScaleY(0.85f);
					switcher.setScaleX(0.85f);
					switcher.setAlpha(0.65f);
				}
				updateSwitcherIcon(position);
				if (!timelines[position].equals(TimelineDefinition.HOME_TIMELINE)) hideNewPostsButton();
				if (fragments[position] instanceof BaseRecyclerFragment<?> page){
					if(!page.loaded && !page.isDataLoading()) page.loadData();
				}
			}
		});

		if (!reduceMotion) {
			pager.setPageTransformer((v, pos) -> {
				if (reduceMotion || tabViews[pager.getCurrentItem()] != v) return;
				float scaleFactor = Math.max(0.85f, 1 - Math.abs(pos) * 0.06f);
				switcher.setScaleY(scaleFactor);
				switcher.setScaleX(scaleFactor);
				switcher.setAlpha(Math.max(0.65f, 1 - Math.abs(pos)));
			});
		}

		updateToolbarLogo();
		updateLiquidToolbarMode();
		updateLiquidToolbarState();

		ViewTreeObserver vto = getToolbar().getViewTreeObserver();
		if (vto.isAlive()) {
			vto.addOnGlobalLayoutListener(()->{
				Toolbar t=getToolbar();
				if(t==null) return;
				int toolbarWidth=t.getWidth();
				if(toolbarWidth==0) return;

				int toolbarFrameWidth=toolbarFrame.getWidth();
				int actionsWidth=toolbarWidth-toolbarFrameWidth;
				// margin (4) + padding (12) + icon (24) + margin (8) + chevron (16) + padding (12)
				int switcherWidth=V.dp(76);
				FrameLayout parent=((FrameLayout) toolbarShowNewPostsBtn.getParent());
				if(actionsWidth==parent.getPaddingStart()) return;
				int paddingMax=Math.max(actionsWidth, switcherWidth);
				int paddingEnd=(Math.max(0, switcherWidth-actionsWidth));

				// toolbar frame goes from screen edge to beginning of right-aligned option buttons.
				// centering button by applying the same space on the left
				parent.setPaddingRelative(paddingMax, 0, paddingEnd, 0);
				toolbarShowNewPostsBtn.setMaxWidth(toolbarWidth-paddingMax*2);

				switcher.setPivotX(V.dp(28)); // padding + half of icon
				switcher.setPivotY(switcher.getHeight() / 2f);
			});
		}

		elevationOnScrollListener = new ElevationOnScrollListener((FragmentRootLinearLayout) view, getToolbar());

		if(GithubSelfUpdater.isSupported()){
			updateUpdateState(GithubSelfUpdater.getInstance().getState());
		}

		new GetLists().setCallback(new Callback<>() {
			@Override
			public void onSuccess(List<FollowList> lists) {
				updateList(lists, listItems);
			}

			@Override
			public void onError(ErrorResponse error) {
				error.showToast(getContext());
			}
		}).exec(accountID);

		new GetFollowedTags(null, 200).setCallback(new Callback<>() {
			@Override
			public void onSuccess(HeaderPaginationList<Hashtag> hashtags) {
				updateList(hashtags, hashtagsItems);
			}

			@Override
			public void onError(ErrorResponse error) {
				error.showToast(getContext());
			}
		}).exec(accountID);

		new GetAnnouncements(false).setCallback(new Callback<>() {
			@Override
			public void onSuccess(List<Announcement> result) {
				if(getActivity()==null) return;
				if (result.stream().anyMatch(a -> !a.read)) {
					announcementsBadged = true;
					if(announcements!=null)
						announcements.setVisible(GlobalUserPreferences.isIosLiquidNavigationEnabled());
					if(announcementsAction!=null)
						announcementsAction.setVisible(!GlobalUserPreferences.isIosLiquidNavigationEnabled());
					updateLiquidToolbarState();
				}
			}

			@Override
			public void onError(ErrorResponse error) {
				error.showToast(getActivity());
			}
		}).exec(accountID);
	}

	public ElevationOnScrollListener getElevationOnScrollListener() {
		return elevationOnScrollListener;
	}

	/**
	 * 非液态模式：更多按钮左侧的独立搜索按钮，带 {@link R.id#home_search_btn} 锚点
	 * 供 SearchQueryFragment 展开动画定位。
	 */
	private ImageButton makeSearchActionView(){
		ImageButton searchBtn=new ImageButton(getContext(), null, 0, R.style.Widget_Mastodon_ActionButton_Overflow);
		searchBtn.setId(R.id.home_search_btn);
		searchBtn.setImageResource(R.drawable.ic_fluent_search_24_regular);
		searchBtn.setContentDescription(getString(R.string.search_hint));
		searchBtn.setOnClickListener(v->openSearch());
		return searchBtn;
	}

	public void setFabBottomInset(int insetPx){
		fabBottomInset=insetPx;
		if(fab!=null){
			FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams) fab.getLayoutParams();
			lp.bottomMargin=V.dp(16)+fabBottomInset;
			fab.setLayoutParams(lp);
		}
	}

	public void openSearch(){
		// 当前时间线为交友宇宙时，搜索按钮切换为交友宇宙搜索
		if(timelines!=null && pager!=null){
			int idx=pager.getCurrentItem();
			if(idx>=0 && idx<timelines.length && timelines[idx].getType()==TimelineDefinition.TimelineType.FRIEND_UNIVERSE){
				Bundle args=new Bundle();
				args.putString("account", accountID);
				args.putBoolean("searchMode", true);
				Nav.go(getActivity(), FriendRequestListFragment.class, args);
				return;
			}
		}
		Bundle args=new Bundle();
		args.putString("account", accountID);
		Nav.go(getActivity(), SearchQueryFragment.class, args);
	}

	private void openQrScanner(){
		if(getActivity()==null)
			return;
		if(scannerIntent==null)
			scannerIntent=BarcodeScanner.createIntent(Barcode.FORMAT_QR_CODE, false, true);
		if(scannerIntent.resolveActivity(getActivity().getPackageManager())!=null){
			startActivityForResult(scannerIntent, SCAN_RESULT);
		}else{
			BarcodeScanner.installScannerModule(getActivity(), ()->startActivityForResult(scannerIntent, SCAN_RESULT));
		}
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data){
		if(requestCode==SCAN_RESULT && resultCode==Activity.RESULT_OK && data!=null){
			if(BarcodeScanner.isValidResult(data)){
				Barcode code=BarcodeScanner.getResult(data);
				if(code!=null){
					if(code.rawValue.startsWith("https:") || code.rawValue.startsWith("http:")){
						((MainActivity)getActivity()).handleURL(Uri.parse(code.rawValue), accountID);
					}else{
						Toast.makeText(getActivity(), R.string.link_not_supported, Toast.LENGTH_SHORT).show();
					}
				}
			}
		}
	}

	private void onFabClick(View v){
		if (fragments[pager.getCurrentItem()] instanceof BaseStatusListFragment<?> l) {
			l.onFabClick(v);
		}
	}

	private boolean onFabLongClick(View v) {
		if (fragments[pager.getCurrentItem()] instanceof BaseStatusListFragment<?> l) {
			return l.onFabLongClick(v);
		} else {
			return false;
		}
	}

	private void addListsToOverflowMenu() {
		Context ctx = getContext();
		listsMenu.clear();
		listsMenu.getItem().setVisible(listItems.size() > 0);
		UiUtils.insetPopupMenuIcon(ctx, UiUtils.makeBackItem(listsMenu));
		listItems.forEach((id, list) -> {
			MenuItem item = listsMenu.add(Menu.NONE, id, Menu.NONE, list.title);
			item.setIcon(R.drawable.ic_fluent_people_24_regular);
			UiUtils.insetPopupMenuIcon(ctx, item);
		});
	}

	private void addHashtagsToOverflowMenu() {
		Context ctx = getContext();
		hashtagsMenu.clear();
		hashtagsMenu.getItem().setVisible(hashtagsItems.size() > 0);
		UiUtils.insetPopupMenuIcon(ctx, UiUtils.makeBackItem(hashtagsMenu));
		hashtagsItems.entrySet().stream()
				.sorted(Comparator.comparing(x -> x.getValue().name, String.CASE_INSENSITIVE_ORDER))
				.forEach(entry -> {
					MenuItem item = hashtagsMenu.add(Menu.NONE, entry.getKey(), Menu.NONE, entry.getValue().name);
					item.setIcon(R.drawable.ic_fluent_number_symbol_24_regular);
					UiUtils.insetPopupMenuIcon(ctx, item);
				});
	}

	public void updateToolbarLogo(){
		Toolbar toolbar = getToolbar();
		ViewParent parentView = toolbarFrame.getParent();
		if (parentView == toolbar) return;
		if (parentView instanceof Toolbar parentToolbar) parentToolbar.removeView(toolbarFrame);
		toolbar.addView(toolbarFrame, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
		toolbar.setOnClickListener(v->scrollToTop());
		toolbar.setNavigationContentDescription(R.string.back);
		toolbar.setContentInsetsAbsolute(0, toolbar.getContentInsetRight());

		updateSwitcherIcon(pager.getCurrentItem());

		toolbarShowNewPostsBtn=toolbarFrame.findViewById(R.id.show_new_posts_btn);
		toolbarShowNewPostsBtn.setCompoundDrawableTintList(toolbarShowNewPostsBtn.getTextColors());
		if(Build.VERSION.SDK_INT<Build.VERSION_CODES.N) UiUtils.fixCompoundDrawableTintOnAndroid6(toolbarShowNewPostsBtn);
		toolbarShowNewPostsBtn.setOnClickListener(this::onNewPostsBtnClick);

		if(newPostsBtnShown){
			toolbarShowNewPostsBtn.setVisibility(View.VISIBLE);
			collapsedChevron.setVisibility(View.VISIBLE);
			collapsedChevron.setAlpha(1f);
			timelineTitle.setVisibility(View.GONE);
			timelineTitle.setAlpha(0f);
		}else{
			toolbarShowNewPostsBtn.setVisibility(View.INVISIBLE);
			toolbarShowNewPostsBtn.setAlpha(0f);
			collapsedChevron.setVisibility(View.GONE);
			collapsedChevron.setAlpha(0f);
			toolbarShowNewPostsBtn.setScaleX(.8f);
			toolbarShowNewPostsBtn.setScaleY(.8f);
			timelineTitle.setVisibility(View.VISIBLE);
		}
	}

	private void updateOverflowMenu() {
		if(getActivity()==null) return;
		Menu m = overflowPopup.getMenu();
		m.clear();
		overflowPopup.inflate(R.menu.home_overflow);
		announcements = m.findItem(R.id.announcements);
		settings = m.findItem(R.id.settings);
		hashtagsMenu = m.findItem(R.id.hashtags).getSubMenu();
		listsMenu = m.findItem(R.id.lists).getSubMenu();

		boolean liquid=GlobalUserPreferences.isIosLiquidNavigationEnabled() && liquidToolbarController!=null;
		announcements.setVisible(liquid || !announcementsBadged);
		announcementsAction.setVisible(!liquid && announcementsBadged);

		settings.setVisible(liquid || !settingsBadged);
		settingsAction.setVisible(!liquid && settingsBadged);

		UiUtils.enablePopupMenuIcons(getContext(), overflowPopup);

		addListsToOverflowMenu();
		addHashtagsToOverflowMenu();
		updateLiquidToolbarState();

		if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.P && !UiUtils.isEMUI() && !UiUtils.isMagic())
			m.setGroupDividerEnabled(true);
	}

	public void setLiquidToolbarController(HomeLiquidToolbarController controller){
		liquidToolbarController=controller;
		updateLiquidToolbarMode();
		if(overflowPopup!=null)
			updateOverflowMenu();
		updateLiquidToolbarState();
	}

	private void updateLiquidToolbarMode(){
		boolean liquid=GlobalUserPreferences.isIosLiquidNavigationEnabled() && liquidToolbarController!=null;
		Toolbar toolbar=getToolbar();
		if(toolbar!=null)
			toolbar.setVisibility(liquid ? View.GONE : View.VISIBLE);
		if(fab!=null)
			fab.setVisibility(liquid ? View.GONE : View.VISIBLE);
		// 液态下标准 toolbar 隐藏、玻璃悬浮其上：列表顶部安全区 = 状态栏 + 玻璃工具栏高度
		int topPadding=V.dp(homeTimelineTopPaddingDp(liquid)) + (liquid ? lastTopInset : 0);
		if(fragments!=null){
			for(Fragment fragment:fragments){
				if(fragment instanceof BaseStatusListFragment<?> statusListFragment){
					statusListFragment.setLiquidToolbarTopPadding(topPadding);
					statusListFragment.setLiquidToolbarFabHidden(liquid);
					statusListFragment.setLiquidNavBottomPadding(liquid ? liquidNavBottomInset : 0);
				}
				if(fragment instanceof FriendRequestListFragment friendRequestListFragment){
					friendRequestListFragment.setLiquidToolbarTopPadding(topPadding);
					friendRequestListFragment.setLiquidNavBottomPadding(liquid ? liquidNavBottomInset : 0);
				}
				if(fragment instanceof HomeTimelineFragment homeTimelineFragment)
					homeTimelineFragment.setLiquidToolbarMode(liquid);
			}
		}
	}

	/** 液态底部导航条 overlay 高度（px），由 HomeFragment 在导航布局尺寸变化时转发 */
	public void setLiquidNavBottomInset(int px){
		if(liquidNavBottomInset==px)
			return;
		liquidNavBottomInset=px;
		updateLiquidToolbarMode();
	}

	private void updateLiquidToolbarState(){
		if(liquidToolbarController==null || timelines==null)
			return;
		ArrayList<HomeToolbarTimeline> toolbarTimelines=new ArrayList<>();
		for(int i=0;i<timelines.length;i++){
			TimelineDefinition timeline=timelines[i];
			if(timeline!=null)
				toolbarTimelines.add(new HomeToolbarTimeline(i, timeline.getTitle(getContext()), timeline.getIcon().iconRes));
		}
		liquidToolbarController.setTimelines(toolbarTimelines, pager==null ? 0 : pager.getCurrentItem());
		liquidToolbarController.setShowNewPosts(newPostsBtnShown);
		liquidToolbarController.setReminderState(announcementsBadged, settingsBadged);
		ArrayList<HomeToolbarMenuItem> root=new ArrayList<>();
		root.add(new HomeToolbarMenuItem(R.id.settings, getString(R.string.settings), R.drawable.ic_fluent_settings_24_regular));
		root.add(new HomeToolbarMenuItem(R.id.announcements, getString(R.string.sk_announcements), R.drawable.ic_fluent_megaphone_24_regular));
		root.add(new HomeToolbarMenuItem(R.id.edit_timelines, getString(R.string.sk_edit_timelines), R.drawable.ic_fluent_edit_24_regular));
		root.add(new HomeToolbarMenuItem(R.id.scan_qr, getString(R.string.scan_qr), R.drawable.ic_fluent_scan_24_regular));
		if(!listItems.isEmpty())
			root.add(new HomeToolbarMenuItem(R.id.lists, getString(R.string.sk_your_lists), R.drawable.ic_fluent_people_24_regular));
		if(!hashtagsItems.isEmpty())
			root.add(new HomeToolbarMenuItem(R.id.hashtags, getString(R.string.sk_hashtags_you_follow), R.drawable.ic_fluent_number_symbol_24_regular));
		ArrayList<HomeToolbarMenuItem> lists=new ArrayList<>();
		listItems.forEach((id, list)->lists.add(new HomeToolbarMenuItem(id, list.title, R.drawable.ic_fluent_people_24_regular)));
		ArrayList<HomeToolbarMenuItem> hashtags=new ArrayList<>();
		hashtagsItems.entrySet().stream().sorted(Comparator.comparing(x->x.getValue().name, String.CASE_INSENSITIVE_ORDER))
				.forEach(entry->hashtags.add(new HomeToolbarMenuItem(entry.getKey(), entry.getValue().name, R.drawable.ic_fluent_number_symbol_24_regular)));
		liquidToolbarController.setMenus(root, lists, hashtags);
	}

	public void onLiquidTimelineSelected(int index){
		if(index>=0 && index<count)
			navigateTo(index);
	}

	public void onLiquidNewPosts(){
		if(toolbarShowNewPostsBtn!=null)
			onNewPostsBtnClick(toolbarShowNewPostsBtn);
	}

	public void onLiquidCompose(){
		onFabClick(fab);
	}

	public void onLiquidMenuItem(int id){
		if(id==R.id.novel){
			startActivity(new Intent(getActivity(), NovelEditorActivity.class).putExtra(NovelEditorActivity.EXTRA_ACCOUNT_ID, accountID));
			return;
		}
		if(id==R.id.scan_qr){
			openQrScanner();
			return;
		}
		if(id==R.id.compose_post){
			onLiquidCompose();
			return;
		}
		if(id==R.id.compose_friend_request){
			Bundle args=new Bundle();
			args.putString("account", accountID);
			Nav.go(getActivity(), FriendRequestCreateFragment.class, args);
			return;
		}
		MenuItem item=overflowPopup==null ? null : overflowPopup.getMenu().findItem(id);
		if(item!=null)
			onOptionsItemSelected(item);
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater){
		inflater.inflate(R.menu.home_custom, menu);

		menu.findItem(R.id.overflow).setActionView(overflowActionView);
		menu.findItem(R.id.search_action).setActionView(searchActionView);
		announcementsAction = menu.findItem(R.id.announcements_action);
		settingsAction = menu.findItem(R.id.settings_action);
		updateOverflowMenu();
	}

	private <T> void updateList(List<T> addItems, Map<Integer, T> items) {
		if (addItems.size() == 0 || getActivity() == null) return;
		for (int i = 0; i < addItems.size(); i++) items.put(View.generateViewId(), addItems.get(i));
		updateOverflowMenu();
		updateLiquidToolbarState();
	}

	private void updateSwitcherMenu() {
		Menu switcherMenu = switcherPopup.getMenu();
		switcherMenu.clear();
		timelinesByMenuItem.clear();

		for (TimelineDefinition tl : timelines) {
			int menuItemId = View.generateViewId();
			timelinesByMenuItem.put(menuItemId, tl);
			MenuItem item = switcherMenu.add(0, menuItemId, 0, tl.getTitle(getContext()));
			item.setIcon(tl.getIcon().iconRes);
		}

		UiUtils.enablePopupMenuIcons(getContext(), switcherPopup);
	}

	private boolean onSwitcherItemSelected(MenuItem item) {
		int id = item.getItemId();

		Bundle args = new Bundle();
		args.putString("account", accountID);

		if (id == R.id.menu_back) {
			switcher.post(() -> switcherPopup.show());
			return true;
		}

		TimelineDefinition tl = timelinesByMenuItem.get(id);
		if (tl != null) {
			for (int i = 0; i < timelines.length; i++) {
				if (timelines[i] == tl) {
					navigateTo(i);
					return true;
				}
			}
		}

		return false;
	}

	private void navigateTo(int i) {
		navigateTo(i, !reduceMotion);
	}

	private void navigateTo(int i, boolean smooth) {
		pager.setCurrentItem(i, smooth);
		updateSwitcherIcon(i);
	}

	@Override
	public void showFab() {
		if (fragments[pager.getCurrentItem()] instanceof BaseStatusListFragment<?> l) l.showFab();
		updateLiquidToolbarMode();
	}

	@Override
	public void hideFab() {
		if (fragments[pager.getCurrentItem()] instanceof BaseStatusListFragment<?> l) l.hideFab();
		updateLiquidToolbarMode();
	}

	@Override
	public boolean isScrolling() {
		return (fragments[pager.getCurrentItem()] instanceof HasFab fabulous)
				&& fabulous.isScrolling();
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		if (elevationOnScrollListener != null) elevationOnScrollListener.setViews(getToolbar());
	}

	private void updateSwitcherIcon(int i) {
		timelineIcon.setImageResource(timelines[i].getIcon().iconRes);
		timelineTitle.setText(timelines[i].getTitle(getContext()));
		showFab();
		updateLiquidToolbarState();
		if (elevationOnScrollListener != null && getCurrentFragment() instanceof IsOnTop f) {
			// FIXME: make this work again
//			elevationOnScrollListener.handleScroll(getContext(), f.isOnTop());
		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item){
		Bundle args=new Bundle();
		args.putString("account", accountID);
		int id = item.getItemId();
		FollowList list;
		Hashtag hashtag;

		if (item.getItemId() == R.id.menu_back) {
			getToolbar().post(() -> overflowPopup.show());
			return true;
		} else if (id == R.id.search_action) {
			openSearch();
		} else if (id == R.id.scan_qr) {
			openQrScanner();
		} else if (id == R.id.settings || id == R.id.settings_action) {
			Nav.go(getActivity(), SettingsMainFragment.class, args);
		} else if (id == R.id.announcements || id == R.id.announcements_action) {
			Nav.goForResult(getActivity(), AnnouncementsFragment.class, args, ANNOUNCEMENTS_RESULT, this);
		} else if (id == R.id.edit_timelines) {
			Nav.go(getActivity(), EditTimelinesFragment.class, args);
		} else if (id == R.id.novel) {
			startActivity(new Intent(getActivity(), NovelEditorActivity.class).putExtra(NovelEditorActivity.EXTRA_ACCOUNT_ID, accountID));
		} else if ((list = listItems.get(id)) != null) {
			args.putString("listID", list.id);
			args.putString("listTitle", list.title);
			args.putBoolean("listIsExclusive", list.exclusive);
			if (list.repliesPolicy != null) args.putInt("repliesPolicy", list.repliesPolicy.ordinal());
			Nav.go(getActivity(), ListTimelineCustomFragment.class, args);
		} else if ((hashtag = hashtagsItems.get(id)) != null) {
			UiUtils.openHashtagTimeline(getContext(), accountID, hashtag);
		}
		return true;
	}

	@Override
	public void scrollToTop(){
		if (((IsOnTop) fragments[pager.getCurrentItem()]).isOnTop() &&
				GlobalUserPreferences.doubleTapToSwipe && !newPostsBtnShown) {
			int nextPage = (pager.getCurrentItem() + 1) % count;
			navigateTo(nextPage);
			return;
		}
		((ScrollableToTop) fragments[pager.getCurrentItem()]).scrollToTop();
	}

	public void hideNewPostsButton(){
		if(!newPostsBtnShown)
			return;
		newPostsBtnShown=false;
		updateLiquidToolbarState();
		if(currentNewPostsAnim!=null){
			currentNewPostsAnim.cancel();
		}
		timelineTitle.setVisibility(View.VISIBLE);
		AnimatorSet set=new AnimatorSet();
		set.playTogether(
				ObjectAnimator.ofFloat(timelineTitle, View.ALPHA, 1f),
				ObjectAnimator.ofFloat(timelineTitle, View.SCALE_X, 1f),
				ObjectAnimator.ofFloat(timelineTitle, View.SCALE_Y, 1f),
				ObjectAnimator.ofFloat(toolbarShowNewPostsBtn, View.ALPHA, 0f),
				ObjectAnimator.ofFloat(toolbarShowNewPostsBtn, View.SCALE_X, .8f),
				ObjectAnimator.ofFloat(toolbarShowNewPostsBtn, View.SCALE_Y, .8f),
				ObjectAnimator.ofFloat(collapsedChevron, View.ALPHA, 0f)
		);
		set.setDuration(reduceMotion ? 0 : 300);
		set.setInterpolator(CubicBezierInterpolator.DEFAULT);
		set.addListener(new AnimatorListenerAdapter(){
			@Override
			public void onAnimationEnd(Animator animation){
				toolbarShowNewPostsBtn.setVisibility(View.INVISIBLE);
				collapsedChevron.setVisibility(View.GONE);
				currentNewPostsAnim=null;
			}
		});
		currentNewPostsAnim=set;
		set.start();
	}

	public void showNewPostsButton(){
		if(newPostsBtnShown || pager == null || !timelines[pager.getCurrentItem()].equals(TimelineDefinition.HOME_TIMELINE))
			return;
		newPostsBtnShown=true;
		updateLiquidToolbarState();
		if(currentNewPostsAnim!=null){
			currentNewPostsAnim.cancel();
		}
		toolbarShowNewPostsBtn.setVisibility(View.VISIBLE);
		collapsedChevron.setVisibility(View.VISIBLE);
		AnimatorSet set=new AnimatorSet();
		set.playTogether(
				ObjectAnimator.ofFloat(timelineTitle, View.ALPHA, 0f),
				ObjectAnimator.ofFloat(timelineTitle, View.SCALE_X, .8f),
				ObjectAnimator.ofFloat(timelineTitle, View.SCALE_Y, .8f),
				ObjectAnimator.ofFloat(toolbarShowNewPostsBtn, View.ALPHA, 1f),
				ObjectAnimator.ofFloat(toolbarShowNewPostsBtn, View.SCALE_X, 1f),
				ObjectAnimator.ofFloat(toolbarShowNewPostsBtn, View.SCALE_Y, 1f),
				ObjectAnimator.ofFloat(collapsedChevron, View.ALPHA, 1f)
		);
		set.setDuration(reduceMotion ? 0 : 300);
		set.setInterpolator(CubicBezierInterpolator.DEFAULT);
		set.addListener(new AnimatorListenerAdapter(){
			@Override
			public void onAnimationEnd(Animator animation){
				timelineTitle.setVisibility(View.GONE);
				currentNewPostsAnim=null;
			}
		});
		currentNewPostsAnim=set;
		set.start();
	}

	public boolean isNewPostsBtnShown() {
		return newPostsBtnShown;
	}

	private void onNewPostsBtnClick(View view) {
		if(newPostsBtnShown){
			scrollToTop();
			hideNewPostsButton();
		}
	}

	@Override
	public void onFragmentResult(int reqCode, boolean success, Bundle result){
		if (reqCode == ANNOUNCEMENTS_RESULT && success) {
			announcementsBadged = false;
			if(announcements!=null)
				announcements.setVisible(true);
			if(announcementsAction!=null)
				announcementsAction.setVisible(false);
			updateLiquidToolbarState();
		}
	}

	private void updateUpdateState(GithubSelfUpdater.UpdateState state){
		if(state!=GithubSelfUpdater.UpdateState.NO_UPDATE && state!=GithubSelfUpdater.UpdateState.CHECKING) {
			settingsBadged = true;
			if(settingsAction!=null)
				settingsAction.setVisible(!GlobalUserPreferences.isIosLiquidNavigationEnabled());
			if(settings!=null)
				settings.setVisible(GlobalUserPreferences.isIosLiquidNavigationEnabled());
			updateLiquidToolbarState();
		}
	}

	@Subscribe
	public void onSelfUpdateStateChanged(SelfUpdateStateChangedEvent ev){
		updateUpdateState(ev.state);
	}

//	@Override
//	public boolean onBackPressed(){
//		if(pager.getCurrentItem() > 0){
//			navigateTo(0);
//			return true;
//		}
//		return false;
//	}

	@Override
	public void onDestroyView(){
		liquidToolbarController=null;
		if (overflowPopup != null) {
			overflowPopup.dismiss();
			overflowPopup = null;
		}
		if (switcherPopup != null) {
			switcherPopup.dismiss();
			switcherPopup = null;
		}
		if(GithubSelfUpdater.isSupported()){
			E.unregister(this);
		}
		super.onDestroyView();
	}

	@Override
	protected void onShown() {
		super.onShown();
		Object timelines = AccountSessionManager.get(accountID).getLocalPreferences().timelines;
		if (timelines != null && timelinesList!= timelines) UiUtils.restartApp();
	}

	@Override
	public void onViewStateRestored(Bundle savedInstanceState) {
		super.onViewStateRestored(savedInstanceState);
		if (savedInstanceState == null || count==0) return;
		int selectedTab=Math.max(0, Math.min(savedInstanceState.getInt("selectedTab"), count-1));
		navigateTo(selectedTab, false);
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putInt("selectedTab", pager.getCurrentItem());
	}

	@Subscribe
	public void onHashtagUpdatedEvent(HashtagUpdatedEvent event) {
		handleListEvent(hashtagsItems, h -> h.name.equalsIgnoreCase(event.name), event.following, () -> {
			Hashtag hashtag = new Hashtag();
			hashtag.name = event.name;
			hashtag.following = true;
			return hashtag;
		});
	}

	@Subscribe
	public void onListDeletedEvent(ListDeletedEvent event) {
		handleListEvent(listItems, l -> l.id.equals(event.listID), false, null);
	}

	@Subscribe
	public void onListCreatedEvent(ListCreatedEvent event) {
		handleListEvent(listItems, l -> l.id.equals(event.list.id), true, () -> {
			FollowList list = new FollowList();
			list.id = event.list.id;
			list.title = event.list.title;
			list.repliesPolicy = event.list.repliesPolicy;
			return list;
		});
	}

	private <T> void handleListEvent(
			Map<Integer, T> existingThings,
			Predicate<T> matchExisting,
			boolean shouldBeInList,
			Supplier<T> makeNewThing
	) {
		Optional<Map.Entry<Integer, T>> existingThing = existingThings.entrySet().stream()
				.filter(e -> matchExisting.test(e.getValue())).findFirst();
		if (shouldBeInList) {
			existingThings.put(existingThing.isPresent()
					? existingThing.get().getKey() : View.generateViewId(), makeNewThing.get());
			updateOverflowMenu();
		} else if (existingThing.isPresent() && !shouldBeInList) {
			existingThings.remove(existingThing.get().getKey());
			updateOverflowMenu();
		}
	}

//	public void rebuildAllDisplayItems(){
//		displayItems.clear();
//		for(T item:data){
//			displayItems.addAll(buildDisplayItems(item));
//		}
//		adapter.notifyDataSetChanged();
//	}

	public Collection<Hashtag> getHashtags() {
		return hashtagsItems.values();
	}

	public Fragment getCurrentFragment() {
		return fragments[pager.getCurrentItem()];
	}

	public ImageButton getFab() {
		return fab;
	}

	@Override
	public void onProvideAssistContent(AssistContent assistContent) {
		callFragmentToProvideAssistContent(fragments[pager.getCurrentItem()], assistContent);
	}

	private class HomePagerAdapter extends RecyclerView.Adapter<SimpleViewHolder> {
		@NonNull
		@Override
		public SimpleViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			FrameLayout tabView = tabViews[viewType % getItemCount()];
			ViewGroup tabParent = (ViewGroup) tabView.getParent();
			if (tabParent != null) tabParent.removeView(tabView);
			tabView.setVisibility(View.VISIBLE);
			return new SimpleViewHolder(tabView);
		}

		@Override
		public void onBindViewHolder(@NonNull SimpleViewHolder holder, int position){}

		@Override
		public int getItemCount(){
			return count;
		}

		@Override
		public int getItemViewType(int position){
			return position;
		}
	}
}
