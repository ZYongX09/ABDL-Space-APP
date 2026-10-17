package org.joinmastodon.android.api;

import android.app.Application;

import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.requests.sponsors.SponsorRequest;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.model.sponsors.SponsorModels.ThanksPage;
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

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.*;

/** Real production transport, Gson snake_case and validation, with loopback-only HTTP. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public class SponsorThanksHttpTest{
	private static final String HOST="sponsor-thanks-offline.example.test";
	private MockWebServer server;
	private OkHttpClient client;
	private Object interceptors;
	private MastodonAPIController controller;
	private static Field field(Class<?> type, String name) throws Exception{
		Field field=type.getDeclaredField(name); field.setAccessible(true); return field;
	}
	@Before public void setup() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication();
		server=new MockWebServer(); server.start(InetAddress.getByName("127.0.0.1"), 0);
		client=(OkHttpClient)field(MastodonAPIController.class, "sensitiveHttpClient").get(null);
		interceptors=field(OkHttpClient.class, "interceptors").get(client);
		okhttp3.Interceptor loopback=chain->{
			okhttp3.Request request=chain.request();
			if(!HOST.equals(request.url().host())) throw new IOException("Unexpected outbound host");
			String query=request.url().encodedQuery();
			return chain.proceed(request.newBuilder().url(server.url(request.url().encodedPath()+(query==null ? "" : "?"+query))).build());
		};
		field(OkHttpClient.class, "interceptors").set(client, List.of(loopback));
		controller=new MastodonAPIController(null);
	}
	@After public void cleanup() throws Exception{
		if(client!=null && interceptors!=null) field(OkHttpClient.class, "interceptors").set(client, interceptors);
		if(client!=null){ client.dispatcher().cancelAll(); client.connectionPool().evictAll(); }
		if(server!=null) server.shutdown();
	}
	private static final class Outcome implements Callback<ThanksPage>{
		ThanksPage value; ErrorResponse error; boolean done;
		@Override public void onSuccess(ThanksPage value){ this.value=value; done=true; }
		@Override public void onError(ErrorResponse error){ this.error=error; done=true; }
	}
	private Outcome submit(int offset, String body, int status) throws Exception{
		SponsorRequest<ThanksPage> request=SponsorRequest.thanks(offset);
		field(MastodonAPIRequest.class, "domain").set(request, HOST);
		Token token=new Token(); token.accessToken="synthetic-thanks-test-token";
		field(MastodonAPIRequest.class, "token").set(request, token);
		assertTrue(request.isSensitiveRequest());
		Outcome result=new Outcome(); request.setCallback(result);
		server.enqueue(new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body));
		controller.submitRequest(request);
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
		while(!result.done && System.nanoTime()<deadline){ ShadowLooper.idleMainLooper(); Thread.sleep(10); }
		ShadowLooper.idleMainLooper(); assertTrue(result.done); return result;
	}
	private static String page(){
		return "{\"items\":[{\"account_id\":\"456\",\"display_name\":\"真实赞助者\",\"username\":\"paid-user\",\"avatar\":\"https://cdn.example/live.png\",\"avatar_static\":\"https://cdn.example/static.png\",\"rank\":101,\"total_amount_cents\":5000000000}],\"total\":101,\"limit\":100,\"offset\":100}";
	}
	@Test public void selectedOriginAuthBoundedPaginationNoStoreAndExactBackendFields() throws Exception{
		Outcome result=submit(100, page(), 200);
		RecordedRequest sent=server.takeRequest(5, TimeUnit.SECONDS);
		assertNotNull(sent); assertEquals("GET", sent.getMethod());
		assertEquals("/api/v1/sponsors/thanks?limit=100&offset=100", sent.getPath());
		assertEquals("Bearer synthetic-thanks-test-token", sent.getHeader("Authorization"));
		assertEquals("no-store", sent.getHeader("Cache-Control"));
		assertNull(result.error); assertEquals(101, result.value.total); assertEquals(100, result.value.offset);
		var item=result.value.items.get(0);
		assertEquals("456", item.accountId); assertEquals("真实赞助者", item.name());
		assertEquals("https://cdn.example/static.png", item.avatarStatic); assertEquals(101, item.rank);
		assertEquals(5000000000L, item.totalAmountCents);
	}
	@Test public void emptyPagesAreValidIncludingOffsetsBeyondShrunkenTotal() throws Exception{
		Outcome result=submit(100, "{\"items\":[],\"total\":0,\"limit\":100,\"offset\":100}", 200);
		assertNull(result.error); assertTrue(result.value.items.isEmpty());
	}
	@Test public void invalidRankCannotReachSuccessCallback() throws Exception{
		Outcome result=submit(100, page().replace("\"rank\":101", "\"rank\":1"), 200);
		assertNull(result.value); assertNotNull(result.error);
	}
	@Test public void authErrorsRetainServerCodeAndSafeMessage() throws Exception{
		Outcome result=submit(0, "{\"error\":\"请重新登录\",\"code\":\"unauthorized\"}", 401);
		assertNull(result.value); assertTrue(result.error instanceof SponsorRequest.SponsorError);
		assertEquals("unauthorized", ((SponsorRequest.SponsorError)result.error).code);
		assertEquals("请重新登录", ((SponsorRequest.SponsorError)result.error).error);
	}
	@Test public void offsetBoundsRejectedBeforeNetwork(){
		for(int offset:new int[]{-1, 1000001}){
			try{ SponsorRequest.thanks(offset); fail("Accepted "+offset); }catch(IllegalArgumentException expected){}
		}
		assertEquals(0, server.getRequestCount());
	}
}
