package org.joinmastodon.android.auth;

import static org.junit.Assert.*;

import android.net.Uri;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.api.requests.nbw.NBWLoginRequest;
import org.joinmastodon.android.events.AccountLoggedOutEvent;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import okio.Buffer;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class)
public class NBWLoginFlowTest{
	private final NBWLoginFlow flow=NBWLoginFlow.getInstance();
	@Before public void setup(){
		MastodonApp.context=RuntimeEnvironment.getApplication();
		flow.cancel(flow.begin());
	}
	public static NBWLoginRequest.Start startResult(){
		NBWLoginRequest.Start result=new NBWLoginRequest.Start();
		result.flow="A".repeat(43); result.expiresIn=600;
		result.authorizationUrl=new Uri.Builder().scheme("https").authority("www.newbabyworld.top").path("/oauth/authorize.php")
				.appendQueryParameter("client_id", "public-registered-client")
				.appendQueryParameter("redirect_uri", NBWLoginRequest.REDIRECT_URI).appendQueryParameter("response_type", "code")
				.appendQueryParameter("state", "nbw-v2-login."+result.flow+"."+(System.currentTimeMillis()+600_000L)+"."+"A".repeat(43)).build().toString();
		return result;
	}
	public static NBWLoginRequest.Exchange exchangeResult(){
		NBWLoginRequest.Exchange result=new NBWLoginRequest.Exchange(); result.action="login"; result.token="eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI0MiJ9.c2lnbmF0dXJl";
		result.user=com.google.gson.JsonParser.parseString("{\"id\":42,\"email\":\"offline@example.test\",\"username\":\"offline\",\"avatar\":null,\"role\":\"user\"}").getAsJsonObject();
		return result;
	}
	public static Uri completion(){ return Uri.parse("abdl-space://callback?nbw_v2_login=complete&code="+"A".repeat(43)); }
	private NBWLoginFlow.Handle waiting(){
		NBWLoginFlow.Handle handle=flow.begin();
		assertNotNull(flow.acceptStart(handle, flow.start(handle), startResult())); return handle;
	}
	private NBWLoginFlow.Handle ready(){
		NBWLoginFlow.Handle handle=waiting(); assertEquals(handle.generation, flow.acceptCompletion(completion())); return handle;
	}
	public static String body(org.joinmastodon.android.api.MastodonAPIRequest<?> request) throws Exception{
		Buffer buffer=new Buffer(); request.getRequestBody().writeTo(buffer); return buffer.readUtf8();
	}
	@Test public void secureProofHasCanonicalShapeAndKnownS256Vector(){
		Set<String> unique=new HashSet<>();
		for(int i=0;i<128;i++){
			String verifier=NBWLoginFlow.newVerifier(); assertTrue(NBWLoginRequest.canonical43(verifier));
			assertTrue(NBWLoginRequest.canonical43(NBWLoginFlow.challenge(verifier))); unique.add(verifier);
		}
		assertEquals(128, unique.size());
		assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", NBWLoginFlow.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
	}
	@Test public void replacementRejectsLateStartExchangeAndOldOwnerCleanup(){
		NBWLoginFlow.Handle first=flow.begin(); var oldStart=flow.start(first);
		NBWLoginFlow.Handle second=flow.begin();
		assertNull(flow.acceptStart(first, oldStart, startResult())); flow.detach(first, false);
		assertTrue(flow.isCurrent(second)); assertNotNull(flow.acceptStart(second, flow.start(second), startResult()));
		flow.acceptCompletion(completion()); var oldExchange=flow.exchange(second, true); NBWLoginFlow.Handle third=flow.begin();
		assertFalse(flow.acceptExchange(second, oldExchange, exchangeResult())); flow.cancel(second); assertTrue(flow.isCurrent(third));
	}
	@Test public void onlyOneExchangeCanRunAndPendingNeedsManualRetry() throws Exception{
		NBWLoginFlow.Handle handle=waiting(); assertNull(flow.exchange(handle, false));
		assertEquals(handle.generation, flow.acceptCompletion(completion()));
		var exchange=flow.exchange(handle, false); assertNotNull(exchange);
		assertNull(flow.exchange(handle, false)); assertNull(flow.exchange(handle, true));
		flow.failure(handle, exchange, new NBWLoginRequest.LoginError(409, "NBW_LOGIN_PENDING"));
		assertTrue(flow.isCurrent(handle)); flow.acceptCompletion(completion()); assertNull(flow.exchange(handle, false));
		AtomicInteger accepted=new AtomicInteger(); CountDownLatch gate=new CountDownLatch(1);
		Runnable attempt=()->{ try{ gate.await(); if(flow.exchange(handle, true)!=null) accepted.incrementAndGet(); }catch(InterruptedException e){ throw new AssertionError(e); } };
		Thread a=new Thread(attempt), b=new Thread(attempt); a.start(); b.start(); gate.countDown(); a.join(); b.join();
		assertEquals(1, accepted.get());
	}
	@Test public void recreationTransfersOwnershipButNeverRepeatsBrowserOrAutomaticExchange() throws Exception{
		NBWLoginFlow.Handle old=ready(); var first=flow.exchange(old, true); String proof=body(first);
		flow.detach(old, true); NBWLoginFlow.Handle fresh=flow.attach(old.generation);
		assertNotNull(fresh); assertFalse(flow.isCurrent(old)); assertNull(flow.start(fresh)); assertNull(flow.exchange(fresh, false));
		flow.detach(old, false); assertTrue(flow.isCurrent(fresh)); assertEquals(proof, body(flow.exchange(fresh, true)));
	}
	@Test public void expiryCancelAndLogoutAreTerminalAndDoNotPersistProof(){
		var prefs=MastodonApp.context.getSharedPreferences("nbw_v2_login", 0); var before=prefs.getAll();
		NBWLoginFlow.Handle handle=waiting(); ShadowSystemClock.advanceBy(Duration.ofMillis(NBWLoginFlow.TTL_MS));
		assertFalse(flow.isCurrent(handle)); assertEquals(0, flow.acceptCompletion(completion())); assertNull(flow.attach(handle.generation));
		handle=waiting(); flow.cancel(handle); assertNull(flow.exchange(handle, true));
		handle=waiting(); flow.onAccountLoggedOut(new AccountLoggedOutEvent("other-account")); assertFalse(flow.isCurrent(handle));
		assertEquals(before, prefs.getAll());
	}
	@Test public void completionRequiresCanonicalCodeCannotBeReplacedAndExpiresBeforeTheFlow(){
		NBWLoginFlow.Handle handle=waiting();
		assertFalse(flow.snapshot(handle).canRetry()); assertNull(flow.exchange(handle,true));
		assertEquals(0,flow.acceptCompletion(Uri.parse("abdl-space://callback?nbw_v2_login=complete")));
		assertEquals(handle.generation,flow.acceptCompletion(completion()));
		assertEquals(0,flow.acceptCompletion(Uri.parse("abdl-space://callback?nbw_v2_login=complete&code="+"B".repeat(42)+"A")));
		assertEquals(handle.generation,flow.acceptCompletion(completion()));
		ShadowSystemClock.advanceBy(Duration.ofMillis(NBWLoginFlow.COMPLETION_TTL_MS));
		assertFalse(flow.isCurrent(handle)); assertNull(flow.exchange(handle,true));
	}
	@Test public void transportFailureRetriesSameFlowButUnboundReauthAndUnknownErrorsCannotInstall() throws Exception{
		for(String code:new String[]{"NBW_LOGIN_NOT_BOUND", "NBW_LOGIN_REAUTH_REQUIRED", "NBW_LOGIN_REJECTED", "NBW_LOGIN_INVALID_FLOW", "NBW_LOGIN_UNAVAILABLE", "unrecognized raw token"}){
			NBWLoginFlow.Handle handle=ready(); var exchange=flow.exchange(handle, true);
			flow.failure(handle, exchange, new NBWLoginRequest.LoginError(403, code));
			assertFalse(flow.isCurrent(handle)); assertFalse(flow.acceptExchange(handle, exchange, exchangeResult()));
		}
		NBWLoginFlow.Handle handle=ready(); var exchange=flow.exchange(handle, true); String proof=body(exchange);
		assertEquals(R.string.nbw_login_network_error, flow.failure(handle, exchange, new MastodonErrorResponse("raw hidden",0,null)));
		assertTrue(flow.isCurrent(handle)); assertEquals(proof, body(flow.exchange(handle,true)));
	}
}
