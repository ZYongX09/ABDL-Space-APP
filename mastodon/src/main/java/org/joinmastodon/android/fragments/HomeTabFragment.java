package org.joinmastodon.android.fragments;

import static org.joinmastodon.android.GlobalUserPreferences.reduceMotion;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.app.assist.AssistContent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RotateDrawable;
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
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
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
import org.joinmastodon.android.api.requests.tags.GetFollowedTags;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.api.session.ContentAccessCoordinator;
import org.joinmastodon.android.events.HashtagUpdatedEvent;
import org.joinmastodon.android.events.ListCreatedEvent;
import org.joinmastodon.android.events.ListDeletedEvent;
import org.joinmastodon.android.events.ListUpdatedEvent;
import org.joinmastodon.android.events.SelfUpdateStateChangedEvent;
import org.joinmastodon.android.fragments.friendcards.MyFriendCardsFragment;
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
import org.joinmastodon.android.novel.app.NovelAppActivity;
import org.joinmastodon.android.ui.SimpleViewHolder;
import org.joinmastodon.android.ui.compose.navigation.ComposeFanMenuController;
import org.joinmastodon.android.ui.compose.navigation.HomeLiquidToolbarController;
import org.joinmastodon.android.ui.compose.navigation.HomeToolbarMenuItem;
import org.joinmastodon.android.ui.compose.navigation.HomeToolbarTimeline;
import org.joinmastodon.android.ui.utils.LocationUtils;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.joinmastodon.android.ui.views.HomeTimelineTabStrip;
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
import me.grishka.appkit.utils.V;
import me.grishka.appkit.views.FragmentRootLinearLayout;

public class HomeTabFragment extends MastodonToolbarFragment implements ScrollableToTop, HasFab, ProvidesAssistContent, HasElevationOnScrollListener {
	private static final int ANNOUNCEMENTS_RESULT = 654;
	private static final int SCAN_RESULT = 456;

	private String accountID;
	private MenuItem announcements, announcementsAction, settings, settingsAction;
	private ImageView toolbarLogo;
	private View.OnLayoutChangeListener toolbarLogoCenter;
	private ViewPager2 pager;
	private ViewPager2.OnPageChangeCallback pagerCallback;
	private HomeTimelineTabStrip timelineTabs;
	private View toolbarGeometryRoot;
	private final ViewTreeObserver.OnPreDrawListener toolbarGeometry=this::updateTimelineTopGeometry;
	private int liquidNavBottomInset;
	private final Map<Integer, FollowList> listItems = new HashMap<>();
	private final Map<Integer, Hashtag> hashtagsItems = new HashMap<>();
	private List<TimelineDefinition> timelinesList;
	private int count;
	private final ArrayList<Integer> visibleTimelineIndices=new ArrayList<>();
	private org.joinmastodon.android.api.session.NBWForumOptionsRepository.Cancelable nbwSubscription;
	private int currentTimelineIndex(){
		int position=pager==null ? 0 : pager.getCurrentItem();
		return visibleTimelineIndices.isEmpty() ? 0 : visibleTimelineIndices.get(Math.min(position, visibleTimelineIndices.size()-1));
	}
	private boolean nbwTimelineVisible(){ return AccountSessionManager.get(accountID).getNBWForumOptionsRepository().getSnapshot().canRead(); }
	private void updateNBWVisiblePages(){
		if(pager==null || pager.getAdapter()==null) return;
		RecyclerView pages=(RecyclerView)pager.getChildAt(0);
		if(pages.isComputingLayout()){
			ViewPager2 currentPager=pager;
			currentPager.post(()->{ if(pager==currentPager) updateNBWVisiblePages(); });
			return;
		}
		int selected=currentTimelineIndex();
		ArrayList<Integer> next=new ArrayList<>();
		for(int i=0;i<timelines.length;i++) if(timelines[i].getType()!=TimelineDefinition.TimelineType.NBW || nbwTimelineVisible()) next.add(i);
		if(next.equals(visibleTimelineIndices)) return;
		visibleTimelineIndices.clear(); visibleTimelineIndices.addAll(next);
		pager.getAdapter().notifyDataSetChanged();
		pager.setCurrentItem(Math.max(0, visibleTimelineIndices.indexOf(selected)), false);
		if(timelineTabs!=null) buildTimelineTabs();
		updateLiquidToolbarState();
	}
	private Fragment[] fragments;
	private FrameLayout[] tabViews;
	private TimelineDefinition[] timelines;
	private SubMenu hashtagsMenu, listsMenu;
	private PopupMenu overflowPopup;
	private View overflowActionView;
	private boolean announcementsBadged, settingsBadged;
	private ImageButton fab;
	private int fabBottomInset;
	private ComposeFanMenuController fanMenu;
	private Intent scannerIntent;
	private ElevationOnScrollListener elevationOnScrollListener;
	private HomeLiquidToolbarController liquidToolbarController;

	// TODO: rename this
	private Runnable returnToBeginningOfPager=this::returnToBeginningOfPager;

	private final Runnable collapseFanMenuOnBack=()->collapseFanMenu();

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
							if(getActivity()!=null && !getActivity().isFinishing()) getActivity().recreate();
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
		if(pager!=null) pager.setCurrentItem(0, !reduceMotion && ValueAnimator.areAnimatorsEnabled());
	}

	@Override
	public void onApplyWindowInsets(WindowInsets insets){
		// 时间线内的交友宇宙 fragment 不在 view 树上自动收到 insets，显式转发
		int top=insets.getSystemWindowInsetTop();
		if(fragments!=null){
			for(Fragment f:fragments){
				if(f instanceof FriendRequestListFragment frf)
					frf.setTopInset(top);
			}
		}
		// AppKit consumes this inset in its root. Content clearance is measured in window
		// coordinates after layout, not added to another fixed toolbar slot here.
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
		// The fan overlay is a sibling of the FAB and must stay below it so the cross stays tappable.
		fab.setTranslationZ(V.dp(2));
		fanMenu = new ComposeFanMenuController(getContext(), this::openComposeAction, this::onFanMenuExpandedChanged);
		fanMenu.getView().setTranslationZ(V.dp(1));
		view.addView(fanMenu.getView(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		pager = new ViewPager2(getContext());
		toolbarLogo=(ImageView)inflater.inflate(R.layout.home_toolbar, getToolbar(), false);

		Bundle args = new Bundle();
		args.putString("account", accountID);
		args.putBoolean("__is_tab", true);
		args.putBoolean("__disable_fab", true);
		args.putBoolean("onlyPosts", true);
		// Fragment containers must be attached to the activity hierarchy so that
		// findFragmentById can resolve them, but they must not be direct children of `view`:
		// ViewPager2's onCreateViewHolder removes each tabView from its parent while the
		// surrounding FrameLayout is laying out, which corrupts the parent's child list.
		FrameLayout tabHost=new FrameLayout(getContext());
		tabHost.setVisibility(View.GONE);
		view.addView(tabHost, new FrameLayout.LayoutParams(0,0));
		FragmentTransaction transaction = null;
		visibleTimelineIndices.clear();
		for (int i = 0; i < count; i++) {
			int containerId=i + 1;
			timelines[i]=timelinesList.get(i);
			if(timelines[i].getType()!=TimelineDefinition.TimelineType.NBW || nbwTimelineVisible()) visibleTimelineIndices.add(i);
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
			tabHost.addView(tabView);
			tabViews[i] = tabView;
		}
		if(transaction!=null)
			transaction.commit();

		int tabsHeight=V.dp(HomeTimelineTabStrip.HEIGHT_DP);
		FrameLayout.LayoutParams pagerParams=new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
		pagerParams.topMargin=tabsHeight;
		view.addView(pager, pagerParams);

		timelineTabs=new HomeTimelineTabStrip(getContext(), this::navigateTo, this::openTimelineEditor);
		view.addView(timelineTabs, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tabsHeight));

		overflowActionView = UiUtils.makeOverflowActionView(getContext());
		overflowPopup = new PopupMenu(getContext(), overflowActionView);
		overflowPopup.setOnMenuItemClickListener(this::onOptionsItemSelected);
		overflowActionView.setOnClickListener(l -> overflowPopup.show());
		overflowActionView.setOnTouchListener(overflowPopup.getDragToOpenListener());

		setFabBottomInset(0);

		return rootView;
	}

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public void onViewCreated(View view, Bundle savedInstanceState){
		super.onViewCreated(view, savedInstanceState);
		toolbarGeometryRoot=view;
		view.getViewTreeObserver().addOnPreDrawListener(toolbarGeometry);

		UiUtils.reduceSwipeSensitivity(pager);
		pager.setUserInputEnabled(!GlobalUserPreferences.disableSwipe);
		pager.setAdapter(new HomePagerAdapter());
		buildTimelineTabs();
		pagerCallback=new ViewPager2.OnPageChangeCallback(){
			@Override
			public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels){
				timelineTabs.setScrollPosition(position+positionOffset);
			}

			@Override
			public void onPageSelected(int position){
				if(position>=visibleTimelineIndices.size()) return;
				int index=visibleTimelineIndices.get(position);
				timelineTabs.setSelectedPage(position, !reduceMotion);
				if(position!=0) {
					addBackCallback(returnToBeginningOfPager);
				} else {
					removeBackCallback(returnToBeginningOfPager);
				}

				showFab();
				updateLiquidToolbarState();
				if (fragments[index] instanceof BaseRecyclerFragment<?> page){
					if(!page.loaded && !page.isDataLoading()) page.loadData();
				}
			}
		};
		pager.registerOnPageChangeCallback(pagerCallback);
		nbwSubscription=AccountSessionManager.get(accountID).getNBWForumOptionsRepository().observe(snapshot->{
			if(getActivity()!=null) getActivity().runOnUiThread(this::updateNBWVisiblePages);
		});

		updateToolbarLogo();
		updateLiquidToolbarMode();
		updateLiquidToolbarState();

		elevationOnScrollListener = new ElevationOnScrollListener((FragmentRootLinearLayout) view, getToolbar());

		if(GithubSelfUpdater.isSupported()){
			updateUpdateState(GithubSelfUpdater.getInstance().getState());
		}

		view.postDelayed(()->loadMenuMetadata(view), 600);
	}

	private void loadMenuMetadata(View view){
		if(getView()!=view) return;
		AccountSession session=AccountSessionManager.getInstance().tryGetAccount(accountID);
		if(session==null || !accountID.equals(AccountSessionManager.getInstance().getLastActiveAccountID())) return;
		session.getCacheController().getLists(new Callback<>() {
			@Override
			public void onSuccess(List<FollowList> lists) {
				if(getView()==view && ContentAccessCoordinator.canRead(accountID)) updateList(lists, listItems);
			}

			@Override
			public void onError(ErrorResponse error) {
				android.util.Log.w("HomeTab", "lists metadata failed: "+error);
			}
		});

		new GetFollowedTags(null, 200).setCallback(new Callback<>() {
			@Override
			public void onSuccess(HeaderPaginationList<Hashtag> hashtags) {
				if(getView()==view && ContentAccessCoordinator.allowsContent(accountID)) updateList(hashtags, hashtagsItems);
			}

			@Override
			public void onError(ErrorResponse error) {
				android.util.Log.w("HomeTab", "followed tags metadata failed: "+error);
			}
		}).exec(accountID);

		new GetAnnouncements(false).setCallback(new Callback<>() {
			@Override
			public void onSuccess(List<Announcement> result) {
				if(getView()!=view || !accountID.equals(AccountSessionManager.getInstance().getLastActiveAccountID())) return;
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
				android.util.Log.w("HomeTab", "announcements metadata failed: "+error);
			}
		}).exec(accountID);
	}

	public ElevationOnScrollListener getElevationOnScrollListener() {
		return elevationOnScrollListener;
	}

	public void setFabBottomInset(int insetPx){
		fabBottomInset=insetPx;
		if(fab!=null){
			FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams) fab.getLayoutParams();
			lp.bottomMargin=V.dp(16)+fabBottomInset;
			fab.setLayoutParams(lp);
		}
		if(fanMenu!=null)
			fanMenu.setBottomInset(insetPx);
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
		if(fanMenu==null)
			return;
		if(fanMenu.isExpanded())
			fanMenu.collapse();
		else
			fanMenu.expand();
	}

	/** Keeps the plus→cross rotation, the back gesture and the pager in sync with the fan state. */
	private void onFanMenuExpandedChanged(boolean expanded){
		animateFabIcon(expanded);
		if(expanded)
			addBackCallback(collapseFanMenuOnBack);
		else
			removeBackCallback(collapseFanMenuOnBack);
	}

	/** Folds the fan away; the icon rotation follows from [onFanMenuExpandedChanged]. */
	private void collapseFanMenu(){
		if(fanMenu!=null)
			fanMenu.collapseIfExpanded();
	}

	private void animateFabIcon(boolean open){
		if(fab==null)
			return;
		Drawable drawable=fab.getDrawable();
		if(!(drawable instanceof RotateDrawable))
			return;
		ValueAnimator animator=ValueAnimator.ofInt(((RotateDrawable) drawable).getLevel(), open ? 10000 : 0);
		animator.setDuration(reduceMotion ? 0 : 220);
		animator.setInterpolator(new DecelerateInterpolator());
		animator.addUpdateListener(a->{
			Drawable current=fab.getDrawable();
			if(current instanceof RotateDrawable)
				((RotateDrawable) current).setLevel((int) a.getAnimatedValue());
		});
		animator.start();
	}

	private boolean onFabLongClick(View v) {
		if (fragments[currentTimelineIndex()] instanceof BaseStatusListFragment<?> l) {
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

	private void updateToolbarLogo(){
		Toolbar toolbar=getToolbar();
		if(toolbarLogo.getParent()==toolbar) return;
		detachToolbarLogoCenter();
		if(toolbarLogo.getParent() instanceof ViewGroup previous) previous.removeView(toolbarLogo);
		// Toolbar centers custom children within its consumed content insets. Correct using
		// the child's real layout position so menu items and system insets cannot shift the mark.
		int size=getResources().getDimensionPixelSize(R.dimen.home_brand_logo_size);
		toolbar.addView(toolbarLogo, new Toolbar.LayoutParams(size,size,android.view.Gravity.CENTER));
		View.OnLayoutChangeListener center=(v,l,t,r,b,oldL,oldT,oldR,oldB)->centerToolbarLogo();
		toolbarLogo.addOnLayoutChangeListener(center);
		toolbar.addOnLayoutChangeListener(center);
		toolbarLogoCenter=center;
		centerToolbarLogo();
		toolbar.setOnClickListener(v->scrollToTop());
		toolbar.setNavigationContentDescription(R.string.back);
	}

	private void detachToolbarLogoCenter(){
		if(toolbarLogo==null || toolbarLogoCenter==null) return;
		toolbarLogo.removeOnLayoutChangeListener(toolbarLogoCenter);
		if(toolbarLogo.getParent() instanceof View parent) parent.removeOnLayoutChangeListener(toolbarLogoCenter);
		toolbarLogoCenter=null;
	}

	private void centerToolbarLogo(){
		if(toolbarLogo==null || !(toolbarLogo.getParent() instanceof Toolbar toolbar) || !toolbar.isAttachedToWindow()) return;
		View window=toolbar.getRootView();
		int[] xy=new int[2];
		window.getLocationInWindow(xy);
		float windowCenter=xy[0]+window.getWidth()*.5f;
		toolbar.getLocationInWindow(xy);
		// The source SVG's ink center is at 50.55% x, 49.60% y of its artboard.
		toolbarLogo.setTranslationX(windowCenter-xy[0]-toolbarLogo.getLeft()-toolbarLogo.getWidth()*.5055f);
		toolbarLogo.setTranslationY(toolbarLogo.getHeight()*.004f);
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
		if(liquid)
			collapseFanMenu();
		Toolbar toolbar=getToolbar();
		if(toolbar!=null)
			toolbar.setVisibility(liquid ? View.GONE : View.VISIBLE);
		if(fab!=null)
			fab.setVisibility(liquid ? View.GONE : View.VISIBLE);
		if(timelineTabs!=null) timelineTabs.setVisibility(liquid ? View.GONE : View.VISIBLE);
		if(pager!=null && pager.getLayoutParams() instanceof FrameLayout.LayoutParams pagerParams){
			int topMargin=liquid ? 0 : V.dp(HomeTimelineTabStrip.HEIGHT_DP);
			if(pagerParams.topMargin!=topMargin){
				pagerParams.topMargin=topMargin;
				pager.setLayoutParams(pagerParams);
			}
		}
		if(fragments!=null){
			for(Fragment fragment:fragments){
				if(fragment instanceof BaseStatusListFragment<?> statusListFragment){
					statusListFragment.setLiquidToolbarFabHidden(liquid);
					statusListFragment.setLiquidNavBottomPadding(liquid ? liquidNavBottomInset : 0);
				}
				if(fragment instanceof FriendRequestListFragment friendRequestListFragment){
					friendRequestListFragment.setLiquidNavBottomPadding(liquid ? liquidNavBottomInset : 0);
				}
				if(fragment instanceof HomeTimelineFragment homeTimelineFragment)
					homeTimelineFragment.setLiquidToolbarMode(liquid);
			}
		}
	}

	/** Only the measured glass can occlude a liquid timeline. Its ancestor may already consume
	 * all or part of the status bar, so subtract the list's real window origin instead of adding
	 * an inset, TOP_SLOT or capture margin. The native refresh indicator positions itself separately. */
	private boolean updateTimelineTopGeometry(){
		if(fragments==null) return true;
		android.graphics.Rect glass=new android.graphics.Rect();
		boolean liquid=liquidToolbarController!=null && liquidToolbarController.getVisibleBoundsInWindow(glass);
		int gap=getResources().getDimensionPixelSize(R.dimen.home_toolbar_content_gap);
		for(Fragment fragment:fragments){
			if(fragment instanceof HomeTimelineFragment || fragment.getView()==null) continue;
			RecyclerView list=findTimelineRecycler(fragment.getView());
			if(list==null || !list.isAttachedToWindow()) continue;
			int padding=0;
			if(liquid){
				int[] origin=new int[2];
				list.getLocationInWindow(origin);
				if(glass.left<origin[0]+list.getWidth() && glass.right>origin[0])
					padding=Math.max(0, glass.bottom+gap-origin[1]);
			}
			if(padding>=list.getHeight()) padding=0;
			if(list.getPaddingTop()==padding) continue;
			if(fragment instanceof BaseStatusListFragment<?> status)
				status.setLiquidToolbarTopPadding(padding);
			else if(fragment instanceof FriendRequestListFragment friends)
				friends.setLiquidToolbarTopPadding(padding);
		}
		return true;
	}

	private static RecyclerView findTimelineRecycler(View view){
		if(view instanceof RecyclerView recycler) return recycler;
		if(view instanceof ViewGroup group) for(int i=0;i<group.getChildCount();i++){
			RecyclerView found=findTimelineRecycler(group.getChildAt(i));
			if(found!=null) return found;
		}
		return null;
	}

	/** 液态底部导航条 overlay 高度（px），由 HomeFragment 在导航布局尺寸变化时转发 */
	public void setLiquidNavBottomInset(int px){
		if(liquidNavBottomInset==px)
			return;
		liquidNavBottomInset=px;
		updateLiquidToolbarMode();
	}

	public boolean hasVisibleLiquidToolbar(){
		return liquidToolbarController!=null && liquidToolbarController.getVisibleBoundsInWindow(new android.graphics.Rect());
	}

	/** Window geometry for the refresh indicator; never use the full-screen glass host's height. */
	public boolean getVisibleHomeToolbarBoundsInWindow(android.graphics.Rect out){
		out.setEmpty();
		Toolbar toolbar=getToolbar();
		if(toolbar!=null && toolbar.isAttachedToWindow() && toolbar.isShown()){
			android.graphics.Rect visible=new android.graphics.Rect();
			if(toolbar.getGlobalVisibleRect(visible)){
				int[] rootInWindow=new int[2];
				toolbar.getRootView().getLocationInWindow(rootInWindow);
				// Global visible bounds are in root coordinates, not physical screen coordinates.
				visible.offset(rootInWindow[0], rootInWindow[1]);
				out.union(visible);
			}
		}
		android.graphics.Rect glass=new android.graphics.Rect();
		if(liquidToolbarController!=null && liquidToolbarController.getVisibleBoundsInWindow(glass))
			out.union(glass);
		return !out.isEmpty();
	}

	private void updateLiquidToolbarState(){
		if(liquidToolbarController==null || timelines==null)
			return;
		ArrayList<HomeToolbarTimeline> toolbarTimelines=new ArrayList<>();
		for(int i=0;i<timelines.length;i++){
			TimelineDefinition timeline=timelines[i];
			if(timeline!=null && (timeline.getType()!=TimelineDefinition.TimelineType.NBW || nbwTimelineVisible()))
				toolbarTimelines.add(new HomeToolbarTimeline(i, timeline.getTitle(getContext()), timeline.getIcon().iconRes));
		}
		liquidToolbarController.setTimelines(toolbarTimelines, currentTimelineIndex());
		liquidToolbarController.setShowNewPosts(getCurrentFragment() instanceof HomeTimelineFragment home && home.hasNewPosts());
		liquidToolbarController.setReminderState(announcementsBadged, settingsBadged);
		ArrayList<HomeToolbarMenuItem> root=new ArrayList<>();
		root.add(new HomeToolbarMenuItem(R.id.settings, getString(R.string.settings), R.drawable.ic_fluent_settings_24_regular));
		root.add(new HomeToolbarMenuItem(R.id.announcements, getString(R.string.sk_announcements), R.drawable.ic_fluent_megaphone_24_regular));
		root.add(new HomeToolbarMenuItem(R.id.edit_timelines, getString(R.string.sk_edit_timelines), R.drawable.ic_fluent_edit_24_regular));
		root.add(new HomeToolbarMenuItem(R.id.novel, getString(R.string.novel), R.drawable.ic_novel_rebuilt));
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
		if(getCurrentFragment() instanceof HomeTimelineFragment home) home.scrollToNewPosts();
	}

	void onHomeNewPostsChanged(){
		if(liquidToolbarController!=null)
			liquidToolbarController.setShowNewPosts(getCurrentFragment() instanceof HomeTimelineFragment home && home.hasNewPosts());
	}

	/** Shared by the home fan, fallback sheet and liquid compose menu. */
	public void openComposeAction(int id){
		if(id==R.id.compose_novel){
			if(getActivity()!=null)
				startActivity(NovelAppActivity.creatorIntent(getActivity(), accountID));
			return;
		}
		Class<? extends Fragment> destination;
		if(id==R.id.compose_post)
			destination=ComposeFragment.class;
		else if(id==R.id.compose_friend_request)
			destination=FriendRequestCreateFragment.class;
		else if(id==R.id.compose_friend_card)
			destination=MyFriendCardsFragment.class;
		else if(id==R.id.compose_album)
			destination=org.joinmastodon.android.fragments.albums.AlbumUploadFragment.class;
		else
			return;
		if(getActivity()==null)
			return;
		Bundle args=new Bundle();
		args.putString("account", accountID);
		Nav.go(getActivity(), destination, args);
	}

	public void onLiquidCompose(){
		openComposeAction(R.id.compose_post);
	}

	public void onLiquidMenuItem(int id){
		if(id==R.id.novel){
			startActivity(NovelAppActivity.intent(getActivity(), accountID));
			return;
		}
		if(id==R.id.scan_qr){
			openQrScanner();
			return;
		}
		if(id==R.id.compose_post || id==R.id.compose_friend_request || id==R.id.compose_friend_card || id==R.id.compose_album || id==R.id.compose_novel){
			openComposeAction(id);
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

	private void buildTimelineTabs(){
		List<HomeTimelineTabStrip.Tab> tabs=new ArrayList<>(visibleTimelineIndices.size());
		for(int index:visibleTimelineIndices){
			TimelineDefinition definition=timelines[index];
			TimelineDefinition.Icon icon=definition.getExplicitIcon();
			if(icon==null){
				icon=switch(definition.getType()){
					case LIST, HASHTAG, NBW, CUSTOM_LOCAL_TIMELINE -> definition.getDefaultIcon();
					default -> null;
				};
			}
			tabs.add(new HomeTimelineTabStrip.Tab(index, definition.getTitle(getContext()), icon==null ? 0 : icon.iconRes,
					icon!=TimelineDefinition.Icon.NBW && icon!=TimelineDefinition.Icon.GNOME, definition.getExplicitIcon()!=null));
		}
		timelineTabs.setTabs(tabs, pager.getCurrentItem());
	}

	private void navigateTo(int i) {
		navigateTo(i, !reduceMotion && ValueAnimator.areAnimatorsEnabled());
	}

	private void navigateTo(int i, boolean smooth) {
		int visible=visibleTimelineIndices.indexOf(i);
		if(visible<0) return;
		pager.setCurrentItem(visible, smooth);
	}

	private void openTimelineEditor(){
		Bundle args=new Bundle();
		args.putString("account", accountID);
		Nav.go(getActivity(), EditTimelinesFragment.class, args);
	}

	@Override
	public void showFab() {
		if (fragments[currentTimelineIndex()] instanceof BaseStatusListFragment<?> l) l.showFab();
		updateLiquidToolbarMode();
	}

	@Override
	public void hideFab() {
		// The FAB cannot slide away while it anchors the open fan.
		collapseFanMenu();
		if (fragments[currentTimelineIndex()] instanceof BaseStatusListFragment<?> l) l.hideFab();
		updateLiquidToolbarMode();
	}

	@Override
	public boolean isScrolling() {
		return (fragments[currentTimelineIndex()] instanceof HasFab fabulous)
				&& fabulous.isScrolling();
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		if (elevationOnScrollListener != null) elevationOnScrollListener.setViews(getToolbar());
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
		} else if (id == R.id.scan_qr) {
			openQrScanner();
		} else if (id == R.id.settings || id == R.id.settings_action) {
			Nav.go(getActivity(), SettingsMainFragment.class, args);
		} else if (id == R.id.announcements || id == R.id.announcements_action) {
			Nav.goForResult(getActivity(), AnnouncementsFragment.class, args, ANNOUNCEMENTS_RESULT, this);
		} else if (id == R.id.edit_timelines) {
			openTimelineEditor();
		} else if (id == R.id.novel) {
			startActivity(NovelAppActivity.intent(getActivity(), accountID));
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
		Fragment current=getCurrentFragment();
		if(current instanceof HomeTimelineFragment home && home.hasNewPosts()){
			home.scrollToNewPosts();
		}else if(current instanceof IsOnTop onTop && onTop.isOnTop() && pager!=null
				&& GlobalUserPreferences.doubleTapToSwipe && !visibleTimelineIndices.isEmpty()){
			int nextPage=(pager.getCurrentItem()+1)%visibleTimelineIndices.size();
			navigateTo(visibleTimelineIndices.get(nextPage));
		}else if(current instanceof ScrollableToTop scrollable){
			scrollable.scrollToTop();
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
		if(nbwSubscription!=null){ nbwSubscription.cancel(); nbwSubscription=null; }
		if(pager!=null && pagerCallback!=null) pager.unregisterOnPageChangeCallback(pagerCallback);
		pagerCallback=null;
		pager=null;
		timelineTabs=null;
		removeBackCallback(returnToBeginningOfPager);
		liquidToolbarController=null;
		if(toolbarGeometryRoot!=null && toolbarGeometryRoot.getViewTreeObserver().isAlive())
			toolbarGeometryRoot.getViewTreeObserver().removeOnPreDrawListener(toolbarGeometry);
		toolbarGeometryRoot=null;
		if(fanMenu!=null){
			if(fanMenu.isExpanded())
				removeBackCallback(collapseFanMenuOnBack);
			fanMenu=null;
		}
		if (overflowPopup != null) {
			overflowPopup.dismiss();
			overflowPopup = null;
		}
		detachToolbarLogoCenter();
		toolbarLogo=null;
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
		if(savedInstanceState==null || visibleTimelineIndices.isEmpty()) return;
		int definition=savedInstanceState.getInt("selectedTimelineId", -1);
		if(definition<0){
			int position=Math.max(0, Math.min(savedInstanceState.getInt("selectedTab"), visibleTimelineIndices.size()-1));
			definition=visibleTimelineIndices.get(position);
		}
		if(!visibleTimelineIndices.contains(definition)) definition=visibleTimelineIndices.get(0);
		navigateTo(definition, false);
		timelineTabs.setSelectedPage(pager.getCurrentItem(), false);
		timelineTabs.setScrollPosition(pager.getCurrentItem());
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		if(pager!=null && !visibleTimelineIndices.isEmpty()) outState.putInt("selectedTimelineId", currentTimelineIndex());
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
		return fragments[currentTimelineIndex()];
	}

	public ImageButton getFab() {
		return fab;
	}

	@Override
	public void onProvideAssistContent(AssistContent assistContent) {
		callFragmentToProvideAssistContent(fragments[currentTimelineIndex()], assistContent);
	}

	private class HomePagerAdapter extends RecyclerView.Adapter<SimpleViewHolder> {
		@NonNull
		@Override
		public SimpleViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			FrameLayout tabView = tabViews[viewType];
			ViewGroup tabParent = (ViewGroup) tabView.getParent();
			if (tabParent != null) tabParent.removeView(tabView);
			tabView.setVisibility(View.VISIBLE);
			return new SimpleViewHolder(tabView);
		}

		@Override
		public void onBindViewHolder(@NonNull SimpleViewHolder holder, int position){}

		@Override
		public int getItemCount(){
			return visibleTimelineIndices.size();
		}

		@Override
		public int getItemViewType(int position){
			return visibleTimelineIndices.get(position);
		}
	}
}
