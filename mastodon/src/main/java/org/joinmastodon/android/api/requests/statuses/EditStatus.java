package org.joinmastodon.android.api.requests.statuses;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.Status;

public class EditStatus extends MastodonAPIRequest<Status>{
	public EditStatus(CreateStatus.Request req, String id){
		this(req.toJson(), id);
	}

	/** Partial updates preserve omitted server-owned metadata, unlike a new-post request. */
	public EditStatus(com.google.gson.JsonObject body, String id){
		super(HttpMethod.PUT, "/statuses/"+id, Status.class);
		setRequestBody(body);
	}
}
