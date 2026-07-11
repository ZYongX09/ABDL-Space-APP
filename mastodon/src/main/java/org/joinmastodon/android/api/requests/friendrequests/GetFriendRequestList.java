package org.joinmastodon.android.api.requests.friendrequests;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;

import java.util.HashMap;
import java.util.Map;

import org.joinmastodon.android.model.FriendRequest;
import okhttp3.internal.http.HttpMethod;

public class GetFriendRequestList extends MastodonAPIRequest<Map<String, Object>>{
	public GetFriendRequestList(int page, int limit, String search, String lookingFor){
		super(HttpMethod.GET, "/friend-request/list", new TypeToken<Map<String, Object>>(){});
		addQueryParameter("page", String.valueOf(page));
		addQueryParameter("limit", String.valueOf(limit));
		if(search!=null && !search.isEmpty()) addQueryParameter("search", search);
		if(lookingFor!=null && !lookingFor.isEmpty()) addQueryParameter("looking_for", lookingFor);
	}

	@Override
	protected String getPathPrefix(){
		return "/api";
	}
}
