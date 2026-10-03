package org.joinmastodon.android.model;

import com.google.gson.annotations.SerializedName;

import org.joinmastodon.android.api.ObjectValidationException;

/**
 * 服务公告 — 对应后端 GET /api/broadcast/notice 返回的 notice 字段。
 * 后端已在 KV 中按 startAt/endAt 时间窗口过滤，返回的即为「当前应展示」的公告。
 * 仅用于本地弹窗展示（不参与 Mastodon 协议），因此不实现 Parcelable。
 */
public class ServiceNotice{
	public String id;
	public String title;
	public String content;
	/** epoch 秒；可为 null（未设置） */
	public Long startAt;
	/** epoch 秒；可为 null（不自动过期） */
	public Long endAt;
	@SerializedName("createdAt")
	public Long createdAt;

	public void postprocess() throws ObjectValidationException{
		if(id==null || id.isEmpty())
			throw new ObjectValidationException();
	}
}
