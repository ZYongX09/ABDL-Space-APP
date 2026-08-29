package org.joinmastodon.android.api.requests.geo;

import com.google.gson.annotations.SerializedName;

import org.joinmastodon.android.api.MastodonAPIRequest;

public class GetIPProvince extends MastodonAPIRequest<GetIPProvince.Response>{
	public GetIPProvince(){
		super(HttpMethod.GET, "/geo/ip-province", Response.class);
	}

	public static class Response{
		@SerializedName("province")
		public String province;
		@SerializedName("city")
		public String city;
	}
}
