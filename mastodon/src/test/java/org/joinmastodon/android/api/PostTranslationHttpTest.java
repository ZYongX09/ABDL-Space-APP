package org.joinmastodon.android.api;

import android.app.Application;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.statuses.GetStatusTranslation;
import org.joinmastodon.android.api.requests.statuses.TranslateStatus;
import org.joinmastodon.android.api.requests.translation.GetTranslationPreferences;
import org.joinmastodon.android.api.requests.translation.TranslationRequest;
import org.joinmastodon.android.api.requests.translation.UpdateTranslationPreferences;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.model.Translation;
import org.joinmastodon.android.model.TranslationPreferences;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.*;

/** Actual production HTTPS request construction, Gson validation and callbacks; outbound is loopback only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public class PostTranslationHttpTest{
	private static final String HOST="post-translation-offline.example.test";
	private MockWebServer server;
	private OkHttpClient client;
	private Object interceptors;
	private MastodonAPIController controller;
	private static Field field(Class<?> type, String name) throws Exception{ Field value=type.getDeclaredField(name); value.setAccessible(true); return value; }
	@Before public void setup() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication(); server=new MockWebServer(); server.start(InetAddress.getByName("127.0.0.1"), 0);
		client=(OkHttpClient)field(MastodonAPIController.class, "sensitiveHttpClient").get(null);
		interceptors=field(OkHttpClient.class, "interceptors").get(client);
		okhttp3.Interceptor local=chain->{
			okhttp3.Request request=chain.request();
			if(!HOST.equals(request.url().host())) throw new IOException("Unexpected external request");
			String query=request.url().encodedQuery();
			return chain.proceed(request.newBuilder().url(server.url(request.url().encodedPath()+(query==null ? "" : "?"+query))).build());
		};
		field(OkHttpClient.class, "interceptors").set(client, List.of(local)); controller=new MastodonAPIController(null);
	}
	@After public void cleanup() throws Exception{
		if(client!=null && interceptors!=null) field(OkHttpClient.class, "interceptors").set(client, interceptors);
		if(client!=null){ client.dispatcher().cancelAll(); client.connectionPool().evictAll(); }
		if(server!=null) server.shutdown();
	}
	private static final class Outcome<T> implements Callback<T>{
		T result; ErrorResponse error; boolean done;
		@Override public void onSuccess(T result){ this.result=result; done=true; }
		@Override public void onError(ErrorResponse error){ this.error=error; done=true; }
	}
	private <T> Outcome<T> submit(MastodonAPIRequest<T> request, int status, String body) throws Exception{
		field(MastodonAPIRequest.class, "domain").set(request, HOST); Token token=new Token(); token.accessToken="synthetic-translation-test-token";
		field(MastodonAPIRequest.class, "token").set(request, token);
		Outcome<T> result=new Outcome<>(); request.setCallback(result);
		server.enqueue(new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)); controller.submitRequest(request);
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
		while(!result.done && System.nanoTime()<deadline){ ShadowLooper.idleMainLooper(); Thread.sleep(10); }
		ShadowLooper.idleMainLooper(); assertTrue("HTTP callback timed out", result.done); return result;
	}
	private RecordedRequest sent() throws Exception{ RecordedRequest result=server.takeRequest(5, TimeUnit.SECONDS); assertNotNull(result); return result; }
	private String ready(){ return "{\"content\":\"<p>Translated &amp; safe</p>\",\"detected_source_language\":\"zh\",\"provider\":\"ABDL Space AI\",\"spoiler_text\":\"Warning\",\"cached\":true,\"source_hash\":\"server-source-1\",\"target_language\":\"en\",\"quota\":{\"limit\":10,\"used\":1,\"reserved\":0,\"day\":\"2026-11-02\",\"reset_at\":1793635200}}"; }
	private String pending(String state){ return "{\"translation_status\":\""+state+"\",\"retry_after\":3,\"source_hash\":\"server-source-1\",\"target_language\":\"en\",\"quota\":{\"limit\":10,\"used\":0,\"reserved\":1}}"; }
	private String prefs(){ return "{\"reading_language\":\"en\",\"auto_translate_enabled\":false,\"revision\":\"rev-1\",\"enabled\":true,\"provider_configured\":true,\"auto_translate_allowed\":true,\"quota\":{\"limit\":10,\"used\":2,\"reserved\":1}}"; }

	@Test public void postUsesExactIdLanguageAutomaticAndNoClientContentOrIdempotencyRequirement() throws Exception{
		TranslateStatus request=new TranslateStatus("nbw_200", "en", true); Outcome<Translation> result=submit(request, 200, ready()); RecordedRequest sent=sent();
		assertEquals("POST", sent.getMethod()); assertEquals("/api/v1/statuses/nbw_200/translate", sent.getPath());
		assertEquals("Bearer synthetic-translation-test-token", sent.getHeader("Authorization")); assertEquals("no-store", sent.getHeader("Cache-Control")); assertNull(sent.getHeader("Idempotency-Key"));
		JsonObject body=JsonParser.parseString(sent.getBody().readUtf8()).getAsJsonObject(); assertEquals(Set.of("lang", "automatic"), body.keySet()); assertTrue(body.get("automatic").getAsBoolean()); assertEquals("en", body.get("lang").getAsString());
		assertNull(result.error); assertTrue(result.result.isReady()); assertTrue(result.result.cached); assertEquals("server-source-1", result.result.sourceHash); assertEquals(1, result.result.quota.used); assertEquals(1793635200L, result.result.quota.resetAt);
	}
	@Test public void pendingAndUnknown202AreNotReadyOrRequiredContentFailures() throws Exception{
		for(String state:List.of("pending", "unknown")){
			Outcome<Translation> result=submit(new TranslateStatus("100", "en"), 202, pending(state)); sent();
			assertNull(result.error); assertNull(result.result.content); assertFalse(result.result.isReady()); assertEquals(state, result.result.translationStatus); assertEquals(Integer.valueOf(3), result.result.retryAfter);
		}
	}
	@Test public void pollIsReadOnlyGetAndRetainsTargetSourceHashAnd404Code() throws Exception{
		Outcome<Translation> result=submit(new GetStatusTranslation("real-quote-10", "en"), 202, pending("pending")); RecordedRequest sent=sent();
		assertEquals("GET", sent.getMethod()); assertEquals("/api/v1/statuses/real-quote-10/translation?lang=en", sent.getPath()); assertEquals(0, sent.getBodySize()); assertTrue(result.result.isPending());
		Outcome<Translation> missing=submit(new GetStatusTranslation("real-quote-10", "en"), 404, "{\"error\":\"Not found\",\"code\":\"translation_not_found\"}"); sent();
		assertNull(missing.result); assertTrue(missing.error instanceof TranslationRequest.TranslationError); assertEquals("translation_not_found", ((TranslationRequest.TranslationError)missing.error).code);
	}
	@Test public void readyAndHttpEnvelopeValidationRejectIncompleteOrContradictoryBodies() throws Exception{
		for(int status:List.of(200, 202)){
			Outcome<Translation> broken=submit(new TranslateStatus("100", "en"), status, "{}"); sent(); assertNull(broken.result); assertNotNull(broken.error);
		}
		Outcome<Translation> ready202=submit(new TranslateStatus("100", "en"), 202, ready()); sent(); assertNull(ready202.result); assertNotNull(ready202.error);
		Outcome<Translation> pending200=submit(new TranslateStatus("100", "en"), 200, pending("pending")); sent(); assertNull(pending200.result); assertNotNull(pending200.error);
	}
	@Test public void preferencesGetAndCasPutKeepExactKeysAndServerCapability() throws Exception{
		Outcome<TranslationPreferences> get=submit(new GetTranslationPreferences(), 200, prefs()); RecordedRequest first=sent(); assertEquals("GET", first.getMethod()); assertEquals("/api/v1/translation/preferences", first.getPath());
		assertNotNull(get.result); assertEquals("rev-1", get.result.revision); assertTrue(get.result.autoTranslateAllowed); assertFalse(get.result.autoTranslateEnabled);
		Outcome<TranslationPreferences> update=submit(new UpdateTranslationPreferences("en", false, "rev-1"), 200, prefs()); RecordedRequest put=sent();
		assertEquals("PUT", put.getMethod()); assertEquals("/api/v1/translation/preferences", put.getPath()); assertEquals("no-store", put.getHeader("Cache-Control"));
		JsonObject body=JsonParser.parseString(put.getBody().readUtf8()).getAsJsonObject(); assertEquals(Set.of("reading_language", "auto_translate_enabled", "revision"), body.keySet()); assertEquals("rev-1", body.get("revision").getAsString()); assertFalse(body.get("auto_translate_enabled").getAsBoolean()); assertNull(update.error);
	}
	@Test public void quotaUnavailableAndCasErrorsExposeOnlyLocalizableMachineCode() throws Exception{
		String[] codes={"translation_quota_exhausted", "translation_unavailable", "revision_conflict", "translation_rate_limited"}; int[] statuses={429, 503, 409, 429}; int[] resources={R.string.post_translation_quota_exhausted, R.string.post_translation_unavailable, R.string.post_translation_preferences_conflict, R.string.post_translation_rate_limited};
		for(int i=0;i<codes.length;i++){
			Outcome<Translation> result=submit(new TranslateStatus("100", "en"), statuses[i], "{\"error\":\"RAW PRIVATE PROVIDER DETAIL\",\"code\":\""+codes[i]+"\"}"); sent();
			assertNull(result.result); assertTrue(result.error instanceof TranslationRequest.TranslationError); var error=(TranslationRequest.TranslationError)result.error;
			assertEquals(codes[i], error.code); assertEquals(resources[i], error.localizedMessage()); assertFalse(error.error.contains("PRIVATE"));
		}
	}
	@Test public void legacyMastodonReadyStillWorksWithOptionalPollAndAltAbsent() throws Exception{
		Outcome<Translation> result=submit(new TranslateStatus("100", "en"), 200, "{\"content\":\"<p>Hello</p>\",\"provider\":\"Legacy\",\"detected_source_language\":\"zh\"}"); RecordedRequest sent=sent();
		assertNull(result.error); assertTrue(result.result.isReady()); assertNull(result.result.poll); assertNull(result.result.mediaAttachments);
		assertFalse(JsonParser.parseString(sent.getBody().readUtf8()).getAsJsonObject().get("automatic").getAsBoolean());
	}
}
