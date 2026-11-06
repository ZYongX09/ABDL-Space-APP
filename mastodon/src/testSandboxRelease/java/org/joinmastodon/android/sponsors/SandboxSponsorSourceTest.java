package org.joinmastodon.android.sponsors;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.JsonParser;

import org.joinmastodon.android.model.sponsors.SponsorModels.*;
import org.joinmastodon.android.model.sponsors.SponsorPaymentModels.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.net.InetAddress;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import okhttp3.Authenticator;
import okhttp3.CookieJar;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.joinmastodon.android.sponsors.SandboxFixtures.*;
import static org.junit.Assert.*;

/** The only network is the same-package injected loopback interceptor. No public host/SDK pay. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=31, application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class SandboxSponsorSourceTest{
	private MockWebServer server;
	private OkHttpClient client;
	private SandboxSession session;
	private SandboxSponsorSource source;
	@Before public void setup() throws Exception{
		Context context=RuntimeEnvironment.getApplication();
		context.getSharedPreferences(SandboxSession.IDENTITY_PREFS, Context.MODE_PRIVATE).edit().clear().commit();
		context.getSharedPreferences("sponsor_payment_recovery", Context.MODE_PRIVATE).edit().clear().commit();
		session=new SandboxSession(context, ORIGIN, ()->NOW);
		server=new MockWebServer(); server.start(InetAddress.getByName("127.0.0.1"), 0);
		client=SandboxSponsorSource.client().newBuilder().addInterceptor(chain->{
			var request=chain.request(); assertEquals(ORIGIN, request.url().scheme()+"://"+request.url().host());
			String query=request.url().encodedQuery();
			return chain.proceed(request.newBuilder().url(server.url(request.url().encodedPath()+(query==null ? "" : "?"+query))).build());
		}).build();
		source=new SandboxSponsorSource(session, client, new Handler(Looper.getMainLooper()));
	}
	@After public void cleanup() throws Exception{
		if(client!=null){ client.dispatcher().cancelAll(); client.connectionPool().evictAll(); client.dispatcher().executorService().shutdownNow(); }
		if(server!=null) server.shutdown();
	}
	private static final class Outcome<T> implements SponsorPaymentCoordinator.Result<T>{
		T value; SponsorPaymentCoordinator.Failure failure; boolean done;
		@Override public void success(T value){ this.value=value; done=true; }
		@Override public void failure(SponsorPaymentCoordinator.Failure failure){ this.failure=failure; done=true; }
	}
	private static void await(Outcome<?> outcome) throws Exception{
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
		while(!outcome.done && System.nanoTime()<deadline){ ShadowLooper.idleMainLooper(); Thread.sleep(5); }
		ShadowLooper.idleMainLooper(); assertTrue("Offline request callback timed out", outcome.done);
	}
	private <T> Outcome<T> submit(Function<SponsorPaymentCoordinator.Result<T>, SponsorPaymentCoordinator.Cancelable> start, String response, int status) throws Exception{
		server.enqueue(new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setHeader("Cache-Control", "private, no-store").setBody(response));
		Outcome<T> outcome=new Outcome<>(); start.apply(outcome); await(outcome); return outcome;
	}
	private RecordedRequest sent() throws Exception{ RecordedRequest request=server.takeRequest(5, TimeUnit.SECONDS); assertNotNull(request); return request; }
	private void paired(){ assertNull(session.accept(pair())); }
	@Test public void noInitialNetworkAndNoAuthenticatedRequestBeforeExplicitPair() throws Exception{
		assertEquals(0, server.getRequestCount());
		Outcome<Catalog> outcome=new Outcome<>(); source.catalog(outcome); await(outcome);
		assertNull(outcome.value); assertEquals(401, outcome.failure.status); assertEquals(0, server.getRequestCount());
		assertFalse(client.followRedirects()); assertFalse(client.followSslRedirects()); assertFalse(client.retryOnConnectionFailure());
		assertNull(client.cache()); assertSame(CookieJar.NO_COOKIES, client.cookieJar()); assertSame(Authenticator.NONE, client.authenticator());
	}
	@Test public void pairSendsOnlyExactCodeBodyAndNoAuthCookieOriginOrQuery() throws Exception{
		Outcome<String> paired=submit(result->source.pair(CODE, result), json(pair()), 200); assertNull(paired.failure);
		RecordedRequest request=sent(); assertEquals("POST", request.getMethod()); assertEquals("/__sandbox/pair", request.getPath());
		var body=JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject(); assertEquals(Set.of("code"), body.keySet());
		assertEquals(CODE, body.get("code").getAsString()); assertNull(request.getHeader("Authorization"));
		assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("Origin")); assertNotNull(session.lease());
	}
	@Test public void exactTypedOptionsAndCreateKeepOriginalContractAndIdempotency() throws Exception{
		paired();
		Outcome<Options> options=submit(result->source.options(SandboxProtocol.PLAN, 1, result), json(options()), 200);
		assertNull(options.failure); assertEquals(1, options.value.schemaVersion); assertFalse(options.value.channel("afdian").available);
		RecordedRequest read=sent(); assertEquals("GET", read.getMethod()); assertEquals("/api/v1/sponsors/payment-options?plan_id=sandbox-cent&plan_version=1", read.getPath());
		assertEquals("Bearer "+TOKEN, read.getHeader("Authorization")); assertEquals("no-store", read.getHeader("Cache-Control"));
		Order order=order(); order.orderStr="offline-only-unchanged-signed-order";
		Outcome<Order> created=submit(result->source.create(SandboxProtocol.PLAN, 1, OPERATION, result), json(order), 200);
		assertNull(created.failure); assertEquals(order.orderStr, created.value.orderStr);
		RecordedRequest post=sent(); assertEquals("POST", post.getMethod()); assertEquals("/api/v1/sponsors/payment-orders", post.getPath());
		assertEquals(OPERATION, post.getHeader("Idempotency-Key"));
		var body=JsonParser.parseString(post.getBody().readUtf8()).getAsJsonObject();
		assertEquals(Set.of("plan_id", "plan_version", "operation_id", "channel"), body.keySet()); assertEquals("alipay", body.get("channel").getAsString());
		assertEquals(OPERATION, body.get("operation_id").getAsString()); assertEquals(1, body.get("plan_version").getAsInt());
	}
	@Test public void catalogMeCurrentOriginalOrderAndExplicitCloseUseOnlyAllowlistedRoutes() throws Exception{
		paired(); assertNull(submit(source::catalog, json(catalog()), 200).failure); assertEquals("/api/v1/sponsors/catalog", sent().getPath());
		assertNull(submit(source::me, json(me()), 200).failure); assertEquals("/api/v1/sponsors/me", sent().getPath());
		assertNull(submit(source::current, "{\"order\":null}", 200).failure); assertEquals("/api/v1/sponsors/payment-orders/current", sent().getPath());
		Outcome<Order> lookup=submit(result->source.order("fixture-order-01", result), json(order()), 200);
		assertNull(lookup.failure); assertEquals("/api/v1/sponsors/payment-orders/fixture-order-01", sent().getPath());
		Order closed=order(); closed.paymentState="CLOSED"; closed.canSwitchChannel=true;
		Outcome<Order> close=submit(result->source.close("fixture-order-01", result), json(closed), 200); assertNull(close.failure);
		RecordedRequest post=sent(); assertEquals("POST", post.getMethod()); assertEquals("/api/v1/sponsors/payment-orders/fixture-order-01/close", post.getPath()); assertEquals("{}", post.getBody().readUtf8());
	}
	@Test public void redirectIsNotFollowedAndUnauthorizedInvalidatesOnlyMemory() throws Exception{
		paired(); Outcome<CurrentOrder> redirect=submit(source::current, "{}", 302); sent();
		assertNull(redirect.value); assertEquals(302, redirect.failure.status); assertEquals(1, server.getRequestCount());
		String owner=session.owner(); var store=new SponsorPaymentCoordinator.PreferencesStore(RuntimeEnvironment.getApplication());
		var pending=new SponsorPaymentCoordinator.Pending(owner, OPERATION, "fixture-order-01"); assertTrue(store.compareAndSet(owner, null, pending));
		Outcome<CurrentOrder> unauthorized=submit(source::current, "{\"code\":\"unauthorized\"}", 401); sent();
		assertEquals(401, unauthorized.failure.status); assertNull(session.lease()); assertEquals(owner, session.owner());
		assertTrue(SponsorPaymentCoordinator.samePending(pending, store.read(owner)));
	}
	@Test public void signedReadMalformedAndBoundedBodiesFailClosed() throws Exception{
		paired(); Order order=order(); order.orderStr="offline-sensitive-fixture";
		Outcome<Order> signed=submit(result->source.order("fixture-order-01", result), json(order), 200); sent(); assertNotNull(signed.failure);
		assertNotNull(submit(source::current, "{\"order\":null,\"order\":null}", 200).failure); sent();
		assertNotNull(submit(source::current, "{\"x\":\""+"x".repeat(SandboxProtocol.BODY_LIMIT)+"\"}", 200).failure); sent();
	}
	@Test public void invalidInputsAndNonAllowlistedOperationsNeverDispatch(){
		paired();
		for(String id:List.of("..", "a/b", "x?secret=y")) assertThrows(IllegalArgumentException.class, ()->source.order(id, new Outcome<>()));
		assertThrows(IllegalArgumentException.class, ()->source.options("month", 1, new Outcome<>()));
		assertThrows(IllegalArgumentException.class, ()->source.create(SandboxProtocol.PLAN, 1, OPERATION.toUpperCase(), new Outcome<>()));
		for(String route:List.of("/api/v1/sponsors/redeem", "/api/v1/sponsors/color", "/api/payments/orders", "/__sandbox/health", "/api/v1/accounts/verify_credentials")){
			assertFalse(SandboxSponsorSource.allowed("GET", route)); assertFalse(SandboxSponsorSource.allowed("POST", route));
		}
		assertFalse(SandboxSponsorSource.allowed("DELETE", "/api/v1/sponsors/payment-orders/fixture-order-01")); assertEquals(0, server.getRequestCount());
	}
}
