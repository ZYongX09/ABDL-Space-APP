package org.joinmastodon.android.events;

import org.joinmastodon.android.model.Status;

public class StatusCountersUpdatedEvent{
	public String id;
	public long favorites, reblogs, replies;
	public boolean favorited, reblogged, bookmarked;

	// MOSHIDON:
	public boolean pinned;

	// 浏览量与热度：随事件一并下发，页面内就地刷新数值
	public long views;
	public double heat;

	public final CounterType type;

	public StatusCountersUpdatedEvent(Status s, CounterType type){
		id=s.id;
		favorites=s.favouritesCount;
		favorited=s.favourited;
		reblogs=s.reblogsCount;
		reblogged=s.reblogged;
		replies=s.repliesCount;

		// MOSHIDON:
		if (s.pinned != null) {
			pinned=s.pinned;
		}
		bookmarked=s.bookmarked;
		views=s.viewsCount;
		heat=s.heat;

		this.type=type;
	}

	public enum CounterType{
		FAVORITES,
		REBLOGS,
		REPLIES,
		BOOKMARKS,

		// 浏览量/热度刷新（浏览/分享后），仅借事件通道触发重绘，不覆盖其它计数
		HEAT

	}
}
