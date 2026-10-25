package org.joinmastodon.android.api.requests.nbw;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.ObjectValidationException;
import org.joinmastodon.android.model.NBWCapabilities;
import org.joinmastodon.android.model.NBWForumOptions;

import java.io.IOException;

import okhttp3.Response;

/** Independent router, not the legacy /api/auth/nbw gate. No V1 retry/fallback. */
public class NBWAppRequest<T> extends MastodonAPIRequest<T>{
	public NBWAppRequest(HttpMethod method, String path, Class<T> response){
		super(method, path, response);
		setTimeout(10000);
		addHeader("X-ABDL-NBW-Protocol", "2");
	}
	@Override protected String getPathPrefix(){ return "/api/app"; }
	@Override public boolean isSensitiveRequest(){ return true; }
	public static NBWAppRequest<NBWCapabilities> capabilities(){ return new NBWAppRequest<>(HttpMethod.GET, "/capabilities", NBWCapabilities.class); }
	public static NBWAppRequest<NBWForumOptions> options(){ return new NBWAppRequest<>(HttpMethod.GET, "/nbw/options", NBWForumOptions.class); }
	public static NBWAppRequest<Object> fallbackConsent(boolean accepted){
		NBWAppRequest<Object> req=new NBWAppRequest<>(HttpMethod.PUT, "/nbw/fallback-consent", Object.class);
		com.google.gson.JsonObject body=new com.google.gson.JsonObject();
		body.addProperty("accepted", accepted); body.addProperty("policy_version", 1);
		req.setRequestBody(body);
		return req;
	}
	public static NBWAppRequest<AuthorizationStart> authorizationStart(){
		NBWAppRequest<AuthorizationStart> req=new NBWAppRequest<>(HttpMethod.POST, "/nbw/authorization/start", AuthorizationStart.class);
		req.setRequestBody(new com.google.gson.JsonObject());
		return req;
	}
	public static class AuthorizationStart extends org.joinmastodon.android.model.BaseModel{
		public String authorizationUrl;
		@Override public void postprocess() throws ObjectValidationException{
			android.net.Uri uri=authorizationUrl==null ? null : android.net.Uri.parse(authorizationUrl);
			if(uri==null || !"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null)
				throw new ObjectValidationException("Invalid authorization URL");
		}
	}
}
