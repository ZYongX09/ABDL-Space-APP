package org.joinmastodon.android.api.requests.statuses;

import org.joinmastodon.android.api.ResultlessMastodonAPIRequest;

/** A successful deletion need not return a complete, renderable Status. */
public class DeleteStatus extends ResultlessMastodonAPIRequest{
	public DeleteStatus(String id){
		super(HttpMethod.DELETE, "/statuses/"+id);
	}
}
