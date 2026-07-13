package org.joinmastodon.android.api.requests.diapers;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;

import java.util.Map;

import okhttp3.internal.http.HttpMethod;

public class GetRankings extends MastodonAPIRequest<Map<String, Object>>{
	public GetRankings(String type, int limit, int offset){
		super(HttpMethod.GET, "/rankings", new TypeToken<Map<String, Object>>(){});
		addQueryParameter("type", type != null ? type : "hot");
		addQueryParameter("limit", String.valueOf(limit));
		addQueryParameter("offset", String.valueOf(offset));
	}

	@Override
	protected String getPathPrefix(){
		return "/api";
	}
}
