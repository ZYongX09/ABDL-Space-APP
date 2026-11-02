package org.joinmastodon.android.api.cache;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.StatusPrivacy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A bounded /all head, not an offline authorization or a source of pagination requests. */
public final class HomeTimelineSnapshot{
	public static final String KEY="home_all_head_v1";
	public static final int FORMAT=1, MAX_POSTS=40, MAX_UTF8_BYTES=512*1024, MAX_POST_UTF8_BYTES=64*1024;
	public static final long MAX_AGE_MS=24*60*60*1000L;
	private static final int MAX_STATUS_DEPTH=4, MAX_JSON_DEPTH=48, MAX_CURSOR_BYTES=4096;
	public final List<Status> posts;
	public final String nextMaxID;
	public final long savedAt;

	private HomeTimelineSnapshot(List<Status> posts, String nextMaxID, long savedAt){
		this.posts=List.copyOf(posts);
		this.nextMaxID=nextMaxID;
		this.savedAt=savedAt;
	}

	/** Only a digest is persisted. The access token itself never enters the cache JSON. */
	public static String identity(String accountID, String accessToken){
		try{
			byte[] digest=MessageDigest.getInstance("SHA-256").digest((accountID+"\u0000"+accessToken).getBytes(StandardCharsets.UTF_8));
			StringBuilder result=new StringBuilder(64);
			for(byte value:digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
			return result.toString();
		}catch(java.security.NoSuchAlgorithmException impossible){ throw new AssertionError(impossible); }
	}

	/** Filter ordinary public content recursively; never truncate a serialized status or invent a cursor. */
	public static String encode(List<Status> posts, String nextMaxID, String identity, long now, boolean canReadNBW){
		if(posts==null || !validCursor(nextMaxID)) return null;
		JsonObject root=new JsonObject();
		root.addProperty("format", FORMAT);
		root.addProperty("saved_at", now);
		root.addProperty("identity", identity);
		root.addProperty("next_max_id", nextMaxID);
		com.google.gson.JsonArray entries=new com.google.gson.JsonArray();
		Set<String> ids=new HashSet<>();
		for(Status post:posts){
			if(entries.size()==MAX_POSTS) break;
			if(!safe(post, canReadNBW, 0) || !ids.add(post.id)) continue;
			try{
				JsonObject json=MastodonAPIController.gson.toJsonTree(post).getAsJsonObject();
				removePrivateAccountFields(json);
				if(bytes(json.toString())>MAX_POST_UTF8_BYTES) continue;
				entries.add(json);
				root.add("posts", entries);
				if(bytes(root.toString())>MAX_UTF8_BYTES){ entries.remove(entries.size()-1); break; }
			}catch(RuntimeException malformed){ return null; }
		}
		if(entries.size()==0) return null;
		root.add("posts", entries);
		String result=root.toString();
		return boundedJson(result) ? result : null;
	}

	/** One bad entry invalidates the complete head, even if it would have been offscreen. */
	public static HomeTimelineSnapshot decode(String json, String identity, long now, boolean canReadNBW){
		if(json==null || bytes(json)>MAX_UTF8_BYTES || !boundedJson(json)) return null;
		try{
			JsonObject root=JsonParser.parseString(json).getAsJsonObject();
			if(!number(root, "format") || root.get("format").getAsDouble()!=FORMAT
					|| !number(root, "saved_at") || !string(root, "identity")
					|| !identity.equals(root.get("identity").getAsString())
					|| !root.has("next_max_id") || !root.has("posts") || !root.get("posts").isJsonArray()) return null;
			long savedAt=root.get("saved_at").getAsLong();
			if(savedAt<=0 || savedAt>now || now-savedAt>MAX_AGE_MS) return null;
			JsonElement cursor=root.get("next_max_id");
			if(!cursor.isJsonNull() && (!cursor.isJsonPrimitive() || !cursor.getAsJsonPrimitive().isString())) return null;
			String next=cursor.isJsonNull() ? null : cursor.getAsString();
			if(!validCursor(next)) return null;
			var entries=root.getAsJsonArray("posts");
			if(entries.size()==0 || entries.size()>MAX_POSTS) return null;
			ArrayList<Status> posts=new ArrayList<>(entries.size());
			Set<String> ids=new HashSet<>();
			for(JsonElement entry:entries){
				if(!entry.isJsonObject() || bytes(entry.toString())>MAX_POST_UTF8_BYTES) return null;
				Status status=MastodonAPIController.gson.fromJson(entry, Status.class);
				if(!safe(status, canReadNBW, 0) || !ids.add(status.id)) return null;
				status.postprocess();
				posts.add(status);
			}
			return new HomeTimelineSnapshot(posts, next, savedAt);
		}catch(Exception malformed){ return null; }
	}

	private static boolean safe(Status status, boolean canReadNBW, int depth){
		if(status==null || depth>MAX_STATUS_DEPTH || status.id==null || status.id.isBlank()
				|| status.uri==null || status.createdAt==null || status.account==null
				|| status.account.id==null || status.account.id.isBlank() || status.account.username==null || status.account.acct==null
				|| status.mediaAttachments==null || status.mentions==null || status.tags==null || status.emojis==null
				|| status.visibility!=StatusPrivacy.PUBLIC && status.visibility!=StatusPrivacy.UNLISTED
				|| status.advertisement!=null || status.friendRequest!=null || status.friendCard!=null
				|| status.novel!=null || status.albumUpdate!=null || status.account.moved!=null) return false;
		if(!canReadNBW && (status.id.startsWith("nbw_") || status.account.id.startsWith("nbw_"))) return false;
		if(status.reblog!=null && !safe(status.reblog, canReadNBW, depth+1)) return false;
		return status.quote==null || status.quote.quotedStatus==null || safe(status.quote.quotedStatus, canReadNBW, depth+1);
	}

	private static void removePrivateAccountFields(JsonObject status){
		JsonObject account=status.getAsJsonObject("account");
		account.remove("source"); account.remove("mute_expires_at"); account.remove("moved");
		if(status.has("reblog") && status.get("reblog").isJsonObject()) removePrivateAccountFields(status.getAsJsonObject("reblog"));
		if(status.has("quote") && status.get("quote").isJsonObject()){
			JsonObject quote=status.getAsJsonObject("quote");
			if(quote.has("quoted_status") && quote.get("quoted_status").isJsonObject()) removePrivateAccountFields(quote.getAsJsonObject("quoted_status"));
		}
	}

	private static boolean validCursor(String value){ return value==null || !value.isEmpty() && bytes(value)<=MAX_CURSOR_BYTES; }
	private static boolean number(JsonObject root, String key){ return root.has(key) && root.get(key).isJsonPrimitive() && root.getAsJsonPrimitive(key).isNumber(); }
	private static boolean string(JsonObject root, String key){ return root.has(key) && root.get(key).isJsonPrimitive() && root.getAsJsonPrimitive(key).isString(); }
	private static int bytes(String value){ return value.getBytes(StandardCharsets.UTF_8).length; }

	/** Bound parser nesting before Gson sees untrusted SQLite text (including escaped quotes). */
	private static boolean boundedJson(String json){
		int depth=0; boolean quoted=false, escaped=false;
		for(int i=0;i<json.length();i++){
			char c=json.charAt(i);
			if(quoted){ if(escaped) escaped=false; else if(c=='\\') escaped=true; else if(c=='"') quoted=false; }
			else if(c=='"') quoted=true;
			else if(c=='{' || c=='['){ if(++depth>MAX_JSON_DEPTH) return false; }
			else if(c=='}' || c==']'){ if(--depth<0) return false; }
		}
		return !quoted && depth==0;
	}
}
