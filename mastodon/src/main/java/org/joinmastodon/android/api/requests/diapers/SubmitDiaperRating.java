package org.joinmastodon.android.api.requests.diapers;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;

import java.util.HashMap;
import java.util.Map;

import okhttp3.internal.http.HttpMethod;

public class SubmitDiaperRating extends MastodonAPIRequest<Map<String, Object>>{
	public SubmitDiaperRating(int diaperId, int absorptionScore, int comfortScore, int thicknessScore, int appearanceScore, int valueScore, String review){
		super(HttpMethod.POST, "/ratings", new TypeToken<Map<String, Object>>(){});
		Map<String, Object> body=new HashMap<>();
		body.put("diaper_id", diaperId);
		body.put("absorption_score", absorptionScore);
		body.put("comfort_score", comfortScore);
		body.put("thickness_score", thicknessScore);
		body.put("appearance_score", appearanceScore);
		body.put("value_score", valueScore);
		if(review!=null && !review.isEmpty())
			body.put("review", review);
		setRequestBody(body);
	}

	@Override
	protected String getPathPrefix(){
		return "/api";
	}
}
