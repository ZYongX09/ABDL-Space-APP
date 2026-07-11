package org.joinmastodon.android.api.requests.friendrequests;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;

import java.util.Map;

import okhttp3.internal.http.HttpMethod;

public class GetMyFriendRequests extends MastodonAPIRequest<Map<String, Object>>{
	public GetMyFriendRequests(){
		super(HttpMethod.GET, "/friend-request/my", new TypeToken<Map<String, Object>>(){});
	}

	@Override
	protected String getPathPrefix(){
		return "/api";
	}
}
