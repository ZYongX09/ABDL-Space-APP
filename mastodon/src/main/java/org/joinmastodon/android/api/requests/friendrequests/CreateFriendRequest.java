package org.joinmastodon.android.api.requests.friendrequests;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;

import java.util.Map;

import okhttp3.internal.http.HttpMethod;

public class CreateFriendRequest extends MastodonAPIRequest<Map<String, Object>>{
	public CreateFriendRequest(String title, String lookingFor, String description, java.util.List<Map<String, Object>> fields){
		super(HttpMethod.POST, "/friend-request/create", new TypeToken<Map<String, Object>>(){});
		Map<String, Object> body = new java.util.HashMap<>();
		body.put("title", title);
		body.put("looking_for", lookingFor);
		if(description!=null) body.put("description", description);
		if(fields!=null && !fields.isEmpty()) body.put("fields", fields);
		setRequestBody(body);
	}

	@Override
	protected String getPathPrefix(){
		return "/api";
	}
}
