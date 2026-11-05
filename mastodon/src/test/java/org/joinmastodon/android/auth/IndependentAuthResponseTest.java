package org.joinmastodon.android.auth;

import static org.junit.Assert.*;

import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.gson.JsonParser;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.ServiceResourcePause;
import org.joinmastodon.android.api.requests.albums.AlbumRequest;
import org.joinmastodon.android.api.requests.nbw.NBWLoginRequest;
import org.joinmastodon.android.api.requests.sponsors.SponsorRequest;
import org.joinmastodon.android.api.requests.translation.TranslationRequest;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;

/** Synthetic responses exercise adapters only; no HTTP, account login or core-owner fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class)
public class IndependentAuthResponseTest{
	private static final String PAUSE="{\"error\":\"paused\",\"code\":\"D1_DAILY_QUOTA_EXHAUSTED\"}";
	private static Response response(int status, String url, ResponseBody body){
		return new Response.Builder().request(new Request.Builder().url(url).build()).protocol(Protocol.HTTP_1_1)
				.code(status).message("test").body(body).build();
	}
	private static final class TrackedBody extends ResponseBody{
		boolean closed;
		long bytesRead;
		final BufferedSource source;
		TrackedBody(String text){
			source=Okio.buffer(new ForwardingSource(new Buffer().writeUtf8(text)){
				@Override public long read(Buffer sink, long count) throws IOException{ long read=super.read(sink,count); if(read>0) bytesRead+=read; return read; }
				@Override public void close() throws IOException{ closed=true; super.close(); }
			});
		}
		@Override public MediaType contentType(){ return MediaType.parse("application/json"); }
		@Override public long contentLength(){ return -1; }
		@Override public BufferedSource source(){ return source; }
	}
	public static final class AuthResult{ public String token; }

	@Test public void trustedPauseIsReadOnceClosedAndNotASecondBodyConsumption() throws Exception{
		TrackedBody body=new TrackedBody(PAUSE);
		var result=IndependentAuthResponse.read(response(503,"https://abdl-space.top/api/auth/login",body));
		assertEquals(PAUSE,result.text); assertNotNull(result.pause); assertTrue(body.closed);
		assertEquals(PAUSE.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,body.bytesRead);
	}
	@Test public void errorBoundDoesNotApplyToSuccessfulTokenEnvelope() throws Exception{
		String token="a".repeat(40*1024);
		TrackedBody body=new TrackedBody("{\"token\":\""+token+"\",\"code\":\"D1_DAILY_QUOTA_EXHAUSTED\"}");
		var result=IndependentAuthResponse.read(response(200,"https://abdl-space.top/api/auth/login",body));
		assertNull(result.pause); assertEquals(token,result.parse(AuthResult.class).token); assertTrue(body.closed);
	}
	@Test public void oversizedChunkedErrorsAreBoundedClosedAndNeverParsed() throws Exception{
		TrackedBody body=new TrackedBody(PAUSE+" ".repeat(128*1024));
		var result=IndependentAuthResponse.read(response(503,"https://abdl-space.top/api/auth/login",body));
		assertEquals("",result.text); assertNull(result.pause); assertTrue(body.closed);
		assertTrue("bounded buffered read",body.bytesRead<=40*1024);
	}
	@Test public void untrustedOrSuccessfulCodeAndHtmlCannotBecomePauseOrRawLoginMessage() throws Exception{
		for(String url:new String[]{"http://abdl-space.top/api/auth/login","https://untrusted.example/api/auth/login"}){
			assertNull(IndependentAuthResponse.read(response(503,url,new TrackedBody(PAUSE))).pause);
		}
		for(String payload:new String[]{"<html>upstream failed</html>","{broken", "{\"error\":{\"html\":\"secret\"}}", "{\"error\":\"<html>secret</html>\"}"}){
			var result=IndependentAuthResponse.read(response(503,"https://abdl-space.top/api/auth/login",new TrackedBody(payload)));
			assertNull(result.pause); AuthResult parsed=result.parse(AuthResult.class); assertTrue(parsed==null || parsed.token==null); assertEquals("safe fallback",result.errorMessage("safe fallback"));
		}
		var ordinary=IndependentAuthResponse.read(response(429,"https://abdl-space.top/api/auth/login",new TrackedBody("{\"error\":\"Try later\"}")));
		assertNull(ordinary.pause); assertEquals("Try later",ordinary.errorMessage("fallback"));
	}
	@Test public void typedTranslationAlbumAndLoginMetadataRetainOriginalErrorsAndReadableBinding() throws Exception{
		var context=RuntimeEnvironment.getApplication();
		ServiceResourcePause pause=IndependentAuthResponse.read(response(503,"https://api.abdl-space.top/api/v1/test",new TrackedBody(PAUSE))).pause;
		assertNotNull(pause);
		var error=new TranslationRequest.TranslationError("translation_quota_exhausted",429);
		error.serviceResourcePause=pause;
		LinearLayout root=new LinearLayout(context); TextView text=new TextView(context); text.setId(R.id.error_text); root.addView(text);
		error.bindErrorView(root); assertEquals(pause.message(context),text.getText().toString()); assertEquals(429,error.httpStatus);
		assertEquals(R.string.post_translation_quota_exhausted,error.localizedMessage());
		error.serviceResourcePause=null; error.bindErrorView(root);
		assertEquals(context.getString(R.string.post_translation_quota_exhausted),text.getText().toString());
		var album=new AlbumRequest.AlbumError(JsonParser.parseString("{\"error\":\"Sponsor notice\",\"code\":\"notice_required\",\"notice_version\":7}").getAsJsonObject(),403);
		album.serviceResourcePause=pause; assertTrue(album instanceof SponsorRequest.SponsorError);
		assertEquals("notice_required",album.code); assertEquals(Integer.valueOf(7),album.noticeVersion); assertEquals(403,album.httpStatus);
		assertEquals(pause.message(context),album.localizedMessage(context));
		var login=new NBWLoginRequest.LoginError(503,"D1_DAILY_QUOTA_EXHAUSTED"); login.setServiceResourcePause(pause);
		assertSame(pause,ServiceResourcePause.fromError(login)); assertFalse(login.retryable);
	}
}
