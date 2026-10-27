package org.joinmastodon.android.api.requests.nbw;

import android.content.Context;
import android.net.Uri;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.ObjectValidationException;
import org.joinmastodon.android.model.BaseModel;
import org.joinmastodon.android.model.Token;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import me.grishka.appkit.api.ErrorResponse;

/** Independent, unauthenticated first-party login. Never falls back to legacy NBW APIs. */
public final class NBWLoginRequest<T> extends MastodonAPIRequest<T>{
	public static final String DOMAIN="api.abdl-space.top";
	public static final String REDIRECT_URI="https://api.abdl-space.top/api/app/nbw/authorization/callback";
	private final String endpoint;

	private NBWLoginRequest(String endpoint, Class<T> response, JsonObject body){
		super(HttpMethod.POST, "/nbw/login/"+endpoint, response);
		this.endpoint=endpoint;
		setRequestBody(body);
		setTimeout(15000);
	}
	@Override protected String getPathPrefix(){ return "/api/app"; }
	@Override public Uri getURL(){ return Uri.parse("https://"+DOMAIN+getPathPrefix()+"/nbw/login/"+endpoint); }
	@Override public boolean isSensitiveRequest(){ return true; }
	@Override public boolean requiresSystemTrust(){ return true; }
	@Override public NBWLoginRequest<T> execNoAuth(String domain){
		if(!DOMAIN.equals(domain)) throw new IllegalArgumentException("First-party login host required");
		super.execNoAuth(DOMAIN);
		return this;
	}
	@Override public MastodonAPIRequest<T> exec(String accountID){ throw new UnsupportedOperationException("Unauthenticated login only"); }
	@Override public MastodonAPIRequest<T> exec(String domain, Token token){ throw new UnsupportedOperationException("Unauthenticated login only"); }

	public static NBWLoginRequest<Start> start(String challenge){
		if(!canonical43(challenge)) throw new IllegalArgumentException("Invalid PKCE challenge");
		JsonObject body=new JsonObject(); body.addProperty("code_challenge", challenge);
		return new NBWLoginRequest<>("start", Start.class, body);
	}
	public static NBWLoginRequest<Exchange> exchange(String flow, String verifier, String completionCode){
		if(!canonical43(flow) || !canonical43(completionCode) || verifier==null || !verifier.matches("[A-Za-z0-9._~-]{43,128}"))
			throw new IllegalArgumentException("Invalid login proof");
		JsonObject body=new JsonObject(); body.addProperty("flow", flow); body.addProperty("code_verifier", verifier); body.addProperty("completion_code", completionCode);
		return new NBWLoginRequest<>("exchange", Exchange.class, body);
	}
	public static boolean canonical43(String value){
		return value!=null && value.matches("[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]");
	}

	@JsonAdapter(StartAdapter.class)
	public static final class Start extends BaseModel{
		public String flow;
		public String authorizationUrl;
		public int expiresIn;
		@Override public void postprocess() throws ObjectValidationException{
			if(!canonical43(flow) || expiresIn!=600 || !validAuthorizationUrl(authorizationUrl)
					|| !flow.equals(Uri.parse(authorizationUrl).getQueryParameter("state").split("\\.", -1)[1]))
				throw new ObjectValidationException("Invalid login authorization response");
		}
	}
	public static boolean validAuthorizationUrl(String value){
		if(value==null || value.length()>2048) return false;
		try{
			Uri uri=Uri.parse(value);
			if(!uri.isHierarchical() || !"https".equals(uri.getScheme()) || !"www.newbabyworld.top".equals(uri.getEncodedAuthority())
					|| uri.getUserInfo()!=null || uri.getPort()!=-1 || uri.getEncodedFragment()!=null
					|| !"/oauth/authorize.php".equals(uri.getEncodedPath())) return false;
			Set<String> expected=Set.of("client_id", "redirect_uri", "response_type", "state");
			if(!uri.getQueryParameterNames().equals(expected)) return false;
			String query=uri.getEncodedQuery();
			if(query==null || query.split("&", -1).length!=4) return false;
			for(String part:query.split("&", -1)){
				int equal=part.indexOf('=');
				if(equal<1 || !expected.contains(part.substring(0, equal))) return false;
			}
			for(String key:expected) if(uri.getQueryParameters(key).size()!=1) return false;
			String client=uri.getQueryParameter("client_id"), state=uri.getQueryParameter("state");
			return client!=null && client.matches("[A-Za-z0-9._~-]{1,128}")
					&& REDIRECT_URI.equals(uri.getQueryParameter("redirect_uri"))
					&& "code".equals(uri.getQueryParameter("response_type"))
					&& validState(state);
		}catch(RuntimeException ignored){ return false; }
	}
	private static boolean validState(String state){
		if(state==null || !state.matches("nbw-v2-login\\.[A-Za-z0-9_-]{43}\\.[1-9][0-9]{12}\\.[A-Za-z0-9_-]{43}")) return false;
		String[] parts=state.split("\\.", -1);
		if(!canonical43(parts[1]) || !canonical43(parts[3])) return false;
		long expiry=Long.parseLong(parts[2]), now=System.currentTimeMillis();
		return expiry>now-60_000L && expiry<=now+660_000L;
	}
	@JsonAdapter(ExchangeAdapter.class)
	public static final class Exchange extends BaseModel{
		public String action;
		public String token;
		public JsonObject user;
		@Override public void postprocess() throws ObjectValidationException{
			if(!"login".equals(action) || token==null || token.length()>16384
					|| !token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
					|| user==null || !Set.of("id", "email", "username", "avatar", "role").equals(user.keySet())
					|| !validId(user.get("id")) || !string(user.get("email"), 320, false)
					|| !string(user.get("username"), 256, false) || !string(user.get("avatar"), 2048, true)
					|| !string(user.get("role"), 64, false))
				throw new ObjectValidationException("Invalid login exchange response");
		}
		private static boolean validId(JsonElement value){
			if(value==null || !value.isJsonPrimitive()) return false;
			String id=value.getAsString();
			return id.matches("[1-9][0-9]{0,15}") && Long.parseLong(id)<=9_007_199_254_740_991L;
		}
		private static boolean string(JsonElement value, int max, boolean nullable){
			return value!=null && (nullable && value.isJsonNull() || value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
					&& value.getAsString().length()<=max && (nullable || !value.getAsString().isEmpty()));
		}
	}

	/** Streaming adapters reject coercion, unknown members, duplicates and nested token-shaped data. */
	private static JsonObject readObject(JsonReader in, Set<String> expected, boolean user) throws IOException{
		if(in.peek()!=JsonToken.BEGIN_OBJECT) throw new IOException("Invalid login response shape");
		in.beginObject(); JsonObject object=new JsonObject(); Set<String> seen=new HashSet<>();
		while(in.hasNext()){
			String key=in.nextName();
			if(!expected.contains(key) || !seen.add(key)) throw new IOException("Invalid login response member");
			if("user".equals(key)) object.add(key, readObject(in, Set.of("id", "email", "username", "avatar", "role"), true));
			else if("expires_in".equals(key)){
				if(in.peek()!=JsonToken.NUMBER || !"600".equals(in.nextString())) throw new IOException("Invalid login expiry");
				object.addProperty(key, 600);
			}else if(user && "id".equals(key)){
				JsonToken type=in.peek();
				if(type!=JsonToken.STRING && type!=JsonToken.NUMBER) throw new IOException("Invalid login user id");
				object.addProperty(key, in.nextString());
			}else if(user && "avatar".equals(key) && in.peek()==JsonToken.NULL){
				in.nextNull(); object.add(key, JsonNull.INSTANCE);
			}else{
				if(in.peek()!=JsonToken.STRING) throw new IOException("Invalid login string");
				String value=in.nextString();
				if(value.length()>16384) throw new IOException("Login response too long");
				object.addProperty(key, value);
			}
		}
		in.endObject();
		if(!seen.equals(expected)) throw new IOException("Incomplete login response");
		return object;
	}
	public static final class StartAdapter extends TypeAdapter<Start>{
		@Override public Start read(JsonReader in) throws IOException{
			JsonObject object=readObject(in, Set.of("flow", "authorization_url", "expires_in"), false);
			Start result=new Start(); result.flow=object.get("flow").getAsString(); result.authorizationUrl=object.get("authorization_url").getAsString(); result.expiresIn=600;
			result.postprocess(); return result;
		}
		@Override public void write(JsonWriter out, Start value) throws IOException{ throw new IOException("Login response is read-only"); }
	}
	public static final class ExchangeAdapter extends TypeAdapter<Exchange>{
		@Override public Exchange read(JsonReader in) throws IOException{
			JsonObject object=readObject(in, Set.of("action", "token", "user"), false);
			Exchange result=new Exchange(); result.action=object.get("action").getAsString(); result.token=object.get("token").getAsString(); result.user=object.getAsJsonObject("user");
			result.postprocess(); return result;
		}
		@Override public void write(JsonWriter out, Exchange value) throws IOException{ throw new IOException("Login response is read-only"); }
	}

	@Override public ErrorResponse deserializeError(JsonObject body, int status){
		String code=null;
		if(body!=null && body.get("code")!=null && body.get("code").isJsonPrimitive() && body.get("code").getAsJsonPrimitive().isString())
			code=body.get("code").getAsString();
		return new LoginError(status, code);
	}
	public static final class LoginError extends ErrorResponse{
		public final int message;
		public final boolean retryable;
		public LoginError(int status, String code){
			int text=R.string.nbw_login_failed; boolean retry=false;
			if("NBW_LOGIN_PENDING".equals(code) && status==409){ text=R.string.nbw_login_pending; retry=true; }
			else if("NBW_LOGIN_NOT_BOUND".equals(code)){ text=R.string.nbw_login_not_bound; }
			else if("NBW_LOGIN_REAUTH_REQUIRED".equals(code)){ text=R.string.nbw_login_reauth_required; }
			else if("NBW_LOGIN_INVALID_FLOW".equals(code) || "NBW_LOGIN_INVALID".equals(code) || "NBW_LOGIN_CONSUMED".equals(code)) text=R.string.nbw_login_expired;
			else if("NBW_LOGIN_REJECTED".equals(code) || "NBW_LOGIN_ACCOUNT_UNAVAILABLE".equals(code)) text=R.string.nbw_login_rejected;
			else if(status==429 && "NBW_LOGIN_RATE_LIMITED".equals(code)){ text=R.string.nbw_login_rate_limited; retry=true; }
			else if(status==503 && "NBW_LOGIN_UNAVAILABLE".equals(code)){ text=R.string.nbw_login_unavailable; }
			message=text; retryable=retry;
		}
		@Override public void bindErrorView(View view){ if(view instanceof TextView text) text.setText(message); }
		@Override public void showToast(Context context){ Toast.makeText(context, message, Toast.LENGTH_LONG).show(); }
	}
}
