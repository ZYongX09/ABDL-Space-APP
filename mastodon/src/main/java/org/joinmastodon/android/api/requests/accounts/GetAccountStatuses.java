package org.joinmastodon.android.api.requests.accounts;

import android.text.TextUtils;

import com.google.gson.reflect.TypeToken;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Status;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Response;

import androidx.annotation.NonNull;

public class GetAccountStatuses extends MastodonAPIRequest<List<Status>>{
	private static final Pattern NEXT_LINK=Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");
	private final String targetID;
	private String nextMaxID;

	public GetAccountStatuses(String id, String maxID, String minID, int limit, @NonNull Filter filter, String hashtag){
		super(HttpMethod.GET, "/accounts/"+id+"/statuses", new TypeToken<>(){});
		targetID=id;
		if(maxID!=null)
			addQueryParameter("max_id", maxID);
		if(minID!=null)
			addQueryParameter("min_id", minID);
		if(limit>0)
			addQueryParameter("limit", ""+limit);
		switch(filter){
			case DEFAULT -> addQueryParameter("exclude_replies", "true");
			case INCLUDE_REPLIES -> {}
			case MEDIA -> addQueryParameter("only_media", "true");
			case NO_REBLOGS -> {
				addQueryParameter("exclude_replies", "true");
				addQueryParameter("exclude_reblogs", "true");
			}
			case OWN_POSTS_AND_REPLIES -> addQueryParameter("exclude_reblogs", "true");
			case PINNED -> addQueryParameter("pinned", "true");
		}
		if(!TextUtils.isEmpty(hashtag))
			addQueryParameter("tagged", hashtag);
		removeUnsupportedItems=true;
	}

	@Override
	public void validateAndPostprocessResponse(List<Status> response, Response httpResponse) throws IOException{
		super.validateAndPostprocessResponse(response, httpResponse);
		nextMaxID=null;
		if(!Account.isNBWAccountID(targetID)) return;
		okhttp3.HttpUrl source=httpResponse.request().url();
		for(String link:httpResponse.headers("Link")){
			Matcher matcher=NEXT_LINK.matcher(link);
			while(matcher.find()){
				okhttp3.HttpUrl next=source.resolve(matcher.group(1));
				if(next==null || !source.scheme().equals(next.scheme()) || !source.host().equals(next.host())
						|| source.port()!=next.port() || !source.encodedPath().equals(next.encodedPath())) continue;
				String cursor=next.queryParameter("max_id");
				if(!TextUtils.isEmpty(cursor)){
					nextMaxID=cursor;
					return;
				}
			}
		}
	}

	public String getNextMaxID(){
		return nextMaxID;
	}

	public enum Filter{
		DEFAULT,
		INCLUDE_REPLIES,
		MEDIA,
		NO_REBLOGS,
		OWN_POSTS_AND_REPLIES,
		PINNED
	}
}
