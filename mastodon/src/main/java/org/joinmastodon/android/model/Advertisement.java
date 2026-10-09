package org.joinmastodon.android.model;

import android.text.TextUtils;

import com.google.gson.annotations.SerializedName;

import org.parceler.Parcel;

/** Optional timeline advertisement payload. Unknown fields are intentionally ignored. */
@Parcel
public class Advertisement {
	@SerializedName(value="id", alternate={"ad_id", "adId"})
	public String id;
	@SerializedName(value="type", alternate={"ad_type", "adType", "kind"})
	public String type;
	@SerializedName(value="official", alternate={"is_official", "isOfficial"})
	public Boolean official;
	@SerializedName(value="display_name", alternate={"displayName", "advertiser_name", "sponsor_name"})
	public String displayName;
	@SerializedName(value="username", alternate={"advertiser", "sponsor"})
	public String username;
	@SerializedName(value="title", alternate={"headline", "name"})
	public String title;
	@SerializedName(value="description", alternate={"body", "text", "content"})
	public String description;
	@SerializedName(value="image_url", alternate={"imageUrl", "image", "media_url", "mediaUrl"})
	public String imageUrl;
	@SerializedName(value="avatar_url", alternate={"avatarUrl", "icon_url", "iconUrl", "icon"})
	public String avatarUrl;
	@SerializedName(value="target_url", alternate={"targetUrl", "landing_url", "landingUrl", "url"})
	public String targetUrl;

	public String getLabel(){
		return Boolean.TRUE.equals(official) || "official".equalsIgnoreCase(type) ? "官方广告" : "商家广告";
	}

	public String getDisplayName(Account fallback){
		if(!TextUtils.isEmpty(displayName))
			return displayName;
		return fallback==null ? "" : fallback.displayName;
	}

	public String getUsername(Account fallback){
		if(!TextUtils.isEmpty(username))
			return username.startsWith("@") ? username : "@"+username;
		return fallback==null ? "" : fallback.getDisplayUsername();
	}

	public String getAvatarUrl(Account fallback){
		if(!TextUtils.isEmpty(avatarUrl))
			return avatarUrl;
		if(fallback==null)
			return null;
		return !TextUtils.isEmpty(fallback.avatarStatic) ? fallback.avatarStatic : fallback.avatar;
	}

	public String getBody(String statusContent){
		if(!TextUtils.isEmpty(description))
			return firstLine(description);
		return firstLine(statusContent);
	}

	public static String firstLine(String content){
		if(TextUtils.isEmpty(content))
			return "";
		int newline=content.indexOf('\n');
		return newline<0 ? content : content.substring(0, newline);
	}
}
