package org.joinmastodon.android.api.requests.friendcards;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.util.Objects;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.MastodonErrorResponse;
import me.grishka.appkit.api.ErrorResponse;

/** Selected-account, no-cache requests. Missing endpoints are errors, never synthetic empty cards. */
public final class FriendCardRequest<T> extends MastodonAPIRequest<T>{
	private static final Gson WIRE=new GsonBuilder().serializeNulls().create();
	private FriendCardRequest(HttpMethod method, String path, Class<T> type, Object body){
		super(method, path(path), type);
		if(body!=null) setRequestBody(body instanceof JsonObject ? body : WIRE.toJsonTree(body));
		addHeader("Cache-Control", "no-store"); setTimeout(30_000);
	}
	private static String path(String path){
		if(path==null || (!path.isEmpty() && !path.startsWith("/")) || path.contains("?") || path.contains("#") || path.contains("..") || path.contains("://")) throw new IllegalArgumentException("Use a friend-card relative path");
		return "/friend-cards"+path;
	}
	private static String id(String id){
		if(id==null || !id.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid identifier");
		return id;
	}
	public static <T> FriendCardRequest<T> get(String path, Class<T> type){ return new FriendCardRequest<>(HttpMethod.GET, path, type, null); }
	public static <T> FriendCardRequest<T> post(String path, Class<T> type, Object body){ return new FriendCardRequest<>(HttpMethod.POST, path, type, body); }
	public FriendCardRequest<T> query(String key, Object value){ if(value!=null) addQueryParameter(key, String.valueOf(value)); return this; }
	public static FriendCardRequest<FriendCardResponses.Options> options(){ return get("/options", FriendCardResponses.Options.class); }
	public static FriendCardRequest<FriendCardResponses.Me> me(){ return get("/me", FriendCardResponses.Me.class); }
	public static FriendCardRequest<FriendCardResponses.Page> feed(String cursor, int limit){ return get("/feed", FriendCardResponses.Page.class).query("cursor", cursor).query("limit", Math.max(1, Math.min(20, limit))); }
	public static FriendCardRequest<FriendCardResponses.Page> history(String cursor){ return get("/history", FriendCardResponses.Page.class).query("cursor", cursor); }
	public static FriendCardRequest<FriendCardResponses.Card> detail(String cardId){ return get("/"+id(cardId), FriendCardResponses.Card.class); }
	public static FriendCardRequest<FriendCardResponses.Card> create(String operationId, String expectedCurrentId, FriendCardInput card){
		JsonObject body=new JsonObject(); body.addProperty("operation_id", Objects.requireNonNull(operationId)); body.addProperty("expected_current_id", expectedCurrentId); body.add("card", WIRE.toJsonTree(Objects.requireNonNull(card)));
		return post("", FriendCardResponses.Card.class, body);
	}
	public static FriendCardRequest<FriendCardResponses.Card> edit(String cardId, int expectedVersion, FriendCardInput card){
		JsonObject body=new JsonObject(); body.addProperty("expected_version", expectedVersion); body.add("card", WIRE.toJsonTree(Objects.requireNonNull(card)));
		return new FriendCardRequest<>(HttpMethod.PATCH, "/"+id(cardId), FriendCardResponses.Card.class, body);
	}
	public static FriendCardRequest<FriendCardResponses.Card> state(String cardId, int expectedVersion, String state){
		if(!java.util.List.of("active", "paused", "archived").contains(state)) throw new IllegalArgumentException("Invalid card state");
		JsonObject body=new JsonObject(); body.addProperty("expected_version", expectedVersion); body.addProperty("state", state);
		return post("/"+id(cardId)+"/state", FriendCardResponses.Card.class, body);
	}
	public static FriendCardRequest<FriendCardResponses.Deleted> delete(String cardId, int expectedVersion){ return new FriendCardRequest<FriendCardResponses.Deleted>(HttpMethod.DELETE, "/"+id(cardId), FriendCardResponses.Deleted.class, null).query("expected_version", expectedVersion); }
	public static FriendCardRequest<FriendCardResponses.Reported> report(String cardId, String reason, String detail){
		JsonObject body=new JsonObject(); body.addProperty("reason", reason); if(detail!=null && !detail.isBlank()) body.addProperty("detail", detail);
		return post("/"+id(cardId)+"/report", FriendCardResponses.Reported.class, body);
	}
	public static FriendCardRequest<FriendCardResponses.Blocked> block(String userId, boolean blocked){ JsonObject body=new JsonObject(); body.addProperty("blocked", blocked); return post("/blocks/"+id(userId), FriendCardResponses.Blocked.class, body); }
	@Override protected String getPathPrefix(){ return "/api"; }
	@Override public boolean isSensitiveRequest(){ return true; }
	@Override public ErrorResponse deserializeError(JsonObject body, int status){ return new FriendCardError(body, status); }
	public static final class FriendCardError extends MastodonErrorResponse{
		public final String code;
		public FriendCardError(JsonObject body, int status){ super(read(body, "error", "扩列服务暂不可用"), status, null); code=read(body, "code", ""); }
		private static String read(JsonObject body, String key, String fallback){ return body!=null && body.has(key) && body.get(key).isJsonPrimitive() ? body.get(key).getAsString() : fallback; }
	}
}
