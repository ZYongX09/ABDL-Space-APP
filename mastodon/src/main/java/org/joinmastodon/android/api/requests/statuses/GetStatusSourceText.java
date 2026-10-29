package org.joinmastodon.android.api.requests.statuses;

import com.google.gson.annotations.SerializedName;

import org.joinmastodon.android.api.RequiredField;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.BaseModel;

public class GetStatusSourceText extends MastodonAPIRequest<GetStatusSourceText.Response>{
	public GetStatusSourceText(String id){
		super(HttpMethod.GET, "/statuses/"+id+"/source", Response.class);
	}

	@org.parceler.Parcel
	public static class Response extends BaseModel{
		@RequiredField public String id;
		@RequiredField public String text;
		@RequiredField
		@SerializedName(value="spoiler_text", alternate={"spoilerText"})
		public String spoilerText;
		// Optional metadata: omission on old servers must preserve the original edit state.
		@SerializedName("friend_card_id") public String friendCardId;
		@SerializedName("novel_id") public String novelId;
		@com.google.gson.annotations.JsonAdapter(org.joinmastodon.android.model.NovelAttachment.Deserializer.class)
		public org.joinmastodon.android.model.NovelAttachment novel;
		public String inReplyToId;
		public org.joinmastodon.android.model.StatusPrivacy visibility;
		public String language;
		public Boolean sensitive, mentalCrisis;
		public String geoProvince, geoCity, geoDistrict;
		public Integer nbwFid;
		public Long nbwTid;
		public Boolean nbwSyncEnabled;
		public org.joinmastodon.android.model.Status.NBWSync nbwSync;
	}
}
