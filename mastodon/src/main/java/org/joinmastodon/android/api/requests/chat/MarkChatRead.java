package org.joinmastodon.android.api.requests.chat;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;

import java.util.Map;

import okhttp3.RequestBody;
import okhttp3.internal.http.HttpMethod;

public class MarkChatRead extends MastodonAPIRequest<Map<String, Object>>{
	public MarkChatRead(long userId, long readUpToId){
		super(HttpMethod.POST, "/messages/"+userId+"/read", new TypeToken<Map<String, Object>>(){});
		JsonObject body=new JsonObject();
		body.addProperty("read_up_to_id", readUpToId);
		setRequestBody(RequestBody.create(okhttp3.MediaType.parse("application/json"), body.toString()));
	}

	@Override
	protected String getPathPrefix(){
		return "/api";
	}
}
