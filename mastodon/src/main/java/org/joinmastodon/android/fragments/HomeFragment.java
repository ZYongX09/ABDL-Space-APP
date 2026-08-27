package org.joinmastodon.android.fragments;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Fragment;
import android.app.NotificationManager;
import android.app.assist.AssistContent;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.squareup.otto.Subscribe;

import org.joinmastodon.android.BuildConfig;
import org.joinmastodon.android.E;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.PushNotificationReceiver;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.notifications.GetNotificationsV1;
import org.joinmastodon.android.api.requests.notifications.GetUnreadNotificationsCount;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.events.NotificationsMarkerUpdatedEvent;
import org.joinmastodon.android.events.StatusDisplaySettingsChangedEvent;
import org.joinmastodon.android.fragments.diapers.DiaperListFragment;
import org.joinmastodon.android.fragments.discover.DiscoverFragment;
import org.joinmastodon.android.fragments.onboarding.OnboardingFollowSuggestionsFragment;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Instance;
import org.joinmastodon.android.model.Notification;
import org.joinmastodon.android.model.NotificationType;
import org.joinmastodon.android.ui.M3AlertDialogBuilder;
import org.joinmastodon.android.ui.OutlineProviders;
import org.joinmastodon.android.ui.compose.navigation.HomeLiquidNavigationController;
import org.joinmastodon.android.ui.compose.navigation.HomeLiquidToolbarController;
import org.joinmastodon.android.ui.compose.navigation.FriendUniverseLiquidToolbarController;
import org.joinmastodon.android.ui.sheets.AccountSwitcherSheet;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.joinmastodon.android.ui.views.BackdropCaptureFrameLayout;
import org.joinmastodon.android.ui.views.TabBar;
import org.joinmastodon.android.utils.ObjectIdComparator;
import org.parceler.Parcels;

import static org.joinmastodon.android.ui.compose.navigation.HomeLiquidToolbarModelKt.homeToolbarCaptureHeightDp;
import static org.joinmastodon.android.ui.compose.navigation.FriendUniverseToolbarModelKt.friendUniverseCaptureHeightDp;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import androidx.annotation.IdRes;
import androidx.annotation.Nullable;
import me.grishka.appkit.FragmentStackActivity;
import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.fragments.AppKitFragment;
import me.grishka.appkit.fragments.LoaderFragment;
import me.grishka.appkit.imageloader.ViewImageLoader;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.V;
import me.grishka.appkit.views.FragmentRootLinearLayout;

public class HomeFragment extends AppKitFragment implements AssistContentProviderFragment, HasAccountID {
	private static final String DIAPER_FEATURE_VERSION="2.3.0";
	private static final String DIAPER_FEATURE_SEEN_KEY="diaperFeatureSeen_"+DIAPER_FEATURE_VERSION;
	private static final String FEATURE_DIALOG_SEEN_KEY="featureDialogSeen_"+DIAPER_FEATURE_VERSION;
	private FragmentRootLinearLayout content;
	private HomeTabFragment homeTabFragment;
	private DiscoverFragment searchFragment;
	private ProfileFragment profileFragment;
	private FriendRequestListFragment friendRequestFragment;
	private DiaperListFragment diaperListFragment;
	private BackdropCaptureFrameLayout fragmentContainer;
	private FrameLayout navigationHost;
	private FrameLayout toolbarHost;
	private TabBar tabBar;
	private View tabBarWrap;
	private ImageView tabBarAvatar;
	private HomeLiquidNavigationController liquidNavigationController;
	private HomeLiquidToolbarController liquidToolbarController;
	private FriendUniverseLiquidToolbarController friendLiquidToolbarController;
	private int bottomSystemInset;
	private int topSystemInset;
	private boolean liquidToolbarMenuOpen;
	@IdRes
	private int currentTab=R.id.tab_home;
	private TextView notificationsBadge;
	private TextView diaperNewFeatureBadge;
	private String unreadNotificationsBadgeText;
	private boolean diaperFeatureBadgeVisible;
	private AlertDialog featureDialog;
	private AlertDialog autoStartGuideDialog;

	private String accountID;

	@Override
	public void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		accountID=getArguments().getString("account");
		setTitle(R.string.app_name);

		if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.N)
			setRetainInstance(true);

		if(savedInstanceState==null){
			Bundle args=new Bundle();
			args.putString("account", accountID);
			homeTabFragment=new HomeTabFragment();
			homeTabFragment.setArguments(args);
			args=new Bundle(args);
			args.putBoolean("noAutoLoad", true);
			searchFragment=new DiscoverFragment();
			searchFragment.setArguments(args);
			friendRequestFragment=new FriendRequestListFragment();
			friendRequestFragment.setArguments(args);
			args=new Bundle(args);
		diaperListFragment=new DiaperListFragment();
		diaperListFragment.setArguments(args);
		args=new Bundle(args);
			args.putParcelable("profileAccount", Parcels.wrap(AccountSessionManager.getInstance().getAccount(accountID).self));
			args.putBoolean("noAutoLoad", true);
			profileFragment=new ProfileFragment();
			profileFragment.setArguments(args);
		}

		E.register(this);
	}

	@Override
	public void onDestroy(){
		super.onDestroy();
		E.unregister(this);
	}

	@Override
	public void onDestroyView(){
		if(liquidNavigationController!=null){
			liquidNavigationController.dispose();
			liquidNavigationController=null;
		}
		if(liquidToolbarController!=null){
			liquidToolbarController.dispose();
			liquidToolbarController=null;
		}
		if(friendLiquidToolbarController!=null){
			friendLiquidToolbarController.dispose();
			friendLiquidToolbarController=null;
		}
		if(homeTabFragment!=null)
			homeTabFragment.setLiquidToolbarController(null);
		if(friendRequestFragment!=null)
			friendRequestFragment.setLiquidToolbarController(null);
		if(featureDialog!=null){
			featureDialog.dismiss();
			featureDialog=null;
		}
		if(autoStartGuideDialog!=null){
			autoStartGuideDialog.dismiss();
			autoStartGuideDialog=null;
		}
		content=null;
		navigationHost=null;
		toolbarHost=null;
		fragmentContainer=null;
		tabBar=null;
		tabBarWrap=null;
		tabBarAvatar=null;
		notificationsBadge=null;
		diaperNewFeatureBadge=null;
		super.onDestroyView();
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState){
		content=new FragmentRootLinearLayout(getActivity());
		content.setOrientation(LinearLayout.VERTICAL);
		FrameLayout homeLayout=new FrameLayout(getActivity());
		content.addView(homeLayout, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

		fragmentContainer=new BackdropCaptureFrameLayout(getActivity());
		fragmentContainer.setId(me.grishka.appkit.R.id.fragment_wrap);
		homeLayout.addView(fragmentContainer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

		toolbarHost=new FrameLayout(getActivity());
		FrameLayout.LayoutParams toolbarLayoutParams=new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP);
		homeLayout.addView(toolbarHost, toolbarLayoutParams);

		navigationHost=new FrameLayout(getActivity());
		FrameLayout.LayoutParams navigationLayoutParams=new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
		homeLayout.addView(navigationHost, navigationLayoutParams);
		navigationHost.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom)->{
			if(fragmentContainer!=null)
				updateCaptureHeights();
		});
		createNavigationBar(inflater);
		if(savedInstanceState==null)
			createLiquidToolbar();

		if(savedInstanceState==null){
			getChildFragmentManager().beginTransaction()
					.add(me.grishka.appkit.R.id.fragment_wrap, homeTabFragment)
					.add(me.grishka.appkit.R.id.fragment_wrap, searchFragment).hide(searchFragment)
				.add(me.grishka.appkit.R.id.fragment_wrap, friendRequestFragment).hide(friendRequestFragment)
				.add(me.grishka.appkit.R.id.fragment_wrap, diaperListFragment).hide(diaperListFragment)
					.add(me.grishka.appkit.R.id.fragment_wrap, profileFragment).hide(profileFragment)
					.commit();

			String defaultTab=getArguments().getString("tab");
			if("notifications".equals(defaultTab)){
				fragmentContainer.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener(){
					@Override
					public boolean onPreDraw(){
						fragmentContainer.getViewTreeObserver().removeOnPreDrawListener(this);
						Bundle args=new Bundle();
						args.putString("account", accountID);
						Nav.go(getActivity(), NotificationsListFragment.class, args);
						return true;
					}
				});
			}
		}
		selectTabInNavigation(currentTab);

		return content;
	}

	@Override
	public void onViewStateRestored(Bundle savedInstanceState){
		super.onViewStateRestored(savedInstanceState);

		// MOSHIDON: we must restore the homeTabFragment
		if(savedInstanceState==null /*|| homeTabFragment!=null*/)
			return;
		homeTabFragment=(HomeTabFragment) getChildFragmentManager().getFragment(savedInstanceState, "homeTabFragment");
		searchFragment=(DiscoverFragment) getChildFragmentManager().getFragment(savedInstanceState, "searchFragment");
		friendRequestFragment=(FriendRequestListFragment) getChildFragmentManager().getFragment(savedInstanceState, "friendRequestFragment");
		diaperListFragment=(DiaperListFragment) getChildFragmentManager().getFragment(savedInstanceState, "diaperListFragment");
		profileFragment=(ProfileFragment) getChildFragmentManager().getFragment(savedInstanceState, "profileFragment");
		createLiquidToolbar();
		currentTab=savedInstanceState.getInt("selectedTab");
		selectTabInNavigation(currentTab);
		updateLiquidToolbarVisibility();
		Fragment current=fragmentForTab(currentTab);
		getChildFragmentManager().beginTransaction()
				.hide(homeTabFragment)
				.hide(searchFragment)
				.hide(friendRequestFragment)
				.hide(diaperListFragment)
				.hide(profileFragment)
				.show(current)
				.commit();
		maybeTriggerLoading(current);
	}

	@Override
	public void onHiddenChanged(boolean hidden){
		super.onHiddenChanged(hidden);
		fragmentForTab(currentTab).onHiddenChanged(hidden);
	}

	@Override
	public boolean wantsLightStatusBar(){
		return !UiUtils.isDarkTheme();
	}

	@Override
	public boolean wantsLightNavigationBar(){
		return !UiUtils.isDarkTheme();
	}

	@Override
	public void onApplyWindowInsets(WindowInsets insets){
		bottomSystemInset=insets.getSystemWindowInsetBottom();
		topSystemInset=insets.getSystemWindowInsetTop();
		applyNavigationBottomInset();
		applyLiquidToolbarInsets();
		super.onApplyWindowInsets(insets.replaceSystemWindowInsets(insets.getSystemWindowInsetLeft(), 0, insets.getSystemWindowInsetRight(), 0));
		WindowInsets topOnlyInsets=insets.replaceSystemWindowInsets(0, insets.getSystemWindowInsetTop(), 0, 0);
		homeTabFragment.onApplyWindowInsets(topOnlyInsets);
		searchFragment.onApplyWindowInsets(topOnlyInsets);
		friendRequestFragment.onApplyWindowInsets(topOnlyInsets);
		diaperListFragment.onApplyWindowInsets(topOnlyInsets);
		profileFragment.onApplyWindowInsets(topOnlyInsets);
	}

	private Fragment fragmentForTab(@IdRes int tab){
		if(tab==R.id.tab_home){
			return homeTabFragment;
		}else if(tab==R.id.tab_search){
			return searchFragment;
		}else if(tab==R.id.tab_friend_request){
			return friendRequestFragment;
		}else if(tab==R.id.tab_diaper){
			return diaperListFragment;
		}else if(tab==R.id.tab_profile){
			return profileFragment;
		}
		throw new IllegalArgumentException();
	}

	public void setCurrentTab(@IdRes int tab){
		if(tab==currentTab)
			return;
		selectTabInNavigation(tab);
		onTabSelected(tab);
	}

	private void onTabSelected(@IdRes int tab){
		Fragment newFragment=fragmentForTab(tab);
		if(tab==R.id.tab_diaper)
			markDiaperFeatureSeen();

		// MOSHIDON:
		if(tab==R.id.tab_search && R.id.tab_search==currentTab){
			searchFragment.openSearch();
		}

		if(tab==currentTab){
			if(newFragment instanceof ScrollableToTop scrollable)
				scrollable.scrollToTop();
			return;
		}

		getChildFragmentManager()
			.beginTransaction()
			.hide(fragmentForTab(currentTab))
			.show(newFragment)
			.commitNow();
		maybeTriggerLoading(newFragment);
		currentTab=tab;
		updateLiquidToolbarVisibility();
		((FragmentStackActivity)getActivity()).invalidateSystemBarColors(this);
	}

	private void updateDiaperNewFeatureBadge(){
		String version=BuildConfig.VERSION_NAME.split("-", 2)[0];
		diaperFeatureBadgeVisible=DIAPER_FEATURE_VERSION.equals(version)
				&& !GlobalUserPreferences.getPrefs().getBoolean(DIAPER_FEATURE_SEEN_KEY, false);
		if(liquidNavigationController!=null)
			liquidNavigationController.setDiaperBadgeVisible(diaperFeatureBadgeVisible);
		else if(diaperNewFeatureBadge!=null)
			diaperNewFeatureBadge.setVisibility(diaperFeatureBadgeVisible ? View.VISIBLE : View.GONE);
	}

	private void markDiaperFeatureSeen(){
		if(!diaperFeatureBadgeVisible)
			return;
		GlobalUserPreferences.getPrefs().edit().putBoolean(DIAPER_FEATURE_SEEN_KEY, true).apply();
		diaperFeatureBadgeVisible=false;
		if(liquidNavigationController!=null)
			liquidNavigationController.setDiaperBadgeVisible(false);
		else if(diaperNewFeatureBadge!=null)
			diaperNewFeatureBadge.setVisibility(View.GONE);
	}

	private void maybeTriggerLoading(Fragment newFragment){
		if(newFragment instanceof LoaderFragment lf){
			if(!lf.loaded && !lf.dataLoading)
				lf.loadData();
		}else if(newFragment instanceof DiscoverFragment){
			((DiscoverFragment) newFragment).loadData();
		}
	}

	private boolean onTabLongClick(@IdRes int tab){
		if(tab==R.id.tab_search){
			if(currentTab!=R.id.tab_search){
				// MOSHIDON: I don't know why using setCurrentTab leads to visual glitches
				// when initially loading the fragment. This solves it somehow
				onTabSelected(R.id.tab_search);
				selectTabInNavigation(R.id.tab_search);
			}
			searchFragment.openSearch();
			return true;
		}

		if(tab==R.id.tab_profile){
			ArrayList<String> options=new ArrayList<>();
			for(AccountSession session:AccountSessionManager.getInstance().getLoggedInAccounts()){
				options.add(session.self.displayName+"\n("+session.self.username+"@"+session.domain+")");
			}
			new AccountSwitcherSheet(getActivity(), this).show();
			return true;
		}
		return false;
	}

	@Override
	public void onSaveInstanceState(Bundle outState){
		super.onSaveInstanceState(outState);
		outState.putInt("selectedTab", currentTab);

		// MOSHIDON: we use the isAdded because of user themes
		if (homeTabFragment.isAdded()) getChildFragmentManager().putFragment(outState, "homeTabFragment", homeTabFragment);

		if (searchFragment.isAdded()) getChildFragmentManager().putFragment(outState, "searchFragment", searchFragment);

		if (friendRequestFragment.isAdded()) getChildFragmentManager().putFragment(outState, "friendRequestFragment", friendRequestFragment);

		if (diaperListFragment.isAdded()) getChildFragmentManager().putFragment(outState, "diaperListFragment", diaperListFragment);

		if (profileFragment.isAdded()) getChildFragmentManager().putFragment(outState, "profileFragment", profileFragment);
	}

	@Override
	protected void onShown(){
		super.onShown();
		showFeatureDialogIfNeeded();
		showAutoStartGuideIfNeeded();
		reloadNotificationsForUnreadCount();
	}

	private void showFeatureDialogIfNeeded(){
		if(featureDialog!=null || getActivity()==null)
			return;
		String version=BuildConfig.VERSION_NAME.split("-", 2)[0];
		if(!DIAPER_FEATURE_VERSION.equals(version)
				|| GlobalUserPreferences.getPrefs().getBoolean(FEATURE_DIALOG_SEEN_KEY, false))
			return;

		featureDialog=new M3AlertDialogBuilder(getActivity())
				.setTitle("功能上新啦！")
				.setMessage("欢迎来到2.3.0版本！在此版本中纸尿裤评分与排行榜功能上线APP啦！欢迎各位小宝宝进入纸尿裤列表页给自己穿过的裤裤进行评分，方便其他同好选择优质的裤裤，给圈内发展贡献出自己的力量！")
				.setPositiveButton("关闭", (dialog, which)->
						GlobalUserPreferences.getPrefs().edit().putBoolean(FEATURE_DIALOG_SEEN_KEY, true).apply())
				.setCancelable(false)
				.create();
		featureDialog.setOnDismissListener(dialog->featureDialog=null);
		featureDialog.show();
	}

	private void showAutoStartGuideIfNeeded(){
		if(autoStartGuideDialog!=null || getActivity()==null)
			return;
		org.joinmastodon.android.ui.utils.OemUtils.Vendor vendor=org.joinmastodon.android.ui.utils.OemUtils.detectVendor();
		if(vendor==org.joinmastodon.android.ui.utils.OemUtils.Vendor.OTHER)
			return;
		if(org.joinmastodon.android.ui.utils.OemUtils.isAutoStartGranted())
			return;
		if(GlobalUserPreferences.getPrefs().getBoolean("autoStartGuideShown_"+BuildConfig.VERSION_NAME, false))
			return;
		autoStartGuideDialog=new M3AlertDialogBuilder(getActivity())
				.setTitle("开启实时通知")
				.setMessage("您当前使用的是"+vendor.displayName+"手机，需要允许后台运行才能尽可能保证您能实时收到通知")
				.setPositiveButton("去设置", (dialog, which)->{
					GlobalUserPreferences.getPrefs().edit().putBoolean("autoStartGuideShown_"+BuildConfig.VERSION_NAME, true).apply();
					startActivity(new Intent(getActivity(), org.joinmastodon.android.ui.NotificationGuideActivity.class));
				})
				.setNegativeButton("以后再说", (dialog, which)->
						GlobalUserPreferences.getPrefs().edit().putBoolean("autoStartGuideShown_"+BuildConfig.VERSION_NAME, true).apply())
				.setCancelable(false)
				.create();
		autoStartGuideDialog.setOnDismissListener(dialog->autoStartGuideDialog=null);
		autoStartGuideDialog.show();
	}

	private void reloadNotificationsForUnreadCount(){
		Instance instance=AccountSessionManager.get(accountID).getInstanceInfo();
		if(instance==null)
			return;
		if(instance.getApiVersion()>=2){
			new GetUnreadNotificationsCount(EnumSet.allOf(NotificationType.class), NotificationType.getGroupableTypes())
					.setCallback(new Callback<>(){
						@Override
						public void onSuccess(GetUnreadNotificationsCount.Response result){
							updateUnreadNotificationsBadge(result.count, false);
						}

						@Override
						public void onError(ErrorResponse error){

						}
					})
					.exec(accountID);
		}else{
			List<Notification>[] notifications=new List[]{null};
			String[] marker={null};
			AccountSessionManager.get(accountID).reloadNotificationsMarker(m->{
				marker[0]=m;
				if(notifications[0]!=null){
					updateUnreadCountV1(notifications[0], marker[0]);
				}
			});

			new GetNotificationsV1(null, 40, EnumSet.allOf(NotificationType.class))
					.setCallback(new Callback<>(){
						@Override
						public void onSuccess(List<Notification> result){
							notifications[0]=result;
							if(marker[0]!=null)
								updateUnreadCountV1(notifications[0], marker[0]);
						}

						@Override
						public void onError(ErrorResponse error){}
					}).exec(accountID);
		}
	}

	@SuppressLint("DefaultLocale")
	private void updateUnreadCountV1(List<Notification> notifications, String marker){
		if(notifications.isEmpty() || ObjectIdComparator.INSTANCE.compare(notifications.get(0).id, marker)<=0){
			updateUnreadNotificationsBadge(0, false);
		}else{
			if(ObjectIdComparator.INSTANCE.compare(notifications.get(notifications.size()-1).id, marker)>0){
				updateUnreadNotificationsBadge(notifications.size(), true);
			}else{
				int count=0;
				for(Notification n:notifications){
					if(n.id.equals(marker))
						break;
					count++;
				}
				updateUnreadNotificationsBadge(count, false);
			}
		}
	}

	private void updateUnreadNotificationsBadge(int count, boolean more){
		unreadNotificationsBadgeText=count==0 ? null : String.format(more ? "%d+" : "%d", count);
		if(liquidNavigationController!=null){
			liquidNavigationController.setUnreadBadge(unreadNotificationsBadgeText);
		}else if(notificationsBadge!=null){
			if(count==0){
				notificationsBadge.setVisibility(View.GONE);
			}else{
				notificationsBadge.setVisibility(View.VISIBLE);
				notificationsBadge.setText(unreadNotificationsBadgeText);
			}
		}
		if(profileFragment!=null)
			profileFragment.setUnreadNotificationsBadge(unreadNotificationsBadgeText);
	}

	@Subscribe
	public void onNotificationsMarkerUpdated(NotificationsMarkerUpdatedEvent ev){
		if(!ev.accountID.equals(accountID))
			return;
		if(ev.clearUnread)
			updateUnreadNotificationsBadge(0, false);
	}

	@Subscribe
	public void onStatusDisplaySettingsChanged(StatusDisplaySettingsChangedEvent ev){
		if(!ev.accountID.equals(accountID))
			return;
		if(navigationHost!=null && GlobalUserPreferences.useIosLiquidNavigation!=(liquidNavigationController!=null)){
			createNavigationBar(LayoutInflater.from(getActivity()));
			createLiquidToolbar();
		}

		// FIXME: figure this out
//		if(homeTabFragment.loaded)
//			homeTabFragment.rebuildAllDisplayItems();
	}

	private void createNavigationBar(LayoutInflater inflater){
		if(liquidNavigationController!=null){
			liquidNavigationController.dispose();
			liquidNavigationController=null;
		}
		navigationHost.removeAllViews();
		tabBar=null;
		tabBarWrap=null;
		tabBarAvatar=null;
		notificationsBadge=null;
		diaperNewFeatureBadge=null;

		Account self=AccountSessionManager.getInstance().getAccount(accountID).self;
		if(GlobalUserPreferences.useIosLiquidNavigation){
			liquidNavigationController=new HomeLiquidNavigationController(getActivity(), currentTab, self.avatar, this::onTabSelected, this::onTabLongClick);
			fragmentContainer.postOnAnimation(()->fragmentContainer.postOnAnimation(()->
			fragmentContainer.setCaptureListener((top, bottom)->{
						if(top!=null){
							if(currentTab==R.id.tab_home && liquidToolbarController!=null)
								liquidToolbarController.setBackdropBitmap(top);
							else if(currentTab==R.id.tab_friend_request && friendLiquidToolbarController!=null)
								friendLiquidToolbarController.setBackdropBitmap(top);
						}
						if(liquidNavigationController!=null && bottom!=null)
							liquidNavigationController.setBackdropBitmap(bottom);
					})));
			navigationHost.addView(liquidNavigationController.getView(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		}else{
			fragmentContainer.setCaptureListener(null);
			inflater.inflate(R.layout.tab_bar, navigationHost, true);
			tabBar=navigationHost.findViewById(R.id.tabbar);
			tabBar.setListeners(this::onTabSelected, this::onTabLongClick);
			tabBarWrap=navigationHost.findViewById(R.id.tabbar_wrap);
			tabBarAvatar=tabBar.findViewById(R.id.tab_profile_ava);
			tabBarAvatar.setOutlineProvider(OutlineProviders.OVAL);
			tabBarAvatar.setClipToOutline(true);
			ViewImageLoader.loadWithoutAnimation(tabBarAvatar, null, new UrlImageLoaderRequest(self.avatar, V.dp(24), V.dp(24)));
			notificationsBadge=tabBar.findViewById(R.id.notifications_badge);
			diaperNewFeatureBadge=tabBar.findViewById(R.id.diaper_new_feature_badge);
		}
		selectTabInNavigation(currentTab);
		if(liquidNavigationController!=null)
			liquidNavigationController.setUnreadBadge(unreadNotificationsBadgeText);
		else if(notificationsBadge!=null){
			notificationsBadge.setVisibility(unreadNotificationsBadgeText==null ? View.GONE : View.VISIBLE);
			notificationsBadge.setText(unreadNotificationsBadgeText);
		}
		updateDiaperNewFeatureBadge();
		applyNavigationBottomInset();
		updateCaptureHeights();
	}

	private void createLiquidToolbar(){
		if(liquidToolbarController!=null){
			liquidToolbarController.dispose();
			liquidToolbarController=null;
		}
		if(friendLiquidToolbarController!=null){
			friendLiquidToolbarController.dispose();
			friendLiquidToolbarController=null;
		}
		toolbarHost.removeAllViews();
		if(!GlobalUserPreferences.useIosLiquidNavigation){
			homeTabFragment.setLiquidToolbarController(null);
			friendRequestFragment.setLiquidToolbarController(null);
			updateCaptureHeights();
			return;
		}
		liquidToolbarController=new HomeLiquidToolbarController(
				getActivity(),
				homeTabFragment::onLiquidTimelineSelected,
				homeTabFragment::onLiquidNewPosts,
				homeTabFragment::onLiquidCompose,
				homeTabFragment::onLiquidMenuItem
		);
		liquidToolbarController.setMenuOpenListener(open->{
			liquidToolbarMenuOpen=open;
			updateCaptureHeights();
		});
		liquidToolbarController.setContentTouchTarget(fragmentContainer);
		toolbarHost.addView(liquidToolbarController.getView(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		friendLiquidToolbarController=new FriendUniverseLiquidToolbarController(
				getActivity(),
				friendRequestFragment::onLiquidSearchChanged,
				friendRequestFragment::onLiquidPublish
		);
		friendLiquidToolbarController.setSearchOpenListener(open->updateCaptureHeights());
		toolbarHost.addView(friendLiquidToolbarController.getView(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		homeTabFragment.setLiquidToolbarController(liquidToolbarController);
		friendRequestFragment.setLiquidToolbarController(friendLiquidToolbarController);
		applyLiquidToolbarInsets();
		updateLiquidToolbarVisibility();
		updateCaptureHeights();
	}

	private void applyLiquidToolbarInsets(){
		if(liquidToolbarController!=null)
			liquidToolbarController.setStatusBarInset(topSystemInset);
		if(friendLiquidToolbarController!=null)
			friendLiquidToolbarController.setStatusBarInset(topSystemInset);
		updateCaptureHeights();
	}

	private void updateLiquidToolbarVisibility(){
		boolean liquid=GlobalUserPreferences.useIosLiquidNavigation;
		boolean homeVisible=liquid && currentTab==R.id.tab_home;
		boolean friendVisible=liquid && currentTab==R.id.tab_friend_request;
		if(toolbarHost!=null)
			toolbarHost.setVisibility(homeVisible || friendVisible ? View.VISIBLE : View.GONE);
		if(liquidToolbarController!=null)
			liquidToolbarController.getView().setVisibility(homeVisible ? View.VISIBLE : View.GONE);
		if(friendLiquidToolbarController!=null)
			friendLiquidToolbarController.getView().setVisibility(friendVisible ? View.VISIBLE : View.GONE);
		if(currentTab!=R.id.tab_home && liquidToolbarController!=null)
			liquidToolbarController.closeMenu();
		if(currentTab!=R.id.tab_friend_request && friendLiquidToolbarController!=null)
			friendLiquidToolbarController.closeSearch();
		updateCaptureHeights();
	}

	private void updateCaptureHeights(){
		if(fragmentContainer==null)
			return;
		int topHeight=0;
		if(toolbarHost!=null && toolbarHost.getVisibility()==View.VISIBLE){
			if(currentTab==R.id.tab_home && liquidToolbarController!=null)
				topHeight=topSystemInset+V.dp(homeToolbarCaptureHeightDp(liquidToolbarMenuOpen));
			else if(currentTab==R.id.tab_friend_request && friendLiquidToolbarController!=null)
				topHeight=topSystemInset+V.dp(friendUniverseCaptureHeightDp(friendLiquidToolbarController.isSearchExpanded()));
		}
		int bottomHeight=navigationHost==null ? 0 : navigationHost.getHeight();
		fragmentContainer.setCaptureHeights(topHeight, bottomHeight);
	}

	public boolean onBackPressed(){
		if(currentTab==R.id.tab_friend_request && friendLiquidToolbarController!=null && friendLiquidToolbarController.closeSearch())
			return true;
		return liquidToolbarController!=null && liquidToolbarController.onBackPressed();
	}

	private void selectTabInNavigation(@IdRes int tab){
		if(liquidNavigationController!=null)
			liquidNavigationController.setSelectedTab(tab);
		else if(tabBar!=null)
			tabBar.selectTab(tab);
	}

	private void applyNavigationBottomInset(){
		if(liquidNavigationController!=null)
			liquidNavigationController.setBottomInset(bottomSystemInset);
		else if(tabBarWrap!=null)
			tabBarWrap.setPadding(0, 0, 0, bottomSystemInset>0 ? Math.max(bottomSystemInset, V.dp(24)) : 0);
	}

	@Override
	public String getAccountID() {
		return accountID;
	}

	@Override
	public void onProvideAssistContent(AssistContent content){
		if(fragmentForTab(currentTab) instanceof AssistContentProviderFragment provider){
			provider.onProvideAssistContent(content);
		}
	}
}
