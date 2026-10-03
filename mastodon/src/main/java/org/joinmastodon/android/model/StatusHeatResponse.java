package org.joinmastodon.android.model;

import com.google.gson.annotations.SerializedName;

/**
 * 浏览/分享打点后的服务端响应：最新计数 + 热度。
 * 后端在 POST /statuses/:id/view|share 时计算并返回，App 据此就地刷新页面内数值。
 */
public class StatusHeatResponse{
	@SerializedName("views_count")
	public long viewsCount;

	@SerializedName("shares_count")
	public long sharesCount;

	@SerializedName("counted")
	public boolean counted;

	@SerializedName("heat")
	public double heat;
}
