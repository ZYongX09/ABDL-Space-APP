package org.joinmastodon.android.fragments;

import android.net.Uri;
import android.os.Bundle;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.timelines.GetHomeTimeline;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.fragments.discover.LocalTimelineFragment;
import org.joinmastodon.android.model.FilterContext;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.utils.ProvidesAssistContent;

import java.util.List;

import me.grishka.appkit.api.SimpleCallback;

/**
 * 「关注」时间线：仅显示已关注用户的帖子（GET /api/v1/timelines/home）。
 * 与「主页」时间线（GetAllTimeline，/timelines/all 全站内容）区分。
 */
public class FollowingTimelineFragment extends StatusListFragment implements ProvidesAssistContent.ProvidesWebUri{
	private String maxID;

	@Override
	public void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		setTitle(R.string.sk_timeline_following);
	}

	@Override
	protected void doLoadData(int offset, int count){
		currentRequest=new GetHomeTimeline(offset>0 ? getMaxID() : null, null, count, null)
				.setCallback(new SimpleCallback<>(this){
					@Override
					public void onSuccess(List<Status> result){
						if(getActivity()==null)
							return;
						boolean more=applyMaxID(result);
						AccountSessionManager.get(accountID).filterStatuses(result, getFilterContext());
						onDataLoaded(result, more);
					}
				})
				.exec(accountID);
	}

	@Override
	protected FilterContext getFilterContext(){
		return FilterContext.HOME;
	}

	@Override
	public Uri getWebUri(Uri.Builder base){
		return base.path("/home").build();
	}
}
