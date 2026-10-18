package org.joinmastodon.android.model;

import static org.joinmastodon.android.api.MastodonAPIController.gson;
import static org.joinmastodon.android.api.MastodonAPIController.gsonWithoutDeserializer;

import android.text.TextUtils;

import org.joinmastodon.android.api.ObjectValidationException;
import org.joinmastodon.android.api.RequiredField;
import org.joinmastodon.android.events.StatusCountersUpdatedEvent;
import org.joinmastodon.android.ui.text.HtmlParser;
import org.parceler.Parcel;

import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;

@Parcel
public class Status extends BaseModel implements DisplayItemsParent, Searchable {
	@RequiredField
	public String id;
	@RequiredField
	public String uri;
	@RequiredField
	public Instant createdAt;
	@RequiredField
	public Account account;
//	@RequiredField
	public String content;
	@RequiredField
	public StatusPrivacy visibility;
	public boolean sensitive;
	@SerializedName("mental_crisis")
	public boolean mentalCrisis;

	@SerializedName("geo_location")
	public String geoLocation;
//	@RequiredField
	public String spoilerText="";
//	@RequiredField
	public List<Attachment> mediaAttachments;
	public Application application;
	@RequiredField
	public List<Mention> mentions;
	@RequiredField
	public List<Hashtag> tags;
	@RequiredField
	public List<Emoji> emojis;
	public long reblogsCount;
	public long favouritesCount;
	public long repliesCount;
	public long quotesCount;
	public long bookmarksCount;
	public long sharesCount;
	public long viewsCount;		// 浏览量（12h 去重）
	public double heat;			// 热度（后端计算下发，App 不重算）
	@SerializedName("friend_request")
	public FriendRequest friendRequest;	// 交友宇宙卡片载荷（非标准字段，仅在跨站时间线内存在）
	/** Optional, independently versioned extension. Invalid payloads must not drop the post. */
	@SerializedName("friend_card")
	@com.google.gson.annotations.JsonAdapter(FriendCardDeserializer.class)
	@Nullable
	public FriendCardAttachment friendCard;
		@SerializedName("album_update")
		public org.joinmastodon.android.model.albums.AlbumModels.AlbumUpdate albumUpdate;

	/** Optional extension payload. Ordinary Mastodon statuses keep this null. */
	@SerializedName(value="advertisement", alternate={"advertisement_data", "ad"})
	@Nullable
	public Advertisement advertisement;
	public Instant editedAt;

	public String url;
	public String inReplyToId;
	public String inReplyToAccountId;
	public Status reblog;
	public Poll poll;
	public Card card;
	public String language;
	public String text;
	public List<FilterResult> filtered;
	public Quote quote;
	public QuoteApproval quoteApproval;

	public boolean favourited;
	public boolean reblogged;
	public Boolean muted;
	public boolean bookmarked;
	public Boolean pinned;

	public transient EnumSet<SpoilerType> revealedSpoilers=EnumSet.noneOf(SpoilerType.class);
	public transient boolean hasGapAfter;
	private transient String strippedText;
	public transient TranslationState translationState=TranslationState.HIDDEN;
	public transient Translation translation;

	// MOSHIDON:
	public boolean localOnly;
	public boolean isRemote;
	public transient boolean sensitiveRevealed;
	public transient boolean preview; // for akkoma compose previews


	public List<EmojiReaction> reactions;
	protected List<EmojiReaction> emojiReactions; // akkoma


	public Status(){}

	@Override
	public void postprocess() throws ObjectValidationException{
		super.postprocess();
		if(application!=null)
			application.postprocess();
		for(Mention m:mentions)
			m.postprocess();
		for(Hashtag t:tags)
			t.postprocess();
		for(Emoji e:emojis)
			e.postprocess();
		for(Attachment a:mediaAttachments)
			a.postprocess();
		account.postprocess();
		if(poll!=null)
			poll.postprocess();
		if(card!=null)
			card.postprocess();
		if(reblog!=null)
			reblog.postprocess();
		if(filtered!=null){
			for(FilterResult fr:filtered)
				fr.postprocess();
		}
		if(quote!=null)
			quote.postprocess();
		if(quoteApproval!=null)
			quoteApproval.postprocess();

		if(!sensitive && (reblog==null || !reblog.sensitive) && TextUtils.isEmpty(spoilerText)){
			revealedSpoilers.add(SpoilerType.CONTENT_WARNING);
		}

		// MOSHIDON:
		if(visibility.equals(StatusPrivacy.LOCAL)) localOnly=true;
		if(emojiReactions!=null) reactions=emojiReactions;
		if(reactions==null) reactions=new ArrayList<>();
	}

	@Override
	public String toString(){
		return "Status{"+
				"account="+account+
				", id='"+id+'\''+
				", uri='"+uri+'\''+
				", createdAt="+createdAt+
				", content='"+content+'\''+
				", visibility="+visibility+
				", sensitive="+sensitive+
				", spoilerText='"+spoilerText+'\''+
				", mediaAttachments="+mediaAttachments+
				", application="+application+
				", mentions="+mentions+
				", tags="+tags+
				", emojis="+emojis+
				", reblogsCount="+reblogsCount+
				", favouritesCount="+favouritesCount+
				", repliesCount="+repliesCount+
				", quotesCount="+quotesCount+
				", editedAt="+editedAt+
				", url='"+url+'\''+
				", inReplyToId='"+inReplyToId+'\''+
				", inReplyToAccountId='"+inReplyToAccountId+'\''+
				", reblog="+reblog+
				", poll="+poll+
				", card="+card+
				", language='"+language+'\''+
				", text='"+text+'\''+
				", filtered="+filtered+
				", quote="+quote+
				", quoteApproval="+quoteApproval+
				", favourited="+favourited+
				", reblogged="+reblogged+
				", muted="+muted+
				", bookmarked="+bookmarked+
				", pinned="+pinned+
				", revealedSpoilers="+revealedSpoilers+
				", hasGapAfter="+hasGapAfter+
				", strippedText='"+strippedText+'\''+
				", translationState="+translationState+
				", translation="+translation+
				'}';
	}

	@Override
	public String getID(){
		return id;
	}

	@Override
	public String getAccountID(){
		return getContentStatus().account.id;
	}

	public void update(StatusCountersUpdatedEvent ev){
		switch(ev.type){
			case FAVORITES -> {
				favouritesCount=ev.favorites;
				favourited=ev.favorited;
			}
			case REBLOGS -> {
				reblogsCount=ev.reblogs;
				reblogged=ev.reblogged;
			}
			case REPLIES -> repliesCount=ev.replies;
			case BOOKMARKS -> bookmarked=ev.bookmarked;
			case HEAT -> { /* 仅同步浏览量/热度，见下 */ }
		}
		if(ev.type==StatusCountersUpdatedEvent.CounterType.HEAT || ev.views>0 || ev.heat>0){
			viewsCount=ev.views;
			heat=ev.heat;
		}
	}

	public Status getContentStatus(){
		return reblog!=null ? reblog : this;
	}

	public String getStrippedText(){
		if(strippedText==null)
			strippedText=HtmlParser.strip(content);
		return strippedText;
	}

	@NonNull
	@Override
	public Status clone(){
		Status copy=(Status) super.clone();
		copy.revealedSpoilers=EnumSet.noneOf(SpoilerType.class);
		copy.translationState=TranslationState.HIDDEN;
		return copy;
	}

	public boolean isEligibleForTranslation(){
		return !TextUtils.isEmpty(content) && !TextUtils.isEmpty(language) && !Objects.equals(Locale.getDefault().getLanguage(), language)
				&& (visibility==StatusPrivacy.PUBLIC || visibility==StatusPrivacy.UNLISTED);
	}

	public enum TranslationState{
		HIDDEN,
		SHOWN,
		LOADING
	}

	public enum SpoilerType{
		CONTENT_WARNING,
		FILTER
	}

	/** Keep a bad/unknown optional extension from failing an otherwise valid status or reblog. */
	public static class FriendCardDeserializer implements JsonDeserializer<FriendCardAttachment>{
		@Override
		public FriendCardAttachment deserialize(JsonElement json, Type type, JsonDeserializationContext context){
			try{
				if(!json.isJsonObject()) return null;
				JsonObject object=json.getAsJsonObject();
				if(!object.has("schema_version") || !object.get("schema_version").isJsonPrimitive()
						|| !object.getAsJsonPrimitive("schema_version").isNumber()
						|| object.get("schema_version").getAsDouble()!=1) return null;
				if(!object.has("mode") || !object.get("mode").isJsonPrimitive()
						|| !object.getAsJsonPrimitive("mode").isString()) return null;
				String mode=object.get("mode").getAsString();
				if(!"publication".equals(mode) && !"link".equals(mode)) return null;
				if(!object.has("available") || !object.get("available").isJsonPrimitive()
						|| !object.getAsJsonPrimitive("available").isBoolean() || !requiredString(object, "id")) return null;
				if(!strings(object, "title", "url", "fallback_suffix")) return null;
				// Unavailable cards must never retain stale private/title/cover metadata on the client.
				if(!object.get("available").getAsBoolean()){
					object=object.deepCopy();
					object.remove("title"); object.remove("url"); object.remove("card");
				}else if(object.has("card") && !object.get("card").isJsonNull() && !validCard(object.get("card"))){
					return null;
				}
				return context.deserialize(object, FriendCardAttachment.class);
			}catch(RuntimeException malformedExtension){
				return null;
			}
		}

		private static boolean requiredString(JsonObject object, String key){
			return object.has(key) && !object.get(key).isJsonNull() && strings(object, key)
					&& !object.get(key).getAsString().isBlank();
		}
		private static boolean strings(JsonObject object, String... keys){
			for(String key:keys){
				JsonElement value=object.get(key);
				if(value!=null && !value.isJsonNull() && (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())) return false;
			}
			return true;
		}
		private static boolean stringLists(JsonObject object, String... keys){
			for(String key:keys){
				JsonElement value=object.get(key);
				if(value==null || value.isJsonNull()) continue;
				if(!value.isJsonArray()) return false;
				for(JsonElement entry:value.getAsJsonArray())
					if(!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) return false;
			}
			return true;
		}
		private static boolean validCard(JsonElement value){
			if(!value.isJsonObject()) return false;
			JsonObject card=value.getAsJsonObject();
			if(!card.has("schema_version") || !card.get("schema_version").isJsonPrimitive()
					|| !card.getAsJsonPrimitive("schema_version").isNumber() || card.get("schema_version").getAsDouble()!=1) return false;
			if(!requiredString(card, "id") || !requiredString(card, "title") || !strings(card, "owner_id", "post_id", "state", "url",
					"gender", "age", "zodiac", "region", "school", "mbti", "interaction_habits", "looking_for", "boundaries", "cover_url")
					|| !stringLists(card, "interests", "games", "public_fields")) return false;
			for(String key:List.of("cover_focus_x", "cover_focus_y")){
				JsonElement focus=card.get(key);
				if(focus!=null && !focus.isJsonNull() && (!focus.isJsonPrimitive() || !focus.getAsJsonPrimitive().isNumber()
						|| !Double.isFinite(focus.getAsDouble()))) return false;
			}
			return true;
		}
	}

	// MOSHIDON: we use this for akkoma quotes
	public static class StatusDeserializer implements JsonDeserializer<Status>{
		@Override
		public Status deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException{
			JsonObject obj=json.getAsJsonObject();
//			// FIXME: find way to make this work with akkoma
//			Status quote=null;
//			if (obj.has("quote") && obj.get("quote").isJsonObject()) {
//				quote=gson.fromJson(obj.get("quote"), Status.class);
//
//				if (quote.account == null) {
//					quote=null;
//				}
//			}
//			obj.remove("quote");
//
//			Status reblog=null;
//			if (obj.has("reblog"))
//				reblog=gson.fromJson(obj.get("reblog"), Status.class);
//			obj.remove("reblog");
//
			Status status=gsonWithoutDeserializer.fromJson(json, Status.class);
			// FIXME: I am not sure this works
//			status.quote=quote;
//			status.reblog=reblog;

			return status;
		}
	}
	// MOSHIDON:
	public static Status ofFake(String id, String text, Instant createdAt) {
		Status s=new Status();
		s.id=id;
		s.mediaAttachments=List.of();
		s.createdAt=createdAt;
		s.content=s.text=text;
		s.spoilerText="";
		s.visibility=StatusPrivacy.PUBLIC;
		s.reactions=List.of();
		s.mentions=List.of();
		s.tags=List.of();
		s.emojis=List.of();
		s.filtered=List.of();
		return s;
	}

	// MOSHIDON:
	@Override
	public String getQuery() {
		return url;
	}
}
