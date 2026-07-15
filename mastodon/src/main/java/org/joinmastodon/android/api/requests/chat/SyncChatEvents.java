package org.joinmastodon.android.api.requests.chat;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;

import java.util.Map;

import okhttp3.internal.http.HttpMethod;

public class SyncChatEvents extends MastodonAPIRequest<Map<String, Object>>{
	public SyncChatEvents(long afterEventId, long throughEventId, int limit){
		super(HttpMethod.GET, "/messages/sync", new TypeToken<Map<String, Object>>(){});
		addQueryParameter("after_event_id", String.valueOf(afterEventId));
		if(throughEventId>0) addQueryParameter("through_event_id", String.valueOf(throughEventId));
		addQueryParameter("limit", String.valueOf(limit));
	}

	@Override
	protected String getPathPrefix(){
		return "/api";
	}
}
