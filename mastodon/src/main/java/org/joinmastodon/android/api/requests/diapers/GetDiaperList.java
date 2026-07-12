package org.joinmastodon.android.api.requests.diapers;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;

import java.util.Map;

import okhttp3.internal.http.HttpMethod;

public class GetDiaperList extends MastodonAPIRequest<Map<String, Object>>{
	public GetDiaperList(int page, int limit, String search, String brand, String sort){
		super(HttpMethod.GET, "/diapers", new TypeToken<Map<String, Object>>(){});
		addQueryParameter("page", String.valueOf(page));
		addQueryParameter("limit", String.valueOf(limit));
		if(search!=null && !search.isEmpty()) addQueryParameter("search", search);
		if(brand!=null && !brand.isEmpty()) addQueryParameter("brand", brand);
		if(sort!=null && !sort.isEmpty()) addQueryParameter("sort", sort);
	}

	@Override
	protected String getPathPrefix(){
		return "/api";
	}
}
