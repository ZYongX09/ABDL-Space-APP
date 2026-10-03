package org.joinmastodon.android.api.requests.timelines;

import android.text.TextUtils;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.Status;

import java.util.List;

public class GetGeoTimeline extends MastodonAPIRequest<List<Status>>{
	public GetGeoTimeline(String province, String city, String district, String maxID, int limit){
		super(HttpMethod.GET, "/timelines/geo", new TypeToken<>(){});
		addQueryParameter("province", province);
		if(!TextUtils.isEmpty(city))
			addQueryParameter("city", city);
		if(!TextUtils.isEmpty(district))
			addQueryParameter("district", district);
		if(!TextUtils.isEmpty(maxID))
			addQueryParameter("max_id", maxID);
		if(limit>0)
			addQueryParameter("limit", limit+"");
		removeUnsupportedItems=true;
	}
}
