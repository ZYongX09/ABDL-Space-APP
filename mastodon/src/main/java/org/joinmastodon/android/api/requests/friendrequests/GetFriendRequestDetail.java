package org.joinmastodon.android.api.requests.friendrequests;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;

import okhttp3.internal.http.HttpMethod;

public class GetFriendRequestDetail extends MastodonAPIRequest<java.util.Map<String, Object>>{
	public GetFriendRequestDetail(String id){
		super(HttpMethod.GET, "/friend-request/"+id, new TypeToken<java.util.Map<String, Object>>(){});
	}

	@Override
	protected String getPathPrefix(){
		return "/api";
	}
}
