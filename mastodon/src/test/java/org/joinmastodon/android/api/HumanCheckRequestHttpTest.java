package org.joinmastodon.android.api;

import static org.junit.Assert.*;

import com.google.gson.JsonParser;

import org.joinmastodon.android.BuildConfig;
import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.requests.auth.HumanCheckRequest;
import org.joinmastodon.android.api.requests.auth.HumanCheckRequest.Endpoint;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.api.ErrorResponse;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** Real anonymous transport, bounded reader, JSON adapter and fixed URL. Loopback is test-only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,28}, application=CompatibilityTestApplication.class, shadows=HumanCheckRequestHttpTest.LocalTransport.class)
public class HumanCheckRequestHttpTest{
	private static MockWebServer server;
	private static final String OP="550e8400-e29b-41d4-a716-446655440000", CID="A".repeat(43), VERIFIER="v".repeat(43);
	@Implements(MastodonAPIController.class) public static class LocalTransport{
		@Implementation public static OkHttpClient getSystemTrustSensitiveHttpClient(){
			return new OkHttpClient.Builder().addInterceptor(chain->{
				Request request=chain.request();
				if(!request.url().isHttps() || !HumanCheckRequest.DOMAIN.equals(request.url().host()) || request.url().query()!=null) throw new IOException("Wrong fixed origin");
				return chain.proceed(request.newBuilder().url(server.url(request.url().encodedPath())).build());
			}).build();
		}
	}
	@Before public void setup() throws Exception{ server=new MockWebServer(); server.start(); MastodonApp.context=RuntimeEnvironment.getApplication(); }
	@After public void close() throws Exception{ server.shutdown(); }
	private static HumanCheckRequest send(){ return HumanCheckRequest.sendCode("member@example.test",OP,CID); }
	private static String sent(){ return "{\"state\":\"sent\",\"operation_id\":\""+OP+"\",\"message\":\"验证码已发送\"}"; }
	private static String challenge(){ return "{\"challenge_id\":\""+CID+"\",\"challenge_url\":\""+HumanCheckRequest.pageUrl(CID)+"\",\"expires_in\":300}"; }
	private ErrorResponse run(HumanCheckRequest request, MockResponse response, boolean success, Set<String> keys) throws Exception{
		AtomicBoolean done=new AtomicBoolean(); AtomicReference<HumanCheckRequest.Reply> result=new AtomicReference<>(); AtomicReference<ErrorResponse> error=new AtomicReference<>();
		request.setCallback(new Callback<>(){
			@Override public void onSuccess(HumanCheckRequest.Reply reply){ result.set(reply); done.set(true); }
			@Override public void onError(ErrorResponse failure){ error.set(failure); done.set(true); }
		});
		assertTrue(request.isSensitiveRequest()); assertTrue(request.requiresSystemTrust());
		server.enqueue(response.setHeader("Content-Type","application/json")); request.execNoAuth(HumanCheckRequest.DOMAIN);
		RecordedRequest wire=server.takeRequest(10,TimeUnit.SECONDS); assertNotNull(wire);
		assertEquals(request.getURL().getEncodedPath(),wire.getPath()); assertEquals("POST",wire.getMethod());
		assertNull(wire.getHeader("Authorization")); assertNull(wire.getHeader("Cookie")); assertNull(wire.getHeader("Referer"));
		assertEquals("1",wire.getHeader("X-App-Human-Check")); assertEquals("MastodonAndroid/"+BuildConfig.VERSION_NAME,wire.getHeader("User-Agent"));
		assertEquals(String.valueOf(BuildConfig.VERSION_CODE),wire.getHeader("X-App-Version-Code")); assertTrue(wire.getHeader("Cache-Control").contains("no-store"));
		assertEquals(keys,JsonParser.parseString(wire.getBody().readUtf8()).getAsJsonObject().keySet());
		long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
		while(!done.get() && System.nanoTime()<until){ ShadowLooper.idleMainLooper(); Thread.sleep(5); }
		assertTrue("transport must settle",done.get());
		if(success){ assertNotNull(result.get()); assertNull(error.get()); }else{ assertNull(result.get()); assertTrue(error.get() instanceof HumanCheckRequest.Failure); }
		return error.get();
	}
	private static Set<String> sendKeys(){ return Set.of("email","type","operation_id","code_challenge"); }
	@Test public void everyEndpointUsesExactBodyWithoutExistingSiteBearerOrCookies() throws Exception{
		run(send(),new MockResponse().setBody(sent()),true,sendKeys());
		run(HumanCheckRequest.challenge(OP,VERIFIER),new MockResponse().setBody(challenge()),true,Set.of("operation_id","code_verifier"));
		run(HumanCheckRequest.complete(OP,VERIFIER,CID),new MockResponse().setBody(sent()),true,Set.of("operation_id","code_verifier","challenge_id"));
		run(HumanCheckRequest.status(OP,VERIFIER),new MockResponse().setBody("{\"state\":\"sent\",\"operation_id\":\""+OP+"\"}"),true,Set.of("operation_id","code_verifier"));
		run(HumanCheckRequest.cancel(OP,VERIFIER),new MockResponse().setBody("{\"cancelled\":true}"),true,Set.of("operation_id","code_verifier"));
		run(HumanCheckRequest.cancelChallenge(OP,VERIFIER,CID),new MockResponse().setBody("{\"cancelled\":true}"),true,Set.of("operation_id","code_verifier","challenge_id"));
	}
	@Test public void authenticatedExecutionAndAlternateDomainsAreImpossible(){
		try{ send().exec("existing-account"); fail(); }catch(UnsupportedOperationException expected){}
		try{ send().exec(HumanCheckRequest.DOMAIN,new Token()); fail(); }catch(UnsupportedOperationException expected){}
		try{ send().execNoAuth("abdl-space.top"); fail(); }catch(IllegalArgumentException expected){}
	}
	@Test public void requiredIsOnly403WithMatchingOperationAndExactExpiry() throws Exception{
		String required="{\"error\":\"raw forbidden text\",\"code\":\"HUMAN_CHECK_REQUIRED\",\"operation_id\":\""+OP+"\",\"expires_in\":300}";
		var failure=(HumanCheckRequest.Failure)run(send(),new MockResponse().setResponseCode(403).setBody(required),false,sendKeys());
		assertEquals("HUMAN_CHECK_REQUIRED",failure.code); assertEquals(OP,failure.operationId);
		for(String invalid:new String[]{required.replace(OP,"650e8400-e29b-41d4-a716-446655440000"),required.replace("300","\"300\""),required.replace("300","301"),required.replace("\"expires_in\":300","\"expires_in\":300,\"expires_in\":300")}){
			failure=(HumanCheckRequest.Failure)run(send(),new MockResponse().setResponseCode(403).setBody(invalid),false,sendKeys()); assertEquals("PROTOCOL",failure.code);
		}
	}
	@Test public void duplicateUnknownTypeNestedShapeTrailingDataFailClosed() throws Exception{
		for(String invalid:new String[]{"{}","[]",sent()+sent(),sent().replace("\"state\":\"sent\"","\"state\":\"sent\",\"state\":\"sent\""),sent().replace("\"sent\"","true"),sent().replace("\"sent\"","{\"token\":\"secret\"}"),sent().replace("\"state\"","\"token\""),sent().replace("\"state\":\"sent\",","\"secret\":\"unexpected\",\"state\":\"sent\",")})
			run(send(),new MockResponse().setBody(invalid),false,sendKeys());
	}
	@Test public void pageUrlIsByteExactAndRejectsAliasesSecretsAndOpaqueIdMismatch() throws Exception{
		for(String invalid:new String[]{challenge().replace("https://","http://"),challenge().replace(HumanCheckRequest.DOMAIN,HumanCheckRequest.DOMAIN+".evil"),challenge().replace("/page?","/page/?"),challenge().replace("page?challenge_id=","page?email=member@example.test&challenge_id="),challenge().replace("page?challenge_id=","page?token=secret&challenge_id="),challenge().replace("/page?","/page#fragment?"),challenge().replace("api.abdl-space.top/","api.abdl-space.top:443/"),challenge().replace("https://","https://user@")})
			run(HumanCheckRequest.challenge(OP,VERIFIER),new MockResponse().setBody(invalid),false,Set.of("operation_id","code_verifier"));
	}
	@Test public void challengeAcceptsRemainingLifetimeButRequiredKeepsExactInitialLifetime() throws Exception{
		for(int ttl:new int[]{1,120,299,300}) run(HumanCheckRequest.challenge(OP,VERIFIER),new MockResponse().setBody(challenge().replace("300",String.valueOf(ttl))),true,Set.of("operation_id","code_verifier"));
		for(String ttl:new String[]{"0","301","-1","1.0","\"120\""}) run(HumanCheckRequest.challenge(OP,VERIFIER),new MockResponse().setBody(challenge().replace("300",ttl)),false,Set.of("operation_id","code_verifier"));
		String required="{\"error\":\"verification required\",\"code\":\"HUMAN_CHECK_REQUIRED\",\"operation_id\":\""+OP+"\",\"expires_in\":299}";
		var failure=(HumanCheckRequest.Failure)run(send(),new MockResponse().setResponseCode(403).setBody(required),false,sendKeys());
		assertEquals("PROTOCOL",failure.code);
	}
	@Test public void oversizedChunkedOrContentLengthBodiesAreRejectedWithoutUnboundedRead() throws Exception{
		run(send(),new MockResponse().setBody("x".repeat(8193)),false,sendKeys());
		run(send(),new MockResponse().setChunkedBody(" ".repeat(8193)+sent(),257),false,sendKeys());
	}
	@Test public void redirectsAreNotFollowedAndNoOldEndpointFallback() throws Exception{
		run(send(),new MockResponse().setResponseCode(302).setHeader("Location","https://abdl-space.top/api/auth/send-code").setBody("{}"),false,sendKeys());
		assertNull(server.takeRequest(100,TimeUnit.MILLISECONDS));
	}
	@Test public void processingUnknownAndErrorMessagesUseOnlyLocalResources() throws Exception{
		run(send(),new MockResponse().setResponseCode(202).setBody("{\"state\":\"processing\",\"operation_id\":\""+OP+"\"}"),true,sendKeys());
		for(String code:new String[]{"HUMAN_CHECK_POLICY_CHANGED","HUMAN_CHECK_DELIVERY_UNKNOWN","HUMAN_CHECK_EXPIRED","HUMAN_CHECK_CANCELLED"}){
			var error=(HumanCheckRequest.Failure)run(send(),new MockResponse().setResponseCode(409).setBody("{\"error\":\"raw body token secret must not display\",\"code\":\""+code+"\"}"),false,sendKeys());
			assertFalse(MastodonApp.context.getString(error.message).contains("raw body"));
		}
		assertEquals(R.string.human_check_network,new HumanCheckRequest.Failure(0,"NETWORK").message);
	}
}
