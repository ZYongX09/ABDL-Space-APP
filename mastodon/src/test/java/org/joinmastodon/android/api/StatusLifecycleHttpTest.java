package org.joinmastodon.android.api;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.requests.statuses.DeleteStatus;
import org.joinmastodon.android.api.requests.statuses.EditStatus;
import org.joinmastodon.android.api.requests.statuses.GetStatusContext;
import org.joinmastodon.android.api.requests.statuses.GetStatusSourceText;
import org.joinmastodon.android.api.requests.statuses.NBWStatusInteraction;
import org.joinmastodon.android.api.requests.statuses.SetStatusFavorited;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.StatusContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.Proxy;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** Real request/controller/Gson/validation over loopback; no account or live backend access. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class)
public class StatusLifecycleHttpTest{
	private static final String HOST="lifecycle.offline.example.test";
	private MockWebServer server;
	private OkHttpClient original, loopback;
	private MastodonAPIController controller;

	@Before public void setup() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication();
		me.grishka.appkit.utils.V.setApplicationContext(MastodonApp.context);
		server=new MockWebServer(); server.start(InetAddress.getByName("127.0.0.1"), 0);
		Field client=field(MastodonAPIController.class, "httpClient");
		original=(OkHttpClient)client.get(null);
		loopback=new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
				.followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
				.connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
				.addInterceptor(chain->{
					if(!HOST.equals(chain.request().url().host())) throw new IOException("Unexpected outbound host");
					return chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url().encodedPath())).build());
				}).build();
		client.set(null, loopback); controller=new MastodonAPIController(null);
	}

	@After public void cleanup() throws Exception{
		if(original!=null) field(MastodonAPIController.class, "httpClient").set(null, original);
		if(loopback!=null){ loopback.dispatcher().cancelAll(); loopback.connectionPool().evictAll(); loopback.dispatcher().executorService().shutdown(); }
		if(server!=null) server.shutdown();
	}

	private static class Outcome<T> implements Callback<T>{
		final AtomicInteger calls=new AtomicInteger();
		volatile T value;
		volatile ErrorResponse error;
		@Override public void onSuccess(T result){ value=result; calls.incrementAndGet(); }
		@Override public void onError(ErrorResponse failure){ error=failure; calls.incrementAndGet(); }
	}

	private <T> Outcome<T> submit(MastodonAPIRequest<T> request, MockResponse response, String method, String path) throws Exception{
		field(MastodonAPIRequest.class, "domain").set(request, HOST);
		Outcome<T> outcome=new Outcome<>(); request.setCallback(outcome);
		server.enqueue(response.setHeader("Content-Type", "application/json"));
		controller.submitRequest(request);
		RecordedRequest sent=server.takeRequest(10, TimeUnit.SECONDS);
		assertNotNull(sent); assertEquals(method, sent.getMethod()); assertEquals("/api/v1"+path, sent.getPath());
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
		while(outcome.calls.get()==0 && System.nanoTime()<deadline){ ShadowLooper.idleMainLooper(); Thread.sleep(10); }
		ShadowLooper.idleMainLooper(); assertEquals(1, outcome.calls.get());
		return outcome;
	}

	private static <T> T success(Outcome<T> outcome){ assertNull(outcome.error); return outcome.value; }
	private static MastodonErrorResponse failure(Outcome<?> outcome, int status){
		assertNull(outcome.value); assertTrue(outcome.error instanceof MastodonErrorResponse);
		MastodonErrorResponse error=(MastodonErrorResponse)outcome.error; assertEquals(status, error.httpStatus); return error;
	}
	private static MockResponse json(int code, String json){ return new MockResponse().setResponseCode(code).setBody(json); }

	@Test public void deletionAcceptsPartialEmptyAndNoContentSuccessBodies() throws Exception{
		for(MockResponse response:new MockResponse[]{json(200, "{\"id\":\"p_1\",\"text\":\"deleted\"}"), json(200, ""), new MockResponse().setResponseCode(204)}){
			Outcome<Void> outcome=submit(new DeleteStatus("p_1"), response, "DELETE", "/statuses/p_1");
			assertNull(success(outcome));
		}
	}

	@Test public void deletionFailureNeverBecomesSuccess() throws Exception{
		assertEquals("权限不足", failure(submit(new DeleteStatus("p_1"), json(403, "{\"error\":\"权限不足\"}"), "DELETE", "/statuses/p_1"), 403).error);
	}

	@Test public void sourceAcceptsSnakeAndLegacyCamelWithOptionalMetadata() throws Exception{
		for(String key:new String[]{"spoiler_text", "spoilerText"}){
			String source="{\"id\":\"c_1\",\"text\":\"original & plain\",\""+key+"\":\"\",\"in_reply_to_id\":\"nbw_123\",\"nbw_fid\":27,\"nbw_tid\":123,\"nbw_sync_enabled\":true,\"nbw_sync\":{\"state\":\"synced\"},\"geo_province\":\"Original province\"}";
			GetStatusSourceText.Response result=success(submit(new GetStatusSourceText("c_1"), json(200, source), "GET", "/statuses/c_1/source"));
			assertEquals("original & plain", result.text); assertEquals("", result.spoilerText);
			assertEquals("nbw_123", result.inReplyToId); assertEquals(Integer.valueOf(27), result.nbwFid);
			assertEquals(Long.valueOf(123), result.nbwTid); assertEquals(Boolean.TRUE, result.nbwSyncEnabled);
			assertEquals("synced", result.nbwSync.state); assertEquals("Original province", result.geoProvince);
		}
		GetStatusSourceText.Response legacy=success(submit(new GetStatusSourceText("p_1"), json(200, "{\"id\":\"p_1\",\"text\":\"plain\",\"spoiler_text\":\"\"}"), "GET", "/statuses/p_1/source"));
		assertNull(legacy.nbwFid); assertNull(legacy.nbwSyncEnabled);
	}

	@Test public void sourceStillRejectsMissingOrNullRequiredSpoiler() throws Exception{
		for(String suffix:new String[]{"", ",\"spoiler_text\":null"}){
			MastodonErrorResponse error=failure(submit(new GetStatusSourceText("p_1"), json(200, "{\"id\":\"p_1\",\"text\":\"original\""+suffix+"}"), "GET", "/statuses/p_1/source"), 200);
			assertTrue(error.underlyingException instanceof ObjectValidationException); assertTrue(error.error.contains("spoilerText"));
		}
	}

	@Test public void editStillValidatesFullStatusAndParsesUnsupportedSyncMetadata() throws Exception{
		JsonObject body=new JsonObject(); body.addProperty("status", "changed");
		JsonObject status=JsonParser.parseString(status("p_1", null)).getAsJsonObject();
		status.add("nbw_sync", JsonParser.parseString("{\"state\":\"unsupported\",\"reason\":\"edit_api_unavailable\"}"));
		Status result=success(submit(new EditStatus(body, "p_1"), json(200, status.toString()), "PUT", "/statuses/p_1"));
		assertEquals("unsupported", result.nbwSync.state); assertEquals("edit_api_unavailable", result.nbwSync.reason);
		status.remove("created_at");
		assertTrue(failure(submit(new EditStatus(body, "p_1"), json(200, status.toString()), "PUT", "/statuses/p_1"), 200).underlyingException instanceof ObjectValidationException);
	}

	@Test public void nbwActionsUseLightweightStateAndNeverFabricateContent() throws Exception{
		Status original=Status.ofFake("nbw_123", "original content", Instant.EPOCH); original.favouritesCount=8;
		NBWStatusInteraction.Result like=success(submit(new NBWStatusInteraction("nbw_123", true, true), json(200, "{\"id\":\"nbw_123\",\"favourited\":true,\"favourites_count\":42}"), "POST", "/statuses/nbw_123/favourite"));
		Status merged=like.merge(original); assertEquals(42, merged.favouritesCount); assertTrue(merged.favourited);
		assertEquals("original content", merged.content); assertEquals(8, original.favouritesCount); assertFalse(original.favourited);
		NBWStatusInteraction.Result bookmark=success(submit(new NBWStatusInteraction("nbw_123", false, false), json(200, "{\"id\":\"nbw_123\",\"bookmarked\":false}"), "POST", "/statuses/nbw_123/unbookmark"));
		assertFalse(bookmark.merge(original).bookmarked); assertEquals(8, bookmark.merge(original).favouritesCount);
	}

	@Test public void nbwRejectsMismatchedIdsMissingStateAndUnsupportedFloorIds() throws Exception{
		for(String response:new String[]{"{\"id\":\"nbw_124\",\"favourited\":true}", "{\"id\":\"nbw_123\"}"}){
			assertTrue(failure(submit(new NBWStatusInteraction("nbw_123", true, true), json(200, response), "POST", "/statuses/nbw_123/favourite"), 200).underlyingException instanceof ObjectValidationException);
		}
		for(String invalid:new String[]{"nbw_0", "nbw_001", "nbw_123_456", "nbw_9223372036854775808", "p_123"}) assertFalse(NBWStatusInteraction.supportsID(invalid));
	}

	@Test public void bindingMachineCodeIsPreservedButOrdinary403IsNotReclassified() throws Exception{
		Outcome<NBWStatusInteraction.Result> required=submit(new NBWStatusInteraction("nbw_123", true, true), json(403, "{\"code\":\"NBW_BINDING_REQUIRED\",\"error\":\"请先绑定宝宝新天地账号后再操作\"}"), "POST", "/statuses/nbw_123/favourite");
		assertTrue(failure(required, 403) instanceof MastodonErrorResponse.NBWBindingRequired);
		assertEquals("请先绑定宝宝新天地账号后再操作", failure(required, 403).error);
		assertFalse(failure(submit(new SetStatusFavorited("p_1", true), json(403, "{\"error\":\"用户组权限不足\"}"), "POST", "/statuses/p_1/favourite"), 403) instanceof MastodonErrorResponse.NBWBindingRequired);
	}

	@Test public void contextParsesExternalAndLocalRepliesWithoutConvertingTypedIds() throws Exception{
		String context="{\"ancestors\":["+status("p_1", null)+"],\"descendants\":["+status("nbw_123_456", "nbw_123")+","+status("c_9", "nbw_123_456")+"]}";
		StatusContext result=success(submit(new GetStatusContext("nbw_123"), json(200, context), "GET", "/statuses/nbw_123/context"));
		assertEquals("nbw_123_456", result.descendants.get(0).id); assertEquals("nbw_123", result.descendants.get(0).inReplyToId);
		assertEquals("nbw_123_456", result.descendants.get(1).inReplyToId); assertEquals("c_9", result.descendants.get(1).id);
	}

	@Test public void contextDegradationIsOptionalAndOrsJsonWithHeader() throws Exception{
		String[] flags={"", ",\"nbw_unavailable\":false", ",\"nbw_unavailable\":true", "", ",\"nbw_unavailable\":false", ",\"nbw_unavailable\":true"};
		String[] headers={null, null, null, "nbw", "NBW", "other"};
		boolean[] degraded={false, false, true, true, true, true};
		for(int i=0;i<flags.length;i++){
			String root=i%2==0 ? "p_1" : "nbw_123";
			String body="{\"ancestors\":[],\"descendants\":["+status("c_local", root)+"]"+flags[i]+"}";
			MockResponse response=json(200, body);
			if(headers[i]!=null) response.setHeader("X-ABDL-Context-Degraded", headers[i]);
			StatusContext result=success(submit(new GetStatusContext(root), response, "GET", "/statuses/"+root+"/context"));
			assertEquals(degraded[i], result.nbwUnavailable);
			assertTrue(result.ancestors.isEmpty()); assertEquals(1, result.descendants.size());
			assertEquals("c_local", result.descendants.get(0).id); assertEquals(root, result.descendants.get(0).inReplyToId);
		}
		StatusContext empty=success(submit(new GetStatusContext("nbw_123"),
				json(200, "{\"ancestors\":[],\"descendants\":[],\"nbw_unavailable\":true}"), "GET", "/statuses/nbw_123/context"));
		assertTrue(empty.nbwUnavailable); assertTrue(empty.descendants.isEmpty());
		for(String root:new String[]{"123", "c_1"}){
			StatusContext nativeContext=success(submit(new GetStatusContext(root),
					json(200, "{\"ancestors\":[],\"descendants\":["+status("c_local", root)+"],\"nbw_unavailable\":true}")
							.setHeader("X-ABDL-Context-Degraded", "nbw"), "GET", "/statuses/"+root+"/context"));
			assertTrue(nativeContext.nbwUnavailable); assertEquals("c_local", nativeContext.descendants.get(0).id);
			assertEquals(root, nativeContext.descendants.get(0).inReplyToId);
		}
	}

	@Test public void degradedContextStillRequiresBothArraysAndFullStatuses() throws Exception{
		for(String required:new String[]{"ancestors", "descendants"}){
			for(boolean explicitNull:new boolean[]{false, true}){
				JsonObject body=JsonParser.parseString("{\"ancestors\":[],\"descendants\":[],\"nbw_unavailable\":true}").getAsJsonObject();
				if(explicitNull) body.add(required, com.google.gson.JsonNull.INSTANCE); else body.remove(required);
				MastodonErrorResponse error=failure(submit(new GetStatusContext("p_1"),
						json(200, body.toString()).setHeader("X-ABDL-Context-Degraded", "nbw"), "GET", "/statuses/p_1/context"), 200);
				assertTrue(error.underlyingException instanceof ObjectValidationException); assertTrue(error.error.contains(required));
			}
			JsonObject body=JsonParser.parseString("{\"ancestors\":[],\"descendants\":[],\"nbw_unavailable\":true}").getAsJsonObject();
			JsonObject invalid=JsonParser.parseString(status("c_bad", "p_1")).getAsJsonObject(); invalid.remove("created_at");
			body.getAsJsonArray(required).add(invalid);
			assertTrue(failure(submit(new GetStatusContext("p_1"), json(200, body.toString()),
					"GET", "/statuses/p_1/context"), 200).underlyingException instanceof ObjectValidationException);
		}
	}

	@Test public void contextHttpFailuresNeverBecomeDegradedSuccess() throws Exception{
		for(String root:new String[]{"p_1", "123", "c_1", "nbw_123"}){
			for(int code:new int[]{500, 503, 401, 403, 400}){
				String message=code==400 ? "invalid_cursor" : code==503 ? "nbw_unavailable" : code==500 ? "local_failure" : "forbidden";
				MastodonErrorResponse error=failure(submit(new GetStatusContext(root),
						json(code, "{\"error\":\""+message+"\",\"ancestors\":[],\"descendants\":[],\"nbw_unavailable\":true}")
								.setHeader("X-ABDL-Context-Degraded", "nbw"), "GET", "/statuses/"+root+"/context"), code);
				assertEquals(message, error.error);
			}
		}
	}

	private static String status(String id, String parent){
		return "{\"id\":\""+id+"\",\"uri\":\"https://"+HOST+"/statuses/"+id+"\",\"created_at\":\"2026-10-22T00:00:00Z\",\"visibility\":\"public\",\"content\":\"<p>Body</p>\",\"spoiler_text\":\"\",\"account\":{\"id\":\"42\",\"username\":\"offline\",\"acct\":\"offline\",\"url\":\"https://"+HOST+"/profile/42\"},\"mentions\":[],\"tags\":[],\"emojis\":[],\"media_attachments\":[],\"in_reply_to_id\":"+(parent==null ? "null" : "\""+parent+"\"")+"}";
	}
	private static Field field(Class<?> type, String name) throws Exception{ Field f=type.getDeclaredField(name); f.setAccessible(true); return f; }
}
