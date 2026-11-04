package org.joinmastodon.android.api.requests.nbw;

import android.net.Uri;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.NBWEndpoints;
import org.joinmastodon.android.model.Token;

/** Discovery is deliberately accountless and always goes to the first-party backend, not NBW. */
public final class GetNBWEndpoints extends MastodonAPIRequest<NBWEndpoints>{
	public GetNBWEndpoints(){ super(HttpMethod.GET,"/nbw/endpoints",NBWEndpoints.class); setTimeout(10000); }
	@Override public Uri getURL(){ return Uri.parse("https://"+NBWLoginRequest.DOMAIN+"/api/nbw/endpoints"); }
	@Override public boolean isSensitiveRequest(){ return true; }
	@Override public boolean requiresSystemTrust(){ return true; }
	@Override public int responseByteLimit(){ return 4096; }
	@Override public GetNBWEndpoints execNoAuth(String domain){
		if(!NBWLoginRequest.DOMAIN.equals(domain)) throw new IllegalArgumentException("First-party discovery host required");
		super.execNoAuth(domain); return this;
	}
	@Override public MastodonAPIRequest<NBWEndpoints> exec(String accountID){ throw new UnsupportedOperationException("Public discovery only"); }
	@Override public MastodonAPIRequest<NBWEndpoints> exec(String domain, Token token){ throw new UnsupportedOperationException("Public discovery only"); }
}
