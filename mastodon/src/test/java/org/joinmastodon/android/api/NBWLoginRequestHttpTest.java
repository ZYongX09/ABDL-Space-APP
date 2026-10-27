package org.joinmastodon.android.api;

import static org.junit.Assert.*;

import android.net.Uri;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.joinmastodon.android.BuildConfig;
import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.nbw.NBWLoginRequest;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.auth.NBWLoginFlowTest;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Token;
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
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** Real request, controller, strict Gson adapters and postprocessing on loopback. Does not test TLS. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,28}, application=CompatibilityTestApplication.class, shadows=NBWLoginRequestHttpTest.LoopbackClient.class)
public class NBWLoginRequestHttpTest{
	private static MockWebServer server;
	private AccountSession session;
	private String previousActive;
	private Map<String,AccountSession> sessions;
	@Implements(MastodonAPIController.class) public static class LoopbackClient{
		@Implementation protected static OkHttpClient createSystemTrustClient(OkHttpClient ignored){
			return new OkHttpClient.Builder().cache(null).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
					.addInterceptor(chain->{
						Request request=chain.request();
						if(!NBWLoginRequest.DOMAIN.equals(request.url().host()) || !request.url().isHttps() || request.url().query()!=null)
							throw new IOException("Unexpected login destination");
						return chain.proceed(request.newBuilder().url(server.url(request.url().encodedPath())).build());
					}).build();
		}
	}
	private static Field field(Class<?> type,String name) throws Exception{ Field value=type.getDeclaredField(name); value.setAccessible(true); return value; }
	@Before @SuppressWarnings("unchecked") public void setup() throws Exception{
		server=new MockWebServer(); server.start(); MastodonApp.context=RuntimeEnvironment.getApplication();
		var ctor=AccountSession.class.getDeclaredConstructor(); ctor.setAccessible(true); session=ctor.newInstance();
		session.domain="offline.example.test"; session.self=new Account(); session.self.id="11"; session.token=new Token(); session.token.accessToken="old-site-token-must-never-leak";
		var manager=AccountSessionManager.getInstance(); sessions=(Map<String,AccountSession>)field(AccountSessionManager.class,"sessions").get(manager);
		sessions.put(session.getID(),session); previousActive=manager.getLastActiveAccountID(); field(AccountSessionManager.class,"lastActiveAccountID").set(manager,session.getID());
	}
	@After public void cleanup() throws Exception{
		sessions.remove(session.getID()); field(AccountSessionManager.class,"lastActiveAccountID").set(AccountSessionManager.getInstance(),previousActive); server.shutdown();
	}
	private static String startJson(){
		var result=NBWLoginFlowTest.startResult(); JsonObject object=new JsonObject(); object.addProperty("flow",result.flow);
		object.addProperty("authorization_url",result.authorizationUrl); object.addProperty("expires_in",600); return object.toString();
	}
	private static String exchangeJson(){
		var result=NBWLoginFlowTest.exchangeResult(); JsonObject object=new JsonObject(); object.addProperty("action",result.action);
		object.addProperty("token",result.token); object.add("user",result.user); return object.toString();
	}
	private <T> ErrorResponse send(NBWLoginRequest<T> request,MockResponse response,boolean success) throws Exception{
		AtomicBoolean done=new AtomicBoolean(); AtomicReference<T> result=new AtomicReference<>(); AtomicReference<ErrorResponse> error=new AtomicReference<>();
		request.setCallback(new Callback<>(){
			@Override public void onSuccess(T value){ result.set(value); done.set(true); }
			@Override public void onError(ErrorResponse value){ error.set(value); done.set(true); }
		});
		assertTrue(request.isSensitiveRequest()); assertTrue(request.requiresSystemTrust());
		assertEquals(NBWLoginRequest.DOMAIN,request.getURL().getHost()); assertNull(request.getURL().getQuery());
		server.enqueue(response.setHeader("Content-Type","application/json")); request.execNoAuth(NBWLoginRequest.DOMAIN);
		RecordedRequest sent=server.takeRequest(15,TimeUnit.SECONDS); assertNotNull(sent);
		assertEquals("POST",sent.getMethod()); assertEquals(request.getURL().getEncodedPath(),sent.getPath());
		assertNull(sent.getHeader("Authorization")); assertEquals("2",sent.getHeader("X-ABDL-NBW-Protocol")); assertEquals("MastodonAndroid/"+BuildConfig.VERSION_NAME,sent.getHeader("User-Agent"));
		assertTrue(sent.getHeader("Cache-Control").contains("no-store"));
		JsonObject body=JsonParser.parseString(sent.getBody().readUtf8()).getAsJsonObject();
		Set<String> keys=sent.getPath().endsWith("/start") ? Set.of("code_challenge") : Set.of("flow","code_verifier","completion_code");
		assertEquals(keys,body.keySet()); assertFalse(body.toString().contains(session.token.accessToken));
		long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
		while(!done.get() && System.nanoTime()<until){ ShadowLooper.idleMainLooper(); Thread.sleep(10); }
		assertTrue("request must settle",done.get());
		if(success){ assertNull(error.get()); assertNotNull(result.get()); }else{ assertNotNull(error.get()); assertNull(result.get()); }
		return error.get();
	}
	private static NBWLoginRequest<NBWLoginRequest.Start> start(){ return NBWLoginRequest.start("A".repeat(43)); }
	private static NBWLoginRequest<NBWLoginRequest.Exchange> exchange(){ return NBWLoginRequest.exchange("A".repeat(43),"v".repeat(43),"A".repeat(43)); }
	@Test public void realBodiesUseOnlyPkceAndCompletionCodeNeverTheExistingSiteBearer() throws Exception{
		send(start(),new MockResponse().setBody(startJson()),true); send(exchange(),new MockResponse().setBody(exchangeJson()),true);
		try{ start().exec(session.getID()); fail(); }catch(UnsupportedOperationException expected){}
		try{ exchange().exec(NBWLoginRequest.DOMAIN,session.token); fail(); }catch(UnsupportedOperationException expected){}
		try{ start().execNoAuth("arbitrary.example"); fail(); }catch(IllegalArgumentException expected){}
	}
	@Test public void startRejectsUrlInjectionWrongFlowAndWrongShape() throws Exception{
		String valid=NBWLoginFlowTest.startResult().authorizationUrl;
		for(String url:new String[]{
				valid.replace("www.newbabyworld.top","www.newbabyworld.top.evil.test"),valid.replace("https://","http://"),
				valid.replace("https://","https://user@"),valid.replace("/oauth/authorize.php","/oauth/else.php"),
				valid.replace("www.newbabyworld.top","www.newbabyworld.top:443"), valid+"#fragment",valid+"&token=secret",
				valid+"&client_id=other",valid.replace("response_type=code","response_type=token"),
				valid.replace(Uri.encode(NBWLoginRequest.REDIRECT_URI),Uri.encode("https://evil.test/callback")),
				valid.replace("nbw-v2-login.","nbw-v2-binding.")}){
			assertFalse(url,NBWLoginRequest.validAuthorizationUrl(url));
			JsonObject object=JsonParser.parseString(startJson()).getAsJsonObject(); object.addProperty("authorization_url",url);
			send(start(),new MockResponse().setBody(object.toString()),false);
		}
		JsonObject mismatch=JsonParser.parseString(startJson()).getAsJsonObject(); mismatch.addProperty("flow","B".repeat(42)+"A"); send(start(),new MockResponse().setBody(mismatch.toString()),false);
		for(String json:new String[]{startJson().replace("\"expires_in\":600","\"expires_in\":\"600\""),startJson().replace("\"expires_in\":600","\"expires_in\":600,\"expires_in\":600"),startJson().replace("\"expires_in\":600","\"expires_in\":600,\"token\":\"unexpected\"")})
			send(start(),new MockResponse().setBody(json),false);
	}
	@Test public void exchangeRejectsTokensInWrongShapeAndRedirectsWithoutFollowing() throws Exception{
		for(String json:new String[]{"{}",exchangeJson().replace("\"login\"","\"bind\""),exchangeJson().replace("\"action\":\"login\"","\"action\":\"login\",\"token\":\"duplicate\""),exchangeJson().replace("\"action\":\"login\"","\"action\":\"login\",\"nbw_token\":\"forbidden\""),exchangeJson().replace("\"id\":42","\"id\":9007199254740992")})
			send(exchange(),new MockResponse().setBody(json),false);
		send(start(),new MockResponse().setResponseCode(302).setHeader("Location","https://evil.test/stolen").setBody("{}"),false);
		assertNull(server.takeRequest(100,TimeUnit.MILLISECONDS));
	}
	@Test public void structuredFailuresUseOnlyLocalMessagesAndOnlyPendingOrRateLimitAreRetryable() throws Exception{
		for(String code:new String[]{"NBW_LOGIN_NOT_BOUND","NBW_LOGIN_REAUTH_REQUIRED","NBW_LOGIN_INVALID_FLOW","NBW_LOGIN_REJECTED","NBW_LOGIN_UNAVAILABLE","unknown raw jwt"}){
			ErrorResponse error=send(exchange(),new MockResponse().setResponseCode(code.equals("NBW_LOGIN_UNAVAILABLE")?503:403).setBody("{\"code\":\""+code+"\",\"error\":\"raw token must not be shown\"}"),false);
			assertTrue(error instanceof NBWLoginRequest.LoginError); assertFalse(((NBWLoginRequest.LoginError)error).retryable);
		}
		var pending=(NBWLoginRequest.LoginError)send(exchange(),new MockResponse().setResponseCode(409).setBody("{\"code\":\"NBW_LOGIN_PENDING\",\"error\":\"raw\"}"),false);
		assertTrue(pending.retryable); assertEquals(R.string.nbw_login_pending,pending.message);
	}
}
