package org.joinmastodon.android.model;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import org.parceler.Parcel;
import java.lang.reflect.Type;
import java.util.List;

/** Independently versioned public projection, never a trusted cached snapshot of a withdrawn work. */
@Parcel
public class NovelAttachment{
	@SerializedName("schema_version") public int schemaVersion;
	public String mode;
	public boolean available;
	@SerializedName("work_id") public String workId;
	@SerializedName("release_id") public String releaseId;
	public String title;
	public Author author;
	@SerializedName("cover_url") public String coverUrl;
	@SerializedName("work_type") public String workType;
	@SerializedName("original_author") public String originalAuthor;
	public boolean completed;
	@SerializedName("chapter_count") public long chapterCount;
	@SerializedName("word_count") public long wordCount;
	public String description;
	public List<Chapter> chapters;
	public String excerpt;
	@SerializedName("first_chapter_id") public String firstChapterId;

	@Parcel public static class Author{
		public long id;
		public String username;
		@SerializedName("display_name") public String displayName;
		public String avatar, url;
	}
	@Parcel public static class Chapter{
		public String id, title;
		@SerializedName("word_count") public long wordCount;
	}

	public boolean isSupported(){
		return schemaVersion==1 && id(workId) && ("new_book".equals(mode) || "chapter_update".equals(mode) || "link".equals(mode));
	}
	public boolean isDisplayable(){ return isSupported() && available && text(title) && author!=null && author.id>0 && text(author.username); }
	public String getDisplayAuthorName(){
		if("repost".equals(workType)) return text(originalAuthor) ? originalAuthor : "原作者未填写";
		return author==null ? "" : text(author.displayName) ? author.displayName : author.username;
	}
	private static boolean text(String s){ return s!=null && !s.isBlank(); }
	private static boolean id(String s){ return s!=null && s.matches("[A-Za-z0-9_-]{1,128}"); }

	/** The annotation is on Status.novel only, so nested/reblog deserialization is independently guarded. */
	public static final class Deserializer implements JsonDeserializer<NovelAttachment>{
		@Override public NovelAttachment deserialize(JsonElement json, Type type, JsonDeserializationContext context){
			try{
				if(json==null || !json.isJsonObject()) return null;
				JsonObject o=json.getAsJsonObject();
				if(!number(o,"schema_version",true) || o.get("schema_version").getAsLong()!=1 || !string(o,"mode",true)
						|| !string(o,"work_id",true) || !id(o.get("work_id").getAsString()) || !bool(o,"available",true)) return null;
				String mode=o.get("mode").getAsString();
				if(!List.of("new_book","chapter_update","link").contains(mode)) return null;
				if(!o.get("available").getAsBoolean()){
					NovelAttachment unavailable=new NovelAttachment(); unavailable.schemaVersion=1; unavailable.mode=mode;
					unavailable.workId=o.get("work_id").getAsString(); return unavailable;
				}
				for(String k:List.of("release_id","title","cover_url","work_type","original_author","description","excerpt","first_chapter_id"))
					if(!string(o,k,false)) return null;
				for(String k:List.of("word_count","chapter_count")) if(!number(o,k,false)) return null;
				if(!bool(o,"completed",false) || !string(o,"title",true)) return null;
				JsonElement a=o.get("author"); if(a==null || !a.isJsonObject()) return null;
				JsonObject author=a.getAsJsonObject();
				if(!number(author,"id",true) || author.get("id").getAsLong()<=0 || !string(author,"username",true)) return null;
				for(String k:List.of("display_name","avatar","url")) if(!string(author,k,false)) return null;
				JsonElement chapters=o.get("chapters");
				if(chapters!=null && !chapters.isJsonNull()){
					if(!chapters.isJsonArray() || chapters.getAsJsonArray().size()>1000) return null;
					for(JsonElement c:chapters.getAsJsonArray()){
						if(!c.isJsonObject()) return null;
						JsonObject chapter=c.getAsJsonObject();
						if(!string(chapter,"id",true) || !id(chapter.get("id").getAsString()) || !string(chapter,"title",true) || !number(chapter,"word_count",false)) return null;
					}
				}
				NovelAttachment result=context.deserialize(o,NovelAttachment.class);
				return result.isDisplayable() ? result : null;
			}catch(RuntimeException malformedOptionalField){ return null; }
		}
		private static boolean string(JsonObject o,String k,boolean required){
			JsonElement v=o.get(k); return v==null || v.isJsonNull() ? !required
					: v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() && (!required || !v.getAsString().isBlank());
		}
		private static boolean bool(JsonObject o,String k,boolean required){
			JsonElement v=o.get(k); return v==null || v.isJsonNull() ? !required : v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean();
		}
		private static boolean number(JsonObject o,String k,boolean required){
			JsonElement v=o.get(k); if(v==null || v.isJsonNull()) return !required;
			if(!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) return false;
			java.math.BigDecimal n=v.getAsBigDecimal(); return n.signum()>=0 && n.stripTrailingZeros().scale()<=0 && n.compareTo(java.math.BigDecimal.valueOf(Long.MAX_VALUE))<=0;
		}
	}
}
