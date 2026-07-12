package org.joinmastodon.android.api.requests.diapers;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;

import java.util.Map;

import okhttp3.internal.http.HttpMethod;

public class GetDiaperBrands extends MastodonAPIRequest<Map<String, Object>>{
	public GetDiaperBrands(){
		super(HttpMethod.GET, "/diapers/brands", new TypeToken<Map<String, Object>>(){});
	}

	@Override
	protected String getPathPrefix(){
		return "/api";
	}
}
