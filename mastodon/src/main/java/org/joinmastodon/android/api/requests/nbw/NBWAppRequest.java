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
	@Override public int responseByteLimit(){ return 1024*1024; }
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
	@com.google.gson.annotations.JsonAdapter(AuthorizationStartAdapter.class)
	public static class AuthorizationStart extends org.joinmastodon.android.model.BaseModel{
		public String authorizationUrl, flow;
		public int expiresIn;
		public org.joinmastodon.android.model.NBWEndpoints endpoints;
		@Override public void postprocess() throws ObjectValidationException{
			if(endpoints==null) throw new ObjectValidationException("Matching NBW endpoint server required");
			endpoints.postprocess();
			if(expiresIn!=600 || !endpoints.canWrite() || !NBWLoginRequest.validAuthorizationUrl(authorizationUrl,endpoints,"nbw-v2-flow.")
					|| !java.util.Objects.equals(flow,android.net.Uri.parse(authorizationUrl).getQueryParameter("state")))
				throw new ObjectValidationException("Invalid authorization URL");
		}
	}
	public static final class AuthorizationStartAdapter extends com.google.gson.TypeAdapter<AuthorizationStart>{
		@Override public AuthorizationStart read(com.google.gson.stream.JsonReader in) throws IOException{
			if(in.peek()!=com.google.gson.stream.JsonToken.BEGIN_OBJECT) throw new IOException("Invalid authorization response");
			AuthorizationStart result=new AuthorizationStart(); java.util.Set<String> seen=new java.util.HashSet<>(); in.beginObject();
			while(in.hasNext()){
				String key=in.nextName();
				if(!java.util.Set.of("flow","authorization_url","expires_in","endpoints").contains(key) || !seen.add(key)) throw new IOException("Invalid authorization member");
				if(key.equals("endpoints")){ result.endpoints=new org.joinmastodon.android.model.NBWEndpoints.Adapter().read(in); continue; }
				if(key.equals("expires_in")){
					if(in.peek()!=com.google.gson.stream.JsonToken.NUMBER || !"600".equals(in.nextString())) throw new IOException("Invalid authorization expiry");
					result.expiresIn=600; continue;
				}
				if(in.peek()!=com.google.gson.stream.JsonToken.STRING) throw new IOException("Invalid authorization string");
				String value=in.nextString(); if(value.length()>2048) throw new IOException("Authorization response too long");
				if(key.equals("flow")) result.flow=value; else result.authorizationUrl=value;
			}
			in.endObject(); if(seen.size()!=4) throw new IOException("Matching NBW endpoint server required");
			result.postprocess(); return result;
		}
		@Override public void write(com.google.gson.stream.JsonWriter out,AuthorizationStart value) throws IOException{ throw new IOException("Read only"); }
	}
}
