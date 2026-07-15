package org.joinmastodon.android.api.requests.chat;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;

import java.util.Map;

import okhttp3.internal.http.HttpMethod;

public class GetConversations extends MastodonAPIRequest<Map<String, Object>>{
	public GetConversations(){
		super(HttpMethod.GET, "/messages/conversations", new TypeToken<Map<String, Object>>(){});
	}

	@Override
	protected String getPathPrefix(){
		return "/api";
	}
}
