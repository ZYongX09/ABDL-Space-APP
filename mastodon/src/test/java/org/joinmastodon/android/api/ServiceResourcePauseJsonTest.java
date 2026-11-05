package org.joinmastodon.android.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class ServiceResourcePauseJsonTest{
	static final String BODY="{\"error\":\"Not retained\",\"code\":\"D1_DAILY_QUOTA_EXHAUSTED\",\"server_time\":\"2026-11-03T15:59:59Z\",\"reset_at\":\"2026-11-04T00:00:00Z\",\"reset_source\":\"estimated_utc_midnight\"}";
	static Response response(String url, int status){ return new Response.Builder().request(new Request.Builder().url(url).build()).protocol(Protocol.HTTP_1_1).code(status).message("test").build(); }
	private ServiceResourcePause detect(String url, int status, String text){ return ServiceResourcePause.fromResponse(response(url, status), text); }
	@Test public void onlyExactHttpsFirstPartyAuthorityWithMachineCodeIsAccepted(){
		for(String host:new String[]{"abdl-space.top", "api.abdl-space.top", "ABDL-SPACE.TOP:443"}) assertNotNull(host, detect("https://"+host+"/api/v1/test", 503, BODY));
		for(String url:new String[]{
				"http://abdl-space.top/api/v1/test", "https://abdl-space.top:444/api/v1/test", "https://api.abdl-space.top:8443/api/v1/test",
				"https://abdl-space.top.evil.test/api/v1/test", "https://evilabdl-space.top/api/v1/test", "https://notabdl-space.top/api/v1/test",
				"https://abdl-space.top./api/v1/test", "https://sub.api.abdl-space.top/api/v1/test", "https://www.abdl-space.top/api/v1/test",
				"https://user:password@abdl-space.top/api/v1/test", "https://abdl-space.top@evil.test/api/v1/test", "https://127.0.0.1/api/v1/test"
		}) assertNull(url, detect(url, 503, BODY));
	}
	@Test public void successStatusIsNeverInspectedEvenWithMatchingErrorOrUserContent(){
		for(int status:new int[]{200, 201, 204, 206, 299}){
			Response response=response("https://api.abdl-space.top/api/v1/statuses", status);
			assertNull(ServiceResourcePause.fromResponse(response, BODY));
			assertNull(ServiceResourcePause.fromResponse(response, ServiceResourcePause.parseErrorObject(BODY)));
			assertNull(ServiceResourcePause.fromResponse(response, "{\"content\":\"D1_DAILY_QUOTA_EXHAUSTED\"}"));
		}
	}
	@Test public void ordinary429QuotaHtmlTimeoutOrTextMentionCannotDeclareServicePause(){
		for(String text:new String[]{"{\"error\":\"D1_DAILY_QUOTA_EXHAUSTED\"}", "{\"error\":\"D1_ERROR: 7500\"}", "{\"error\":\"quota exhausted\",\"code\":\"translation_quota_exceeded\"}", "{\"error\":\"timeout\"}", "{\"error\":\"Too many requests\"}", "<html>D1_DAILY_QUOTA_EXHAUSTED</html>", "", "null", "[]", "42", "\"D1_DAILY_QUOTA_EXHAUSTED\""}){
			assertNull(text, detect("https://api.abdl-space.top/test", 429, text));
			assertNull(text, detect("https://api.abdl-space.top/test", 503, text));
		}
	}
	@Test public void machineCodeHasStrictTypeAndExactValue(){
		for(String value:new String[]{"true", "0", "null", "[]", "{}", "\"d1_daily_quota_exhausted\"", "\"D1_DAILY_QUOTA_EXHAUSTED \""}) assertNull(value, detect("https://abdl-space.top/test", 503, "{\"code\":"+value+"}"));
		assertNotNull(detect("https://abdl-space.top/test", 429, "{\"code\":\"D1_DAILY_QUOTA_EXHAUSTED\"}"));
	}
	@Test public void jsonRejectsCommentsSingleQuotesDuplicatesUnquotedKeysAndTrailingDocuments(){
		for(String text:new String[]{"/* comment */"+BODY, BODY+" {}", BODY+" trailing", "{code:\"D1_DAILY_QUOTA_EXHAUSTED\"}", "{'code':'D1_DAILY_QUOTA_EXHAUSTED'}", "{\"code\":\"ordinary\",\"code\":\"D1_DAILY_QUOTA_EXHAUSTED\"}", "{\"code\":\"D1_DAILY_QUOTA_EXHAUSTED\",\"x\":NaN}", "{\"code\":\"D1_DAILY_QUOTA_EXHAUSTED\",}", "{\"code\":\"D1_DAILY_QUOTA_EXHAUSTED\",\"x\":01}"}){
			assertNull(text, ServiceResourcePause.parseErrorObject(text));
			assertNull(text, detect("https://abdl-space.top/test", 503, text));
		}
	}
	@Test public void stringAndJsonObjectEntryPointsBoundActualUtf8AndDepth(){
		String multibyte="{\"code\":\"D1_DAILY_QUOTA_EXHAUSTED\",\"error\":\""+"汉".repeat(12000)+"\"}";
		assertTrue(multibyte.length()<ServiceResourcePause.MAX_ERROR_BYTES);
		assertNull(ServiceResourcePause.parseErrorObject(multibyte));
		JsonObject oversized=new JsonObject(); oversized.addProperty("code", ServiceResourcePause.CODE); oversized.addProperty("error", "汉".repeat(12000));
		assertNull(ServiceResourcePause.fromResponse(response("https://abdl-space.top/test", 503), oversized));
		String nested="{\"code\":\"D1_DAILY_QUOTA_EXHAUSTED\",\"x\":"+"[".repeat(40)+"0"+"]".repeat(40)+"}";
		assertNull(ServiceResourcePause.parseErrorObject(nested));
		assertNull(ServiceResourcePause.fromResponse((Response)null, BODY));
		assertNull(ServiceResourcePause.fromResponse(response("https://abdl-space.top/test", 503), (String)null));
	}
	@Test public void metadataMutationAfterDetectionCannotChangeDescriptor(){
		JsonObject body=ServiceResourcePause.parseErrorObject(BODY);
		ServiceResourcePause pause=ServiceResourcePause.fromResponse(response("https://abdl-space.top/test", 503), body,
				Clock.fixed(Instant.parse("2026-11-03T15:59:59Z"), ZoneOffset.UTC), ()->0);
		body.addProperty("reset_at", "2099-01-01T00:00:00Z"); body.addProperty("error", "secret raw text");
		android.content.Context english=org.robolectric.RuntimeEnvironment.getApplication();
		assertTrue(pause.message(english).contains("tomorrow"));
		assertFalse(pause.message(english).contains("secret"));
	}
	@Test public void invalidTimeTypesStillCarryPauseButNeverTrustedTime(){
		JsonObject body=ServiceResourcePause.parseErrorObject(BODY); body.add("server_time", new JsonPrimitive(1793750400000L));
		assertNotNull(ServiceResourcePause.fromResponse(response("https://abdl-space.top/test", 503), body));
	}
}
