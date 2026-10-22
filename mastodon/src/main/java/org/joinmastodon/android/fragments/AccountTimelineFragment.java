package org.joinmastodon.android.fragments;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.accounts.GetAccountStatuses;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.events.RemoveAccountPostsEvent;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.FilterContext;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.ui.drawables.EmptyDrawable;
import org.joinmastodon.android.ui.views.FilterChipView;
import org.parceler.Parcels;

import java.util.Collections;
import java.util.List;

import androidx.recyclerview.widget.RecyclerView;
import me.grishka.appkit.api.SimpleCallback;
import me.grishka.appkit.utils.MergeRecyclerAdapter;
import me.grishka.appkit.utils.SingleViewRecyclerAdapter;
import me.grishka.appkit.utils.V;

public class AccountTimelineFragment extends StatusListFragment{
	private Account user;
	private GetAccountStatuses.Filter filter;
	private String nbwNextMaxID;
	private HorizontalScrollView filtersBar;
	private FilterChipView defaultFilter, withRepliesFilter, mediaFilter;

	public AccountTimelineFragment(){
		setListLayoutId(R.layout.recycler_fragment_no_refresh);
	}

	public static AccountTimelineFragment newInstance(String accountID, Account profileAccount, boolean load){
		AccountTimelineFragment f=new AccountTimelineFragment();
		Bundle args=new Bundle();
		args.putString("account", accountID);
		args.putParcelable("profileAccount", Parcels.wrap(profileAccount));
		if(!load)
			args.putBoolean("noAutoLoad", true);
		args.putBoolean("__is_tab", true);
		f.setArguments(args);
		return f;
	}

	@Override
	public void onAttach(Activity activity){
		user=Parcels.unwrap(getArguments().getParcelable("profileAccount"));
		filter=GetAccountStatuses.Filter.DEFAULT;
		super.onAttach(activity);
	}

	@Override
	protected void doLoadData(){
		nbwNextMaxID=null; // Initial load, refresh and filter changes start a new sequence.
		super.doLoadData();
	}

	@Override
	protected void doLoadData(int offset, int count){
		// Sparse NBW pages may be empty: offset can remain zero while a next cursor still exists.
		String maxID=user.isNBWAccount() ? nbwNextMaxID : offset>0 ? getMaxID() : null;
		GetAccountStatuses request=new GetAccountStatuses(user.id, maxID, null, count, filter, null);
		currentRequest=request.setCallback(new SimpleCallback<>(this){
					@Override
					public void onSuccess(List<Status> result){
						if(getActivity()==null)
							return;
						boolean empty=result.isEmpty();
						nbwNextMaxID=request.getNextMaxID();
						AccountSessionManager.get(accountID).filterStatuses(result, FilterContext.ACCOUNT);
						onDataLoaded(result, user.isNBWAccount() ? nbwNextMaxID!=null : !empty);
					}
				})
				.exec(accountID);
	}

	@Override
	public void onViewCreated(View view, Bundle savedInstanceState){
		super.onViewCreated(view, savedInstanceState);
		view.setBackground(null); // prevents unnecessary overdraw
	}

	@Override
	protected void onShown(){
		super.onShown();
		if(!getArguments().getBoolean("noAutoLoad") && !loaded && !dataLoading)
			loadData();
	}

	protected void onStatusCreated(Status status){
		if(!AccountSessionManager.getInstance().isSelf(accountID, user) || !AccountSessionManager.getInstance().isSelf(accountID, status.account)
				|| getStatusByID(status.id)!=null)
			return;
		if(filter==GetAccountStatuses.Filter.DEFAULT){
			// Match exclude_replies=true even for self-replies or a missing parent account.
			if(status.inReplyToId!=null)
				return;
		}else if(filter==GetAccountStatuses.Filter.MEDIA){
			if(status.mediaAttachments.isEmpty())
				return;
		}
		prependItems(Collections.singletonList(status), true);
	}

	@Override
	protected void onRemoveAccountPostsEvent(RemoveAccountPostsEvent ev){
		// no-op
	}

	@Override
	protected RecyclerView.Adapter getAdapter(){
		filtersBar=new HorizontalScrollView(getActivity());
		LinearLayout filtersLayout=new LinearLayout(getActivity());
		filtersBar.addView(filtersLayout);
		filtersLayout.setOrientation(LinearLayout.HORIZONTAL);
		filtersLayout.setPadding(V.dp(16), V.dp(16), V.dp(16), V.dp(8));
		filtersLayout.setDividerDrawable(new EmptyDrawable(V.dp(8), 1));
		filtersLayout.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);

		defaultFilter=new FilterChipView(getActivity());
		defaultFilter.setText(R.string.posts);
		defaultFilter.setTag(GetAccountStatuses.Filter.DEFAULT);
		defaultFilter.setSelected(filter==GetAccountStatuses.Filter.DEFAULT);
		defaultFilter.setOnClickListener(this::onFilterClick);
		filtersLayout.addView(defaultFilter);

		withRepliesFilter=new FilterChipView(getActivity());
		withRepliesFilter.setText(R.string.posts_and_replies);
		withRepliesFilter.setTag(GetAccountStatuses.Filter.INCLUDE_REPLIES);
		withRepliesFilter.setSelected(filter==GetAccountStatuses.Filter.INCLUDE_REPLIES);
		withRepliesFilter.setOnClickListener(this::onFilterClick);
		filtersLayout.addView(withRepliesFilter);

		mediaFilter=new FilterChipView(getActivity());
		mediaFilter.setText(R.string.media);
		mediaFilter.setTag(GetAccountStatuses.Filter.MEDIA);
		mediaFilter.setSelected(filter==GetAccountStatuses.Filter.MEDIA);
		mediaFilter.setOnClickListener(this::onFilterClick);
		filtersLayout.addView(mediaFilter);

		MergeRecyclerAdapter mergeAdapter=new MergeRecyclerAdapter();
		mergeAdapter.addAdapter(new SingleViewRecyclerAdapter(filtersBar));
		mergeAdapter.addAdapter(super.getAdapter());
		return mergeAdapter;
	}

	private FilterChipView getViewForFilter(GetAccountStatuses.Filter filter){
		return switch(filter){
			case DEFAULT -> defaultFilter;
			case INCLUDE_REPLIES -> withRepliesFilter;
			case MEDIA -> mediaFilter;
			default -> throw new IllegalStateException("Unexpected value: "+filter);
		};
	}

	private void onFilterClick(View v){
		GetAccountStatuses.Filter newFilter=(GetAccountStatuses.Filter) v.getTag();
		if(newFilter==filter)
			return;
		// TODO maybe cache the filtered timelines that were already loaded?
		if(currentRequest!=null){
			currentRequest.cancel();
			currentRequest=null;
		}
		getViewForFilter(filter).setSelected(false);
		filter=newFilter;
		v.setSelected(true);
		data.clear();
		preloadedData.clear();
		int size=displayItems.size();
		displayItems.clear();
		adapter.notifyItemRangeRemoved(0, size);
		loaded=false;
		dataLoading=true;
		doLoadData();
	}

	// MOSHIDON:
	@Override
	protected FilterContext getFilterContext() {
		return FilterContext.ACCOUNT;
	}

	@Override
	public Uri getWebUri(Uri.Builder base) {
		// could return different uris based on filter (e.g. media -> "/media"), but i want to
		// return the remote url to the user, and i don't know whether i'd need to append
		// '#media' (akkoma/pleroma) or '/media' (glitch/mastodon) since i don't know anything
		// about the remote instance. so, just returning the base url to the user instead
		return Uri.parse(user.url);
	}
}
