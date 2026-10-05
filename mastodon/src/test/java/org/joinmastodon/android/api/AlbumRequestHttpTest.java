package org.joinmastodon.android.api;

import static org.junit.Assert.*;

import android.app.Application;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.requests.albums.AlbumRequest;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.model.albums.AlbumModels;
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
import java.util.Map;
import java.util.concurrent.TimeUnit;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public class AlbumRequestHttpTest{
	private static final String HOST="album-offline.example.test";
	private MockWebServer server;
	private OkHttpClient originalClient, client;
	private Object originalInterceptors;
	private MastodonAPIController controller;
	private static Field field(Class<?> type, String name) throws Exception{
		Field field=type.getDeclaredField(name); field.setAccessible(true); return field;
	}

	@Before public void setup() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication();
		server=new MockWebServer(); server.start(InetAddress.getByName("127.0.0.1"), 0);
		Field sensitive=field(MastodonAPIController.class, "sensitiveHttpClient");
		originalClient=(OkHttpClient)sensitive.get(null);
		client=originalClient;
		originalInterceptors=field(OkHttpClient.class, "interceptors").get(client);
		okhttp3.Interceptor loopback=chain->{
			okhttp3.Request request=chain.request();
			if(!HOST.equals(request.url().host())) throw new IOException("Unexpected outbound host");
			String query=request.url().encodedQuery();
			return chain.proceed(request.newBuilder().url(server.url(request.url().encodedPath()+(query==null ? "" : "?"+query))).build());
		};
		field(OkHttpClient.class, "interceptors").set(client, java.util.List.of(loopback));
		controller=new MastodonAPIController(null);
	}
	@After public void cleanup() throws Exception{
		if(client!=null && originalInterceptors!=null) field(OkHttpClient.class, "interceptors").set(client, originalInterceptors);
		if(client!=null){ client.dispatcher().cancelAll(); client.connectionPool().evictAll(); }
		if(server!=null) server.shutdown();
	}

	private static final class Outcome<T> implements Callback<T>{
		volatile T value; volatile ErrorResponse error; volatile boolean done;
		@Override public void onSuccess(T result){ value=result; done=true; }
		@Override public void onError(ErrorResponse result){ error=result; done=true; }
	}
	private <T> Outcome<T> submit(AlbumRequest<T> request, MockResponse response) throws Exception{
		field(MastodonAPIRequest.class, "domain").set(request, HOST);
		Token token=new Token(); token.accessToken="synthetic-offline-album-token";
		field(MastodonAPIRequest.class, "token").set(request, token);
		assertTrue(request.isSensitiveRequest()); assertFalse(request.requiresSystemTrust());
		Outcome<T> outcome=new Outcome<>(); request.setCallback(outcome);
		server.enqueue(response.setHeader("Content-Type", "application/json"));
		controller.submitRequest(request);
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
		while(!outcome.done && System.nanoTime()<deadline){ ShadowLooper.idleMainLooper(); Thread.sleep(10); }
		ShadowLooper.idleMainLooper(); assertTrue("Exactly one callback must finish", outcome.done);
		return outcome;
	}

	@Test public void albumListUsesSelectedOriginAndNoStore() throws Exception{
		Outcome<AlbumModels.ListResponse> result=submit(AlbumRequest.get("", AlbumModels.ListResponse.class).query("owner_id", 42),
				new MockResponse().setBody("{\"albums\":[{\"id\":\"a\",\"owner_id\":42,\"name\":\"宝宝相册\",\"visibility\":\"private\",\"is_default\":true,\"photo_count\":0}],\"has_more\":false}"));
		RecordedRequest sent=server.takeRequest(5, TimeUnit.SECONDS);
		assertNotNull(sent); assertEquals("/api/v1/albums?owner_id=42", sent.getPath());
		assertEquals("Bearer synthetic-offline-album-token", sent.getHeader("Authorization"));
		assertTrue(sent.getHeader("Cache-Control").contains("no-store"));
		assertNull(result.error); assertEquals(42, result.value.albums.get(0).ownerId);
		assertTrue(result.value.albums.get(0).isDefault);
	}

	@Test public void originalAuthorizationRetainsOperationAndQuotaError() throws Exception{
		String operation="70917d03-06fa-4134-a842-cc8d818d6d9a";
		Outcome<AlbumModels.Authorization> result=submit(AlbumRequest.post("/photos/p/authorize", AlbumModels.Authorization.class,
				Map.of("variant", "original", "operation_id", operation)), new MockResponse().setResponseCode(402)
				.setBody("{\"error\":\"今日原图额度已用完\",\"code\":\"quota_exhausted\",\"quota\":{\"limit\":3,\"used\":3,\"remaining\":0,\"day_key\":\"2026-10-05\",\"resets_at\":1791216000}}"));
		RecordedRequest sent=server.takeRequest(5, TimeUnit.SECONDS);
		assertEquals("POST", sent.getMethod()); assertEquals("/api/v1/albums/photos/p/authorize", sent.getPath());
		JsonObject body=JsonParser.parseString(sent.getBody().readUtf8()).getAsJsonObject();
		assertEquals(operation, body.get("operation_id").getAsString()); assertEquals("original", body.get("variant").getAsString());
		assertNull(result.value); assertTrue(result.error instanceof AlbumRequest.AlbumError);
		AlbumRequest.AlbumError error=(AlbumRequest.AlbumError)result.error;
		assertEquals("quota_exhausted", error.code); assertNotNull(error.quota); assertEquals(0, error.quota.remaining);
	}

	@Test public void privateApiRedirectDoesNotSendCredentialsElsewhere() throws Exception{
		Outcome<AlbumModels.ListResponse> result=submit(AlbumRequest.get("", AlbumModels.ListResponse.class),
				new MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example.test/api/v1/albums").setBody("{}"));
		assertNull(result.value); assertNotNull(result.error); assertEquals(1, server.getRequestCount());
	}

	@Test public void refusesAbsoluteOrTraversalPaths(){
		for(String path:new String[]{"https://evil.test", "/../x", "/photos/p?token=bad", "/x#fragment"}){
			try{ AlbumRequest.get(path, AlbumModels.ListResponse.class); fail(path); }
			catch(IllegalArgumentException expected){}
		}
	}
}
