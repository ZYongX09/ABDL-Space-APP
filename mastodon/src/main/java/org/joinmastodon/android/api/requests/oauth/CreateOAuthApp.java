package org.joinmastodon.android.api.requests.oauth;

import com.google.gson.annotations.SerializedName;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Application;

public class CreateOAuthApp extends MastodonAPIRequest<Application>{
	public CreateOAuthApp(){
		super(HttpMethod.POST, "/apps", Application.class);
		setRequestBody(new Request());
	}

	private static class Request{
		@SerializedName("client_name")
		public String clientName="ABDL Space";
		@SerializedName("redirect_uris")
		public String redirectUris=AccountSessionManager.REDIRECT_URI;
		@SerializedName("scopes")
		public String scopes=AccountSessionManager.SCOPE;
		public String website="https://github.com/LucasGGamerM/moshidon";
	}
}
