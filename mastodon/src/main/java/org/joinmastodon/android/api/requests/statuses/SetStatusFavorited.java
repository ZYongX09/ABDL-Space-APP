package org.joinmastodon.android.api.requests.statuses;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.Status;

public class SetStatusFavorited extends MastodonAPIRequest<Status>{
	public SetStatusFavorited(String id, boolean favorited){
		super(HttpMethod.POST, "/statuses/"+id+"/"+(favorited ? "favourite" : "unfavourite"), Status.class);
		setRequestBody(new Object());
	}

	@Override
	public me.grishka.appkit.api.ErrorResponse deserializeError(com.google.gson.JsonObject body, int status){
		return org.joinmastodon.android.api.MastodonErrorResponse.nbwBindingError(body, status);
	}
}
