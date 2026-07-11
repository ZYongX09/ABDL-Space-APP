package org.joinmastodon.android.api.requests.friendrequests;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;

import java.util.Map;

import okhttp3.internal.http.HttpMethod;

public class DeleteFriendRequest extends MastodonAPIRequest<Map<String, Object>>{
	public DeleteFriendRequest(String id){
		super(HttpMethod.DELETE, "/friend-request/"+id, new TypeToken<Map<String, Object>>(){});
	}

	@Override
	protected String getPathPrefix(){
		return "/api";
	}
}
