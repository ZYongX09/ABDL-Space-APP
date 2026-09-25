package org.joinmastodon.android.api.requests.statuses;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.StatusHeatResponse;

/**
 * 浏览打点 — 用户打开帖子详情页或查看大图时调用。
 * 服务端做 12 小时滑窗去重（同一用户对同一帖子每 12h 只计 1 次），
 * 并在响应中返回最新浏览量与热度，供页面内就地刷新。
 */
public class RecordStatusView extends MastodonAPIRequest<StatusHeatResponse>{
	public RecordStatusView(String id){
		super(HttpMethod.POST, "/statuses/"+id+"/view", StatusHeatResponse.class);
		setRequestBody(new Object());
	}
}
