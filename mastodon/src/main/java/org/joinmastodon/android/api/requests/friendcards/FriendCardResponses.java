package org.joinmastodon.android.api.requests.friendcards;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import org.joinmastodon.android.model.FriendCard;

public final class FriendCardResponses{
	private FriendCardResponses(){}
	public static class Game{
		@SerializedName("id") public String id;
		@SerializedName("name") public String name;
	}
	public static class Options{
		@SerializedName("schema_version") public int schemaVersion;
		@SerializedName("games") public List<Game> games;
		@SerializedName("interest_presets") public List<String> interestPresets;
		@SerializedName("can_create") public boolean canCreate;
		@SerializedName("verification_state") public String verificationState;
	}
	public static class Me{
		@SerializedName("card") public FriendCard card;
		@SerializedName("can_create") public boolean canCreate;
		@SerializedName("verification_state") public String verificationState;
	}
	public static class Page{
		@SerializedName("cards") public List<FriendCard> cards;
		@SerializedName("next_cursor") public String nextCursor;
	}
	public static class Card{
		@SerializedName("card") public FriendCard card;
		@SerializedName("replayed") public boolean replayed;
	}
	public static class Deleted{ @SerializedName("deleted") public boolean deleted; }
	public static class Reported{ @SerializedName("reported") public boolean reported; }
	public static class Blocked{ @SerializedName("blocked") public boolean blocked; }
}
