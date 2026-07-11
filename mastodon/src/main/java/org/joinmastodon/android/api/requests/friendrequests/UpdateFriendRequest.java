package org.joinmastodon.android.api.requests.friendrequests;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.internal.http.HttpMethod;

public class UpdateFriendRequest extends MastodonAPIRequest<Map<String, Object>>{
	public UpdateFriendRequest(String id, String title, String lookingFor, String description, List<Map<String, Object>> fields){
		super(HttpMethod.PATCH, "/friend-request/"+id, new TypeToken<Map<String, Object>>(){});
		Map<String, Object> body = new HashMap<>();
		if(title!=null) body.put("title", title);
		if(lookingFor!=null) body.put("looking_for", lookingFor);
		if(description!=null) body.put("description", description);
		if(fields!=null) body.put("fields", fields);
		setRequestBody(body);
	}

	@Override
	protected String getPathPrefix(){
		return "/api";
	}
}
