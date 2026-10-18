package org.joinmastodon.android.model;

import com.google.gson.annotations.SerializedName;
import org.parceler.Parcel;
import java.util.ArrayList;
import java.util.List;

/** Wire DTO. Keep explicit names: this object is also read with plain Gson and Parceler. */
@Parcel
public class FriendCard extends BaseModel{
	@SerializedName("id") public String id;
	@SerializedName("schema_version") public int schemaVersion;
	@SerializedName("version") public int version;
	@SerializedName("owner_id") public String ownerId;
	@SerializedName("owner") public Owner owner;
	@SerializedName("state") public String state;
	@SerializedName("post_id") public String postId;
	@SerializedName("url") public String url;
	@SerializedName("title") public String title;
	@SerializedName("gender") public String gender;
	@SerializedName("age") public String age;
	@SerializedName("zodiac") public String zodiac;
	@SerializedName("region") public String region;
	@SerializedName("school") public String school;
	@SerializedName("mbti") public String mbti;
	@SerializedName("interaction_habits") public String interactionHabits;
	@SerializedName("interests") public List<String> interests=new ArrayList<>();
	@SerializedName("games") public List<String> games=new ArrayList<>();
	@SerializedName("diapers") public List<Diaper> diapers=new ArrayList<>();
	@SerializedName("looking_for") public String lookingFor;
	@SerializedName("boundaries") public String boundaries;
	@SerializedName("public_fields") public List<String> publicFields=new ArrayList<>();
	@SerializedName("cover_upload_id") public String coverUploadId;
	@SerializedName("cover_url") public String coverUrl;
	@SerializedName("cover_focus_x") public float coverFocusX=.5f;
	@SerializedName("cover_focus_y") public float coverFocusY=.4f;
	@SerializedName("created_at") public String createdAt;
	@SerializedName("updated_at") public String updatedAt;
	@SerializedName("reason") public String reason;

	public boolean isSupported(){
		return schemaVersion==1 && id!=null && !id.isBlank() && title!=null && !title.isBlank();
	}
	public boolean isPublic(String field){ return publicFields!=null && publicFields.contains(field); }

	@Parcel public static class Owner{
		@SerializedName("id") public String id;
		@SerializedName("username") public String username;
		@SerializedName("display_name") public String displayName;
		@SerializedName("avatar") public String avatar;
	}
	@Parcel public static class Diaper{
		@SerializedName("id") public int id;
		@SerializedName("name") public String name;
		@SerializedName("image_url") public String imageUrl;
		@SerializedName("available") public boolean available;
	}
}
