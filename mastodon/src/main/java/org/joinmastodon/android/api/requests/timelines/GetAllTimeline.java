package org.joinmastodon.android.api.requests.timelines;

import android.text.TextUtils;
import android.net.Uri;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.Status;

import java.util.List;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Response;

public class GetAllTimeline extends MastodonAPIRequest<List<Status>>{
	private static final Pattern NEXT_LINK_PATTERN=Pattern.compile("<([^>]+)>;\\s*rel=\\\"next\\\"");
	private String nextMaxID;

	public GetAllTimeline(String maxID, int limit){
		super(HttpMethod.GET, "/timelines/all", new TypeToken<>(){});
		if(!TextUtils.isEmpty(maxID))
			addQueryParameter("max_id", maxID);
		if(limit>0)
			addQueryParameter("limit", limit+"");
		removeUnsupportedItems=true;
	}

	@Override
	public void validateAndPostprocessResponse(List<Status> response, Response httpResponse) throws IOException{
		super.validateAndPostprocessResponse(response, httpResponse);
		String link=httpResponse.header("Link");
		if(link==null) return;
		Matcher matcher=NEXT_LINK_PATTERN.matcher(link);
		if(matcher.find())
			nextMaxID=Uri.parse(matcher.group(1)).getQueryParameter("max_id");
	}

	public String getNextMaxID(){
		return nextMaxID;
	}
}
