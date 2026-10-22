package org.joinmastodon.android.api.requests.statuses;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.Status;

public class SetStatusBookmarked extends MastodonAPIRequest<Status>{
	public SetStatusBookmarked(String id, boolean bookmarked){
		super(HttpMethod.POST, "/statuses/"+id+"/"+(bookmarked ? "bookmark" : "unbookmark"), Status.class);
		setRequestBody(new Object());
	}

	@Override
	public me.grishka.appkit.api.ErrorResponse deserializeError(com.google.gson.JsonObject body, int status){
		return org.joinmastodon.android.api.MastodonErrorResponse.nbwBindingError(body, status);
	}
}
