package org.joinmastodon.android.api.requests.statuses;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

import org.joinmastodon.android.api.MastodonAPIController;

import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.StatusPrivacy;
import org.joinmastodon.android.model.StatusQuotePolicy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class CreateStatus extends MastodonAPIRequest<Status>{
	public CreateStatus(CreateStatus.Request req, String uuid){
		super(HttpMethod.POST, "/statuses", Status.class);
		setRequestBody(req.toJson());
		addHeader("Idempotency-Key", uuid);
	}

	public static class Request{
		public String status;
		public List<MediaAttribute> mediaAttributes;
		public List<String> mediaIds;
		public Poll poll;
		public String inReplyToId;
		public boolean sensitive;
		@SerializedName("mental_crisis")
		public boolean mentalCrisis;
		public String spoilerText;
		public StatusPrivacy visibility;
		public Instant scheduledAt;
		public String language;
		public StatusQuotePolicy quoteApprovalPolicy;
		public String quotedStatusId;
		@SerializedName("nbw_fid")
		public Integer nbwFid;
		@SerializedName("nbw_typeid")
		public Integer nbwTypeid;
		@SerializedName("nbw_classification")
		public String nbwClassification;

		public void setNBWSelection(org.joinmastodon.android.model.NBWSelection selection){
			nbwClassification=selection.mode;
			nbwFid=selection.isManual() ? selection.fid : null;
			nbwTypeid=selection.isManual() ? selection.typeid : null;
		}
		/** Absent preserves an edit's current reference; setFriendCard(null) explicitly removes it. */
		@SerializedName("friend_card_id")
		public String friendCardId;
		private transient boolean friendCardSpecified;

		public void setFriendCard(String id){
			friendCardId=id;
			friendCardSpecified=true;
		}

		@SerializedName("novel_id") public String novelId;
		private transient boolean novelSpecified;

		/** Omitted on unchanged edits, JSON null on removal, a string on selection/replacement. */
		public void setNovel(String id){ novelId=id; novelSpecified=true; }

		public JsonObject toJson(){
			if(novelId!=null && friendCardId!=null)
				throw new IllegalArgumentException("mixed_entity_refs");
			JsonObject body=MastodonAPIController.gson.toJsonTree(this).getAsJsonObject();
			if(novelSpecified || novelId!=null) body.addProperty("novel_id", novelId);
			if(novelId!=null){
				body.remove("nbw_fid"); body.remove("nbw_typeid");
				body.remove("nbw_classification");
			}
			if(friendCardSpecified || friendCardId!=null){
				body.addProperty("friend_card_id", friendCardId);
				// NBW has no friend-card representation. Never silently synchronize an attached card.
				if(friendCardId!=null) body.remove("nbw_fid");
			}
			return body;
		}
		@SerializedName("geo_province")
		public String geoProvince;
		@SerializedName("geo_city")
		public String geoCity;
		@SerializedName("geo_district")
		public String geoDistrict;

		public static class Poll{
			public ArrayList<String> options=new ArrayList<>();
			public int expiresIn;
			public boolean multiple;
			public boolean hideTotals;
		}

		public static class MediaAttribute{
			public String id;
			public String description;
			public String focus;
			public String blurhash;

			public MediaAttribute(String id, String description, String focus, String blurhash){
				this.id=id;
				this.description=description;
				this.focus=focus;
				this.blurhash=blurhash;
			}
		}
	}
}
