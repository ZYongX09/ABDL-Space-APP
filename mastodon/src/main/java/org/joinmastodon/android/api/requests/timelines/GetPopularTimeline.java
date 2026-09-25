package org.joinmastodon.android.api.requests.timelines;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.Status;

import java.util.List;

/** 按后端热度降序获取热门帖子，使用 offset 分页。 */
public class GetPopularTimeline extends MastodonAPIRequest<List<Status>>{
	public GetPopularTimeline(int offset, int limit){
		super(HttpMethod.GET, "/timelines/popular", new TypeToken<>(){});
		if(offset>0)
			addQueryParameter("offset", offset+"");
		if(limit>0)
			addQueryParameter("limit", limit+"");
		removeUnsupportedItems=true;
	}
}
