package org.joinmastodon.android.api;

import android.content.Context;
import android.view.View;

import com.google.gson.JsonObject;

import org.joinmastodon.android.BuildConfig;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.requests.verification.VerificationRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.*;

/** Actual controller, HTTP, strict decoder and callbacks. Only the transport URL is rerouted to loopback;
 * the final response retains the original authority, exactly as an application interceptor would.
 * This does not exercise TLS trust, a deployed backend, retrying writes or an installed APK.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, qualifiers="en")
@LooperMode(LooperMode.Mode.PAUSED)
public class ServiceResourcePauseHttpTest{
	private MockWebServer server;
	private OkHttpClient original, client;
	private MastodonAPIController controller;
	private final AtomicInteger callbacks=new AtomicInteger();
	private final AtomicReference<ErrorResponse> failure=new AtomicReference<>();
	private final AtomicReference<JsonObject> success=new AtomicReference<>();
	private CountDownLatch responseSeen;
	private static Field field(Class<?> type, String name) throws Exception{ Field field=type.getDeclaredField(name); field.setAccessible(true); return field; }

	private static class RequestFixture extends MastodonAPIRequest<JsonObject>{
		RequestFixture(){ super(HttpMethod.GET, "/test", JsonObject.class); }
	}
	private static final class InterfaceError extends ErrorResponse implements ServiceResourcePause.ErrorMetadata{
		final int status; final boolean retryable;
		ServiceResourcePause pause;
		InterfaceError(int status){ this.status=status; retryable=status>=500; }
		@Override public void setServiceResourcePause(ServiceResourcePause pause){ this.pause=pause; }
		@Override public ServiceResourcePause getServiceResourcePause(){ return pause; }
		@Override public void bindErrorView(View view){}
		@Override public void showToast(Context context){}
	}
	@Before public void setup() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication();
		me.grishka.appkit.utils.V.setApplicationContext(MastodonApp.context);
		server=new MockWebServer(); server.start(); responseSeen=new CountDownLatch(1);
		Field http=field(MastodonAPIController.class, "httpClient"); original=(OkHttpClient)http.get(null);
		client=new OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor(chain->{
			Request request=chain.request();
			if(!request.url().host().equals("api.abdl-space.top") && !request.url().host().equals("foreign.example.test")) throw new IOException("Unexpected outbound host");
			Response response=chain.proceed(request.newBuilder().url(server.url(request.url().encodedPath())).build());
			responseSeen.countDown();
			return response.newBuilder().request(request).build();
		}).build();
		http.set(null, client); controller=new MastodonAPIController(null);
	}
	@After public void cleanup() throws Exception{
		field(MastodonAPIController.class, "httpClient").set(null, original);
		client.dispatcher().cancelAll(); server.shutdown(); client.connectionPool().evictAll(); client.dispatcher().executorService().shutdown();
	}
	private void waitFor(BooleanSupplier done) throws Exception{
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
		while(!done.getAsBoolean() && System.nanoTime()<deadline){ ShadowLooper.idleMainLooper(); Thread.sleep(10); }
		ShadowLooper.idleMainLooper(); assertTrue("HTTP/callback did not complete", done.getAsBoolean());
	}
	private RecordedRequest start(RequestFixture request, MockResponse response, String host) throws Exception{
		field(MastodonAPIRequest.class, "domain").set(request, host);
		request.setCallback(new Callback<>(){
			@Override public void onSuccess(JsonObject value){ success.set(value); callbacks.incrementAndGet(); }
			@Override public void onError(ErrorResponse error){ failure.set(error); callbacks.incrementAndGet(); }
		});
		server.enqueue(response); controller.submitRequest(request);
		RecordedRequest recorded=server.takeRequest(10, TimeUnit.SECONDS); assertNotNull(recorded); return recorded;
	}
	private ErrorResponse fail(RequestFixture request, MockResponse response) throws Exception{
		start(request, response, "api.abdl-space.top"); waitFor(()->callbacks.get()>0);
		assertNull(success.get()); assertEquals(1, callbacks.get()); assertNotNull(failure.get()); return failure.get();
	}
	private MockResponse json(int status, String body){ return new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body); }

	@Test public void actual503Code36AttachesMetadataAndNativeIdentityWithoutRetry() throws Exception{
		RequestFixture request=new RequestFixture();
		RecordedRequest recorded=start(request, json(503, ServiceResourcePauseJsonTest.BODY), "api.abdl-space.top"); waitFor(()->callbacks.get()>0);
		assertEquals("36", recorded.getHeader("X-App-Version-Code")); assertEquals(36, BuildConfig.VERSION_CODE);
		assertEquals("MastodonAndroid/"+BuildConfig.VERSION_NAME, recorded.getHeader("User-Agent"));
		MastodonErrorResponse error=(MastodonErrorResponse)failure.get(); assertEquals(503, error.httpStatus); assertNotNull(ServiceResourcePause.fromError(error));
		assertFalse(error.localizedMessage(MastodonApp.context).contains("Not retained")); assertEquals(1, server.getRequestCount());
	}
	@Test public void machineCodeWorksForCompatibility429WithoutReplacingStatus() throws Exception{
		MastodonErrorResponse error=(MastodonErrorResponse)fail(new RequestFixture(), json(429, ServiceResourcePauseJsonTest.BODY));
		assertEquals(429, error.httpStatus); assertNotNull(error.serviceResourcePause);
	}
	@Test public void ordinary429KeepsOriginalTextAndNoPause() throws Exception{
		MastodonErrorResponse error=(MastodonErrorResponse)fail(new RequestFixture(), json(429, "{\"error\":\"ordinary rate limit\",\"code\":\"translation_quota_exhausted\"}"));
		assertEquals("ordinary rate limit", error.error); assertEquals("ordinary rate limit", error.localizedMessage(MastodonApp.context)); assertNull(error.serviceResourcePause);
	}
	@Test public void successfulMatchingCodeRemainsSuccessfulUserJson() throws Exception{
		start(new RequestFixture(), json(200, ServiceResourcePauseJsonTest.BODY), "api.abdl-space.top"); waitFor(()->callbacks.get()>0);
		assertNull(failure.get()); assertEquals(ServiceResourcePause.CODE, success.get().get("code").getAsString());
	}
	@Test public void foreignAuthorityDoesNotAcquirePauseFromSameHttpPayload() throws Exception{
		start(new RequestFixture(), json(503, ServiceResourcePauseJsonTest.BODY), "foreign.example.test"); waitFor(()->callbacks.get()>0);
		assertNull(ServiceResourcePause.fromError(failure.get())); assertEquals(503, ((MastodonErrorResponse)failure.get()).httpStatus);
	}
	@Test public void malformedHtmlIsNotExposedAsRawError() throws Exception{
		MastodonErrorResponse error=(MastodonErrorResponse)fail(new RequestFixture(), new MockResponse().setResponseCode(502).setHeader("Content-Type", "text/html").setBody("<html>private origin D1_DAILY_QUOTA_EXHAUSTED</html>"));
		assertNull(error.serviceResourcePause); assertFalse(error.error.contains("private origin")); assertEquals(502, error.httpStatus);
	}
	@Test public void actualChunkedOversizedBodyIsBoundedEvenWithoutContentLength() throws Exception{
		String body="{\"code\":\"D1_DAILY_QUOTA_EXHAUSTED\",\"error\":\""+"汉".repeat(12000)+"\"}";
		MastodonErrorResponse error=(MastodonErrorResponse)fail(new RequestFixture(), new MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json").setChunkedBody(body, 3072));
		assertEquals(503, error.httpStatus); assertNull(error.serviceResourcePause); assertFalse(error.error.contains("汉"));
	}
	@Test public void advertisedOversizedBodyIsRejectedBeforeParsingItsMarker() throws Exception{
		MastodonErrorResponse error=(MastodonErrorResponse)fail(new RequestFixture(), json(429, "{\"code\":\"D1_DAILY_QUOTA_EXHAUSTED\",\"error\":\""+"a".repeat(40000)+"\"}"));
		assertEquals(429, error.httpStatus); assertNull(error.serviceResourcePause);
	}
	@Test public void realHttpDuplicateMachineCodeAndMalformedUtf8AreRejected() throws Exception{
		MastodonErrorResponse error=(MastodonErrorResponse)fail(new RequestFixture(), json(429, "{\"code\":\"ordinary\",\"code\":\"D1_DAILY_QUOTA_EXHAUSTED\"}"));
		assertNull(error.serviceResourcePause);
		callbacks.set(0); failure.set(null);
		okio.Buffer invalid=new okio.Buffer().writeUtf8("{\"code\":\"D1_DAILY_QUOTA_EXHAUSTED\",\"error\":\"").writeByte(0xc3).writeByte(0x28).writeUtf8("\"}");
		error=(MastodonErrorResponse)fail(new RequestFixture(), new MockResponse().setResponseCode(429).setBody(invalid)); assertNull(error.serviceResourcePause);
	}
	@Test public void actualTypedVerificationErrorPreservesClassStatusAndRetryability() throws Exception{
		AtomicReference<VerificationRequest.VerificationError> decoded=new AtomicReference<>();
		RequestFixture request=new RequestFixture(){ @Override public ErrorResponse deserializeError(JsonObject body, int status){
			VerificationRequest.VerificationError error=new VerificationRequest.VerificationError(body, status); decoded.set(error); return error;
		}};
		ErrorResponse error=fail(request, json(503, ServiceResourcePauseJsonTest.BODY));
		assertSame(decoded.get(), error); assertEquals(VerificationRequest.VerificationError.class, error.getClass());
		assertEquals(503, decoded.get().httpStatus); assertTrue(decoded.get().retryable); assertTrue(decoded.get().outcomeUnknown); assertNotNull(ServiceResourcePause.fromError(error));
	}
	@Test public void interfaceMetadataPreservesNonMastodonCustomErrorIdentity() throws Exception{
		AtomicReference<InterfaceError> decoded=new AtomicReference<>();
		ErrorResponse error=fail(new RequestFixture(){ @Override public ErrorResponse deserializeError(JsonObject body, int status){ InterfaceError custom=new InterfaceError(status); decoded.set(custom); return custom; }}, json(503, ServiceResourcePauseJsonTest.BODY));
		assertSame(decoded.get(), error); assertEquals(503, decoded.get().status); assertTrue(decoded.get().retryable); assertNotNull(ServiceResourcePause.fromError(error));
	}
	@Test public void silentRemoteErrorNeverAcquiresPresentationMetadata() throws Exception{
		ErrorResponse error=fail(new RequestFixture(){ @Override public ErrorResponse deserializeError(JsonObject body, int status){ return new MastodonErrorResponse.SilentNBWError("NBW unavailable", status, null); }}, json(503, ServiceResourcePauseJsonTest.BODY));
		assertEquals(MastodonErrorResponse.SilentNBWError.class, error.getClass()); assertNull(ServiceResourcePause.fromError(error)); assertNull(((MastodonErrorResponse)error).serviceResourcePause);
	}
	@Test public void cancellationWhileActualResponseBodyPendingKeepsCallCancellableAndSuppressesCallback() throws Exception{
		RequestFixture request=new RequestFixture(); start(request, json(503, ServiceResourcePauseJsonTest.BODY).setBodyDelay(2, TimeUnit.SECONDS), "api.abdl-space.top");
		assertTrue(responseSeen.await(10, TimeUnit.SECONDS)); assertNotNull("Call must not be cleared at headers", request.okhttpCall);
		request.cancel(); assertTrue(request.canceled); ShadowLooper.idleMainLooper();
		client.dispatcher().cancelAll();
		waitFor(()->client.dispatcher().runningCallsCount()==0);
		assertEquals(0, callbacks.get());
	}
	@Test public void cancellationAfterQueuedErrorBeforeUiDeliverySuppressesCallback(){
		RequestFixture request=new RequestFixture(); request.setCallback(new Callback<>(){
			@Override public void onSuccess(JsonObject value){ callbacks.incrementAndGet(); }
			@Override public void onError(ErrorResponse error){ callbacks.incrementAndGet(); }
		});
		request.onError(new MastodonErrorResponse("test", 503, null)); request.cancel(); ShadowLooper.idleMainLooper(); assertEquals(0, callbacks.get());
	}
}
