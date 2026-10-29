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
import org.joinmastodon.android.novel.app.design.NovelComposeState;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.List;
import okio.Buffer;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=CompatibilityTestApplication.class)
public class NovelStatusWireTest{
	@Before public void setup(){MastodonApp.context=RuntimeEnvironment.getApplication();}
	@Test public void statusAndSourceUseFieldAdapterWithBothGsonConfigsAndRealParcel(){
		for(Gson gson:List.of(new Gson(),MastodonAPIController.gson)){
			Status status=gson.fromJson("{\"id\":\"p-1\",\"content\":\"body\",\"novel\":"+NovelAttachmentWireTest.NOVEL+"}",Status.class);
			assertEquals("work-1",status.novel.workId);assertEquals("chapter-12",status.novel.chapters.get(0).id);
			android.os.Parcel parcel=android.os.Parcel.obtain();
			try{parcel.writeParcelable(org.parceler.Parcels.wrap(status),0);parcel.setDataPosition(0);
				Status restored=org.parceler.Parcels.unwrap(parcel.readParcelable(getClass().getClassLoader()));
				assertEquals("body",restored.content);assertEquals("release-2",restored.novel.releaseId);assertEquals(42,restored.novel.author.id);assertEquals("第13章",restored.novel.chapters.get(1).title);
			}finally{parcel.recycle();}
		}
		GetStatusSourceText.Response source=MastodonAPIController.gson.fromJson("{\"id\":\"p-1\",\"text\":\"clean user body\",\"spoiler_text\":\"\",\"novel_id\":\"work-1\",\"novel\":[]}",GetStatusSourceText.Response.class);
		assertEquals("clean user body",source.text);assertEquals("work-1",source.novelId);assertNull(source.novel);
	}
	@Test public void realPostprocessSurvivesMalformedNovelAndNestedReblog() throws Exception{
		String json="""
			{"id":"p_1","uri":"https://offline.invalid/1","created_at":"2026-10-18T00:00:00Z",
			 "visibility":"public","content":"<p>Must remain readable</p>","spoiler_text":"",
			 "account":{"id":"42","username":"offline","acct":"offline","url":"https://offline.invalid/42"},
			 "mentions":[],"tags":[],"emojis":[],"media_attachments":[],"novel":{"schema_version":1,"mode":"link","available":true,"work_id":"work-1","title":{}}}
			""";
		Status status=MastodonAPIController.gson.fromJson(json,Status.class);status.postprocess();
		assertNull(status.novel);assertEquals("<p>Must remain readable</p>",status.content);
		for(Gson gson:List.of(new Gson(),MastodonAPIController.gson)){
			Status nested=gson.fromJson("{\"id\":\"outer\",\"novel\":true,\"reblog\":{\"id\":\"inner\",\"novel\":42,\"content\":\"body\"}}",Status.class);
			assertNull(nested.novel);assertNull(nested.reblog.novel);assertEquals("body",nested.reblog.content);
		}
	}
	@Test public void createAndEditRealSerializersRespectTriStateAndNeverSyncNovelToNBW() throws Exception{
		CreateStatus.Request body=new CreateStatus.Request();body.status="user body";body.nbwFid=27;body.nbwTypeid=2;body.nbwClassification="manual";
		assertFalse(wire(new EditStatus(body,"p-1")).has("novel_id"));assertFalse(wire(new CreateStatus(body,"op")).has("novel_id"));
		body.setNovel("work-1");
		for(MastodonAPIRequest<?> request:List.of(new EditStatus(body,"p-1"),new CreateStatus(body,"op"))){
			JsonObject json=wire(request);assertEquals("work-1",json.get("novel_id").getAsString());assertEquals("user body",json.get("status").getAsString());
			for(String key:List.of("nbw_fid","nbw_typeid","nbw_classification"))assertFalse(json.has(key));
		}
		body.setNovel(null);JsonObject removed=wire(new EditStatus(body,"p-1"));assertTrue(removed.has("novel_id"));assertTrue(removed.get("novel_id").isJsonNull());
		body.setNovel("work-1");body.setFriendCard("card-1");assertThrows(IllegalArgumentException.class,body::toJson);
	}
	@Test public void statePersistsKeepReplaceRemoveWithoutConvertingUntouchedIntoExplicitNull(){
		NovelComposeState state=new NovelComposeState();state.initialize("work-1",null);
		CreateStatus.Request untouched=new CreateStatus.Request();state.apply(untouched);assertFalse(untouched.toJson().has("novel_id"));assertFalse(state.isChanged());
		state.select("work-2",null);CreateStatus.Request replaced=new CreateStatus.Request();state.apply(replaced);assertEquals("work-2",replaced.toJson().get("novel_id").getAsString());assertTrue(state.isChanged());
		state.remove();CreateStatus.Request removed=new CreateStatus.Request();state.apply(removed);assertTrue(removed.toJson().get("novel_id").isJsonNull());
	}
	private static JsonObject wire(MastodonAPIRequest<?> request) throws Exception{Buffer b=new Buffer();request.getRequestBody().writeTo(b);return JsonParser.parseString(b.readUtf8()).getAsJsonObject();}
}
