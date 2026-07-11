package org.joinmastodon.android.api.requests.friendrequests;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;

import java.util.HashMap;
import java.util.Map;

import okhttp3.internal.http.HttpMethod;

public class ReportFriendRequest extends MastodonAPIRequest<Map<String, Object>>{
	public ReportFriendRequest(String requestId, String reason, String[] evidenceUrls){
		super(HttpMethod.POST, "/friend-request/"+requestId+"/report", new TypeToken<Map<String, Object>>(){});
		Map<String, Object> body = new HashMap<>();
		body.put("reason", reason);
		if(evidenceUrls!=null && evidenceUrls.length>0) body.put("evidence_urls", evidenceUrls);
		setRequestBody(body);
	}

	@Override
	protected String getPathPrefix(){
		return "/api";
	}
}
