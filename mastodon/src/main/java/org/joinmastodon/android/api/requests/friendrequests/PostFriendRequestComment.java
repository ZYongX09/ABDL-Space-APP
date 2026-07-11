package org.joinmastodon.android.api.requests.friendrequests;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;

import java.util.HashMap;
import java.util.Map;

import okhttp3.internal.http.HttpMethod;

public class PostFriendRequestComment extends MastodonAPIRequest<Map<String, Object>>{
	public PostFriendRequestComment(String requestId, String content, String parentId){
		super(HttpMethod.POST, "/friend-request/"+requestId+"/comment", new TypeToken<Map<String, Object>>(){});
		Map<String, Object> body = new HashMap<>();
		body.put("content", content);
		if(parentId!=null) body.put("parent_id", parentId);
		setRequestBody(body);
	}

	@Override
	protected String getPathPrefix(){
		return "/api";
	}
}
