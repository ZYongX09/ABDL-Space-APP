package org.joinmastodon.android.fragments;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.WindowInsets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;

import com.squareup.otto.Subscribe;

import org.joinmastodon.android.BuildConfig;
import org.joinmastodon.android.E;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.api.CacheController;
import org.joinmastodon.android.api.cache.HomeTimelineSnapshot;
import org.joinmastodon.android.api.session.AccountSession;
import me.grishka.appkit.utils.Preloader;
import me.grishka.appkit.imageloader.ListImageLoaderAdapter;
import me.grishka.appkit.imageloader.requests.ImageLoaderRequest;
import org.joinmastodon.android.api.requests.catalog.GetDonationCampaigns;
import org.joinmastodon.android.api.requests.markers.SaveMarkers;
import org.joinmastodon.android.api.requests.timelines.GetAllTimeline;
import org.joinmastodon.android.api.requests.timelines.GetListTimeline;
import org.joinmastodon.android.api.requests.timelines.GetPublicTimeline;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.events.DismissDonationCampaignBannerEvent;
import org.joinmastodon.android.events.SelfUpdateStateChangedEvent;
import org.joinmastodon.android.fragments.settings.SettingsMainFragment;
import org.joinmastodon.android.model.FilterContext;
import org.joinmastodon.android.model.FollowList;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.TimelineMarkers;
import org.joinmastodon.android.model.donations.DonationCampaign;
import org.joinmastodon.android.ui.displayitems.GapStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.StatusDisplayItem;
import org.joinmastodon.android.ui.sheets.DonationSheet;
import org.joinmastodon.android.ui.sheets.DonationSuccessfulSheet;
import org.joinmastodon.android.ui.utils.DiscoverInfoBannerHelper;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.joinmastodon.android.ui.viewcontrollers.HomeTimelineMenuController;
import org.joinmastodon.android.ui.viewcontrollers.ToolbarDropdownMenuController;
import org.joinmastodon.android.ui.views.FixedAspectRatioImageView;
import org.joinmastodon.android.ui.views.NestedRecyclerScrollView;
import org.joinmastodon.android.ui.views.NewPostsButtonContainer;
import org.joinmastodon.android.updater.GithubSelfUpdater;
import org.parceler.Parcels;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.CircularProgressDrawable;
import me.grishka.appkit.Nav;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.api.SimpleCallback;
import me.grishka.appkit.utils.CubicBezierInterpolator;
import me.grishka.appkit.utils.MergeRecyclerAdapter;
import me.grishka.appkit.utils.V;
import me.grishka.appkit.views.BottomSheet;

public class HomeTimelineFragment extends StatusListFragment implements ToolbarDropdownMenuController.HostFragment{
	private static final int DONATION_RESULT=211;

	// MOSHIDON: we don't use the fab from this fragment, we use the one from BaseStatusListFragment
	//	private ImageButton fab;
	private LinearLayout listsDropdown;
	private FixedAspectRatioImageView listsDropdownArrow;
	private TextView listsDropdownText;
	private Button newPostsBtn;
	private NewPostsButtonContainer newPostsBtnWrap;
	private boolean newPostsBtnShown;
	private AnimatorSet currentNewPostsAnim;
	private ToolbarDropdownMenuController dropdownController;
	private HomeTimelineMenuController dropdownMainMenuController;
	private List<FollowList> lists=List.of();
	private ListMode listMode=ListMode.FOLLOWING;
	private FollowList currentList;
	private MergeRecyclerAdapter mergeAdapter;
	private DiscoverInfoBannerHelper localTimelineBannerHelper;
	private View donationBanner;
	private boolean donationBannerDismissing;
	private NestedRecyclerScrollView scrollWrapper;

	private String scrollBackItemID;
	private int scrollBackItemOffset, scrollBackItemIndex;
	private long scrollBackTime;

	private String maxID;
	private String lastSavedMarkerID;
	private DonationCampaign currentDonationCampaign;
	private BottomSheet donationSheet;
	private CacheController homeCache;
	private CacheController.HomeTimelineLease homeLease;
	private AccountSession homeSession;
	private org.joinmastodon.android.model.Token homeToken;
	private String homeAccessToken;
	private long homeNBWGeneration;
	private long homeGeneration;
	private final Handler homeHandler=new Handler(Looper.getMainLooper());
	private long homeRecoveryGeneration=-1;
	private final Runnable homeRoutingRecovery=this::resumeHomeAfterRoutingChange;
	private View homeRecoveryView;
	private final View.OnAttachStateChangeListener homeRecoveryAttachment=new View.OnAttachStateChangeListener(){
		@Override public void onViewAttachedToWindow(View view){ scheduleHomeRoutingRecovery(); }
		@Override public void onViewDetachedFromWindow(View view){ homeHandler.removeCallbacks(homeRoutingRecovery); }
	};
	private final ViewTreeObserver.OnPreDrawListener homeRecoveryVisibility=()->{
		if(homeRecoveryGeneration>=0) scheduleHomeRoutingRecovery();
		return true;
	};
	private boolean homeHeadInFlight, homeHeadManual, homeHistoryAttempted, showingHomeHistory, homeViewDestroyed;
	private boolean checkingNewPosts, listRefreshInFlight, ownsRefreshIndicator;
	private ImageView homeRefreshCircle;
	private ViewTreeObserver homeRefreshObserver;
	private WindowInsets homeRefreshInsets;
	private final Rect homeRefreshViewport=new Rect(), toolbarBounds=new Rect(), homeRefreshClip=new Rect(), currentRefreshClip=new Rect();
	private final int[] geometryOrigin=new int[2];
	private final ViewTreeObserver.OnPreDrawListener homeRefreshPosition=this::updateHomeRefreshGeometry;
	private final View.OnAttachStateChangeListener homeRefreshAttachment=new View.OnAttachStateChangeListener(){
		@Override public void onViewAttachedToWindow(View view){ observeHomeRefreshGeometry(); }
		@Override public void onViewDetachedFromWindow(View view){
			stopObservingHomeRefreshGeometry();
			// SwipeRefreshLayout resets the drawable on detach, not its mRefreshing flag.
			// A detached pager view still owns its request; reattach will render that state again.
			refreshLayout.setRefreshing(false);
		}
	};
	private Runnable pendingHomeDelivery;
	private boolean homeHistoryPending;
	private ErrorResponse homeErrorWaitingForHistory;
	private long lastAutomaticHomeReadAt=-1;
	private static final long HOME_REVALIDATE_INTERVAL_MS=30_000;
	private final Runnable homeIdentityCheck=()->{
		if(!homeHeadInFlight) return;
		if(!isHomeIdentityCurrent()) acceptsHomeResponse(homeGeneration);
		else scheduleHomeIdentityCheck();
	};

	public HomeTimelineFragment(){
		setLayout(R.layout.fragment_loader_hiding_toolbar);
		setListLayoutId(R.layout.fragment_timeline);
	}

	@Override
	public void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		localTimelineBannerHelper=new DiscoverInfoBannerHelper(DiscoverInfoBannerHelper.BannerType.LOCAL_TIMELINE, accountID);

		if(AccountSessionManager.get(accountID).isEligibleForDonations()){
			GetDonationCampaigns req=new GetDonationCampaigns(Locale.getDefault().toLanguageTag().replace('-', '_'), String.valueOf(AccountSessionManager.get(accountID).getDonationSeed()), null);
			if(getActivity().getSharedPreferences("debug", Context.MODE_PRIVATE).getBoolean("donationsStaging", false)){
				req.setStaging(true);
			}
			req.setCallback(new Callback<>(){
						@Override
						public void onSuccess(DonationCampaign result){
							if(result==null)
								return;
							AccountSessionManager.getInstance().runIfDonationCampaignNotDismissed(result.id, ()->showDonationBanner(result));
						}

						@Override
						public void onError(ErrorResponse error){}
					})
					.execNoAuth("");
		}
		E.register(this);
	}

	@Override
	public void onDestroy(){
		cancelLoading();
		super.onDestroy();
		E.unregister(this);
	}

	@Override
	public void onAttach(Activity activity){
		super.onAttach(activity);
		dropdownController=new ToolbarDropdownMenuController(this);
		dropdownMainMenuController=new HomeTimelineMenuController(dropdownController, new HomeTimelineMenuController.Callback(){
			@Override
			public void onFollowingSelected(){
				if(listMode==ListMode.FOLLOWING)
					return;
				listMode=ListMode.FOLLOWING;
				reload();
			}

			@Override
			public void onLocalSelected(){
				if(listMode==ListMode.LOCAL)
					return;
				listMode=ListMode.LOCAL;
				reload();
			}

			@Override
			public List<FollowList> getLists(){
				return lists;
			}

			@Override
			public void onListSelected(FollowList list){
				if(listMode==ListMode.LIST && currentList==list)
					return;
				listMode=ListMode.LIST;
				currentList=list;
				reload();
			}
		}, AccountSessionManager.get(accountID).canAccessLocalTimeline());
		setHasOptionsMenu(true);
		loadData();
	}

	private boolean isHomeAccountCurrent(){
		return contentAccessOpen() && homeSession!=null && AccountSessionManager.getInstance().tryGetAccount(accountID)==homeSession
				&& homeToken==homeSession.token && homeToken!=null && java.util.Objects.equals(homeAccessToken, homeToken.accessToken)
				&& accountID.equals(AccountSessionManager.getInstance().getLastActiveAccountID());
	}

	private boolean isHomeIdentityCurrent(){
		return isHomeAccountCurrent()
				&& homeNBWGeneration==homeSession.getNBWForumOptionsRepository().getSnapshot().generation
				&& (homeLease==null || homeCache.isHomeTimelineLeaseCurrent(homeLease));
	}

	private void recoverHomeAfterRoutingChange(){
		cancelLoading(true);
		showingHomeHistory=true;
		if(list!=null && !data.isEmpty()){
			HomeScrollAnchor anchor=captureHomeScrollAnchor();
			ArrayList<Status> localHistory=new ArrayList<>(data);
			localHistory.removeIf(status->status.id!=null && status.id.startsWith("nbw_"));
			homeSession.filterStatuses(localHistory, getFilterContext());
			replaceHomeHead(localHistory, null, false);
			if(anchor!=null) restoreHomeScrollAnchor(anchor);
		}
		maxID=null;
		preloader.setMoreAvailable(false);
		// Initial discovery is not logout. Keep local history, reject the old head and coalesce
		// one current /all read; a missing/disabled remote source must not strand the local feed.
		homeRecoveryGeneration=homeGeneration;
		scheduleHomeRoutingRecovery();
	}

	private void scheduleHomeRoutingRecovery(){
		if(homeRecoveryGeneration<0) return;
		View view=getView();
		if(view!=null && homeRecoveryView!=view){
			clearHomeRecoveryView();
			homeRecoveryView=view;
			view.addOnAttachStateChangeListener(homeRecoveryAttachment);
			view.getViewTreeObserver().addOnPreDrawListener(homeRecoveryVisibility);
		}
		if(view!=null && view.isAttachedToWindow() && view.isShown()){
			homeHandler.removeCallbacks(homeRoutingRecovery);
			homeHandler.post(homeRoutingRecovery);
		}
	}

	private void clearHomeRecoveryView(){
		if(homeRecoveryView==null) return;
		homeRecoveryView.removeOnAttachStateChangeListener(homeRecoveryAttachment);
		if(homeRecoveryView.getViewTreeObserver().isAlive())
			homeRecoveryView.getViewTreeObserver().removeOnPreDrawListener(homeRecoveryVisibility);
		homeRecoveryView=null;
	}

	private void resumeHomeAfterRoutingChange(){
		long generation=homeRecoveryGeneration;
		if(generation!=homeGeneration || homeViewDestroyed || homeHeadInFlight || listMode!=ListMode.FOLLOWING
				|| getActivity()==null || getActivity().isFinishing() || !isHomeAccountCurrent()){
			homeRecoveryGeneration=-1;
			clearHomeRecoveryView();
			updateHomeRefreshIndicator();
			return;
		}
		View view=getView();
		// A pager may temporarily detach or hide this view without another Fragment.onShown.
		// Keep the same pending generation until attach/pre-draw makes the page usable again.
		if(view==null || !view.isAttachedToWindow() || !view.isShown()){ scheduleHomeRoutingRecovery(); return; }
		homeRecoveryGeneration=-1;
		clearHomeRecoveryView();
		startHomeHead(itemsPerPage*2, false);
	}

	private boolean acceptsHomeResponse(long generation){
		if(generation!=homeGeneration || homeViewDestroyed || getActivity()==null || listMode!=ListMode.FOLLOWING) return false;
		if(!isHomeIdentityCurrent()){
			if(isHomeAccountCurrent() && homeNBWGeneration!=homeSession.getNBWForumOptionsRepository().getSnapshot().generation){
				recoverHomeAfterRoutingChange();
				return false;
			}
			cancelLoading();
			if(AccountSessionManager.getInstance().tryGetAccount(accountID)!=null && list!=null){
				showingHomeHistory=true;
				replaceHomeHead(List.of(), null, false);
			}
			return false;
		}
		return true;
	}

	private void deliverHomeResult(long generation, Runnable delivery){
		if(!homeHeadInFlight || !acceptsHomeResponse(generation)) return;
		if(list==null){
			Runnable previous=pendingHomeDelivery;
			pendingHomeDelivery=()->{
				if(previous!=null) previous.run();
				if(homeHeadInFlight && acceptsHomeResponse(generation)) delivery.run();
			};
		}else delivery.run();
	}

	private void startHomeHead(int count, boolean allowHistory){ startHomeHead(count, allowHistory, false); }

	private void startHomeHead(int count, boolean allowHistory, boolean manual){
		if(!contentAccessOpen()){ cancelLoading(); return; }
		if(homeHeadInFlight) return;
		AccountSession session=AccountSessionManager.getInstance().tryGetAccount(accountID);
		if(session==null || !accountID.equals(AccountSessionManager.getInstance().getLastActiveAccountID())){ cancelLoading(); return; }
		homeViewDestroyed=false;
		homeSession=session;
		homeToken=session.token;
		homeAccessToken=homeToken==null ? null : homeToken.accessToken;
		homeNBWGeneration=session.getNBWForumOptionsRepository().getSnapshot().generation;
		homeCache=session.getCacheController();
		homeLease=homeCache.beginHomeTimelineSnapshot();
		long generation=++homeGeneration;
		homeHeadInFlight=true;
		homeHeadManual=manual;
		homeHistoryPending=allowHistory && homeLease!=null && !homeHistoryAttempted && data.isEmpty();
		homeHistoryAttempted=true;
		homeErrorWaitingForHistory=null;
		dataLoading=true;
		maxID=null; // Cached opaque cursor is metadata only, never a pagination capability.
		preloader.setMoreAvailable(false);
		scheduleHomeIdentityCheck();
		updateHomeRefreshIndicator();
		if(homeHistoryPending){
			homeCache.getHomeTimelineSnapshot(homeLease, snapshot->deliverHomeResult(generation, ()->{
				homeHistoryPending=false;
				if(snapshot!=null && data.isEmpty()){
					ArrayList<Status> history=new ArrayList<>(snapshot.posts);
					session.filterStatuses(history, getFilterContext());
					if(!history.isEmpty()){
						showingHomeHistory=true;
						replaceHomeHead(history, null, false);
						dataLoading=true;
						showHomeContentImmediately();
						android.util.Log.i("HomeTimeline", "snapshot rendered "+history.size());
					}
				}
				if(homeErrorWaitingForHistory!=null) completeHomeFailure(homeErrorWaitingForHistory);
			}));
		}
		// The optional, account-scoped disk read and the authoritative GET run independently.
		// Once fresh data (even an empty response) wins, no late local read can replace it.
		sendHomeHead(generation, count);
	}

	private void sendHomeHead(long generation, int count){
		if(!acceptsHomeResponse(generation)) return;
		GetAllTimeline request=new GetAllTimeline(null, count);
		currentRequest=request;
		request.setNBWCancellationCleanup(()->{
			if(generation==homeGeneration && currentRequest==request && acceptsHomeResponse(generation)) cancelLoading();
		});
		request.setCallback(new Callback<>(){
			@Override public void onSuccess(List<Status> result){
				deliverHomeResult(generation, ()->{
					ArrayList<Status> authoritative=new ArrayList<>(result);
					homeSession.filterStatuses(authoritative, getFilterContext());
					homeCache.putHomeTimelineSnapshot(homeLease, authoritative, request.getNextMaxID());
					showingHomeHistory=false;
					lastAutomaticHomeReadAt=SystemClock.elapsedRealtime();
					HomeScrollAnchor anchor=homeHeadManual ? null : captureHomeScrollAnchor();
					replaceHomeHead(authoritative, request.getNextMaxID(), request.getNextMaxID()!=null);
					if(anchor!=null) restoreHomeScrollAnchor(anchor);
					android.util.Log.i("HomeTimeline","network rendered "+authoritative.size());
					finishHomeHead();
				});
			}
			@Override public void onError(ErrorResponse error){
				deliverHomeResult(generation, ()->{
					if(homeHistoryPending && !isHomeAuthorizationError(error)){
						homeErrorWaitingForHistory=error;
						currentRequest=null;
					}else completeHomeFailure(error);
				});
			}
		}).exec(accountID);
	}

	private static boolean isHomeAuthorizationError(ErrorResponse error){
		return error instanceof MastodonErrorResponse response && (response.httpStatus==401 || response.httpStatus==403);
	}

	private void completeHomeFailure(ErrorResponse error){
		finishHomeHead();
		if(isHomeAuthorizationError(error)){
			homeCache.invalidateHomeTimelineSnapshot();
			showingHomeHistory=false;
			replaceHomeHead(List.of(), null, false);
			loaded=false;
			doReauthOnErrorRetry=true;
			super.onError(error);
		}else if(showingHomeHistory || !data.isEmpty()){
			preloader.setMoreAvailable(false);
			showHomeContentImmediately();
			if(homeHeadManual && getActivity()!=null) error.showToast(getActivity());
		}else super.onError(error);
	}

	private void scheduleHomeIdentityCheck(){
		homeHandler.removeCallbacks(homeIdentityCheck);
		if(homeHeadInFlight) homeHandler.postDelayed(homeIdentityCheck, 500);
	}

	private void finishHomeHead(){
		homeHeadInFlight=false;
		homeHistoryPending=false;
		homeErrorWaitingForHistory=null;
		dataLoading=false;
		currentRequest=null;
		homeHandler.removeCallbacks(homeIdentityCheck);
		refreshDone();
	}

	/** AppKit 1.4.8 has no reset for preloading/preloadOnReady. Rebind its real backing arrays. */
	private void replaceHomeHead(List<Status> posts, String nextMaxID, boolean more){
		data.clear(); preloadedData.clear();
		preloader=new Preloader<>(this, itemsPerPage);
		data=preloader.getData(); preloadedData=preloader.getPreloadedData();
		preloader.setFooterViews(footerProgress, footerError);
		knownAccounts.clear(); relationships.clear();
		maxID=nextMaxID;
		preloadingFailed=false;
		refreshing=true;
		onDataLoaded(posts, more);
	}

	private record HomeScrollAnchor(String statusId, StatusDisplayItem.Type type, int offset){}

	private HomeScrollAnchor captureHomeScrollAnchor(){
		LinearLayoutManager manager=(LinearLayoutManager)list.getLayoutManager();
		int start=getMainAdapterOffset();
		int position=manager.findFirstVisibleItemPosition();
		View row=manager.findViewByPosition(position);
		int index=position-start;
		if(row==null || index<0 || index>=displayItems.size()) return null;
		list.getDecoratedBoundsWithMargins(row, tmpRect);
		int offset=tmpRect.top-list.getPaddingTop();
		if(index==0 && offset==0) return null; // At the head, let new first posts appear normally.
		StatusDisplayItem item=displayItems.get(index);
		return new HomeScrollAnchor(item.parentID, item.getType(), offset);
	}

	private void restoreHomeScrollAnchor(HomeScrollAnchor anchor){
		int fallback=-1;
		for(int i=0;i<displayItems.size();i++){
			StatusDisplayItem item=displayItems.get(i);
			if(!anchor.statusId.equals(item.parentID)) continue;
			if(fallback<0) fallback=i;
			if(item.getType()==anchor.type){ fallback=i; break; }
		}
		if(fallback>=0)
			((LinearLayoutManager)list.getLayoutManager()).scrollToPositionWithOffset(getMainAdapterOffset()+fallback, anchor.offset);
	}

	private void showHomeContentImmediately(){
		if(content!=null){ content.animate().cancel(); content.setAlpha(1f); content.setVisibility(View.VISIBLE); }
		if(progress!=null){ progress.animate().cancel(); progress.setVisibility(View.GONE); }
		if(errorView!=null){ errorView.animate().cancel(); errorView.setVisibility(View.GONE); }
		updateHomeRefreshIndicator();
	}

	private boolean isRefreshPending(){
		return listMode==ListMode.FOLLOWING
				? (homeHeadInFlight || homeRecoveryGeneration>=0 || checkingNewPosts)
				: listRefreshInFlight;
	}

	private void updateHomeRefreshIndicator(){
		if(refreshLayout==null || !refreshLayout.isAttachedToWindow()) return;
		boolean pending=isRefreshPending() && refreshLayout.isShown();
		if(pending){
			ownsRefreshIndicator=true;
			if(!refreshLayout.isRefreshing()) refreshLayout.setRefreshing(true);
		}else if(ownsRefreshIndicator){
			// Don't interrupt a native swipe that is still settling before it calls onRefresh().
			if(refreshLayout.isRefreshing()){
				if(refreshLayout.isShown()) refreshLayout.setRefreshing(false);
				else resetHomeRefreshIndicator();
			}
			ownsRefreshIndicator=isRefreshPending();
		}
	}

	private void resetHomeRefreshIndicator(){
		// An error, cancellation or hidden view needn't draw a scale-down frame before reset.
		refreshLayout.setRefreshing(false);
		boolean enabled=refreshLayout.isEnabled();
		refreshLayout.setEnabled(false);
		if(enabled) refreshLayout.setEnabled(true);
	}

	@Override public void refreshDone(){
		listRefreshInFlight=false;
		// AppKit also calls this when the cache is rendered as a replacement page. Its
		// replacement flag must reset, but cache hydration hasn't completed the home refresh.
		if(isRefreshPending()){
			refreshing=false;
			updateHomeRefreshIndicator();
		}else{
			if(refreshLayout!=null && (ownsRefreshIndicator || refreshLayout.isRefreshing()) && !refreshLayout.isShown())
				resetHomeRefreshIndicator();
			super.refreshDone();
			ownsRefreshIndicator=false;
		}
	}

	private void observeHomeRefreshGeometry(){
		if(homeRefreshObserver!=null) return;
		homeRefreshObserver=refreshLayout.getViewTreeObserver();
		homeRefreshObserver.addOnPreDrawListener(homeRefreshPosition);
	}

	private void stopObservingHomeRefreshGeometry(){
		if(homeRefreshObserver!=null && homeRefreshObserver.isAlive())
			homeRefreshObserver.removeOnPreDrawListener(homeRefreshPosition);
		homeRefreshObserver=null;
	}

	private void bindHomeRefreshCircle(){
		// The pinned AppKit widget has one content child and a native CircleImageView. Use
		// its public drawable type to identify the latter, not a private field or child index.
		for(int i=0;i<refreshLayout.getChildCount();i++){
			if(refreshLayout.getChildAt(i) instanceof ImageView image && image.getDrawable() instanceof CircularProgressDrawable){
				homeRefreshCircle=image;
				break;
			}
		}
		refreshLayout.addOnAttachStateChangeListener(homeRefreshAttachment);
		if(refreshLayout.isAttachedToWindow()) observeHomeRefreshGeometry();
	}

	/** Follow the measured window viewport (including a scrolled toolbar or pager), without
	 * setting padding on the refresh wrapper or resetting its live animation/refresh listener. */
	private boolean updateHomeRefreshGeometry(){
		updateHomeRefreshIndicator();
		if(!refreshLayout.isShown()) return true;
		Rect viewport=homeRefreshViewport;
		if(!refreshLayout.getGlobalVisibleRect(viewport)){
			if(homeRefreshCircle!=null) homeRefreshCircle.setAlpha(0f);
			return true;
		}
		View root=refreshLayout.getRootView();
		root.getLocationInWindow(geometryOrigin);
		// Global visible bounds are root-relative, not physical screen coordinates.
		viewport.offset(geometryOrigin[0], geometryOrigin[1]);
		WindowInsets insets=root.getRootWindowInsets();
		if(insets==null || insets.isConsumed()) insets=homeRefreshInsets;
		if(insets!=null){
			int left, top, right, bottom;
			if(Build.VERSION.SDK_INT>=30){
				android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
				left=safe.left; top=safe.top; right=safe.right; bottom=safe.bottom;
			}else{
				left=insets.getSystemWindowInsetLeft(); top=insets.getSystemWindowInsetTop();
				right=insets.getSystemWindowInsetRight(); bottom=insets.getSystemWindowInsetBottom();
				if(Build.VERSION.SDK_INT>=28 && insets.getDisplayCutout()!=null){
					left=Math.max(left, insets.getDisplayCutout().getSafeInsetLeft());
					top=Math.max(top, insets.getDisplayCutout().getSafeInsetTop());
					right=Math.max(right, insets.getDisplayCutout().getSafeInsetRight());
					bottom=Math.max(bottom, insets.getDisplayCutout().getSafeInsetBottom());
				}
			}
			// Intersect rather than count system bars again when AppKit already consumed them.
			viewport.left=Math.max(viewport.left, geometryOrigin[0]+left);
			viewport.top=Math.max(viewport.top, geometryOrigin[1]+top);
			viewport.right=Math.min(viewport.right, geometryOrigin[0]+root.getWidth()-right);
			viewport.bottom=Math.min(viewport.bottom, geometryOrigin[1]+root.getHeight()-bottom);
		}
		int contentGap=getResources().getDimensionPixelSize(R.dimen.home_toolbar_content_gap);
		int overlayPadding=0;
		if(getParentFragment() instanceof HomeTabFragment home && home.getVisibleHomeToolbarBoundsInWindow(toolbarBounds)
				&& toolbarBounds.left<viewport.right && toolbarBounds.right>viewport.left){
			viewport.top=Math.max(viewport.top, toolbarBounds.bottom);
			if(home.hasVisibleLiquidToolbar()){
				list.getLocationInWindow(geometryOrigin);
				overlayPadding=Math.max(0, viewport.top+contentGap-geometryOrigin[1]);
				// A large open glass menu may be taller than the entire list. Don't blank it.
				if(overlayPadding>=list.getHeight()) overlayPadding=0;
			}
		}
		if(list.getPaddingTop()!=overlayPadding) setLiquidToolbarTopPadding(overlayPadding);
		Toolbar toolbar=getToolbar();
		if(toolbar!=null && toolbar.isShown() && toolbar.getGlobalVisibleRect(toolbarBounds)){
			root.getLocationInWindow(geometryOrigin);
			toolbarBounds.offset(geometryOrigin[0], geometryOrigin[1]);
			if(toolbarBounds.left<viewport.right && toolbarBounds.right>viewport.left)
				viewport.top=Math.max(viewport.top, toolbarBounds.bottom);
		}
		positionHomeRefreshCircle(viewport);
		return true;
	}

	private void positionHomeRefreshCircle(Rect viewport){
		ImageView circle=homeRefreshCircle;
		if(circle==null || circle.getVisibility()!=View.VISIBLE) return;
		// The raw default end offset is relative to the hidden start (-diameter), not
		// the visible circle's top. Keep the same resting gap and gesture travel as Thread.
		int restTop=refreshLayout.getProgressViewStartOffset()+refreshLayout.getProgressViewEndOffset();
		int diameter=refreshLayout.getProgressCircleDiameter();
		boolean fits=viewport.width()>=diameter && viewport.height()>=restTop+diameter;
		circle.setAlpha(fits ? 1f : 0f);
		if(!fits) return;
		refreshLayout.getLocationInWindow(geometryOrigin);
		circle.setTranslationX(viewport.exactCenterX()-geometryOrigin[0]-(circle.getLeft()+circle.getRight())/2f);
		circle.setTranslationY(viewport.top-geometryOrigin[1]);
		// While dragging, the native circle enters from above its start. Clip that travel
		// at the visible safe edge, not over the system bars or glass; never clip the list.
		int x=geometryOrigin[0]+circle.getLeft()+Math.round(circle.getTranslationX());
		int y=viewport.top+circle.getTop();
		homeRefreshClip.set(Math.max(0, viewport.left-x), Math.max(0, viewport.top-y),
				Math.min(circle.getWidth(), viewport.right-x), Math.min(circle.getHeight(), viewport.bottom-y));
		if(homeRefreshClip.isEmpty()) homeRefreshClip.setEmpty();
		if(homeRefreshClip.contains(0, 0, circle.getWidth(), circle.getHeight())){
			if(circle.getClipBounds(currentRefreshClip)) circle.setClipBounds(null);
		}else if(!circle.getClipBounds(currentRefreshClip) || !homeRefreshClip.equals(currentRefreshClip)){
			circle.setClipBounds(homeRefreshClip);
		}
	}

	@Override
	public void onApplyWindowInsets(WindowInsets insets){
		homeRefreshInsets=insets;
		super.onApplyWindowInsets(insets);
		if(refreshLayout!=null) refreshLayout.invalidate();
	}

	@Override protected boolean shouldAutomaticallyTranslateVisibleContent(){ return !showingHomeHistory; }
	@Override protected boolean shouldFetchMissingNestedQuotes(){ return !showingHomeHistory; }
	@Override protected void loadRelationships(Set<String> ids){ if(!showingHomeHistory) super.loadRelationships(ids); }
	@Override public boolean isDataLoading(){ return homeHeadInFlight || showingHomeHistory || super.isDataLoading(); }
	@Override public void onError(ErrorResponse error){
		if(listMode==ListMode.FOLLOWING && error instanceof MastodonErrorResponse response && (response.httpStatus==401 || response.httpStatus==403)){
			cancelLoading();
			if(homeCache!=null) homeCache.invalidateHomeTimelineSnapshot();
			showingHomeHistory=false;
			if(list!=null) replaceHomeHead(List.of(), null, false);
			loaded=false;
			doReauthOnErrorRetry=true;
		}
		super.onError(error);
	}
	@Override public void onScrolledToLastItem(){ if(!homeHeadInFlight && !showingHomeHistory) super.onScrolledToLastItem(); }
	@Override public void loadData(int offset, int count){ if(!homeHeadInFlight && !showingHomeHistory) super.loadData(offset, count); }
	@Override protected void showProgress(){
		if(listMode==ListMode.FOLLOWING && !data.isEmpty()) showHomeContentImmediately();
		else super.showProgress();
	}

	@Override protected void onContentAccessClosed(){
		showingHomeHistory=false; homeHistoryAttempted=false; homeLease=null; maxID=null; lists=List.of();
	}

	@Override protected void cancelLoading(){ cancelLoading(false); }

	private void cancelLoading(boolean keepRefreshIndicator){
		// This may also be called by the content gate, before it hides the timeline.
		if(!keepRefreshIndicator && refreshLayout!=null && (ownsRefreshIndicator || refreshLayout.isRefreshing()))
			resetHomeRefreshIndicator();
		homeHandler.removeCallbacks(homeRoutingRecovery);
		homeHandler.removeCallbacks(homeIdentityCheck);
		homeRecoveryGeneration=-1;
		clearHomeRecoveryView();
		homeGeneration++;
		homeHeadInFlight=false;
		homeHistoryPending=false;
		homeErrorWaitingForHistory=null;
		checkingNewPosts=false;
		listRefreshInFlight=false;
		pendingHomeDelivery=null;
		super.cancelLoading();
		dataLoading=false;
		if(keepRefreshIndicator) refreshing=false;
		else refreshDone();
	}

	@Override
	protected void doLoadData(int offset, int count){
		if(!contentAccessOpen()){ cancelLoading(); return; }
		if(listMode==ListMode.FOLLOWING && offset==0){ startHomeHead(count, true); return; }
		if(listMode==ListMode.FOLLOWING && (homeHeadInFlight || showingHomeHistory)) return;
		switch(listMode){
			case FOLLOWING -> {
				long generation=homeGeneration;
				GetAllTimeline req=new GetAllTimeline(maxID, count);
				currentRequest=req;
				req.setCallback(new Callback<>(){
					@Override public void onSuccess(List<Status> result){
						if(!acceptsHomeResponse(generation) || list==null) return;
						maxID=req.getNextMaxID();
						ArrayList<Status> filtered=new ArrayList<>(result);
						homeSession.filterStatuses(filtered, getFilterContext());
						onDataLoaded(filtered, maxID!=null);
					}
					@Override public void onError(ErrorResponse error){
						if(acceptsHomeResponse(generation) && list!=null) HomeTimelineFragment.this.onError(error);
					}
				}).exec(accountID);
			}
			case LOCAL -> {
				currentRequest=new GetPublicTimeline(true, false, offset>0 ? maxID : null, null, count, null, /* MOSHIDON */ null)
						.setCallback(new SimpleCallback<>(this){
							@Override
							public void onSuccess(List<Status> result){
								if(refreshing)
									list.scrollToPosition(0);
								maxID=result.isEmpty() ? null : result.get(result.size()-1).id;
								AccountSessionManager.get(accountID).filterStatuses(result, FilterContext.PUBLIC);
								onDataLoaded(result, !result.isEmpty());
							}
						})
						.exec(accountID);
			}
			case LIST -> {
				currentRequest=new GetListTimeline(currentList.id, offset>0 ? maxID : null, null, count, null)
						.setCallback(new SimpleCallback<>(this){
							@Override
							public void onSuccess(List<Status> result){
								if(refreshing)
									list.scrollToPosition(0);
								maxID=result.isEmpty() ? null : result.get(result.size()-1).id;
								AccountSessionManager.get(accountID).filterStatuses(result, FilterContext.HOME);
								onDataLoaded(result, !result.isEmpty());
							}
						})
						.exec(accountID);
			}
		}
	}

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public void onViewCreated(View view, Bundle savedInstanceState){
		super.onViewCreated(view, savedInstanceState);
//		fab=view.findViewById(R.id.fab);
//		fab.setOnClickListener(this::onFabClick);
		newPostsBtn=view.findViewById(R.id.new_posts_btn);
		newPostsBtn.setOnClickListener(this::onNewPostsBtnClick);
		newPostsBtnWrap=view.findViewById(R.id.new_posts_btn_wrap);
		homeViewDestroyed=false;
		bindHomeRefreshCircle();
		updateHomeRefreshIndicator();

		if(newPostsBtnShown){
			newPostsBtnWrap.setVisibility(View.VISIBLE);
		}else{
			newPostsBtnWrap.setVisibility(View.GONE);
			newPostsBtnWrap.setScaleX(0.9f);
			newPostsBtnWrap.setScaleY(0.9f);
			newPostsBtnWrap.setAlpha(0f);
			newPostsBtnWrap.setTranslationY(V.dp(-56));
		}
		newPostsBtnWrap.setOnHideButtonListener(this::hideNewPostsButton);
		updateToolbarLogo();
		list.addOnScrollListener(new RecyclerView.OnScrollListener(){
			private HashSet<GapStatusDisplayItem> gaps=new HashSet<>();

			@Override
			public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy){
				if(newPostsBtnShown && list.getChildAdapterPosition(list.getChildAt(0))<=getMainAdapterOffset()){
					hideNewPostsButton();
				}
				for(StatusDisplayItem item:displayItems){
					if(item instanceof GapStatusDisplayItem gap){
						gaps.add(gap);
					}
				}
				if(gaps.isEmpty())
					return;
				for(int i=0;i<list.getChildCount();i++){
					View child=list.getChildAt(i);
					if(list.getChildViewHolder(child) instanceof GapStatusDisplayItem.Holder holder){
						GapStatusDisplayItem gap=holder.getItem();
						if(!gap.visible){
							gap.visible=true;
							gap.enteredFromTop=child.getTop()<list.getHeight()/2;
							holder.text.setText(gap.enteredFromTop ? R.string.load_missing_posts_above : R.string.load_missing_posts_below);
						}
						gaps.remove(gap);
					}
				}
				for(GapStatusDisplayItem gap:gaps){
					gap.visible=false;
				}
				gaps.clear();
			}
		});
		View bottomOverlays=view.findViewById(R.id.bottom_overlays);
		NestedRecyclerScrollView scroller=view.findViewById(R.id.scroller);
		scroller.setScrollableChildSupplier(()->list);
		scroller.setTakePriorityOverChildViews(true);
		scroller.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY)->{
			Toolbar toolbar=getToolbar();
			bottomOverlays.setTranslationY(scrollY-(toolbar==null ? 0 : toolbar.getHeight()));
		});
		scroller.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener(){
			@Override
			public boolean onPreDraw(){
				scroller.getViewTreeObserver().removeOnPreDrawListener(this);
				// MOSHIDON
//				bottomOverlays.setTranslationY(scroller.getScrollY()-getToolbar().getHeight());
				return true;
			}
		});
		scrollWrapper=scroller;

		if(GithubSelfUpdater.isSupported()){
			updateUpdateState(GithubSelfUpdater.getInstance().getState());
		}
		if(currentDonationCampaign!=null)
			showDonationBanner(currentDonationCampaign);
		if(pendingHomeDelivery!=null){ Runnable delivery=pendingHomeDelivery; pendingHomeDelivery=null; delivery.run(); }
		if(homeRecoveryGeneration>=0) scheduleHomeRoutingRecovery();
		if(showingHomeHistory && !homeHeadInFlight && homeRecoveryGeneration<0) startHomeHead(itemsPerPage*2, false);
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater){
		inflater.inflate(R.menu.home, menu);
		menu.findItem(R.id.edit_list).setVisible(listMode==ListMode.LIST);
		GithubSelfUpdater.UpdateState state=GithubSelfUpdater.UpdateState.NO_UPDATE;
		GithubSelfUpdater updater=GithubSelfUpdater.getInstance();
		if(updater!=null)
			state=updater.getState();
		if(state!=GithubSelfUpdater.UpdateState.NO_UPDATE && state!=GithubSelfUpdater.UpdateState.CHECKING)
			getToolbar().getMenu().findItem(R.id.settings).setIcon(R.drawable.ic_settings_updateready_24px);

		if("debug".equals(BuildConfig.BUILD_TYPE)){
			menu.add(0, 1, 0, "Make a gap");
		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item){
		Bundle args=new Bundle();
		args.putString("account", accountID);
		int id=item.getItemId();
		if(id==R.id.settings){
			Nav.go(getActivity(), SettingsMainFragment.class, args);
		}else if(id==R.id.edit_list){
			args.putParcelable("list", Parcels.wrap(currentList));
			Nav.go(getActivity(), EditListFragment.class, args);
		}else if(id==1){
			if(data.size()<35){
				Toast.makeText(getActivity(), "Too few posts. Load at least 35", Toast.LENGTH_SHORT).show();
				return true;
			}
			Status gapStatus=data.get(1);
			gapStatus.hasGapAfter=true;
			onStatusUpdated(gapStatus);
			for(Status s:new ArrayList<>(data.subList(2, 32))){
				removeStatus(s);
			}
		}
		return true;
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig){
		super.onConfigurationChanged(newConfig);
		updateToolbarLogo();
	}

	@Override
	protected void onShown(){
		super.onShown();
		if(!getArguments().getBoolean("noAutoLoad")){
			if(homeRecoveryGeneration>=0){ scheduleHomeRoutingRecovery(); return; }
			if(showingHomeHistory && !homeHeadInFlight){ startHomeHead(itemsPerPage*2, false); return; }
			if(!loaded && !dataLoading){
				loadData();
			}else if(!dataLoading){
				loadNewPosts();
			}
		}
	}

	@Override
	protected void onHidden(){
		super.onHidden();
		if(!showingHomeHistory && !homeHeadInFlight && !data.isEmpty() && list!=null && list.getChildCount()>0 && !displayItems.isEmpty() && listMode==ListMode.FOLLOWING){
			String topPostID=displayItems.get(Math.max(0, list.getChildAdapterPosition(list.getChildAt(0))-getMainAdapterOffset())).parentID;
			if(!topPostID.equals(lastSavedMarkerID)){
				lastSavedMarkerID=topPostID;
				new SaveMarkers(topPostID, null)
						.setCallback(new Callback<>(){
							@Override
							public void onSuccess(TimelineMarkers result){
							}

							@Override
							public void onError(ErrorResponse error){
								lastSavedMarkerID=null;
							}
						})
						.exec(accountID);
			}
		}
	}

	public void onStatusCreated(Status status){
		// Replies belong in their thread, not in a locally synthesized root timeline.
		if(!contentAccessOpen() || status.inReplyToId!=null || getStatusByID(status.id)!=null)
			return;
		prependItems(Collections.singletonList(status), true);
	}

	// MOSHIDON:
	@Override
	public void onFabClick(View v){
		Bundle args=new Bundle();
		args.putString("account", accountID);
		Nav.go(getActivity(), ComposeFragment.class, args);
	}

	private void loadNewPosts(){
		if(!contentAccessOpen() || homeHeadInFlight || homeRecoveryGeneration>=0 || showingHomeHistory || dataLoading) return;
		if(listMode==ListMode.FOLLOWING){
			long now=SystemClock.elapsedRealtime();
			if(lastAutomaticHomeReadAt>=0 && now-lastAutomaticHomeReadAt<HOME_REVALIDATE_INTERVAL_MS) return;
			lastAutomaticHomeReadAt=now;
		}
		dataLoading=true;
		checkingNewPosts=listMode==ListMode.FOLLOWING;
		updateHomeRefreshIndicator();
		// The idea here is that we request the timeline such that if there are fewer than `limit` posts,
		// we'll get the currently topmost post as last in the response. This way we know there's no gap
		// between the existing and newly loaded parts of the timeline.
		String sinceID=data.size()>1 ? data.get(1).id : "1";
		boolean needCache=false;
		loadAdditionalPosts(null, null, 20, sinceID, new Callback<>(){
					@Override
					public void onSuccess(List<Status> result){
						finishNewPostsCheck();
						if(result.isEmpty() || getActivity()==null)
							return;
						Status last=result.get(result.size()-1);
						List<Status> toAdd;
						if(!data.isEmpty() && last.id.equals(data.get(0).id)){ // This part intersects with the existing one
							toAdd=result.subList(0, result.size()-1); // Remove the already known last post
						}else{
							result.get(result.size()-1).hasGapAfter=true;
							toAdd=result;
						}
						if(!(toAdd instanceof ArrayList<?>))
							toAdd=new ArrayList<>(toAdd);
						Set<String> existingPostIDs=data.stream().map(s->s.id).collect(Collectors.toSet());
						toAdd.removeIf(s->existingPostIDs.contains(s.id));
						if(listMode==ListMode.FOLLOWING)
							AccountSessionManager.get(accountID).filterStatuses(toAdd, FilterContext.HOME);
						if(!toAdd.isEmpty()){
							prependItems(toAdd, true);
							showNewPostsButton();
							if(needCache)
								AccountSessionManager.getInstance().getAccount(accountID).getCacheController().putHomeTimeline(new ArrayList<>(toAdd), false);
						}
					}

					@Override
					public void onError(ErrorResponse error){ finishNewPostsCheck(); }
				});
	}

	private void finishNewPostsCheck(){
		currentRequest=null;
		dataLoading=false;
		checkingNewPosts=false;
		updateHomeRefreshIndicator();
	}

	@Override
	public void onGapClick(GapStatusDisplayItem.Holder item){
		if(homeHeadInFlight || showingHomeHistory || dataLoading)
			return;
		GapStatusDisplayItem gap=item.getItem();
		gap.loading=true;
		V.setVisibilityAnimated(item.progress, View.VISIBLE);
		V.setVisibilityAnimated(item.text, View.GONE);
		dataLoading=true;
		boolean needCache=false;
		boolean insertBelowGap=!gap.enteredFromTop;
		String maxID, minID;
		if(gap.enteredFromTop){
			maxID=null;
			int gapPos=displayItems.indexOf(gap);
			if(displayItems.size()==gapPos+1){ // The gap is the last item in the list somehow
				if(!preloader.isMoreAvailable()){ // Can't load any more posts anyway, just remove the gap then
					displayItems.remove(gapPos);
					adapter.notifyItemRemoved(gapPos);
				}
				return;
			}else{
				minID=displayItems.get(gapPos+1).parentID;
			}
		}else{
			maxID=item.getItemID();
			minID=null;
		}
		loadAdditionalPosts(maxID, minID, 20, null, new Callback<>(){
					@Override
					public void onSuccess(List<Status> result){

						currentRequest=null;
						dataLoading=false;
						if(getActivity()==null)
							return;
						int gapPos=displayItems.indexOf(gap);
						if(gapPos==-1)
							return;
						if(result.isEmpty()){
							displayItems.remove(gapPos);
							adapter.notifyItemRemoved(getMainAdapterOffset()+gapPos);
							Status gapStatus=getStatusByID(gap.parentID);
							if(gapStatus!=null){
								gapStatus.hasGapAfter=false;
								if(needCache)
									AccountSessionManager.getInstance().getAccount(accountID).getCacheController().putHomeTimeline(List.of(gapStatus), false);
							}
						}else if(insertBelowGap){
							Set<String> idsBelowGap=new HashSet<>();
							boolean belowGap=false;
							int gapPostIndex=0;
							for(Status s:data){
								if(belowGap){
									idsBelowGap.add(s.id);
								}else if(s.id.equals(gap.parentID)){
									belowGap=true;
									s.hasGapAfter=false;
									if(needCache)
										AccountSessionManager.getInstance().getAccount(accountID).getCacheController().putHomeTimeline(List.of(s), false);
								}else{
									gapPostIndex++;
								}
							}
							int endIndex=0;
							for(Status s:result){
								endIndex++;
								if(idsBelowGap.contains(s.id))
									break;
							}
							if(endIndex==result.size()){
								result.get(result.size()-1).hasGapAfter=true;
							}else{
								result=result.subList(0, endIndex);
							}
							if(listMode==ListMode.FOLLOWING)
								AccountSessionManager.get(accountID).filterStatuses(result, FilterContext.HOME);
							List<StatusDisplayItem> targetList=displayItems.subList(gapPos, gapPos+1); // Get a sub-list that contains the gap item
							targetList.clear(); // remove the gap item
							List<Status> insertedPosts=data.subList(gapPostIndex+1, gapPostIndex+1);
							for(Status s:result){
								if(idsBelowGap.contains(s.id))
									break;
								targetList.addAll(buildDisplayItems(s));
								insertedPosts.add(s);
							}
							if(targetList.isEmpty()){
								// oops. We didn't add new posts, but at least we know there are none.
								adapter.notifyItemRemoved(getMainAdapterOffset()+gapPos);
							}else{
								adapter.notifyItemChanged(getMainAdapterOffset()+gapPos);
								adapter.notifyItemRangeInserted(getMainAdapterOffset()+gapPos+1, targetList.size()-1);
							}
							if(needCache)
								AccountSessionManager.getInstance().getAccount(accountID).getCacheController().putHomeTimeline(new ArrayList<>(insertedPosts), false);
						}else{
							Set<String> idsAboveGap=new HashSet<>();
							int gapPostIndex=0;
							Status gapPost=null;
							for(Status s:data){
								if(s.id.equals(gap.parentID)){
									gapPost=s;
									break;
								}else{
									idsAboveGap.add(s.id);
									gapPostIndex++;
								}
							}
							if(gapPost==null)
								return;
							boolean needAdjustScroll=false;
							int scrollTop=0;
							for(int i=0;i<list.getChildCount();i++){
								View child=list.getChildAt(i);
								if(list.getChildViewHolder(child) instanceof GapStatusDisplayItem.Holder gapHolder && gapHolder.getItem()==gap){
									needAdjustScroll=true;
									scrollTop=child.getBottom()+1;
									break;
								}
							}
							List<StatusDisplayItem> targetList=displayItems.subList(gapPos+1, gapPos+1);
							List<Status> insertedPosts=data.subList(gapPostIndex+1, gapPostIndex+1);
							for(int i=result.size()-1;i>=0;i--){
								Status s=result.get(i);
								if(idsAboveGap.contains(s.id))
									break;
								targetList.addAll(0, buildDisplayItems(s));
								insertedPosts.add(0, s);
							}
							int addedItemCount=targetList.size();
							boolean gapRemoved=false;
							if(insertedPosts.size()<result.size()){ // There was an intersection, remove the gap
								gapRemoved=true;
								gapPost.hasGapAfter=false;
								if(needCache)
									AccountSessionManager.getInstance().getAccount(accountID).getCacheController().putHomeTimeline(List.of(gapPost), false);
								displayItems.remove(gapPos);
								adapter.notifyItemRemoved(getMainAdapterOffset()+gapPos);
							}else{
								gap.loading=false;
								adapter.notifyItemChanged(getMainAdapterOffset()+gapPos);
							}
							if(!insertedPosts.isEmpty()){
								if(needCache)
									AccountSessionManager.getInstance().getAccount(accountID).getCacheController().putHomeTimeline(new ArrayList<>(insertedPosts), false);
								adapter.notifyItemRangeInserted(getMainAdapterOffset()+gapPos+(gapRemoved ? 0 : 1), addedItemCount);
								if(needAdjustScroll){
									((LinearLayoutManager)list.getLayoutManager()).scrollToPositionWithOffset(getMainAdapterOffset()+gapPos+(gapRemoved ? 0 : 1)+addedItemCount, scrollTop);
								}
							}
						}
					}

					@Override
					public void onError(ErrorResponse error){
						currentRequest=null;
						dataLoading=false;
						gap.loading=false;
						Activity a=getActivity();
						if(a!=null){
							error.showToast(a);
							int gapPos=displayItems.indexOf(gap);
							if(gapPos>=0)
								adapter.notifyItemChanged(gapPos);
						}
					}
				});
	}

	private void loadAdditionalPosts(String maxID, String minID, int limit, String sinceID, Callback<List<Status>> callback){
		MastodonAPIRequest<List<Status>> req=switch(listMode){
			case FOLLOWING -> new GetAllTimeline(maxID, limit);
			case LOCAL -> new GetPublicTimeline(true, false, maxID, minID, limit, sinceID, /* MOSHIDON */ null);
			case LIST -> new GetListTimeline(currentList.id, maxID, minID, limit, sinceID);
		};
		long generation=homeGeneration;
		ListMode requestedMode=listMode;
		currentRequest=req;
		if(checkingNewPosts) req.setNBWCancellationCleanup(()->{
			if(generation==homeGeneration && currentRequest==req && acceptsHomeResponse(generation)) finishNewPostsCheck();
		});
		req.setCallback(new Callback<>(){
			private boolean current(){ return requestedMode==listMode && list!=null && (requestedMode!=ListMode.FOLLOWING || acceptsHomeResponse(generation)); }
			@Override public void onSuccess(List<Status> result){ if(current()) callback.onSuccess(result); }
			@Override public void onError(ErrorResponse error){ if(current()) callback.onError(error); }
		}).exec(accountID);
	}

	@Override
	public void onRefresh(){
		if(homeViewDestroyed || !contentAccessOpen()){
			cancelLoading();
			return;
		}
		if(listMode==ListMode.FOLLOWING){
			// An explicit refresh still supersedes a cache read or older head. The new
			// generation owns the same native indicator, with no stop/start in between.
			cancelLoading(true);
			startHomeHead(itemsPerPage*2, false, true);
			return;
		}
		if(currentRequest!=null){
			currentRequest.cancel();
			currentRequest=null;
			dataLoading=false;
		}
		listRefreshInFlight=true;
		updateHomeRefreshIndicator();
		super.onRefresh();
	}

	private void updateToolbarLogo(){
		listsDropdown=new LinearLayout(getActivity());
		listsDropdown.setOnClickListener(this::onListsDropdownClick);
		listsDropdown.setBackgroundResource(R.drawable.bg_button_m3_text);
		listsDropdown.setAccessibilityDelegate(new View.AccessibilityDelegate(){
			@Override
			public void onInitializeAccessibilityNodeInfo(@NonNull View host, @NonNull AccessibilityNodeInfo info){
				super.onInitializeAccessibilityNodeInfo(host, info);
				info.setClassName("android.widget.Spinner");
			}
		});
		listsDropdownArrow=new FixedAspectRatioImageView(getActivity());
		listsDropdownArrow.setUseHeight(true);
		listsDropdownArrow.setImageResource(R.drawable.ic_arrow_drop_down_24px);
		listsDropdownArrow.setScaleType(ImageView.ScaleType.CENTER);
		listsDropdownArrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		listsDropdown.addView(listsDropdownArrow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
		listsDropdownText=new TextView(getActivity());
		listsDropdownText.setTextAppearance(R.style.action_bar_title);
		listsDropdownText.setSingleLine();
		listsDropdownText.setEllipsize(TextUtils.TruncateAt.END);
		listsDropdownText.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
		listsDropdownText.setPaddingRelative(V.dp(4), 0, V.dp(16), 0);
		listsDropdownText.setText(getCurrentListTitle());
		listsDropdownArrow.setImageTintList(listsDropdownText.getTextColors());
		listsDropdown.setBackgroundTintList(listsDropdownText.getTextColors());
		listsDropdown.addView(listsDropdownText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

		FrameLayout logoWrap=new FrameLayout(getActivity());
		FrameLayout.LayoutParams ddlp=new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
		ddlp.topMargin=ddlp.bottomMargin=V.dp(8);
		logoWrap.addView(listsDropdown, ddlp);

		// MOSHIDON: Moshidon does not use this fragment, so we just commented out everything that caused compile and runtime errors somehow
//		Toolbar toolbar=getToolbar();
//		toolbar.addView(logoWrap, new Toolbar.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
//		toolbar.setContentInsetsRelative(V.dp(16), 0);
	}

	private void showNewPostsButton(){
		if(newPostsBtnShown)
			return;
		newPostsBtnShown=true;
		if(getParentFragment() instanceof HomeTabFragment home){
			home.onHomeNewPostsChanged();
			if(home.hasVisibleLiquidToolbar()){
				newPostsBtnWrap.setVisibility(View.GONE);
				return;
			}
		}
		if(currentNewPostsAnim!=null){
			currentNewPostsAnim.cancel();
		}
		newPostsBtnWrap.setVisibility(View.VISIBLE);
		AnimatorSet set=new AnimatorSet();
		set.playTogether(
				ObjectAnimator.ofFloat(newPostsBtnWrap, View.ALPHA, 1f),
				ObjectAnimator.ofFloat(newPostsBtnWrap, View.SCALE_X, 1f),
				ObjectAnimator.ofFloat(newPostsBtnWrap, View.SCALE_Y, 1f),
				ObjectAnimator.ofFloat(newPostsBtnWrap, View.TRANSLATION_Y, 0f)
		);
		set.setDuration(getResources().getInteger(R.integer.m3_sys_motion_duration_medium3));
		set.setInterpolator(AnimationUtils.loadInterpolator(getActivity(), R.interpolator.m3_sys_motion_easing_standard_decelerate));
		set.addListener(new AnimatorListenerAdapter(){
			@Override
			public void onAnimationEnd(Animator animation){
				currentNewPostsAnim=null;
			}
		});
		currentNewPostsAnim=set;
		set.start();
	}

	public void setLiquidToolbarMode(boolean liquid){
		if(newPostsBtnWrap!=null)
			newPostsBtnWrap.setVisibility(liquid ? View.GONE : (newPostsBtnShown ? View.VISIBLE : View.GONE));
	}

	private void hideNewPostsButton(){
		if(!newPostsBtnShown)
			return;
		newPostsBtnShown=false;
		if(getParentFragment() instanceof HomeTabFragment home) home.onHomeNewPostsChanged();
		if(currentNewPostsAnim!=null){
			currentNewPostsAnim.cancel();
		}
		AnimatorSet set=new AnimatorSet();
		set.playTogether(
				ObjectAnimator.ofFloat(newPostsBtnWrap, View.ALPHA, 0f),
				ObjectAnimator.ofFloat(newPostsBtnWrap, View.SCALE_X, .9f),
				ObjectAnimator.ofFloat(newPostsBtnWrap, View.SCALE_Y, .9f),
				ObjectAnimator.ofFloat(newPostsBtnWrap, View.TRANSLATION_Y, V.dp(-56))
		);
		set.setDuration(getResources().getInteger(R.integer.m3_sys_motion_duration_medium3));
		set.setInterpolator(AnimationUtils.loadInterpolator(getActivity(), R.interpolator.m3_sys_motion_easing_standard_accelerate));
		set.addListener(new AnimatorListenerAdapter(){
			@Override
			public void onAnimationEnd(Animator animation){
				newPostsBtnWrap.setVisibility(View.GONE);
				newPostsBtn.setTranslationY(0);
				currentNewPostsAnim=null;
			}
		});
		currentNewPostsAnim=set;
		set.start();
	}

	boolean hasNewPosts(){ return newPostsBtnShown; }

	void scrollToNewPosts(){
		if(newPostsBtnShown && list!=null){
			hideNewPostsButton();
			smoothScrollRecyclerViewToTop(list);
		}
	}

	private void onNewPostsBtnClick(View v){ scrollToNewPosts(); }

	private void onListsDropdownClick(View v){
		listsDropdownArrow.animate().rotation(-180f).setDuration(150).setInterpolator(CubicBezierInterpolator.DEFAULT).start();
		dropdownController.show(dropdownMainMenuController);
		AccountSessionManager.get(accountID).getCacheController().getLists(new Callback<>(){
			@Override
			public void onSuccess(java.util.List<FollowList> result){
				lists=result;
			}

			@Override
			public void onError(ErrorResponse error){}
		});
	}

	@Override
	public void onDestroyView(){
		cancelLoading();
		homeViewDestroyed=true;
		stopObservingHomeRefreshGeometry();
		if(refreshLayout!=null) refreshLayout.removeOnAttachStateChangeListener(homeRefreshAttachment);
		homeRefreshInsets=null;
		homeRefreshCircle=null;
		super.onDestroyView();
		donationBanner=null;
		donationBannerDismissing=false;
	}

	private void updateUpdateState(GithubSelfUpdater.UpdateState state){
		// MOSHIDON: for now this is broken :D
//		if(state!=GithubSelfUpdater.UpdateState.NO_UPDATE && state!=GithubSelfUpdater.UpdateState.CHECKING)
//			getToolbar().getMenu().findItem(R.id.settings).setIcon(R.drawable.ic_settings_updateready_24px);
	}

	@Subscribe
	public void onSelfUpdateStateChanged(SelfUpdateStateChangedEvent ev){
		updateUpdateState(ev.state);
	}

	@Subscribe
	public void onDismissDonationCampaignBanner(DismissDonationCampaignBannerEvent ev){
		if(currentDonationCampaign!=null && ev.campaignID.equals(currentDonationCampaign.id)){
			dismissDonationBanner();
		}
	}

	@Override
	protected boolean shouldRemoveAccountPostsWhenUnfollowing(){
		return true;
	}

	@Override
	public Toolbar getToolbar(){
		return super.getToolbar();
	}

	@Override
	public void onDropdownWillDismiss(){
		listsDropdownArrow.animate().rotation(0f).setDuration(150).setInterpolator(CubicBezierInterpolator.DEFAULT).start();

	}

	@Override
	public void onDropdownDismissed(){

	}

	@Override
	public void reload(){
		cancelLoading();
		if(listMode==ListMode.FOLLOWING){
			startHomeHead(itemsPerPage*2, false);
			if(listsDropdownText!=null) listsDropdownText.setText(getCurrentListTitle());
			invalidateOptionsMenu();
			return;
		}
		showingHomeHistory=false;
		if(currentRequest!=null){
			currentRequest.cancel();
			currentRequest=null;
		}
		refreshing=true;
		showProgress();
		loadData();
		listsDropdownText.setText(getCurrentListTitle());
		invalidateOptionsMenu();
	}

	@Override
	protected ListImageLoaderAdapter getImageLoaderAdapter(){
		ListImageLoaderAdapter delegate=super.getImageLoaderAdapter();
		if(delegate==null) return null;
		return new ListImageLoaderAdapter(){
			@Override public int getCount(){ return delegate.getCount(); }
			@Override public int getImageCountForItem(int position){ return delegate.getImageCountForItem(position); }
			@Override public ImageLoaderRequest getImageRequest(int position, int image){
				ImageLoaderRequest request=delegate.getImageRequest(position, image);
				if(!showingHomeHistory || request==null) return request;
				return org.joinmastodon.android.ui.utils.ContentMediaImageDownloader.cacheOnly(request, homeSession);
			}
			@Override public void imageLoaded(int position, int image, android.graphics.drawable.Drawable drawable){
				if(!showingHomeHistory || isHomeIdentityCurrent()) delegate.imageLoaded(position, image, drawable);
			}
			@Override public void imageLoadingFailed(int position, int image, Throwable error){ delegate.imageLoadingFailed(position, image, error); }
		};
	}

	@Override
	protected RecyclerView.Adapter getAdapter(){
		mergeAdapter=new MergeRecyclerAdapter();
		mergeAdapter.addAdapter(super.getAdapter());
		return mergeAdapter;
	}

	@Override
	protected void onDataLoaded(List<Status> d, boolean more){
		if(refreshing){
			if(listMode==ListMode.LOCAL){
				localTimelineBannerHelper.maybeAddBanner(list, mergeAdapter);
				localTimelineBannerHelper.onBannerBecameVisible();
			}else{
				localTimelineBannerHelper.removeBanner(mergeAdapter);
			}
		}
		super.onDataLoaded(d, more);
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data){
		if(requestCode==DONATION_RESULT){
			if(donationSheet!=null)
				donationSheet.dismissWithoutAnimation();
			if(resultCode==Activity.RESULT_OK){
				new DonationSuccessfulSheet(getActivity(), accountID, data.getStringExtra("postText")).showWithoutAnimation();
			}
		}
	}

	@Override
	public void scrollToTop(){
		if(list.getChildCount()==0)
			return;
		scrollWrapper.smoothScrollTo(0, 0);
		View topChild=list.getLayoutManager().getChildAt(0);
		if(list.getChildAdapterPosition(topChild)==0){
			if(topChild.getTop()==list.getPaddingTop() && scrollBackItemID!=null && System.currentTimeMillis()-scrollBackTime<5*60_000){
				int indexWithinPost=0;
				for(int i=0;i<displayItems.size();i++){
					StatusDisplayItem item=displayItems.get(i);
					if(item.parentID.equals(scrollBackItemID)){
						if(indexWithinPost==scrollBackItemIndex){
							((LinearLayoutManager)list.getLayoutManager()).scrollToPositionWithOffset(i+getMainAdapterOffset(), scrollBackItemOffset);
							list.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener(){
								@Override
								public boolean onPreDraw(){
									list.getViewTreeObserver().removeOnPreDrawListener(this);
									list.scrollBy(0, V.dp(-300));
									list.smoothScrollBy(0, V.dp(300));
									return true;
								}
							});
							if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S)
								UiUtils.playVibrationEffectIfSupported(getActivity(), VibrationEffect.Composition.PRIMITIVE_THUD);
							return;
						}
						indexWithinPost++;
					}
				}
			}else{
				smoothScrollRecyclerViewToTop(list);
				return;
			}
		}else if(list.getChildViewHolder(topChild) instanceof StatusDisplayItem.Holder<?> itemHolder){
			int postIndex;
			String id=itemHolder.getItemID();
			for(postIndex=0;postIndex<data.size();postIndex++){
				if(data.get(postIndex).id.equals(id))
					break;
			}
			if(postIndex>1){
				scrollBackItemID=id;
				scrollBackItemIndex=0;
				for(StatusDisplayItem item:displayItems){
					if(item.parentID.equals(id)){
						if(item==itemHolder.getItem())
							break;
						scrollBackItemIndex++;
					}
				}
				scrollBackItemOffset=topChild.getTop();
				scrollBackTime=System.currentTimeMillis();
			}else{
				scrollBackItemID=null;
			}
		}
		smoothScrollRecyclerViewToTop(list);
		if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S)
			UiUtils.playVibrationEffectIfSupported(getActivity(), VibrationEffect.Composition.PRIMITIVE_QUICK_RISE);
	}

	private String getCurrentListTitle(){
		return switch(listMode){
			case FOLLOWING -> getString(R.string.timeline_all);
			case LOCAL -> getString(R.string.local_timeline);
			case LIST -> currentList.title;
		};
	}

	private void showDonationBanner(DonationCampaign campaign){
		if(getActivity()==null)
			return;
		currentDonationCampaign=campaign;
		if(donationBanner==null){
			ViewStub stub=contentView.findViewById(R.id.donation_banner);
			donationBanner=stub.inflate();
			donationBanner.findViewById(R.id.banner_dismiss).setOnClickListener(v->{
				AccountSessionManager.getInstance().markDonationCampaignAsDismissed(currentDonationCampaign.id);
				dismissDonationBanner();
			});
			donationBanner.setOnClickListener(v->openDonationSheet());
		}else{
			donationBanner.setVisibility(View.VISIBLE);
		}
		TextView text=donationBanner.findViewById(R.id.banner_text);
		SpannableStringBuilder ssb=new SpannableStringBuilder(campaign.bannerMessage);
		if(!campaign.bannerMessage.endsWith("\n"))
			ssb.append(' ');
		int start=ssb.length();
		ssb.append(campaign.bannerButtonText.trim());
		ssb.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.masterialDark_colorGoldenrodContainer, getActivity().getTheme())), start, ssb.length(), 0);
		ssb.setSpan(new UnderlineSpan(), start, ssb.length(), 0);
		ssb.setSpan(new TypefaceSpan("sans-serif-medium"), start, ssb.length(), 0);
		text.setText(ssb);
		donationBanner.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener(){
			@Override
			public boolean onPreDraw(){
				donationBanner.getViewTreeObserver().removeOnPreDrawListener(this);

				AnimatorSet set=new AnimatorSet();
				set.playTogether(
						ObjectAnimator.ofFloat(donationBanner, View.TRANSLATION_Y, donationBanner.getHeight(), 0)
						// MOSHIDON:
//						ObjectAnimator.ofFloat(fab, View.TRANSLATION_Y, -donationBanner.getHeight())
				);
				set.setDuration(250);
				set.setInterpolator(CubicBezierInterpolator.DEFAULT);
				set.start();

				return true;
			}
		});
	}

	private void dismissDonationBanner(){
		if(donationBanner==null || donationBannerDismissing)
			return;
		AnimatorSet set=new AnimatorSet();
		set.playTogether(
				ObjectAnimator.ofFloat(donationBanner, View.TRANSLATION_Y, donationBanner.getHeight())
				// MOSHIDON:
//				ObjectAnimator.ofFloat(fab, View.TRANSLATION_Y, 0)
		);
		set.setDuration(250);
		set.setInterpolator(CubicBezierInterpolator.DEFAULT);
		set.addListener(new AnimatorListenerAdapter(){
			@Override
			public void onAnimationEnd(Animator animation){
				donationBanner.setVisibility(View.GONE);
				donationBannerDismissing=false;
			}
		});
		donationBannerDismissing=true;
		set.start();
		currentDonationCampaign=null;
	}

	private void openDonationSheet(){
		donationSheet=new DonationSheet(getActivity(), currentDonationCampaign, accountID, intent->startActivityForResult(intent, DONATION_RESULT));
		donationSheet.setOnDismissListener(dialog->donationSheet=null);
		donationSheet.show();
	}

	private enum ListMode{
		FOLLOWING,
		LOCAL,
		LIST
	}

	// MOSHIDON: stuff we use and need to have here:
	@Override
	protected FilterContext getFilterContext() {
		return FilterContext.HOME;
	}

	@Override
	public Uri getWebUri(Uri.Builder base) {
		return base.path("/").build();
	}
}
