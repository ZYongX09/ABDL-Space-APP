package org.joinmastodon.android.fragments.discover;

import android.net.Uri;
import android.os.Bundle;

import org.joinmastodon.android.api.requests.timelines.GetAllTimeline;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.StatusListFragment;
import org.joinmastodon.android.model.FilterContext;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.ui.utils.DiscoverInfoBannerHelper;
import org.joinmastodon.android.utils.ProvidesAssistContent;

import java.util.List;

import androidx.recyclerview.widget.RecyclerView;
import me.grishka.appkit.api.SimpleCallback;
import me.grishka.appkit.utils.MergeRecyclerAdapter;

public class FederatedTimelineFragment extends StatusListFragment implements ProvidesAssistContent.ProvidesWebUri{
	private DiscoverInfoBannerHelper bannerHelper;
	private String maxID;

	@Override
	public void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		bannerHelper=new DiscoverInfoBannerHelper(DiscoverInfoBannerHelper.BannerType.FEDERATED_TIMELINE, accountID);
	}

	@Override
	protected void doLoadData(int offset, int count){
		if(offset==0) maxID=null;
		GetAllTimeline request=new GetAllTimeline(maxID, count);
		currentRequest=request
				.setCallback(new SimpleCallback<>(this){
					@Override
					public void onSuccess(List<Status> result){
						if(getActivity()==null) return;
						maxID=request.getNextMaxID();
						AccountSessionManager.get(accountID).filterStatuses(result, getFilterContext());
						onDataLoaded(result, maxID!=null);
						bannerHelper.onBannerBecameVisible();
					}
				})
				.exec(accountID);
	}

	@Override
	protected String getMaxID(){
		return maxID;
	}

	@Override
	protected RecyclerView.Adapter<?> getAdapter(){
		MergeRecyclerAdapter adapter=new MergeRecyclerAdapter();
		bannerHelper.maybeAddBanner(list, adapter);
		adapter.addAdapter(super.getAdapter());
		return adapter;
	}

	@Override
	protected FilterContext getFilterContext(){
		return FilterContext.PUBLIC;
	}

	@Override
	public Uri getWebUri(Uri.Builder base){
		return base.path("/api/v1/timelines/all").build();
	}
}
