package org.joinmastodon.android.api;

import static org.junit.Assert.*;

import org.joinmastodon.android.BuildConfig;
import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.requests.nbw.GetNBWEndpoints;
import org.joinmastodon.android.api.requests.nbw.NBWLoginRequest;
import org.joinmastodon.android.model.NBWEndpoints;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowLooper;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

/** Real URL/request/controller/streaming adapters, with only TLS transport routed to loopback. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=CompatibilityTestApplication.class,shadows=NBWEndpointTransportTest.Loopback.class)
public class NBWEndpointTransportTest{
	private static MockWebServer server;
	@Implements(MastodonAPIController.class) public static class Loopback{
		@Implementation protected static OkHttpClient createSystemTrustClient(OkHttpClient ignored){
			return new OkHttpClient.Builder().cache(null).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
					.addInterceptor(chain->{
						var request=chain.request();
						if(!request.url().isHttps() || !NBWLoginRequest.DOMAIN.equals(request.url().host()) || !"/api/nbw/endpoints".equals(request.url().encodedPath())) throw new IOException("Unexpected discovery URL");
						return chain.proceed(request.newBuilder().url(server.url(request.url().encodedPath())).build());
					}).build();
		}
	}
	@Before public void setup() throws Exception{ MastodonApp.context=RuntimeEnvironment.getApplication(); server=new MockWebServer(); server.start(); }
	@After public void cleanup() throws Exception{ server.shutdown(); }
	private NBWEndpoints send(MockResponse response,boolean success) throws Exception{
		AtomicBoolean done=new AtomicBoolean(); AtomicReference<NBWEndpoints> value=new AtomicReference<>(); AtomicReference<ErrorResponse> error=new AtomicReference<>();
		server.enqueue(response);
		GetNBWEndpoints request=new GetNBWEndpoints(); assertEquals("https://api.abdl-space.top/api/nbw/endpoints",request.getURL().toString());
		request.setCallback(new Callback<>(){
			@Override public void onSuccess(NBWEndpoints result){ value.set(result); done.set(true); }
			@Override public void onError(ErrorResponse result){ error.set(result); done.set(true); }
		}).execNoAuth(NBWLoginRequest.DOMAIN);
		var sent=server.takeRequest(10,TimeUnit.SECONDS); assertNotNull(sent); assertEquals("GET",sent.getMethod()); assertEquals("/api/nbw/endpoints",sent.getPath());
		assertNull(sent.getHeader("Authorization")); assertNull(sent.getHeader("Cookie")); assertEquals(0,sent.getBodySize());
		assertEquals("2",sent.getHeader("X-ABDL-NBW-Protocol")); assertEquals("MastodonAndroid/"+BuildConfig.VERSION_NAME,sent.getHeader("User-Agent"));
		assertEquals(String.valueOf(BuildConfig.VERSION_CODE),sent.getHeader("X-App-Version-Code")); assertTrue(sent.getHeader("Cache-Control").contains("no-store"));
		long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
		while(!done.get() && System.nanoTime()<end){ ShadowLooper.idleMainLooper(); Thread.sleep(10); }
		assertTrue("Discovery must terminate",done.get()); assertEquals(success,value.get()!=null); assertEquals(!success,error.get()!=null); return value.get();
	}
	private static String json(){ return MastodonAPIController.gson.toJson(NBWEndpointFixtures.primary()); }
	private static MockResponse json(String body){ return new MockResponse().setHeader("Content-Type","application/json").setBody(body); }
	@Test public void bothOriginsUseOnlyPublicBackendGetAndNativeVersionIdentity() throws Exception{
		assertEquals("primary",send(json(json()),true).mode);
		assertEquals("backup",send(json(MastodonAPIController.gson.toJson(NBWEndpointFixtures.endpoints("backup","r2","read_only"))),true).mode);
	}
	@Test public void schemaCoercionDuplicatesMixedOriginsUnknownAndMissingFieldsFailClosed() throws Exception{
		String valid=json();
		for(String body:new String[]{"{}",valid.replace("\"schema_version\":1","\"schema_version\":\"1\""),
				valid.replace("\"schema_version\":1","\"schema_version\":1,\"schema_version\":1"),
				valid.replace("\"mode\":\"primary\"","\"mode\":\"backup\""),
				valid.replace("\"ttl_seconds\":30","\"ttl_seconds\":31"),valid.replace("\"ttl_seconds\":30","\"ttl_seconds\":30,\"token\":\"forbidden\"")}) send(json(body),false);
	}
	@Test public void actualChunkedLimitContentTypeRedirectAndLegacyServerAreTerminal() throws Exception{
		send(new MockResponse().setHeader("Content-Type","application/json").setChunkedBody(" ".repeat(4097)+json(),127),false);
		send(new MockResponse().setHeader("Content-Type","text/html").setBody(json()),false);
		send(new MockResponse().setResponseCode(302).setHeader("Location","https://evil.example/steal").setBody("{}"),false);
		assertNull(server.takeRequest(100,TimeUnit.MILLISECONDS));
		send(json("{\"error\":\"legacy server\"}").setResponseCode(404),false);
		assertNull(server.takeRequest(100,TimeUnit.MILLISECONDS));
	}
}
