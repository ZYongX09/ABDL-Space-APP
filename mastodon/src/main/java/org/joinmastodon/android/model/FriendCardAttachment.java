package org.joinmastodon.android.model;

import com.google.gson.annotations.SerializedName;
import org.parceler.Parcel;

/** Optional post extension; malformed/unknown data must fall back to the original post body. */
@Parcel
public class FriendCardAttachment{
	@SerializedName("schema_version") public int schemaVersion;
	@SerializedName("mode") public String mode;
	@SerializedName("id") public String id;
	@SerializedName("title") public String title;
	@SerializedName("url") public String url;
	@SerializedName("available") public boolean available;
	@SerializedName("card") public FriendCard card;
	@SerializedName("fallback_suffix") public String fallbackSuffix;
	public boolean isSupported(){ return schemaVersion==1 && id!=null && !id.isBlank() && ("publication".equals(mode) || "link".equals(mode)); }
	public boolean hasPublication(){ return isSupported() && "publication".equals(mode) && available && card!=null && card.isSupported() && id.equals(card.id); }
}
