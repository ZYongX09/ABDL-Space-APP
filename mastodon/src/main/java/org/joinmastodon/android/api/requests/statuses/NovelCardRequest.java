package org.joinmastodon.android.api.requests.statuses;

import com.google.gson.JsonObject;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.MastodonErrorResponse;
import java.util.UUID;
import me.grishka.appkit.api.ErrorResponse;

/** Tiny raw transport for native timeline/composer consumers. Wire keys are the shared /novels/app contract. */
public final class NovelCardRequest extends MastodonAPIRequest<JsonObject>{
	private NovelCardRequest(HttpMethod method,String path,JsonObject body){
		super(method,"/novels/app"+path,JsonObject.class);
		addHeader("Cache-Control","no-store"); setTimeout(30_000);
		if(body!=null){ setRequestBody(body); addHeader("Idempotency-Key",UUID.randomUUID().toString()); }
	}
	public static NovelCardRequest creator(){ return new NovelCardRequest(HttpMethod.GET,"/creator",null); }
	public static NovelCardRequest ownerWorks(){ return new NovelCardRequest(HttpMethod.GET,"/owner/works",null); }
	public static NovelCardRequest work(String id){ return new NovelCardRequest(HttpMethod.GET,"/works/"+id(id),null); }
	public static NovelCardRequest shelf(String id,boolean collected){
		JsonObject b=new JsonObject(); b.addProperty("collected",collected);
		return new NovelCardRequest(HttpMethod.POST,"/shelf/"+id(id),b);
	}
	private static String id(String id){ if(id==null || !id.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid novel identifier"); return id; }
	@Override public boolean isSensitiveRequest(){ return true; }
	@Override public ErrorResponse deserializeError(JsonObject body,int status){
		String message="小说服务暂不可用";
		if(body!=null && body.has("error") && body.get("error").isJsonPrimitive() && body.getAsJsonPrimitive("error").isString()) message=body.get("error").getAsString();
		return new MastodonErrorResponse(message,status,null);
	}
}
