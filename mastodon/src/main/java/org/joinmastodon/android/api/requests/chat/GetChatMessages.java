package org.joinmastodon.android.api.requests.chat;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;

import java.util.Map;

import okhttp3.internal.http.HttpMethod;

public class GetChatMessages extends MastodonAPIRequest<Map<String, Object>>{
	public GetChatMessages(long userId, long beforeId, int limit){
		super(HttpMethod.GET, "/messages/"+userId, new TypeToken<Map<String, Object>>(){});
		if(beforeId>0) addQueryParameter("before_id", String.valueOf(beforeId));
		addQueryParameter("limit", String.valueOf(limit));
	}

	@Override
	protected String getPathPrefix(){
		return "/api";
	}
}
