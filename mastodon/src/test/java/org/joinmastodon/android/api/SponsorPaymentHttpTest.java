package org.joinmastodon.android.api;

import android.app.Application;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.requests.sponsors.SponsorPaymentRequest;
import org.joinmastodon.android.api.requests.sponsors.SponsorRequest;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.model.sponsors.SponsorPaymentModels.Order;
import org.joinmastodon.android.sponsors.SponsorPaymentFixtures;
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

/** Actual HTTP controller, auth, sensitive transport, JSON adapter, status and model validation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public class SponsorPaymentHttpTest{
	private static final String HOST="sponsor-payment-offline.example.test";
	private static final String OPERATION="65adffb9-98bf-46b0-9fca-59a3bc6afdd0";
	private MockWebServer server;
	private OkHttpClient client;
	private Object interceptors;
	private MastodonAPIController controller;
	private static Field field(Class<?> type, String name) throws Exception{ Field f=type.getDeclaredField(name); f.setAccessible(true); return f; }
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
		field(OkHttpClient.class, "interceptors").set(client, List.of(loopback)); controller=new MastodonAPIController(null);
	}
	@After public void cleanup() throws Exception{
		if(client!=null && interceptors!=null) field(OkHttpClient.class, "interceptors").set(client, interceptors);
		if(client!=null){ client.dispatcher().cancelAll(); client.connectionPool().evictAll(); }
		if(server!=null) server.shutdown();
	}
	private static final class Outcome<T> implements Callback<T>{
		T value; ErrorResponse error; boolean done;
		@Override public void onSuccess(T value){ this.value=value; done=true; }
		@Override public void onError(ErrorResponse error){ this.error=error; done=true; }
	}
	private <T> Outcome<T> submit(SponsorPaymentRequest<T> request, String body, int status) throws Exception{
		field(MastodonAPIRequest.class, "domain").set(request, HOST);
		Token token=new Token(); token.accessToken="synthetic-payment-fixture-token"; field(MastodonAPIRequest.class, "token").set(request, token);
		assertTrue(request.isSensitiveRequest()); assertTrue(request.responseByteLimit()>0);
		Outcome<T> result=new Outcome<>(); request.setCallback(result);
		server.enqueue(new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body));
		controller.submitRequest(request);
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
		while(!result.done && System.nanoTime()<deadline){ ShadowLooper.idleMainLooper(); Thread.sleep(10); }
		ShadowLooper.idleMainLooper(); assertTrue("HTTP callback timed out", result.done); return result;
	}
	private RecordedRequest sent() throws Exception{ RecordedRequest request=server.takeRequest(5, TimeUnit.SECONDS); assertNotNull(request); return request; }
	private static String order(){ return MastodonAPIController.gson.toJson(SponsorPaymentFixtures.order("PENDING_PAYMENT", "PENDING")); }
	@Test public void exactOptionsPathQueryAuthAndSnakeCaseProjection() throws Exception{
		var result=submit(SponsorPaymentRequest.options("fixture-month", 7), MastodonAPIController.gson.toJson(SponsorPaymentFixtures.options("limits_unverified")), 200);
		RecordedRequest request=sent(); assertEquals("GET", request.getMethod());
		assertEquals("/api/v1/sponsors/payment-options?plan_id=fixture-month&plan_version=7", request.getPath());
		assertEquals("Bearer synthetic-payment-fixture-token", request.getHeader("Authorization")); assertEquals("no-store", request.getHeader("Cache-Control"));
		assertNull(result.error); assertEquals(1, result.value.schemaVersion); assertEquals(7, result.value.plan.version);
		assertEquals("limits_unverified", result.value.channels.get(0).reason); assertNull(result.value.activeOrder);
	}
	@Test public void createHasOnlyServerContractFieldsAndSameCanonicalIdempotencyKey() throws Exception{
		Order pending=SponsorPaymentFixtures.order("PENDING_PAYMENT", "PENDING"); pending.orderStr="synthetic-only-order-string";
		var result=submit(SponsorPaymentRequest.create("fixture-month", 7, OPERATION), MastodonAPIController.gson.toJson(pending), 200);
		RecordedRequest request=sent(); assertEquals("POST", request.getMethod()); assertEquals("/api/v1/sponsors/payment-orders", request.getPath());
		assertEquals(OPERATION, request.getHeader("Idempotency-Key"));
		JsonObject body=JsonParser.parseString(request.getBody().readUtf8()).getAsJsonObject();
		assertEquals(Set.of("plan_id", "plan_version", "channel", "operation_id"), body.keySet());
		assertEquals("alipay", body.get("channel").getAsString()); assertEquals(OPERATION, body.get("operation_id").getAsString());
		assertEquals("fixture-month", body.get("plan_id").getAsString()); assertEquals(7, body.get("plan_version").getAsInt());
		assertNull(result.error); assertEquals("synthetic-only-order-string", result.value.orderStr); assertEquals(590, result.value.amountMinor);
	}
	@Test public void currentAndOrderAreReadOnlyAndCloseRequiresExplicitEmptyBody() throws Exception{
		var current=submit(SponsorPaymentRequest.current(), "{\"order\":null}", 200); assertNull(current.error); assertNull(current.value.order);
		RecordedRequest read=sent(); assertEquals("GET", read.getMethod()); assertEquals("/api/v1/sponsors/payment-orders/current", read.getPath());
		var lookup=submit(SponsorPaymentRequest.order("fixture-order-01"), order(), 200); assertNull(lookup.error); assertNull(lookup.value.orderStr);
		assertEquals("/api/v1/sponsors/payment-orders/fixture-order-01", sent().getPath());
		var closed=submit(SponsorPaymentRequest.close("fixture-order-01"), MastodonAPIController.gson.toJson(SponsorPaymentFixtures.order("CLOSED", "PENDING")), 200);
		RecordedRequest close=sent(); assertEquals("POST", close.getMethod()); assertEquals("/api/v1/sponsors/payment-orders/fixture-order-01/close", close.getPath());
		assertEquals("{}", close.getBody().readUtf8()); assertNull(closed.error); assertEquals("CLOSED", closed.value.paymentState); assertTrue(closed.value.canSwitchChannel);
	}
	@Test public void errorsKeepHttpStatusAndStableBackendCodeWithoutInventedSuccess() throws Exception{
		for(int status:new int[]{401, 404, 409, 429, 501, 502, 503}){
			var result=submit(SponsorPaymentRequest.current(), "{\"error\":\"Fixture payment is unavailable\",\"code\":\"payment_result_unknown\"}", status);
			sent(); assertNull(result.value); assertTrue(result.error instanceof SponsorRequest.SponsorError);
			var error=(SponsorRequest.SponsorError)result.error; assertEquals(status, error.httpStatus); assertEquals("payment_result_unknown", error.code);
		}
	}
	@Test public void signedDataCannotArriveThroughReadPaths() throws Exception{
		Order pending=SponsorPaymentFixtures.order("PENDING_PAYMENT", "PENDING"); pending.orderStr="synthetic-sensitive-order";
		String signed=MastodonAPIController.gson.toJson(pending);
		assertNotNull(submit(SponsorPaymentRequest.order(pending.orderId), signed, 200).error); sent();
		assertNotNull(submit(SponsorPaymentRequest.current(), "{\"order\":"+signed+"}", 200).error); sent();
		var options=SponsorPaymentFixtures.options(null); options.activeOrder=pending;
		assertNotNull(submit(SponsorPaymentRequest.options("fixture-month", 7), MastodonAPIController.gson.toJson(options), 200).error); sent();
	}
	@Test public void invalidServerStatesAreRejectedByActualAdapter() throws Exception{
		for(String invalid:List.of(
				order().replace("\"can_switch_channel\":false", "\"can_switch_channel\":true"),
				order().replace("\"amount_minor\":590", "\"amount_minor\":0"),
				order().replace("\"payment_state\":\"PENDING_PAYMENT\"", "\"payment_state\":\"SDK_SUCCESS\""),
				order().replace("\"fulfillment_state\":\"PENDING\"", "\"fulfillment_state\":\"GRANTED\""))){
			var result=submit(SponsorPaymentRequest.order("fixture-order-01"), invalid, 200); sent(); assertNull(result.value); assertNotNull(result.error);
		}
	}
	@Test public void redirectsAreNotFollowedBySensitivePaymentTransport() throws Exception{
		var result=submit(SponsorPaymentRequest.current(), "{}", 302); sent(); assertNull(result.value); assertNotNull(result.error); assertEquals(1, server.getRequestCount());
	}
	@Test public void invalidIdsAndOperationsAreRejectedBeforeHttp(){
		for(String operation:List.of("not-uuid", OPERATION.toUpperCase(), "1-1-1-1-1")){
			try{ SponsorPaymentRequest.create("fixture-month", 7, operation); fail("Accepted invalid operation"); }catch(IllegalArgumentException expected){}
		}
		for(String id:List.of("../current", "..", "a/b", "x?secret=y")){
			try{ SponsorPaymentRequest.order(id); fail("Accepted invalid order id"); }catch(IllegalArgumentException expected){}
		}
		assertEquals(0, server.getRequestCount());
	}
}
