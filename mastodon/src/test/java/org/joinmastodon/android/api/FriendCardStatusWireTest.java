package org.joinmastodon.android.api;

import static org.junit.Assert.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.requests.statuses.CreateStatus;
import org.joinmastodon.android.api.requests.statuses.EditStatus;
import org.joinmastodon.android.api.requests.statuses.GetStatusSourceText;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.ui.displayitems.FriendCardStatusDisplayItem;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

import okio.Buffer;

/** Uses the actual field adapter and request-body serializers, never a global Gson workaround. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class)
public class FriendCardStatusWireTest{
	private static final String CARD="""
		{"schema_version":1,"mode":"publication","id":"card-1","title":"Title","url":"https://abdl-space.top/forum/1","available":true,
		 "card":{"schema_version":1,"id":"card-1","state":"active","title":"Title","public_fields":["looking_for"],"looking_for":"Friends"}}
		""";

	@Before public void setup(){ MastodonApp.context=RuntimeEnvironment.getApplication(); }

	@Test public void supportedPayloadUsesExplicitWireNamesWithBothGsonConfigurationsAndParcels(){
		for(Gson gson:List.of(new Gson(), MastodonAPIController.gson)){
			Status status=gson.fromJson("{\"id\":\"p_1\",\"content\":\"legacy body\",\"friend_card\":"+CARD+"}", Status.class);
			assertEquals("p_1", status.id); assertEquals("legacy body", status.content);
			assertNotNull(status.friendCard); assertTrue(status.friendCard.hasPublication());
			assertEquals("Friends", status.friendCard.card.lookingFor);
			android.os.Parcel parcel=android.os.Parcel.obtain();
			try{
				parcel.writeParcelable(org.parceler.Parcels.wrap(status), 0); parcel.setDataPosition(0);
				Status restored=org.parceler.Parcels.unwrap(parcel.readParcelable(getClass().getClassLoader()));
				assertEquals("card-1", restored.friendCard.id); assertEquals("Friends", restored.friendCard.card.lookingFor);
			}finally{ parcel.recycle(); }
		}
	}

	@Test public void malformedOptionalExtensionNeverDropsOuterStatusOrNestedReblog(){
		for(String bad:List.of("null", "[]", "true", "42", "\"bad\"", "{}",
				CARD.replace("\"schema_version\":1", "\"schema_version\":2"),
				CARD.replace("\"schema_version\":1", "\"schema_version\":\"1\""),
				CARD.replace("\"available\":true", "\"available\":\"true\""),
				CARD.replace("\"title\":\"Title\"", "\"title\":{}"),
				CARD.replace("\"public_fields\":[\"looking_for\"]", "\"public_fields\":[{}]"),
				CARD.replace("\"looking_for\":\"Friends\"", "\"cover_focus_x\":\"bad\""),
				CARD.replace("\"looking_for\":\"Friends\"", "\"interests\":42"))){
			for(Gson gson:List.of(new Gson(), MastodonAPIController.gson)){
				String nested="{\"id\":\"p_nested\",\"content\":\"still here\",\"friend_card\":"+bad+"}";
				Status status=gson.fromJson("{\"id\":\"boost\",\"content\":\"outer\",\"friend_card\":"+bad+",\"reblog\":"+nested+"}", Status.class);
				assertEquals("boost", status.id); assertEquals("outer", status.content); assertNull(bad, status.friendCard);
				assertEquals("p_nested", status.reblog.id); assertEquals("still here", status.reblog.content); assertNull(status.reblog.friendCard);
			}
		}
	}

	@Test public void actualPostprocessSurvivesCorruptOptionalAttachment() throws Exception{
		String json="""
			{"id":"p_1","uri":"https://abdl-space.top/api/v1/statuses/1","created_at":"2026-10-18T00:00:00Z",
			 "visibility":"public","content":"<p>Must remain readable</p>","spoiler_text":"",
			 "account":{"id":"42","username":"offline","acct":"offline","url":"https://abdl-space.top/profile/42"},
			 "mentions":[],"tags":[],"emojis":[],"media_attachments":[],"friend_card":{"schema_version":1,"mode":"publication","available":true,"id":"card","card":{"title":{}}}}
			""";
		Status status=MastodonAPIController.gson.fromJson(json, Status.class); status.postprocess();
		assertEquals("p_1",status.id); assertEquals("<p>Must remain readable</p>",status.content); assertNull(status.friendCard);
		assertEquals("42",status.account.id);
	}

	@Test public void invalidPublicationFallsBackAndUnavailableLinkDropsStalePrivateMetadata(){
		Gson gson=MastodonAPIController.gson;
		Status mismatch=gson.fromJson("{\"friend_card\":"+CARD.replace("\"id\":\"card-1\",\"state\"", "\"id\":\"wrong\",\"state\"")+"}", Status.class);
		assertEquals(FriendCardStatusDisplayItem.Presentation.NONE, FriendCardStatusDisplayItem.presentation(mismatch.friendCard));
		Status unavailable=gson.fromJson("{\"friend_card\":"+CARD.replace("\"publication\"", "\"link\"").replace("\"available\":true", "\"available\":false")+"}", Status.class);
		assertNotNull(unavailable.friendCard); assertNull(unavailable.friendCard.title); assertNull(unavailable.friendCard.url); assertNull(unavailable.friendCard.card);
		assertEquals(FriendCardStatusDisplayItem.Presentation.UNAVAILABLE_LINK, FriendCardStatusDisplayItem.presentation(unavailable.friendCard));
		assertNull(gson.fromJson("{\"content\":\"old server\"}", Status.class).friendCard);
	}

	@Test public void sourceAcceptsMissingOrNullReferenceAndKeepsPlainTextWithoutProjection() throws Exception{
		for(String suffix:List.of("", ",\"friend_card_id\":null", ",\"friend_card_id\":\"card-1\"")){
			GetStatusSourceText.Response response=MastodonAPIController.gson.fromJson("{\"id\":\"p_1\",\"text\":\"plain & original\\n\",\"spoiler_text\":\"\""+suffix+"}", GetStatusSourceText.Response.class);
			response.postprocess();
			assertEquals("plain & original\n", response.text);
			assertEquals(suffix.contains("card-1") ? "card-1" : null, response.friendCardId);
		}
	}

	@Test public void createAndEditSerializeAbsentSetAndExplicitNullWithoutGlobalSerializeNulls() throws Exception{
		CreateStatus.Request body=new CreateStatus.Request(); body.status="Original"; body.nbwFid=27;
		for(boolean editing:List.of(false, true)){
			JsonObject absent=wire(editing ? new EditStatus(body, "p_1") : new CreateStatus(body, "operation"));
			assertFalse(absent.has("friend_card_id")); assertEquals(27, absent.get("nbw_fid").getAsInt());
		}
		body.setFriendCard("card-1");
		for(boolean editing:List.of(false, true)){
			JsonObject attached=wire(editing ? new EditStatus(body, "p_1") : new CreateStatus(body, "operation"));
			assertEquals("card-1", attached.get("friend_card_id").getAsString());
			assertFalse("No friend-card representation is supported by NBW", attached.has("nbw_fid"));
		}
		body.setFriendCard(null); body.nbwFid=null;
		for(boolean editing:List.of(false, true)){
			JsonObject removed=wire(editing ? new EditStatus(body, "p_1") : new CreateStatus(body, "operation"));
			assertTrue(removed.has("friend_card_id")); assertTrue(removed.get("friend_card_id").isJsonNull());
			assertFalse(removed.has("nbw_fid")); assertFalse(removed.has("in_reply_to_id"));
		}
		assertFalse(MastodonAPIController.gson.toJsonTree(new CreateStatus.Request()).getAsJsonObject().has("friend_card_id"));
	}

	private static JsonObject wire(MastodonAPIRequest<?> request) throws Exception{
		Buffer buffer=new Buffer(); request.getRequestBody().writeTo(buffer);
		return JsonParser.parseString(buffer.readUtf8()).getAsJsonObject();
	}
}
