package org.joinmastodon.android.api.requests.friendrequests;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;

import java.util.Map;

import okhttp3.internal.http.HttpMethod;

public class GetFriendRequestComments extends MastodonAPIRequest<Map<String, Object>>{
	public GetFriendRequestComments(String requestId){
		super(HttpMethod.GET, "/friend-request/"+requestId+"/comments", new TypeToken<Map<String, Object>>(){});
	}

	@Override
	protected String getPathPrefix(){
		return "/api";
	}
}
