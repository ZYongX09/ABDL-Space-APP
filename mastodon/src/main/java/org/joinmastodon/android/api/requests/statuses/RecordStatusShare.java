package org.joinmastodon.android.api.requests.statuses;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.StatusHeatResponse;

/**
 * 原生分享打点 — 用户点击分享按钮并使用系统分享时调用。
 * 服务端每次 +1（无去重），响应返回最新分享数与热度，供页面内就地刷新。
 * 失败静默，不阻塞主流程。
 */
public class RecordStatusShare extends MastodonAPIRequest<StatusHeatResponse>{
	public RecordStatusShare(String id){
		super(HttpMethod.POST, "/statuses/"+id+"/share", StatusHeatResponse.class);
		setRequestBody(new Object());
	}
}
