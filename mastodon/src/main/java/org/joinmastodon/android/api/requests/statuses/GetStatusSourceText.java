package org.joinmastodon.android.api.requests.statuses;

import com.google.gson.annotations.SerializedName;

import org.joinmastodon.android.api.RequiredField;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.BaseModel;

public class GetStatusSourceText extends MastodonAPIRequest<GetStatusSourceText.Response>{
	public GetStatusSourceText(String id){
		super(HttpMethod.GET, "/statuses/"+id+"/source", Response.class);
	}

	public static class Response extends BaseModel{
		@RequiredField public String id;
		@RequiredField public String text;
		@RequiredField public String spoilerText;
		// Optional even on legacy servers; null is a valid source with no attached card.
		@SerializedName("friend_card_id") public String friendCardId;
	}
}
