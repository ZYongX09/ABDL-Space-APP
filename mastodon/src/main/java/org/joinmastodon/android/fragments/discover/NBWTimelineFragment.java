package org.joinmastodon.android.fragments.discover;

import android.net.Uri;

import org.joinmastodon.android.api.requests.timelines.GetNBWTimeline;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.StatusListFragment;
import org.joinmastodon.android.model.FilterContext;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.utils.ProvidesAssistContent;

import java.util.List;

import me.grishka.appkit.api.SimpleCallback;

public class NBWTimelineFragment extends StatusListFragment implements ProvidesAssistContent.ProvidesWebUri{
	private String maxID;

	@Override
	protected void doLoadData(int offset, int count){
		if(offset==0) maxID=null;
		GetNBWTimeline request=new GetNBWTimeline(maxID, count, null, null);
		currentRequest=request
				.setCallback(new SimpleCallback<>(this){
					@Override
					public void onSuccess(List<Status> result){
						if(getActivity()==null) return;
						maxID=request.getNextMaxID();
						AccountSessionManager.get(accountID).filterStatuses(result, getFilterContext());
						onDataLoaded(result, maxID!=null);
					}
				})
				.exec(accountID);
	}

	@Override
	protected String getMaxID(){
		return maxID;
	}

	@Override
	protected FilterContext getFilterContext(){
		return FilterContext.PUBLIC;
	}

	@Override
	public Uri getWebUri(Uri.Builder base){
		return base.path("/api/v1/timelines/nbw").build();
	}
}
