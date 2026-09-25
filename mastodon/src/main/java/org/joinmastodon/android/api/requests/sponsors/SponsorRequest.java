package org.joinmastodon.android.api.requests.sponsors;

import android.content.Context;
import android.widget.Toast;

import com.google.gson.JsonObject;

import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.model.sponsors.SponsorModels.*;

import java.util.HashMap;
import java.util.Map;

import me.grishka.appkit.api.ErrorResponse;

/** All requests execute on the selected session's origin, never a central backend override. */
public class SponsorRequest<T> extends MastodonAPIRequest<T>{
	private SponsorRequest(HttpMethod method, String path, Class<T> type, Object body){
		super(method, "/sponsors"+path, type);
		if(body!=null) setRequestBody(body);
		setTimeout(30000);
	}
	@Override public boolean isSensitiveRequest(){ return true; }
	@Override public ErrorResponse deserializeError(JsonObject body, int status){ return new SponsorError(body, status); }
	public static SponsorRequest<Catalog> catalog(){ return new SponsorRequest<>(HttpMethod.GET, "/catalog", Catalog.class, null); }
	public static SponsorRequest<Me> me(){ return new SponsorRequest<>(HttpMethod.GET, "/me", Me.class, null); }
	public static SponsorRequest<Me> redeem(String code, String operation){
		return new SponsorRequest<>(HttpMethod.POST, "/redeem", Me.class, Map.of("code", code, "operation_id", operation));
	}
	public static SponsorRequest<Me> color(String key){ return new SponsorRequest<>(HttpMethod.PUT, "/color", Me.class, Map.of("color_key", key)); }
	public static SponsorRequest<Me> claim(String benefit, String operation){
		return new SponsorRequest<>(HttpMethod.POST, "/claims", Me.class, Map.of("benefit_id", benefit, "operation_id", operation));
	}
	public static SponsorRequest<History> history(int offset){
		SponsorRequest<History> request=new SponsorRequest<>(HttpMethod.GET, "/redemptions", History.class, null);
		request.addQueryParameter("limit", "20");
		request.addQueryParameter("offset", String.valueOf(offset));
		return request;
	}
	public static SponsorRequest<Authorization> authorize(String operation, String media, Integer noticeVersion){
		Map<String, Object> body=new HashMap<>();
		body.put("operation_id", operation);
		body.put("media_key", media);
		if(noticeVersion!=null) body.put("notice_version", noticeVersion);
		return new SponsorRequest<>(HttpMethod.POST, "/original-authorizations", Authorization.class, body);
	}
	public static class SponsorError extends MastodonErrorResponse{
		public final String code;
		public final Quota quota;
		public final Integer noticeVersion;
		public SponsorError(JsonObject body, int status){
			super(string(body, "error", "赞助者服务请求失败，请重试"), status, null);
			code=string(body, "code", "unknown");
			Quota parsed=null;
			Integer version=null;
			try{
				if(body.has("quota")) parsed=MastodonAPIController.gson.fromJson(body.get("quota"), Quota.class);
				if(body.has("notice_version") && !body.get("notice_version").isJsonNull()) version=body.get("notice_version").getAsInt();
			}catch(RuntimeException ignored){}
			quota=parsed!=null && parsed.valid() ? parsed : null;
			noticeVersion=version;
		}
		private static String string(JsonObject body, String key, String fallback){
			try{ return body.has(key) && !body.get(key).isJsonNull() ? body.get(key).getAsString() : fallback; }
			catch(RuntimeException ignored){ return fallback; }
		}
		@Override public void showToast(Context context){ if(context!=null) Toast.makeText(context, error, Toast.LENGTH_LONG).show(); }
	}
}
