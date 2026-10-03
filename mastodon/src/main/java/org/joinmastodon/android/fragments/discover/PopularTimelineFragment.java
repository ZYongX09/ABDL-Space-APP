package org.joinmastodon.android.fragments.discover;

import android.net.Uri;

import org.joinmastodon.android.api.requests.timelines.GetPopularTimeline;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.StatusListFragment;
import org.joinmastodon.android.model.FilterContext;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.utils.ProvidesAssistContent;

import java.util.List;

import me.grishka.appkit.api.SimpleCallback;

/** 热门时间线 — 按后端热度从高到低展示公开根帖。 */
public class PopularTimelineFragment extends StatusListFragment implements ProvidesAssistContent.ProvidesWebUri{
	@Override
	protected void doLoadData(int offset, int count){
		currentRequest=new GetPopularTimeline(offset, count)
				.setCallback(new SimpleCallback<>(this){
					@Override
					public void onSuccess(List<Status> result){
						if(getActivity()==null) return;
						AccountSessionManager.get(accountID).filterStatuses(result, getFilterContext());
						onDataLoaded(result, result.size()>=count);
					}
				})
				.exec(accountID);
	}

	@Override
	protected FilterContext getFilterContext(){
		return FilterContext.PUBLIC;
	}

	@Override
	public Uri getWebUri(Uri.Builder base){
		return base.path("/api/v1/timelines/popular").build();
	}
}
