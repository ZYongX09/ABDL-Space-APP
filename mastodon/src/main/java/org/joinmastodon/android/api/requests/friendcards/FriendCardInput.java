package org.joinmastodon.android.api.requests.friendcards;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.List;
import org.joinmastodon.android.model.FriendCard;

/** Only the editable fields; never send account profile data or a client verification flag. */
public class FriendCardInput{
	@SerializedName("title") public String title="";
	@SerializedName("gender") public String gender="";
	@SerializedName("age") public String age="";
	@SerializedName("zodiac") public String zodiac="";
	@SerializedName("region") public String region="";
	@SerializedName("school") public String school="";
	@SerializedName("mbti") public String mbti="";
	@SerializedName("interaction_habits") public String interactionHabits="";
	@SerializedName("interests") public List<String> interests=new ArrayList<>();
	@SerializedName("games") public List<String> games=new ArrayList<>();
	@SerializedName("diaper_ids") public List<Integer> diaperIds=new ArrayList<>();
	@SerializedName("looking_for") public String lookingFor="";
	@SerializedName("boundaries") public String boundaries="";
	@SerializedName("public_fields") public List<String> publicFields=new ArrayList<>(List.of("gender", "age", "zodiac", "region", "mbti", "interaction_habits", "interests", "games", "diapers", "looking_for", "boundaries"));
	@SerializedName("cover_upload_id") public String coverUploadId;
	@SerializedName("cover_focus_x") public float coverFocusX=.5f;
	@SerializedName("cover_focus_y") public float coverFocusY=.4f;

	public static FriendCardInput fromCard(FriendCard card){
		FriendCardInput value=new FriendCardInput();
		value.title=text(card.title); value.gender=text(card.gender); value.age=text(card.age); value.zodiac=text(card.zodiac);
		value.region=text(card.region); value.school=text(card.school); value.mbti=text(card.mbti); value.interactionHabits=text(card.interactionHabits);
		value.interests=new ArrayList<>(card.interests==null ? List.of() : card.interests);
		value.games=new ArrayList<>(card.games==null ? List.of() : card.games);
		if(card.diapers!=null) for(FriendCard.Diaper diaper:card.diapers) if(diaper!=null && !value.diaperIds.contains(diaper.id)) value.diaperIds.add(diaper.id);
		value.lookingFor=text(card.lookingFor); value.boundaries=text(card.boundaries);
		value.publicFields=new ArrayList<>(card.publicFields==null ? List.of() : card.publicFields);
		value.coverUploadId=card.coverUploadId; value.coverFocusX=card.coverFocusX; value.coverFocusY=card.coverFocusY;
		return value;
	}
	private static String text(String value){ return value==null ? "" : value; }
}
