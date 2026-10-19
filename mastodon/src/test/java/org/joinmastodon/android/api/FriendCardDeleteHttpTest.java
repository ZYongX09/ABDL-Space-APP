package org.joinmastodon.android.api;

import static org.junit.Assert.*;

import android.app.Application;

import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.requests.friendcards.FriendCardRequest;
import org.joinmastodon.android.api.requests.friendcards.FriendCardResponses;
import org.joinmastodon.android.model.Token;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** Real Controller/Gson/callback flow over loopback HTTP, not a real backend or TLS test. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public class FriendCardDeleteHttpTest{
	private static final String HOST="friend-cards-offline.example.test";
	private static final String TOKEN="synthetic-offline-friend-card-token";
	private static final String CARD_ID="card-7_a";
	private MockWebServer server;
	private OkHttpClient client;
	private Object originalInterceptors;
	private MastodonAPIController controller;

	private static Field field(Class<?> type, String name) throws Exception{
		Field field=type.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	@Before public void setup() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication();
		server=new MockWebServer();
		server.start(InetAddress.getByName("127.0.0.1"), 0);
		client=(OkHttpClient)field(MastodonAPIController.class, "sensitiveHttpClient").get(null);
		originalInterceptors=field(OkHttpClient.class, "interceptors").get(client);
		okhttp3.Interceptor loopback=chain->{
			okhttp3.Request request=chain.request();
			if(!HOST.equals(request.url().host())) throw new IOException("Unexpected outbound host");
			String query=request.url().encodedQuery();
			return chain.proceed(request.newBuilder()
					.url(server.url(request.url().encodedPath()+(query==null ? "" : "?"+query))).build());
		};
		field(OkHttpClient.class, "interceptors").set(client, List.of(loopback));
		controller=new MastodonAPIController(null);
	}

	@After public void cleanup() throws Exception{
		try{
			if(client!=null && originalInterceptors!=null)
				field(OkHttpClient.class, "interceptors").set(client, originalInterceptors);
		}finally{
			if(client!=null){
				client.dispatcher().cancelAll();
				client.connectionPool().evictAll();
			}
			if(server!=null) server.shutdown();
		}
	}

	private static final class Outcome implements Callback<FriendCardResponses.Deleted>{
		final AtomicInteger successCalls=new AtomicInteger();
		final AtomicInteger errorCalls=new AtomicInteger();
		volatile FriendCardResponses.Deleted value;
		volatile ErrorResponse error;

		@Override public void onSuccess(FriendCardResponses.Deleted result){
			value=result;
			successCalls.incrementAndGet();
		}

		@Override public void onError(ErrorResponse result){
			error=result;
			errorCalls.incrementAndGet();
		}
	}

	private Outcome submitDelete(int expectedVersion, MockResponse response) throws Exception{
		FriendCardRequest<FriendCardResponses.Deleted> request=FriendCardRequest.delete(CARD_ID, expectedVersion);
		field(MastodonAPIRequest.class, "domain").set(request, HOST);
		Token token=new Token();
		token.accessToken=TOKEN;
		field(MastodonAPIRequest.class, "token").set(request, token);
		assertTrue(request.isSensitiveRequest());
		assertFalse(request.requiresSystemTrust());
		Outcome outcome=new Outcome();
		request.setCallback(outcome);
		server.enqueue(response.setHeader("Content-Type", "application/json"));
		controller.submitRequest(request);

		RecordedRequest sent=server.takeRequest(5, TimeUnit.SECONDS);
		assertNotNull("Controller must send an actual HTTP request", sent);
		assertEquals("DELETE", sent.getMethod());
		assertEquals("/api/friend-cards/"+CARD_ID+"?expected_version="+expectedVersion, sent.getPath());
		assertEquals(List.of("Bearer "+TOKEN), sent.getHeaders().values("Authorization"));
		assertEquals("application/json", sent.getHeader("Content-Type"));
		assertEquals("{}", sent.getBody().readUtf8());

		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
		while(outcome.successCalls.get()+outcome.errorCalls.get()==0 && System.nanoTime()<deadline){
			ShadowLooper.idleMainLooper();
			Thread.sleep(10);
		}
		ShadowLooper.idleMainLooper();
		assertEquals("Exactly one response callback must complete", 1, outcome.successCalls.get()+outcome.errorCalls.get());
		assertEquals("Delete must not retry", 1, server.getRequestCount());
		return outcome;
	}

	@Test public void deleteSendsEmptyJsonAndVersionQueryAndParsesSuccess() throws Exception{
		Outcome result=submitDelete(7, new MockResponse().setResponseCode(200).setBody("{\"deleted\":true}"));
		assertEquals(1, result.successCalls.get());
		assertEquals(0, result.errorCalls.get());
		assertNull(result.error);
		assertNotNull(result.value);
		assertTrue(result.value.deleted);
	}

	@Test public void deleteConflictPreservesStructured409Error() throws Exception{
		Outcome result=submitDelete(6, new MockResponse().setResponseCode(409)
				.setBody("{\"error\":\"扩列卡片已更新，请刷新后重试\",\"code\":\"version_conflict\"}"));
		assertEquals(0, result.successCalls.get());
		assertEquals(1, result.errorCalls.get());
		assertNull(result.value);
		assertTrue(result.error instanceof FriendCardRequest.FriendCardError);
		FriendCardRequest.FriendCardError error=(FriendCardRequest.FriendCardError)result.error;
		assertEquals(409, error.httpStatus);
		assertEquals("version_conflict", error.code);
		assertEquals("扩列卡片已更新，请刷新后重试", error.error);
		assertNull(error.underlyingException);
	}
}
