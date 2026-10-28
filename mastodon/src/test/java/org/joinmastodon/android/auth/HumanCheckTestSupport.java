package org.joinmastodon.android.auth;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebView;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.joinmastodon.android.HumanCheckActivity;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.requests.auth.HumanCheckRequest;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.junit.Assert;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import me.grishka.appkit.api.APIRequest;
import me.grishka.appkit.api.Callback;
import okio.Buffer;

/** Only transport and WebView capability are controlled. Coordinator/requests/entry/Activity are real. */
public final class HumanCheckTestSupport{
	public static final String EMAIL="preserved@example.test", CID="A".repeat(43);
	public static final List<HumanCheckRequest> sent=new ArrayList<>();
	public static WebViewCompat.WebMessageListener bridge;
	public static WebView bridgedView;
	public static boolean supported=true;
	public static int factoryCalls;
	@Implements(HumanCheckRequest.class) public static class Requests{
		@RealObject HumanCheckRequest request;
		@Implementation public HumanCheckRequest execNoAuth(String domain){ Assert.assertEquals(HumanCheckRequest.DOMAIN, domain); sent.add(request); return request; }
	}
	@Implements(AccountSessionManager.class) public static class Sessions{
		public static String active;
		public static AccountSession session;
		@Implementation public String getLastActiveAccountID(){ return active; }
		@Implementation public AccountSession tryGetAccount(String id){ return session; }
	}
	@Implements(WebViewFeature.class) public static class Features{
		@Implementation public static boolean isFeatureSupported(String name){ return supported; }
	}
	@Implements(WebViewCompat.class) public static class WebMessages{
		@Implementation public static void addWebMessageListener(WebView view, String name, Set<String> origins, WebViewCompat.WebMessageListener listener){
			Assert.assertEquals("ABDLHumanCheck", name); Assert.assertEquals(Set.of(HumanCheckRequest.ORIGIN), origins);
			bridge=listener; bridgedView=view;
		}
		@Implementation public static void removeWebMessageListener(WebView view, String name){ bridge=null; bridgedView=null; }
	}
	@Implements(HumanCheckActivity.class) public static class Factory extends org.robolectric.shadows.ShadowActivity{
		@RealObject HumanCheckActivity activity;
		@Implementation protected WebView createChallengeWebView(){ factoryCalls++; return new WebView(activity); }
	}
	public static Field field(Class<?> type, String name) throws Exception{ Field f=type.getDeclaredField(name); f.setAccessible(true); return f; }
	@SuppressWarnings("unchecked") public static Callback<HumanCheckRequest.Reply> callback(HumanCheckRequest request) throws Exception{
		return (Callback<HumanCheckRequest.Reply>)field(APIRequest.class, "callback").get(request);
	}
	public static JsonObject body(HumanCheckRequest request) throws Exception{ Buffer buffer=new Buffer(); request.getRequestBody().writeTo(buffer); return JsonParser.parseString(buffer.readUtf8()).getAsJsonObject(); }
	public static HumanCheckRequest last(){ return sent.get(sent.size()-1); }
	public static String op(HumanCheckRequest request) throws Exception{ return body(request).get("operation_id").getAsString(); }
	public static void reply(HumanCheckRequest request, String json) throws Exception{ callback(request).onSuccess(HumanCheckRequest.parse(json)); }
	public static void required(HumanCheckRequest request) throws Exception{
		// Use the real bounded protocol reader/error parser, not a fabricated pass/failure state.
		var response=new okhttp3.Response.Builder().request(new okhttp3.Request.Builder().url(request.getURL().toString()).build())
				.protocol(okhttp3.Protocol.HTTP_1_1).code(403).message("Forbidden")
				.body(okhttp3.ResponseBody.create(okhttp3.MediaType.parse("application/json"), "{\"error\":\"raw must not display\",\"code\":\"HUMAN_CHECK_REQUIRED\",\"operation_id\":\""+op(request)+"\",\"expires_in\":300}")).build();
		var parsed=HumanCheckRequest.readResponse(response, request.endpoint, op(request));
		var ctor=HumanCheckRequest.Failure.class.getDeclaredConstructor(int.class,String.class,String.class,int.class); ctor.setAccessible(true);
		callback(request).onError(ctor.newInstance(403, parsed.code, parsed.operationId, parsed.expiresIn));
	}
	public static void challenge(HumanCheckRequest request) throws Exception{ reply(request, "{\"challenge_id\":\""+CID+"\",\"challenge_url\":\""+HumanCheckRequest.pageUrl(CID)+"\",\"expires_in\":300}"); }
	public static void sent(HumanCheckRequest request) throws Exception{ reply(request, "{\"state\":\"sent\",\"operation_id\":\""+op(request)+"\",\"message\":\"验证码已发送\"}"); }
	public static void status(HumanCheckRequest request, String state) throws Exception{ reply(request, "{\"state\":\""+state+"\",\"operation_id\":\""+op(request)+"\"}"); }
	public static void cancelled(HumanCheckRequest request) throws Exception{ reply(request,"{\"cancelled\":true}"); }
	public static void event(String event){
		Assert.assertNotNull(bridge);
		bridge.onPostMessage(bridgedView, new androidx.webkit.WebMessageCompat("{\"event\":\""+event+"\",\"challenge_id\":\""+CID+"\"}"), Uri.parse(HumanCheckRequest.ORIGIN), true, null);
	}
	public static void reset() throws Exception{
		HumanCheckCoordinator coordinator=HumanCheckCoordinator.getInstance();
		// Simulate a fresh process between tests; no field is used to advance verification or send success.
		field(HumanCheckCoordinator.class,"pending").set(coordinator,null);
		sent.clear(); bridge=null; bridgedView=null; supported=true; factoryCalls=0; Sessions.active=null; Sessions.session=null;
	}
	private HumanCheckTestSupport(){}
}
