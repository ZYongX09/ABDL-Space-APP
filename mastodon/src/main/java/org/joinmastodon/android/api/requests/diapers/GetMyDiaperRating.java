package org.joinmastodon.android.api.requests.diapers;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;

import java.util.Map;

import okhttp3.internal.http.HttpMethod;

public class GetMyDiaperRating extends MastodonAPIRequest<Map<String, Object>>{
	public GetMyDiaperRating(int diaperId){
		super(HttpMethod.GET, "/ratings/me/"+diaperId, new TypeToken<Map<String, Object>>(){});
	}

	@Override
	protected String getPathPrefix(){
		return "/api";
	}
}
