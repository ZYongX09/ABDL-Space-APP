package org.joinmastodon.android.fragments;

import android.app.Activity;
import android.app.assist.AssistContent;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.TranslateAnimation;
import android.widget.ImageButton;
import android.widget.Toolbar;

import org.joinmastodon.android.E;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.accounts.GetAccountRelationships;
import org.joinmastodon.android.api.requests.polls.SubmitPollVote;
import org.joinmastodon.android.api.requests.statuses.GetStatusByID;
import org.joinmastodon.android.api.requests.statuses.GetStatusesByIDs;
import org.joinmastodon.android.api.requests.statuses.RecordStatusView;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.events.PollUpdatedEvent;
import org.joinmastodon.android.events.StatusCountersUpdatedEvent;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.DisplayItemsParent;
import org.joinmastodon.android.model.Poll;
import org.joinmastodon.android.model.Relationship;
import org.joinmastodon.android.model.SearchResult;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.StatusHeatResponse;
import org.joinmastodon.android.ui.BetterItemAnimator;
import org.joinmastodon.android.ui.PhotoLayoutHelper;
import org.joinmastodon.android.ui.displayitems.AccountStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.GapStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.HashtagStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.MediaGridStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.NestedQuoteStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.PollFooterStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.PollOptionStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.SpoilerStatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.StatusDisplayItem;
import org.joinmastodon.android.ui.displayitems.TextStatusDisplayItem;
import org.joinmastodon.android.ui.photoviewer.PhotoViewer;
import org.joinmastodon.android.ui.photoviewer.PhotoViewerHost;
import org.joinmastodon.android.ui.sheets.NonMutualPreReplySheet;
import org.joinmastodon.android.ui.sheets.OldPostPreReplySheet;
import org.joinmastodon.android.ui.utils.MediaAttachmentViewController;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.joinmastodon.android.ui.views.MediaGridLayout;
import org.joinmastodon.android.utils.ProvidesAssistContent;
import org.joinmastodon.android.utils.TypedObjectPool;
import org.parceler.Parcels;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.RecyclerView;
import me.grishka.appkit.Nav;
import me.grishka.appkit.api.APIRequest;
import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import me.grishka.appkit.imageloader.ImageLoaderRecyclerAdapter;
import me.grishka.appkit.imageloader.ImageLoaderViewHolder;
import me.grishka.appkit.imageloader.requests.ImageLoaderRequest;
import me.grishka.appkit.utils.BindableViewHolder;
import me.grishka.appkit.utils.MergeRecyclerAdapter;
import me.grishka.appkit.utils.V;
import me.grishka.appkit.views.UsableRecyclerView;

public abstract class BaseStatusListFragment<T extends DisplayItemsParent> extends MastodonRecyclerFragment<T> implements PhotoViewerHost, ScrollableToTop, StatusDisplayItem.Callbacks, /* MOSHIDON: */IsOnTop, HasFab, ProvidesAssistContent.ProvidesWebUri{
	protected ArrayList<StatusDisplayItem> displayItems=new ArrayList<>();
	protected DisplayItemsAdapter adapter;
	protected String accountID;
	protected PhotoViewer currentPhotoViewer;
	protected HashMap<String, Account> knownAccounts=new HashMap<>();
	protected HashMap<String, Relationship> relationships=new HashMap<>();
	protected Rect tmpRect=new Rect();
	protected TypedObjectPool<MediaGridStatusDisplayItem.GridItemType, MediaAttachmentViewController> attachmentViewsPool=new TypedObjectPool<>(this::makeNewMediaAttachmentView);
	protected HashMap<String, Status> knownStatuses=new HashMap<>();
	protected HashSet<APIRequest<?>> requestsToCancelWhenListClears=new HashSet<>();
	private org.joinmastodon.android.api.session.NBWForumOptionsRepository.Cancelable nbwSubscription;
	private long observedNBWGeneration=-1;
	private SpringAnimation listShakeAnimation;
	private org.joinmastodon.android.translation.PostTranslationCoordinator translationCoordinator;
	private org.joinmastodon.android.translation.PostTranslationCoordinator.Visit translationVisit=new org.joinmastodon.android.translation.PostTranslationCoordinator.Visit();
	private org.joinmastodon.android.translation.PostTranslationCoordinator.Cancelable translationPreferencesSubscription;
	private boolean translationScreenActive;
	private final Runnable translationVisibilityCheck=this::checkVisibleTranslations;
	private boolean hideNBWRemoteItems(){
		var session=AccountSessionManager.getInstance().tryGetAccount(accountID);
		return session!=null && !session.getNBWForumOptionsRepository().getSnapshot().canRead();
	}
	private boolean isNBWRemoteItem(T item){
		if(item instanceof SearchResult result && result.account!=null)
			return result.account.id!=null && result.account.id.startsWith("nbw_");
		Status status=asStatus(item);
		return status!=null && status.id!=null && status.id.startsWith("nbw_")
				|| item.getID()!=null && item.getID().startsWith("nbw_");
	}
	private boolean pruneNBWRemoteItems(){
		HashSet<String> hiddenParents=new HashSet<>();
		for(T item:data) if(isNBWRemoteItem(item)) hiddenParents.add(item.getID());
		for(T item:preloadedData) if(isNBWRemoteItem(item)) hiddenParents.add(item.getID());
		boolean changed=data.removeIf(this::isNBWRemoteItem);
		changed|=preloadedData.removeIf(this::isNBWRemoteItem);
		knownStatuses.entrySet().removeIf(item->item.getKey().startsWith("nbw_"));
		knownAccounts.entrySet().removeIf(item->item.getKey().startsWith("nbw_"));
		changed|=displayItems.removeIf(item->hiddenParents.contains(item.parentID)
				|| item.parentID!=null && item.parentID.startsWith("nbw_"));
		return changed;
	}
	private List<T> visibleNBWItems(List<T> items){
		if(!hideNBWRemoteItems()) return items;
		// Thread roots and created replies arrive as immutable singleton lists. Never mutate
		// callers or a subList owned by AppKit's preloader; data has already been appended there.
		ArrayList<T> visible=new ArrayList<>(items);
		visible.removeIf(this::isNBWRemoteItem);
		pruneNBWRemoteItems();
		return visible;
	}
	private void applyNBWMode(){
		var session=AccountSessionManager.getInstance().tryGetAccount(accountID);
		if(session==null) return;
		var snapshot=session.getNBWForumOptionsRepository().getSnapshot();
		boolean revisionChanged=observedNBWGeneration>=0 && observedNBWGeneration!=snapshot.generation;
		observedNBWGeneration=snapshot.generation;
		if(!revisionChanged && snapshot.canRead()) return;
		// Each NBW media viewer owns its lease; local p_/c_ media must not be closed here.
		if(pruneNBWRemoteItems() && adapter!=null) adapter.notifyDataSetChanged();
	}
	private boolean retryFailedImagesScheduled;
	private final Runnable retryFailedImagesRunnable=()->{
		retryFailedImagesScheduled=false;
		if(imgLoader!=null)
			imgLoader.retryFailedRequests();
	};

	// MOSHIDON: truly fabulous
	protected ImageButton fab;
	protected int scrollDiff = 0;
	protected boolean currentlyScrolling;

	// MOSHIDON: maxID stuff, I don't know why we have it :D
	protected String maxID;

	public BaseStatusListFragment(){
		super(20);

		// MOSHIDON: fabulous
		if (wantsComposeButton()) setListLayoutId(R.layout.recycler_fragment_with_fab);
	}

	// MOSHIDON: fabulous part 2
	protected boolean wantsComposeButton() {
		return false;
	}

	// MOSHIDON: fabulous part 3
	@Override
	public @Nullable View getFab() {
		if (getParentFragment() instanceof HasFab l) return l.getFab();
		else return fab;
	}

	// MOSHIDON: I thought it'd be less fabulous
	public boolean onFabLongClick(View v) {
		return UiUtils.pickAccountForCompose(getActivity(), accountID);
	}

	// MOSHIDON:
	@Override
	public void showFab() {
		if(GlobalUserPreferences.isIosLiquidNavigationEnabled() && getParentFragment() instanceof HomeTabFragment)
			return;
		View fab = getFab();
		if (fab == null || fab.getVisibility() == View.VISIBLE) return;
		fab.setVisibility(View.VISIBLE);
		TranslateAnimation animate = new TranslateAnimation(
				0,
				0,
				fab.getHeight() * 2,
				0);
		animate.setDuration(300);
		fab.startAnimation(animate);
	}

	// MOSHIDON:
	public boolean isScrolling() {
		return currentlyScrolling;
	}

	// MOSHIDON:
	@Override
	public void hideFab() {
		View fab = getFab();
		if (fab == null || fab.getVisibility() != View.VISIBLE) return;
		TranslateAnimation animate = new TranslateAnimation(
				0,
				0,
				0,
				fab.getHeight() * 2);
		animate.setDuration(300);
		fab.startAnimation(animate);
		fab.setVisibility(View.INVISIBLE);
		scrollDiff = 0;
	}

	// MOSHIDON:
	public void onFabClick(View v){
		Bundle args=new Bundle();
		args.putString("account", accountID);
		Nav.go(getActivity(), ComposeFragment.class, args);
	}


	// MOSHIDON:
	@Override
	public void onProvideAssistContent(AssistContent assistContent) {
		assistContent.setWebUri(getWebUri(getSession().getInstanceUri().buildUpon()));
	}

	// MOSHIDON: is it on top?
	@Override
	public boolean isOnTop() {
		return isRecyclerViewOnTop(list);
	}

	// MOSHIDON: maxID shenanigans
	protected boolean applyMaxID(List<Status> result){
		boolean empty=result.isEmpty();
		if(!empty) maxID=result.get(result.size()-1).id;
		return !empty;
	}


	@Override
	public void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.N)
			setRetainInstance(true);
	}

	@Override
	protected RecyclerView.Adapter getAdapter(){
		return adapter=new DisplayItemsAdapter();
	}

	@Override
	public void onAttach(Activity activity){
		super.onAttach(activity);
		accountID=getArguments().getString("account");
		for(StatusDisplayItem item:displayItems){
			item.context=activity;
		}
	}

	@Override
	protected void onDataLoaded(List<T> items, boolean moreAvailable){
		super.onDataLoaded(visibleNBWItems(items), moreAvailable);
	}

	@Override
	public void onAppendItems(List<T> items){
		items=visibleNBWItems(items);
		super.onAppendItems(items);
		for(T s:items){
			addAccountToKnown(s);
		}
		postprocessNewlyLoadedStatuses(items);
		for(T s:items){
			List<StatusDisplayItem> newItems=buildDisplayItems(s);
			populateNestedQuotes(newItems);
			displayItems.addAll(newItems);
		}
		loadRelationships(items.stream().map(DisplayItemsParent::getAccountID).filter(Objects::nonNull).collect(Collectors.toSet()));
	}

	@Override
	public void onClearItems(){
		if(translationCoordinator!=null) translationCoordinator.suspend(translationVisit);
		translationVisit=new org.joinmastodon.android.translation.PostTranslationCoordinator.Visit();
		super.onClearItems();
		displayItems.clear();
		knownStatuses.clear();
		for(APIRequest<?> req:requestsToCancelWhenListClears){
			req.cancel();
		}
		requestsToCancelWhenListClears.clear();
	}

	protected void prependItems(List<T> items, boolean notify){
		items=visibleNBWItems(items);
		data.addAll(0, items);
		int offset=0;
		for(T s:items){
			addAccountToKnown(s);
		}
		postprocessNewlyLoadedStatuses(items);
		for(T s:items){
			List<StatusDisplayItem> toAdd=buildDisplayItems(s);
			populateNestedQuotes(toAdd);
			displayItems.addAll(offset, toAdd);
			offset+=toAdd.size();
		}
		if(notify)
			adapter.notifyItemRangeInserted(0, offset);
		loadRelationships(items.stream().map(DisplayItemsParent::getAccountID).filter(Objects::nonNull).collect(Collectors.toSet()));
	}

	protected void postprocessNewlyLoadedStatuses(List<T> items){
		for(T item:items){
			Status status=asStatus(item);
			if(status!=null){
				knownStatuses.put(status.id, status);
				if(status.quote!=null && status.quote.quotedStatus!=null)
					knownStatuses.put(status.quote.quotedStatus.id, status.quote.quotedStatus);
			}
		}
	}

	protected boolean shouldFetchMissingNestedQuotes(){ return true; }

	protected void populateNestedQuotes(List<StatusDisplayItem> items){
		HashSet<String> needExtraStatuses=new HashSet<>();
		ArrayList<StatusDisplayItem> itemsWithMissingStatuses=new ArrayList<>();
		populateNestedQuotes(items, needExtraStatuses, itemsWithMissingStatuses);
		if(shouldFetchMissingNestedQuotes() && !needExtraStatuses.isEmpty()){
			APIRequest<?>[] req=new APIRequest[1];
			req[0]=new GetStatusesByIDs(needExtraStatuses)
					.setCallback(new Callback<>(){
						@Override
						public void onSuccess(List<Status> result){
							requestsToCancelWhenListClears.remove(req[0]);
							for(Status s:result){
								knownStatuses.put(s.id, s);
							}
							populateNestedQuotes(itemsWithMissingStatuses, null, null);
						}

						@Override
						public void onError(ErrorResponse error){
							requestsToCancelWhenListClears.remove(req[0]);
						}
					})
					.exec(accountID);
			requestsToCancelWhenListClears.add(req[0]);
		}
	}

	private void populateNestedQuotes(List<StatusDisplayItem> items, Set<String> needExtraStatuses, List<StatusDisplayItem> itemsWithMissingStatuses){
		for(StatusDisplayItem item:items){
			if(item instanceof NestedQuoteStatusDisplayItem nq && nq.status==null && nq.statusID!=null){
				Status status=knownStatuses.get(nq.statusID);
				if(status!=null){
					nq.status=status;
					nq.quote.quotedStatus=status;
					StatusDisplayItem.Holder<NestedQuoteStatusDisplayItem> holder=findHolderForItem(nq);
					if(holder!=null)
						holder.rebind();
				}else if(needExtraStatuses!=null){
					needExtraStatuses.add(nq.statusID);
					itemsWithMissingStatuses.add(nq);
				}
			}else if(item instanceof SpoilerStatusDisplayItem spoiler){
				populateNestedQuotes(spoiler.contentItems, needExtraStatuses, itemsWithMissingStatuses);
			}
		}
	}

	protected String getMaxID(){
		if(!preloadedData.isEmpty())
			return preloadedData.get(preloadedData.size()-1).getID();
		else if(!data.isEmpty())
			return data.get(data.size()-1).getID();
		else
			return null;
	}

	protected abstract List<StatusDisplayItem> buildDisplayItems(T s);
	protected abstract void addAccountToKnown(T s);
	protected abstract Status asStatus(T s);

	@Override
	protected void onHidden(){
		stopPostTranslations();
		super.onHidden();
		// Clear any loaded images from the list to make it possible for the GC to deallocate them.
		// The delay avoids blank image views showing up in the app switcher.
		content.postDelayed(()->{
			if(!isHidden())
				return;
			imgLoader.deactivate();
			UsableRecyclerView list=(UsableRecyclerView) this.list;
			for(int i=0; i<list.getChildCount(); i++){
				RecyclerView.ViewHolder holder=list.getChildViewHolder(list.getChildAt(i));
				if(holder instanceof ImageLoaderViewHolder ivh){
					int pos=holder.getAbsoluteAdapterPosition();
					if(pos<0)
						continue;
					for(int j=0;j<list.getImageCountForItem(pos);j++){
						ivh.clearImage(j);
					}
				}
			}
		}, 100);
	}

	@Override
	protected void onShown(){
		super.onShown();
		imgLoader.activate();
		startPostTranslations();
	}

@Override
	public void openPhotoViewer(String parentID, Status _status, int attachmentIndex, MediaGridStatusDisplayItem.Holder gridHolder){
		final Status status=_status.getContentStatus();
		new RecordStatusView(status.id)
				.setCallback(new Callback<>(){
					@Override
					public void onSuccess(StatusHeatResponse response){
						if(response==null)
							return;
						status.viewsCount=response.viewsCount;
						status.heat=response.heat;
						E.post(new StatusCountersUpdatedEvent(status, StatusCountersUpdatedEvent.CounterType.HEAT));
					}

					@Override
					public void onError(ErrorResponse error){
						// 大图浏览打点失败静默
					}
				})
				.exec(accountID);
		currentPhotoViewer=new PhotoViewer(getActivity(), this, status.mediaAttachments, attachmentIndex, status, accountID, new PhotoViewer.Listener(){
			private MediaAttachmentViewController transitioningHolder;

			@Override
			public void setPhotoViewVisibility(int index, boolean visible){
				MediaAttachmentViewController holder=findPhotoViewHolder(index);
				if(holder!=null)
					holder.photo.setAlpha(visible ? 1f : 0f);
			}

			@Override
			public boolean startPhotoViewTransition(int index, @NonNull Rect outRect, @NonNull int[] outCornerRadius){
				MediaAttachmentViewController holder=findPhotoViewHolder(index);
				if(holder!=null && list!=null){
					transitioningHolder=holder;
					View view=transitioningHolder.photo;
					int[] pos={0, 0};
					view.getLocationOnScreen(pos);
					outRect.set(pos[0], pos[1], pos[0]+view.getWidth(), pos[1]+view.getHeight());
					list.setClipChildren(false);
					gridHolder.setClipChildren(false);
					transitioningHolder.view.setElevation(1f);
					int cornerMask=((MediaGridLayout.LayoutParams)holder.view.getLayoutParams()).tile.getRoundCornersMask();
					if((cornerMask & PhotoLayoutHelper.CORNER_TL)!=0)
						outCornerRadius[0]=V.dp(8);
					if((cornerMask & PhotoLayoutHelper.CORNER_TR)!=0)
						outCornerRadius[1]=V.dp(8);
					if((cornerMask & PhotoLayoutHelper.CORNER_BR)!=0)
						outCornerRadius[2]=V.dp(8);
					if((cornerMask & PhotoLayoutHelper.CORNER_BL)!=0)
						outCornerRadius[3]=V.dp(8);
					return true;
				}
				return false;
			}

			@Override
			public void setTransitioningViewTransform(float translateX, float translateY, float scale){
				View view=transitioningHolder.photo;
				view.setTranslationX(translateX);
				view.setTranslationY(translateY);
				view.setScaleX(scale);
				view.setScaleY(scale);
			}

			@Override
			public void endPhotoViewTransition(){
				// fix drawable callback
				Drawable d=transitioningHolder.photo.getDrawable();
				transitioningHolder.photo.setImageDrawable(null);
				transitioningHolder.photo.setImageDrawable(d);

				View view=transitioningHolder.photo;
				view.setTranslationX(0f);
				view.setTranslationY(0f);
				view.setScaleX(1f);
				view.setScaleY(1f);
				transitioningHolder.view.setElevation(0f);
				if(list!=null)
					list.setClipChildren(true);
				gridHolder.setClipChildren(true);
				transitioningHolder=null;
			}

			@Override
			public Drawable getPhotoViewCurrentDrawable(int index){
				MediaAttachmentViewController holder=findPhotoViewHolder(index);
				if(holder!=null)
					return holder.photo.getDrawable();
				return null;
			}

			@Override
			public void photoViewerDismissed(){
				currentPhotoViewer=null;
				gridHolder.itemView.setHasTransientState(false);
			}

			@Override
			public void onRequestPermissions(String[] permissions){
				requestPermissions(permissions, PhotoViewer.PERMISSION_REQUEST);
			}

			private MediaAttachmentViewController findPhotoViewHolder(int index){
				return gridHolder.getViewController(index);
			}
		});
		gridHolder.itemView.setHasTransientState(true);
	}

	@Override
	public void onViewCreated(View view, Bundle savedInstanceState){
		super.onViewCreated(view, savedInstanceState);
		var session=AccountSessionManager.getInstance().tryGetAccount(accountID);
		if(session!=null) nbwSubscription=session.getNBWForumOptionsRepository().observe(snapshot->{
			if(getActivity()!=null) getActivity().runOnUiThread(this::applyNBWMode);
		});

		// MOSHIDON: this is for the sake of the fabulous
		fab=view.findViewById(R.id.fab);

		list.addOnScrollListener(new RecyclerView.OnScrollListener(){
			@Override
			public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy){
				if(currentPhotoViewer!=null)
					currentPhotoViewer.offsetView(-dx, -dy);
				scheduleTranslationVisibilityCheck();

				// MOSHIDON:
				View fab = getFab();
				if (fab!=null && GlobalUserPreferences.autoHideFab && dy != UiUtils.SCROLL_TO_TOP_DELTA) {
					if (dy > 0 && fab.getVisibility() == View.VISIBLE) {
						hideFab();
					} else if (dy < 0 && fab.getVisibility() != View.VISIBLE) {
						if (list.getChildAt(0).getTop() == 0 || scrollDiff > 400) {
							showFab();
							scrollDiff = 0;
						} else {
							scrollDiff += Math.abs(dy);
						}
					}
				}
			}
		});
		list.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener(){
			@Override public void onChildViewAttachedToWindow(@NonNull View child){ scheduleTranslationVisibilityCheck(); }
			@Override public void onChildViewDetachedFromWindow(@NonNull View child){ scheduleTranslationVisibilityCheck(); }
		});
		list.addItemDecoration(new StatusListItemDecoration());
		TypedArray ta=getContext().obtainStyledAttributes(new int[]{android.R.attr.selectableItemBackground});
		Drawable defaultSelector=ta.getDrawable(0);
		ta.recycle();
		Drawable roundedSelector=getResources().getDrawable(R.drawable.bg_list_item_rounded, getActivity().getTheme());

		((UsableRecyclerView)list).setSelectorBoundsProvider(new UsableRecyclerView.SelectorBoundsProvider(){
			private Rect tmpRect=new Rect();

			@Override
			public void getSelectorBounds(View view, Rect outRect){}

			@Override
			public void getSelectorBounds(View view, float x, float y, Rect outRect){
				if(((UsableRecyclerView) list).isIncludeMarginsInItemHitbox()){
					list.getDecoratedBoundsWithMargins(view, outRect);
				}else{
					outRect.set(view.getLeft(), view.getTop(), view.getRight(), view.getBottom());
				}
				RecyclerView.ViewHolder holder=list.getChildViewHolder(view);
				if(holder instanceof StatusDisplayItem.Holder<?> sih){
					if(sih.getItem() instanceof StatusDisplayItem sdi && sdi.getType()==StatusDisplayItem.Type.GAP){
						outRect.setEmpty();
						return;
					}
					StatusDisplayItem item=(StatusDisplayItem) sih.getItem();
					String id=sih.getItemID();
					int quoteLeft, quoteRight;
					if(item.fullWidth){
						quoteLeft=V.dp(16);
						quoteRight=list.getWidth()-V.dp(16);
					}else if(view.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL){
						quoteLeft=V.dp(16);
						quoteRight=list.getWidth()-V.dp(48+16);
					}else{
						quoteLeft=V.dp(48+16);
						quoteRight=list.getWidth()-V.dp(16);
					}
					if(item.isQuote && x<quoteRight && x>quoteLeft){
						outRect.left=quoteLeft;
						outRect.right=quoteRight;
						boolean wasInsideQuote=false;
						for(int i=0;i<list.getChildCount();i++){
							View child=list.getChildAt(i);
							holder=list.getChildViewHolder(child);
							if(holder instanceof StatusDisplayItem.Holder<?> sih2){
								String otherID=sih2.getItemID();
								if(otherID.equals(id)){
									StatusDisplayItem item2=(StatusDisplayItem) sih2.getItem();
									if(item2.isQuote){
										wasInsideQuote=true;
										list.getDecoratedBoundsWithMargins(child, tmpRect);
										outRect.top=Math.min(outRect.top, child.getTop());
										outRect.bottom=Math.max(outRect.bottom, child.getBottom());
									}else if(wasInsideQuote){
										outRect.bottom=child.getTop()-V.dp(8);
										break;
									}
								}
							}
						}
						((UsableRecyclerView)list).setSelector(roundedSelector);
					}else{
						for(int i=0;i<list.getChildCount();i++){
							View child=list.getChildAt(i);
							holder=list.getChildViewHolder(child);
							if(holder instanceof StatusDisplayItem.Holder<?> sih2){
								String otherID=sih2.getItemID();
								if(otherID.equals(id)){
									list.getDecoratedBoundsWithMargins(child, tmpRect);
									outRect.left=Math.min(outRect.left, tmpRect.left);
									outRect.top=Math.min(outRect.top, tmpRect.top);
									outRect.right=Math.max(outRect.right, tmpRect.right);
									outRect.bottom=Math.max(outRect.bottom, tmpRect.bottom);
								}
							}
						}
						((UsableRecyclerView)list).setSelector(defaultSelector);
					}
				}
			}
		});
		list.setItemAnimator(new BetterItemAnimator());
		((UsableRecyclerView) list).setIncludeMarginsInItemHitbox(true);
		if(imgLoader!=null)
			imgLoader.setPrefetchAmount(getImagePrefetchScreens());
		updateToolbar();

		// MOSHIDON: this is also for the fabulous
		if (wantsComposeButton() && !getArguments().getBoolean("__disable_fab", false)) {
			fab.setVisibility(View.VISIBLE);
			fab.setOnClickListener(this::onFabClick);
			fab.setOnLongClickListener(this::onFabLongClick);
		} else if (fab != null) {
			fab.setVisibility(View.GONE);
		}
	}

	protected float getImagePrefetchScreens(){
		return 0;
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig){
		super.onConfigurationChanged(newConfig);
		updateToolbar();
		if(translationCoordinator!=null) translationCoordinator.prune();
		if(adapter!=null) rebuildAllDisplayItems();
		scheduleTranslationVisibilityCheck();
	}

	private void updateToolbar(){
		Toolbar toolbar=getToolbar();
		if(toolbar==null)
			return;
		toolbar.setOnClickListener(v->scrollToTop());
		toolbar.setNavigationContentDescription(R.string.back);
	}

	public int getMainAdapterOffset(){
		if(list.getAdapter() instanceof MergeRecyclerAdapter mergeAdapter){
			return mergeAdapter.getPositionForAdapter(adapter);
		}
		return 0;
	}

	protected void drawDivider(View child, View bottomSibling, RecyclerView.ViewHolder holder, RecyclerView.ViewHolder siblingHolder, RecyclerView parent, Canvas c, Paint paint){
		parent.getDecoratedBoundsWithMargins(child, tmpRect);
		tmpRect.offset(0, Math.round(child.getTranslationY()));
		float y=tmpRect.bottom;
		int strokeWidth=V.dp(0.5f);
		if(strokeWidth%2==1){
			y-=0.5f;
		}
		paint.setAlpha(Math.round(255*child.getAlpha()));
		c.drawLine(0, y, parent.getWidth(), y, paint);
	}

	protected boolean needDividerForExtraItem(View child, View bottomSibling, RecyclerView.ViewHolder holder, RecyclerView.ViewHolder siblingHolder){
		return false;
	}

	@Override
	public abstract void onItemClick(String id);

	@Override
	public void onItemClick(String id, boolean quote){
		onItemClick(id);
	}

	private String seamlessClickParent;
	private Status seamlessClickVisibility;

	/** Preserve the clicked occurrence without bypassing ThreadFragment's navigate override. */
	protected final void navigateToStatusFromClick(Status status, String parentID, Status visibilityStatus){
		String previousParent=seamlessClickParent;
		Status previousVisibility=seamlessClickVisibility;
		seamlessClickParent=parentID;
		seamlessClickVisibility=visibilityStatus;
		try{ navigateToStatus(status); }
		finally{ seamlessClickParent=previousParent; seamlessClickVisibility=previousVisibility; }
	}

	public void navigateToStatus(Status status){
		Bundle args=new Bundle();
		args.putString("account", accountID);
		args.putParcelable("status", Parcels.wrap(status.clone()));
		if(status.inReplyToAccountId!=null && knownAccounts.containsKey(status.inReplyToAccountId))
			args.putParcelable("inReplyToAccount", Parcels.wrap(knownAccounts.get(status.inReplyToAccountId)));
		boolean opened=seamlessClickParent==null
				? org.joinmastodon.android.ui.seamless.SeamlessTransitions.openPost(getActivity(), list, accountID, status, args)
				: org.joinmastodon.android.ui.seamless.SeamlessTransitions.openPost(getActivity(), list, accountID, status, args,
						seamlessClickParent, seamlessClickVisibility, displayItems);
		if(opened) return;
		Nav.go(getActivity(), ThreadFragment.class, args);
	}

	protected void updatePoll(String itemID, Status status, Poll poll){
		if(status.poll!=poll)
			status.poll=poll;
		int firstOptionIndex=-1, footerIndex=-1;
		int i=0;
		for(StatusDisplayItem item:displayItems){
			if(item.parentID.equals(itemID)){
				if(item instanceof PollOptionStatusDisplayItem && firstOptionIndex==-1){
					firstOptionIndex=i;
				}else if(item instanceof PollFooterStatusDisplayItem){
					footerIndex=i;
					break;
				}
			}
			i++;
		}
		if(firstOptionIndex==-1 || footerIndex==-1)
			throw new IllegalStateException("Can't find all poll items in displayItems");
		List<StatusDisplayItem> pollItems=displayItems.subList(firstOptionIndex, footerIndex+1);
		int prevSize=pollItems.size();
		pollItems.clear();
		StatusDisplayItem.buildPollItems(itemID, this, getActivity(), poll, status, pollItems);
		if(prevSize!=pollItems.size()){
			adapter.notifyItemRangeRemoved(firstOptionIndex, prevSize);
			adapter.notifyItemRangeInserted(firstOptionIndex, pollItems.size());
		}else{
			adapter.notifyItemRangeChanged(firstOptionIndex, pollItems.size());
		}
	}

	public void onPollOptionClick(PollOptionStatusDisplayItem.Holder holder){
		Poll poll=holder.getItem().poll;
		Poll.Option option=holder.getItem().option;
		if(poll.selectedOptions==null)
			poll.selectedOptions=new ArrayList<>();
		if(poll.multiple){
			if(poll.selectedOptions.contains(option)){
				poll.selectedOptions.remove(option);
			}else{
				poll.selectedOptions.add(option);
			}
		}else{
			if(poll.selectedOptions.contains(option))
				return;
			if(!poll.selectedOptions.isEmpty()){
				Poll.Option previouslySelected=poll.selectedOptions.get(0);
				poll.selectedOptions.clear();
				for(int i=0;i<list.getChildCount();i++){
					RecyclerView.ViewHolder vh=list.getChildViewHolder(list.getChildAt(i));
					if(vh instanceof PollOptionStatusDisplayItem.Holder otherOption){
						if(otherOption.getItemID().equals(holder.getItemID()) && otherOption.getItem().option==previouslySelected){
							otherOption.updateCheckedState();
							break;
						}
					}
				}
			}
			poll.selectedOptions.add(option);
		}
		holder.updateCheckedState();
		for(int i=0;i<list.getChildCount();i++){
			RecyclerView.ViewHolder vh=list.getChildViewHolder(list.getChildAt(i));
			if(vh instanceof PollFooterStatusDisplayItem.Holder footer){
				if(footer.getItemID().equals(holder.getItemID())){
					footer.rebind();
					break;
				}
			}
		}
	}

	public void onPollToggleResultsClick(PollFooterStatusDisplayItem.Holder holder){
		Status status=holder.getItem().status.getContentStatus();
		status.poll.showResults=!status.poll.showResults;
		String itemID=holder.getItemID();
		if(status.poll.selectedOptions!=null)
			status.poll.selectedOptions.clear();
		int firstOptionIndex=-1, footerIndex=-1;
		int i=0;
		for(StatusDisplayItem item:displayItems){
			if(item.parentID.equals(itemID)){
				if(item instanceof PollOptionStatusDisplayItem optItem){
					if(firstOptionIndex==-1)
						firstOptionIndex=i;
					optItem.showResults=status.poll.showResults;
				}else if(item instanceof PollFooterStatusDisplayItem){
					footerIndex=i;
					break;
				}
			}
			i++;
		}
		if(firstOptionIndex==-1 || footerIndex==-1)
			throw new IllegalStateException("Can't find all poll items in displayItems");
		adapter.notifyItemRangeChanged(firstOptionIndex, status.poll.options.size());
	}

	public void onPollVoteButtonClick(PollFooterStatusDisplayItem.Holder holder){
		Poll poll=holder.getItem().poll;
		if(poll==null || poll.selectedOptions==null || poll.selectedOptions.isEmpty())
			return;
		submitPollVote(holder.getItemID(), poll.id, poll.selectedOptions.stream().map(opt->poll.options.indexOf(opt)).collect(Collectors.toList()));
	}

	protected void submitPollVote(String parentID, String pollID, List<Integer> choices){
		if(refreshing)
			return;
		new SubmitPollVote(pollID, choices)
				.setCallback(new Callback<>(){
					@Override
					public void onSuccess(Poll result){
						E.post(new PollUpdatedEvent(accountID, result));
					}

					@Override
					public void onError(ErrorResponse error){
						error.showToast(getActivity());
					}
				})
				.wrapProgress(getActivity(), R.string.loading, true)
				.exec(accountID);
	}

	public void onRevealSpoilerClick(SpoilerStatusDisplayItem.Holder holder){
		Status status=holder.getItem().status;
		SpoilerStatusDisplayItem spoilerItem=holder.getItem();
		if(status.revealedSpoilers.contains(spoilerItem.spoilerType))
			status.revealedSpoilers.remove(spoilerItem.spoilerType);
		else
			status.revealedSpoilers.add(spoilerItem.spoilerType);

		if(status.quote!=null && status.quote.quotedStatus!=null && !status.quote.quotedStatus.revealedSpoilers.isEmpty()){
			for(StatusDisplayItem item:displayItems){
				if(item.parentID.equals(spoilerItem.parentID) && item.isQuote && item instanceof SpoilerStatusDisplayItem quoteSpoilerItem
						&& status.quote.quotedStatus.revealedSpoilers.contains(quoteSpoilerItem.spoilerType)){
					status.quote.quotedStatus.revealedSpoilers.remove(quoteSpoilerItem.spoilerType);
					toggleSpoiler(status.quote.quotedStatus, quoteSpoilerItem);
					break;
				}
			}
		}

		holder.rebind();
		toggleSpoiler(status, spoilerItem);
	}

	private void toggleSpoiler(Status status, SpoilerStatusDisplayItem spoilerItem){
		int index=displayItems.indexOf(spoilerItem);
		if(status.revealedSpoilers.contains(spoilerItem.spoilerType)){
			int itemCount=spoilerItem.contentItems.size();
			displayItems.addAll(index+1, spoilerItem.contentItems);
			if(spoilerItem.spoilerType==Status.SpoilerType.FILTER && spoilerItem.contentItems.get(0) instanceof SpoilerStatusDisplayItem nestedSpoiler
					&& nestedSpoiler.spoilerType==Status.SpoilerType.CONTENT_WARNING && !GlobalUserPreferences.showCWs){
				status.revealedSpoilers.add(Status.SpoilerType.CONTENT_WARNING);
				displayItems.addAll(index+1+itemCount, nestedSpoiler.contentItems);
				itemCount+=nestedSpoiler.contentItems.size();
			}
			adapter.notifyItemRangeInserted(index+1, itemCount);
		}else{
			int itemCount=spoilerItem.contentItems.size();
			if(spoilerItem.contentItems.get(0) instanceof SpoilerStatusDisplayItem nestedSpoiler && status.revealedSpoilers.contains(nestedSpoiler.spoilerType)){
				status.revealedSpoilers.remove(nestedSpoiler.spoilerType);
				itemCount+=nestedSpoiler.contentItems.size();
			}
			displayItems.subList(index+1, index+1+itemCount).clear();
			adapter.notifyItemRangeRemoved(index+1, itemCount);
		}
		list.invalidateItemDecorations();
		scheduleTranslationVisibilityCheck();
	}

	public void onGapClick(GapStatusDisplayItem.Holder item){}

	public String getAccountID(){
		return accountID;
	}

	public Relationship getRelationship(String id){
		return relationships.get(id);
	}

	public void putRelationship(String id, Relationship rel){
		relationships.put(id, rel);
	}

	protected void loadRelationships(Set<String> ids){
		if(ids.isEmpty())
			return;
		ids=ids.stream().filter(id->!relationships.containsKey(id)).collect(Collectors.toSet());
		if(ids.isEmpty())
			return;
		// TODO somehow manage these and cancel outstanding requests on refresh
		new GetAccountRelationships(ids)
				.setCallback(new Callback<>(){
					@Override
					public void onSuccess(List<Relationship> result){
						for(Relationship r:result)
							relationships.put(r.id, r);
						onRelationshipsLoaded();
					}

					@Override
					public void onError(ErrorResponse error){

					}
				})
				.exec(accountID);
	}

	protected void loadExtraStatuses(Set<String> ids){

	}

	protected void onRelationshipsLoaded(){}

	@Nullable
	protected <I extends StatusDisplayItem> I findItemOfType(String id, Class<I> type){
		for(StatusDisplayItem item:displayItems){
			if(item.parentID.equals(id) && type.isInstance(item))
				return type.cast(item);
		}
		return null;
	}

	@Nullable
	protected <I extends StatusDisplayItem, H extends StatusDisplayItem.Holder<I>> H findHolderOfType(String id, Class<H> type){
		for(int i=0;i<list.getChildCount();i++){
			RecyclerView.ViewHolder holder=list.getChildViewHolder(list.getChildAt(i));
			if(holder instanceof StatusDisplayItem.Holder<?> itemHolder && itemHolder.getItemID().equals(id) && type.isInstance(holder))
				return type.cast(holder);
		}
		return null;
	}

	protected <I extends StatusDisplayItem, H extends StatusDisplayItem.Holder<I>> List<H> findAllHoldersOfType(String id, Class<H> type){
		ArrayList<H> holders=new ArrayList<>();
		for(int i=0;i<list.getChildCount();i++){
			RecyclerView.ViewHolder holder=list.getChildViewHolder(list.getChildAt(i));
			if(holder instanceof StatusDisplayItem.Holder<?> itemHolder && itemHolder.getItemID().equals(id) && type.isInstance(holder))
				holders.add(type.cast(holder));
		}
		return holders;
	}

	/** @noinspection unchecked*/
	@Nullable
	protected <I extends StatusDisplayItem> StatusDisplayItem.Holder<I> findHolderForItem(I item){
		if(list==null)
			return null;
		for(int i=0;i<list.getChildCount();i++){
			RecyclerView.ViewHolder holder=list.getChildViewHolder(list.getChildAt(i));
			if(holder instanceof StatusDisplayItem.Holder<?> itemHolder && itemHolder.getItem()==item)
				return (StatusDisplayItem.Holder<I>) itemHolder;
		}
		return null;
	}

	@Override
	public void scrollToTop(){
		smoothScrollRecyclerViewToTop(list);
	}

	protected int getListWidthForMediaLayout(){
		return list.getWidth();
	}

	protected boolean wantsOverlaySystemNavigation(){
		return true;
	}

	protected void onSetFabBottomInset(int inset){

	}

	public boolean isItemEnabled(StatusDisplayItem item){
		return true;
	}

	public ArrayList<StatusDisplayItem> getDisplayItems(){
		return displayItems;
	}

	public void setLiquidToolbarTopPadding(int padding){
		if(list==null)
			return;
		list.setClipToPadding(false);
		list.setPadding(list.getPaddingLeft(), Math.max(0, padding), list.getPaddingRight(), list.getPaddingBottom());
	}

	public void setLiquidToolbarFabHidden(boolean hidden){
		if(fab!=null && hidden)
			fab.setVisibility(View.GONE);
	}

	// 液态导航条为 overlay：列表底部需要在系统 inset 之上再让出导航玻璃高度
	private int liquidNavBottomPadding;
	private int systemBottomPadding = -1;
	private int baseListBottomPadding = -1;

	/** 由 HomeTabFragment 转发的液态底部导航 overlay 高度（px，不含系统 inset） */
	public void setLiquidNavBottomPadding(int padding){
		if(liquidNavBottomPadding==padding)
			return;
		liquidNavBottomPadding=padding;
		applyListBottomPadding();
	}

	private void applyListBottomPadding(){
		if(list==null)
			return;
		if(baseListBottomPadding<0)
			baseListBottomPadding=list.getPaddingBottom();
		int bottom=Math.max(baseListBottomPadding, Math.max(systemBottomPadding, liquidNavBottomPadding));
		list.setPadding(list.getPaddingLeft(), list.getPaddingTop(), list.getPaddingRight(), bottom);
	}

	@Override
	public void onApplyWindowInsets(WindowInsets insets){
		if(Build.VERSION.SDK_INT>=29 && insets.getTappableElementInsets().bottom==0 && wantsOverlaySystemNavigation()){
			systemBottomPadding=insets.getSystemWindowInsetBottom();
			applyListBottomPadding();
			onSetFabBottomInset(insets.getSystemWindowInsetBottom());
			insets=insets.inset(0, 0, 0, insets.getSystemWindowInsetBottom());
		}else{
			systemBottomPadding=0;
			onSetFabBottomInset(0);
		}
		super.onApplyWindowInsets(insets);
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults){
		if(requestCode==PhotoViewer.PERMISSION_REQUEST && currentPhotoViewer!=null){
			currentPhotoViewer.onRequestPermissionsResult(permissions, grantResults);
		}
	}

	@Override
	public void onResume(){
		super.onResume();
		startPostTranslations();
	}

	@Override
	public void onPause(){
		stopPostTranslations();
		super.onPause();
		if(currentPhotoViewer!=null)
			currentPhotoViewer.onPause();
	}

	private MediaAttachmentViewController makeNewMediaAttachmentView(MediaGridStatusDisplayItem.GridItemType type){
		return new MediaAttachmentViewController(getActivity(), type);
	}

	public TypedObjectPool<MediaGridStatusDisplayItem.GridItemType, MediaAttachmentViewController> getAttachmentViewsPool(){
		return attachmentViewsPool;
	}

	@Override public boolean supportsPostTranslation(){
		// Report screens are native status lists too, but must have no translation side effects.
		return !getClass().getName().startsWith("org.joinmastodon.android.fragments.report.");
	}

	private void startPostTranslations(){
		if(!supportsPostTranslation() || getActivity()==null || list==null || isHidden() || !isResumed()) return;
		translationScreenActive=true;
		translationCoordinator=org.joinmastodon.android.translation.PostTranslationCoordinator.get(getActivity(), accountID);
		if(translationCoordinator==null) return;
		translationCoordinator.resume(translationVisit);
		if(translationPreferencesSubscription==null)
			translationPreferencesSubscription=translationCoordinator.observePreferences(this::scheduleTranslationVisibilityCheck);
		translationCoordinator.refreshPreferences(false);
		scheduleTranslationVisibilityCheck();
	}
	private void stopPostTranslations(){
		translationScreenActive=false;
		if(list!=null) list.removeCallbacks(translationVisibilityCheck);
		if(translationPreferencesSubscription!=null){ translationPreferencesSubscription.cancel(); translationPreferencesSubscription=null; }
		if(translationCoordinator!=null) translationCoordinator.suspend(translationVisit);
	}
	private boolean translationHostCurrent(){
		return translationScreenActive && supportsPostTranslation() && getActivity()!=null && list!=null && !isHidden()
				&& accountID.equals(AccountSessionManager.getInstance().getLastActiveAccountID())
				&& AccountSessionManager.getInstance().tryGetAccount(accountID)!=null;
	}
	private void scheduleTranslationVisibilityCheck(){
		if(list==null || !translationScreenActive) return;
		list.removeCallbacks(translationVisibilityCheck); list.post(translationVisibilityCheck);
	}
	/** A historical list is not a current server permission check. */
	protected boolean shouldAutomaticallyTranslateVisibleContent(){ return true; }

	private void checkVisibleTranslations(){
		if(!shouldAutomaticallyTranslateVisibleContent() || !translationHostCurrent() || translationCoordinator==null) return;
		translationCoordinator.prune();
		for(int i=0;i<list.getChildCount();i++){
			View child=list.getChildAt(i);
			if(!translationChildVisible(child)) continue;
			RecyclerView.ViewHolder holder=list.getChildViewHolder(child);
			if(holder instanceof StatusDisplayItem.Holder<?> itemHolder && itemHolder.getItem() instanceof StatusDisplayItem displayItem){
				var item=translationModuleForVisibleItem(displayItem);
				if(item!=null)
					translationCoordinator.automaticallyTranslate(item.contentStatus, translationVisit, translationListener(item.contentStatus, item.parentID));
			}
		}
		// Re-check capability expiry even when a stationary list has no scroll/bind callbacks.
		if(translationScreenActive) list.postDelayed(translationVisibilityCheck, 1000);
	}
	private boolean translationContentRevealed(org.joinmastodon.android.ui.displayitems.TranslationStatusDisplayItem item){
		Status content=item.contentStatus, owner=item.visibilityStatus;
		if(!android.text.TextUtils.isEmpty(content.spoilerText) && !owner.revealedSpoilers.contains(Status.SpoilerType.CONTENT_WARNING)) return false;
		if(owner.filtered!=null) for(var filter:owner.filtered)
			if(filter.filter.isActive() && !owner.revealedSpoilers.contains(Status.SpoilerType.FILTER)) return false;
		return true;
	}
	private boolean translationChildVisible(View child){
		Rect viewport=new Rect(), bounds=new Rect();
		return child.isShown() && list.getGlobalVisibleRect(viewport) && child.getGlobalVisibleRect(bounds)
				&& Rect.intersects(viewport, bounds);
	}
	private org.joinmastodon.android.ui.displayitems.TranslationStatusDisplayItem translationModuleForVisibleItem(StatusDisplayItem displayItem){
		if(!displayItems.contains(displayItem)) return null;
		if(displayItem instanceof org.joinmastodon.android.ui.displayitems.TranslationStatusDisplayItem module)
			return translationContentRevealed(module) ? module : null;
		if(!(displayItem instanceof TextStatusDisplayItem text)) return null;
		Status content=text.status.getContentStatus();
		// Only a current, expanded module authorizes its own body. Headers/media/footers never prefetch.
		for(StatusDisplayItem candidate:displayItems){
			if(candidate instanceof org.joinmastodon.android.ui.displayitems.TranslationStatusDisplayItem module
					&& module.contentStatus==content && Objects.equals(module.contentStatus.id, content.id)
					&& Objects.equals(module.parentID, text.parentID) && module.isQuote==text.isQuote
					&& org.joinmastodon.android.translation.PostTranslationSupport.sourceVersion(module.contentStatus).equals(org.joinmastodon.android.translation.PostTranslationSupport.sourceVersion(content))
					&& translationContentRevealed(module)) return module;
		}
		return null;
	}
	private boolean resourceVisible(Status status){
		if(!translationHostCurrent()) return false;
		for(int i=0;i<list.getChildCount();i++){
			View child=list.getChildAt(i);
			if(!translationChildVisible(child)) continue;
			if(list.getChildViewHolder(child) instanceof StatusDisplayItem.Holder<?> holder
					&& holder.getItem() instanceof StatusDisplayItem displayItem){
				var module=translationModuleForVisibleItem(displayItem);
				if(module!=null && module.contentStatus==status) return true;
			}
		}
		return false;
	}
	private boolean containsTranslationResource(List<StatusDisplayItem> items, Status status){
		for(StatusDisplayItem item:items){
			if(translationStatus(item)==status) return true;
			if(item instanceof SpoilerStatusDisplayItem spoiler && containsTranslationResource(spoiler.contentItems, status)) return true;
		}
		return false;
	}
	private Status translationStatus(StatusDisplayItem item){
		if(item instanceof TextStatusDisplayItem text) return text.status.getContentStatus();
		if(item instanceof org.joinmastodon.android.ui.displayitems.TranslationStatusDisplayItem module) return module.contentStatus;
		if(item instanceof SpoilerStatusDisplayItem spoiler) return spoiler.contentStatus;
		if(item instanceof org.joinmastodon.android.ui.displayitems.HeaderStatusDisplayItem header) return header.status==null ? null : header.status.getContentStatus();
		if(item instanceof MediaGridStatusDisplayItem media) return media.status.getContentStatus();
		if(item instanceof PollOptionStatusDisplayItem poll) return poll.status.getContentStatus();
		if(item instanceof org.joinmastodon.android.ui.displayitems.FooterStatusDisplayItem footer) return footer.status.getContentStatus();
		return null;
	}
	private org.joinmastodon.android.translation.PostTranslationCoordinator.Listener translationListener(Status status, String parentID){
		return new org.joinmastodon.android.translation.PostTranslationCoordinator.Listener(){
			@Override public boolean current(org.joinmastodon.android.translation.PostTranslationCoordinator.Key key){
				return translationHostCurrent() && accountID.equals(key.accountID()) && status.id.equals(key.resourceID())
						&& org.joinmastodon.android.translation.PostTranslationSupport.target(getActivity()).equals(key.target())
						&& org.joinmastodon.android.translation.PostTranslationSupport.sourceVersion(status).equals(key.sourceVersion())
						&& containsTranslationResource(displayItems, status);
			}
			@Override public boolean visible(){ return resourceVisible(status); }
			@Override public void changed(){ updateTranslation(status); }
			@Override public void error(ErrorResponse error){
					if(!translationHostCurrent() || !resourceVisible(status)) return;
					var pause=org.joinmastodon.android.api.ServiceResourcePause.fromError(error);
					if(pause!=null) pause.show(getActivity());
					else if(error instanceof org.joinmastodon.android.api.requests.translation.TranslationRequest.TranslationError translated) translated.showToast(getActivity());
				else android.widget.Toast.makeText(getActivity(), R.string.post_translation_failed, android.widget.Toast.LENGTH_LONG).show();
			}
		};
	}
	public void togglePostTranslation(Status original, String itemID){
		startPostTranslations();
		if(!translationHostCurrent() || translationCoordinator==null) return;
		Status status=original.getContentStatus();
		translationCoordinator.toggle(status, translationVisit, translationListener(status, itemID));
	}
	private void updateTranslation(Status status){
		if(adapter==null || list==null) return;
		// A quote shares parentID with its parent. Match the real content resource + version instead.
		for(int i=0;i<displayItems.size();i++){
			Status candidate=translationStatus(displayItems.get(i));
			if(candidate!=null && Objects.equals(candidate.id, status.id)
					&& org.joinmastodon.android.translation.PostTranslationSupport.sourceVersion(candidate).equals(org.joinmastodon.android.translation.PostTranslationSupport.sourceVersion(status))){
				if(candidate!=status){
					candidate.translation=status.translation; candidate.translationState=status.translationState;
					candidate.translationAccountID=status.translationAccountID; candidate.translationTargetLanguage=status.translationTargetLanguage;
					candidate.translationSourceVersion=status.translationSourceVersion; candidate.translationSourceHash=status.translationSourceHash;
					candidate.translationValidatedAt=status.translationValidatedAt;
				}
				adapter.notifyItemChanged(i);
			}
		}
	}

	public void rebuildAllDisplayItems(){
		displayItems.clear();
		for(T item:data){
			displayItems.addAll(buildDisplayItems(item));
		}
		adapter.notifyDataSetChanged();
	}

	public void maybeShowPreReplySheet(Status status, Runnable proceed){
		Relationship rel=getRelationship(status.account.id);
		if(!GlobalUserPreferences.isOptedOutOfPreReplySheet(GlobalUserPreferences.PreReplySheetType.NON_MUTUAL, status.account, accountID) &&
				!status.account.id.equals(AccountSessionManager.get(accountID).self.id) && rel!=null && !rel.followedBy && status.account.followingCount>=1){
			new NonMutualPreReplySheet(getActivity(), notAgain->{
				GlobalUserPreferences.optOutOfPreReplySheet(GlobalUserPreferences.PreReplySheetType.NON_MUTUAL, notAgain ? null : status.account, accountID);
				proceed.run();
			}, status.account, accountID).show();
		}else if(!GlobalUserPreferences.isOptedOutOfPreReplySheet(GlobalUserPreferences.PreReplySheetType.OLD_POST, null, null) &&
				status.createdAt.isBefore(Instant.now().minus(90, ChronoUnit.DAYS)) && !status.account.id.equals(AccountSessionManager.get(accountID).self.id)){
			new OldPostPreReplySheet(getActivity(), notAgain->{
				if(notAgain)
					GlobalUserPreferences.optOutOfPreReplySheet(GlobalUserPreferences.PreReplySheetType.OLD_POST, null, null);
				proceed.run();
			}, status).show();
		}else{
			proceed.run();
		}
	}

	protected void onModifyItemViewHolder(BindableViewHolder<StatusDisplayItem> holder){}

	public void shakeListView(){
		if(listShakeAnimation!=null)
			listShakeAnimation.cancel();
		SpringAnimation anim=new SpringAnimation(list, DynamicAnimation.TRANSLATION_X, 0);
		anim.setStartVelocity(V.dp(-500));
		anim.getSpring().setStiffness(500).setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY);
		listShakeAnimation=anim;
		anim.addEndListener((animation, canceled, value, velocity)->listShakeAnimation=null);
		anim.start();
	}

	public void retryFailedImages(){
		imgLoader.retryFailedRequests();
	}

	@Override
	public void scheduleRetryFailedImages(){
		if(list==null || retryFailedImagesScheduled)
			return;
		retryFailedImagesScheduled=true;
		list.postDelayed(retryFailedImagesRunnable, 1500);
	}

	@Override
	public void onDestroyView(){
		stopPostTranslations();
		if(nbwSubscription!=null){ nbwSubscription.cancel(); nbwSubscription=null; }
		if(list!=null){
			list.removeCallbacks(retryFailedImagesRunnable);
			retryFailedImagesScheduled=false;
		}
		super.onDestroyView();
	}

	public void removeDisplayItem(StatusDisplayItem item){
		int index=displayItems.indexOf(item);
		if(index==-1)
			return;
		displayItems.remove(index);
		adapter.notifyItemRemoved(index);
	}

	@Override
	public void refresh(){
		super.refresh();
	}

	protected class DisplayItemsAdapter extends UsableRecyclerView.Adapter<BindableViewHolder<StatusDisplayItem>> implements ImageLoaderRecyclerAdapter{

		public DisplayItemsAdapter(){
			super(imgLoader);
		}

		@NonNull
		@Override
		public BindableViewHolder<StatusDisplayItem> onCreateViewHolder(@NonNull ViewGroup parent, int viewType){
			BindableViewHolder<StatusDisplayItem> holder=(BindableViewHolder<StatusDisplayItem>) StatusDisplayItem.createViewHolder(org.joinmastodon.android.ui.displayitems.StatusDisplayItem.Type.values()[viewType & (~0x80000000)], getActivity(), parent, BaseStatusListFragment.this);
			onModifyItemViewHolder(holder);
			return holder;
		}

		@Override
		public void onBindViewHolder(BindableViewHolder<StatusDisplayItem> holder, int position){
			holder.bind(displayItems.get(position));
			super.onBindViewHolder(holder, position);
		}

		@Override
		public int getItemCount(){
			return displayItems.size();
		}

		@Override
		public int getItemViewType(int position){
			return displayItems.get(position).getType().ordinal() | 0x80000000;
		}

		@Override
		public int getImageCountForItem(int position){
			return displayItems.get(position).getImageCount();
		}

		@Override
		public ImageLoaderRequest getImageRequest(int position, int image){
			return displayItems.get(position).getImageRequest(image);
		}
	}

	private class StatusListItemDecoration extends RecyclerView.ItemDecoration{
		private Paint dividerPaint=new Paint();
		private Drawable quoteBorder=getResources().getDrawable(R.drawable.bg_inline_status, getActivity().getTheme()).mutate();
		private ArrayList<StatusDisplayItem.Holder<?>> itemsTopToBottom=new ArrayList<>();

		{
			dividerPaint.setColor(UiUtils.getThemeColor(getActivity(), R.attr.colorM3OutlineVariant));
			dividerPaint.setStyle(Paint.Style.STROKE);
			dividerPaint.setStrokeWidth(V.dp(0.5f));
		}

		@Override
		public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state){
			itemsTopToBottom.clear();
			for(int i=0;i<parent.getChildCount();i++){
				View child=parent.getChildAt(i);
				RecyclerView.ViewHolder holder=parent.getChildViewHolder(child);
				if(i<parent.getChildCount()-1){
					View bottomSibling=parent.getChildAt(i+1);
					RecyclerView.ViewHolder siblingHolder=parent.getChildViewHolder(bottomSibling);
					if(needDrawDivider(holder, siblingHolder)){
						drawDivider(child, bottomSibling, holder, siblingHolder, parent, c, dividerPaint);
					}
				}

				if(holder instanceof StatusDisplayItem.Holder<?> ih){
					itemsTopToBottom.add(ih);
				}
			}
			itemsTopToBottom.sort((v1, v2)->Float.compare(v1.itemView.getY(), v2.itemView.getY()));

			boolean insideQuote=false;
			int quoteTop=0;
			StatusDisplayItem.Holder<?> quoteStartHolder=null;
			boolean isRTL=parent.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL;
			for(StatusDisplayItem.Holder<?> vh:itemsTopToBottom){
				StatusDisplayItem item=(StatusDisplayItem) vh.getItem();
				if(item.isQuote){
					if(!insideQuote){
						insideQuote=true;
						quoteStartHolder=vh;
						quoteTop=Math.round(vh.itemView.getY());
						if(item.getType()!=StatusDisplayItem.Type.HEADER_COMPACT)
							quoteTop-=V.dp(16);
					}
				}else if(insideQuote){
					insideQuote=false;
					StatusDisplayItem firstItem=(StatusDisplayItem) quoteStartHolder.getItem();
					quoteBorder.setAlpha(Math.round(quoteStartHolder.itemView.getAlpha()*255));
					int bottom=Math.round(vh.itemView.getY());
//					if(item.getType()!=StatusDisplayItem.Type.EXTENDED_FOOTER)
						bottom-=V.dp(8);
					if(isRTL)
						quoteBorder.setBounds(V.dp(16), quoteTop, parent.getWidth()-V.dp(firstItem.fullWidth ? 16 : 48+16), bottom);
					else
						quoteBorder.setBounds(firstItem.fullWidth ? V.dp(16) : V.dp(48+16), quoteTop, parent.getWidth()-V.dp(16), bottom);
					quoteBorder.draw(c);
				}
			}
			if(insideQuote){
				StatusDisplayItem firstItem=(StatusDisplayItem) quoteStartHolder.getItem();
				quoteBorder.setAlpha(Math.round(quoteStartHolder.itemView.getAlpha()*255));
				if(isRTL)
					quoteBorder.setBounds(V.dp(16), quoteTop, parent.getWidth()-V.dp(firstItem.fullWidth ? 16 : 48+16), parent.getHeight()+V.dp(16));
				else
					quoteBorder.setBounds(firstItem.fullWidth ? V.dp(16) : V.dp(48+16), quoteTop, parent.getWidth()-V.dp(16), parent.getHeight()+V.dp(16));
				quoteBorder.draw(c);
			}
		}

		@Override
		public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state){
			RecyclerView.ViewHolder holder=parent.getChildViewHolder(view);
			if(holder instanceof StatusDisplayItem.Holder<?> ih && ih.getItem() instanceof StatusDisplayItem item){
				if(item.isQuote){
					outRect.left=outRect.right=V.dp(12);
					if(item.getType()==StatusDisplayItem.Type.HEADER_COMPACT){
						outRect.top=V.dp(8);
					}
				}else if((item.getType()==StatusDisplayItem.Type.FOOTER && !item.fullWidth) || item.getType()==StatusDisplayItem.Type.EXTENDED_FOOTER){
					// Apply this as the top offset to the footer to avoid breaking spoiler transitions
					int itemIndex=ih.getAbsoluteAdapterPosition()-getMainAdapterOffset();
					if(itemIndex>0){
						StatusDisplayItem topSibling=displayItems.get(itemIndex-1);
						if(topSibling.isQuote){
							outRect.top=V.dp(switch(topSibling.getType()){
								case TEXT, CARD_COMPACT, CARD_LARGE -> 12;
								case MEDIA_GRID, AUDIO -> 14;
								default -> 20;
							});
						}
					}
				}
			}
		}

		private boolean needDrawDivider(RecyclerView.ViewHolder holder, RecyclerView.ViewHolder siblingHolder){
			if(needDividerForExtraItem(holder.itemView, siblingHolder.itemView, holder, siblingHolder))
				return true;
			if(holder instanceof StatusDisplayItem.Holder<?> ih && siblingHolder instanceof StatusDisplayItem.Holder<?> sh){
				// Do not draw dividers between hashtag and/or account rows
				if((ih instanceof HashtagStatusDisplayItem.Holder || ih instanceof AccountStatusDisplayItem.Holder) && (sh instanceof HashtagStatusDisplayItem.Holder || sh instanceof AccountStatusDisplayItem.Holder))
					return false;
				return !ih.getItemID().equals(sh.getItemID()) && ih.getItem() instanceof StatusDisplayItem sdi && sdi.getType()!=StatusDisplayItem.Type.GAP;
			}
			return false;
		}
	}
}
