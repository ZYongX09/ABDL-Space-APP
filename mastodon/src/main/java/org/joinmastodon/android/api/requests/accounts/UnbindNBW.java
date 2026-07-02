package org.joinmastodon.android.api.requests.accounts;

import org.joinmastodon.android.api.MastodonAPIRequest;

public class UnbindNBW extends MastodonAPIRequest<Object>{
	public UnbindNBW(){
		super(HttpMethod.POST, "/auth/nbw/unbind", Object.class);
		setRequestBody(new Object());
	}

	@Override
	protected String getPathPrefix(){
		return "/api";
	}
}
