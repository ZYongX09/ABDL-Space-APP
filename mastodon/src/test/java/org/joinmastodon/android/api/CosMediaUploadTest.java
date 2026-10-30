package org.joinmastodon.android.api;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.CookieJar;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.internal.http.BridgeInterceptor;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class)
public class CosMediaUploadTest{
	@Before public void setup(){ MastodonApp.context=RuntimeEnvironment.getApplication(); }
	@Test
	public void parsesSnakeCaseAuthorizationAndCopiesOnlyRequiredHeaders(){
		CosUploadAuthorization authorization=new CosUploadAuthorization();
		authorization.uploadId="upload-id";
		authorization.uploadUrl="https://bucket.cos.ap-shanghai.myqcloud.com/media/a.jpg";
		authorization.publicUrl="https://media.example.test/media/a.jpg";
		authorization.expiresAt=1234;
		authorization.requiredHeaders=new LinkedHashMap<>();
		authorization.requiredHeaders.put("Authorization", "signed");
		authorization.requiredHeaders.put("Content-Type", "image/jpeg");
		authorization.requiredHeaders.put("x-cos-forbid-overwrite", "true");

		Map<String, String> headers=CosMediaUpload.requiredHeaders(authorization);
		assertEquals(3, headers.size());
		assertEquals("signed", headers.get("Authorization"));
		assertFalse(headers.containsKey("Host"));
	}

	@Test
	public void aggregatesOriginalAndPreviewProgressMonotonically(){
		CosMediaUpload.Progress progress=new CosMediaUpload.Progress(900, 100);
		assertEquals(0, progress.updateOriginal(0));
		assertEquals(45, progress.updateOriginal(450));
		assertEquals(90, progress.updateOriginal(900));
		assertEquals(95, progress.updatePreview(50));
		assertEquals(100, progress.updatePreview(100));
		assertEquals(100, progress.updateOriginal(900));
	}

	@Test
	public void validatesAuthorizationContract(){
		CosUploadAuthorization authorization=new CosUploadAuthorization();
		authorization.uploadId="upload-id";
		authorization.uploadUrl="https://bucket.cos.ap-shanghai.myqcloud.com/media/a.jpg";
		authorization.publicUrl="https://media.example.test/media/a.jpg";
		authorization.requiredHeaders=Map.of("Authorization", "signed", "Content-Type", "image/jpeg", "x-cos-forbid-overwrite", "true");
		assertTrue(CosMediaUpload.isValidAuthorization(authorization));
		authorization.requiredHeaders=Map.of("Authorization", "signed");
		assertFalse(CosMediaUpload.isValidAuthorization(authorization));
	}

	private static final RequestBody PIXELS=RequestBody.create(MediaType.get("image/png"), new byte[]{1, 2, 3});
	private static CosUploadAuthorization authorization(){
		CosUploadAuthorization auth=new CosUploadAuthorization();
		auth.uploadId="fixture-upload";
		auth.uploadUrl="https://cos.example.test/generic/42/2026-10-30/fixture.png";
		auth.publicUrl="https://media.example.test/generic/42/2026-10-30/fixture.png";
		auth.requiredHeaders=Map.of("Authorization", "fixture-signature", "Content-Type", "image/png", "x-cos-forbid-overwrite", "true");
		return auth;
	}
	private static OkHttpClient responseClient(int status, String body, String requestId){
		return new OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false).addInterceptor(chain->new Response.Builder()
				.request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("unsafe-private-reason-token")
				.header("x-cos-request-id", requestId).body(ResponseBody.create(MediaType.get("text/plain"), body)).build()).build();
	}
	private static void runStage(CosMediaUpload.Stage stage, OkHttpClient client, CosMediaUpload.CallListener listener) throws IOException{
		switch(stage){
			case AUTHORIZE -> CosMediaUpload.authorize(client, "api.example.test", "private-token", "generic", PIXELS, 1, 1, listener);
			case PUT -> CosMediaUpload.put(client, authorization(), PIXELS, listener);
			case COMPLETE -> CosMediaUpload.complete(client, "api.example.test", "private-token", "fixture-upload", null, listener);
		}
	}
	private static CosMediaUpload.UploadException failure(CosMediaUpload.Stage stage, int status, String text) throws Exception{
		try{
			runStage(stage, responseClient(status, text, "fixture-request_123"), call->{});
			throw new AssertionError("Upload unexpectedly succeeded");
		}catch(CosMediaUpload.UploadException error){
			assertEquals(stage, error.stage); assertEquals(status, error.status);
			if(status>=300) assertEquals("fixture-request_123", error.requestId);
			assertFalse(error.getMessage().contains("private")); assertFalse(error.toString().contains("https://"));
			return error;
		}
	}

	@Test public void xml403PreservesPutStatusCosCodeAndSafeRequestId() throws Exception{
		CosMediaUpload.UploadException error=failure(CosMediaUpload.Stage.PUT, 403,
				"<Error><Code>SignatureDoesNotMatch</Code><Message>private-token https://signed-url/?secret</Message><RequestId>xml-request_42</RequestId></Error>");
		assertEquals(CosMediaUpload.Category.PERMISSION, error.category);
		assertEquals("SignatureDoesNotMatch", error.cosCode); assertNull(error.code); assertNull(error.getCause());
		OkHttpClient client=responseClient(403, "<Error><Code>AccessDenied</Code><RequestId>xml-request_42</RequestId></Error>", "https://unsafe/token");
		try{ runStage(CosMediaUpload.Stage.PUT, client, call->{}); fail(); }
		catch(CosMediaUpload.UploadException xml){ assertEquals("xml-request_42", xml.requestId); assertEquals("AccessDenied", xml.cosCode); }
	}

	@Test public void htmlMalformedEmptyAndNonStringErrorBodiesNeverMaskHttpAcrossAllStages() throws Exception{
		for(CosMediaUpload.Stage stage:CosMediaUpload.Stage.values()){
			for(String body:new String[]{"<html>private-token gateway failure</html>", "{malformed private-token", "", "null", "[]", "\"private-token\"",
					"{\"code\":null,\"error\":{\"token\":\"private-token\"}}", "{\"code\":42,\"error\":false}", "{\"code\":[],\"error\":[1,2]}",
					"{\"code\":\"private-token\",\"request_id\":\"https://unsafe/signature\"}", "{\"code\":\"cos_head_failed\"} private-token"}){
				CosMediaUpload.UploadException error=failure(stage, 502, body);
				assertNull(error.code); assertNull(error.cosCode); assertEquals(CosMediaUpload.Category.SERVER, error.category);
			}
		}
	}

	@Test public void complete409422502RetainsAllowlistedCodeAndUsesSafeCategories() throws Exception{
		assertEquals(CosMediaUpload.Category.EXPIRED, failure(CosMediaUpload.Stage.COMPLETE, 409, "{\"code\":\"upload_expired\",\"error\":\"private-token\"}").category);
		assertEquals(CosMediaUpload.Category.CONFLICT, failure(CosMediaUpload.Stage.COMPLETE, 409, "{\"code\":\"upload_conflict\"}").category);
		for(String code:new String[]{"verification_header_missing", "size_mismatch", "type_mismatch"}){
			CosMediaUpload.UploadException error=failure(CosMediaUpload.Stage.COMPLETE, 422, "{\"code\":\""+code+"\"}");
			assertEquals(code, error.code); assertEquals(CosMediaUpload.Category.VERIFICATION, error.category);
		}
		CosMediaUpload.UploadException head=failure(CosMediaUpload.Stage.COMPLETE, 502, "{\"code\":\"cos_head_failed\"}");
		assertEquals("cos_head_failed", head.code); assertEquals(CosMediaUpload.Category.VERIFICATION, head.category);
	}

	@Test public void oversizedAndDeepErrorBodiesAreBoundedAndDtdCannotTriggerIo() throws Exception{
		assertNull(failure(CosMediaUpload.Stage.PUT, 403, "<Error><Code>AccessDenied</Code><Message>"+"x".repeat(20000)+"</Message></Error>").cosCode);
		assertEquals(502, failure(CosMediaUpload.Stage.COMPLETE, 502, "{\"error\":"+"[".repeat(2000)+"0"+"]".repeat(2000)+"}").status);
		CosMediaUpload.UploadException dtd=failure(CosMediaUpload.Stage.PUT, 403, "<!DOCTYPE Error [<!ENTITY x SYSTEM 'https://trap.example.test/private'>]><Error><Code>&x;</Code></Error>");
		assertNull(dtd.cosCode); assertNotNull(dtd.getCause());
		AtomicReference<Long> read=new AtomicReference<>(0L);
		BufferedSource source=Okio.buffer(new ForwardingSource(new Buffer().writeUtf8("x".repeat(100000))){
			@Override public long read(Buffer sink, long byteCount) throws IOException{
				long count=super.read(sink, byteCount); if(count>0) read.set(read.get()+count); return count;
			}
		});
		OkHttpClient client=new OkHttpClient.Builder().addInterceptor(chain->new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
				.code(502).message("unsafe-private-reason").body(ResponseBody.create(null, -1, source)).build()).build();
		try{ runStage(CosMediaUpload.Stage.COMPLETE, client, call->{}); fail(); }
		catch(CosMediaUpload.UploadException error){ assertEquals(502, error.status); assertTrue(read.get()<=24*1024); }
	}

	@Test public void transportCauseIsInternalAndCancellationListenerKeepsOriginalType() throws Exception{
		IOException transport=new IOException("private-token https://signed-url/?credential");
		OkHttpClient client=new OkHttpClient.Builder().addInterceptor(chain->{ throw transport; }).build();
		try{ runStage(CosMediaUpload.Stage.PUT, client, call->{}); fail(); }
		catch(CosMediaUpload.UploadException error){ assertEquals(CosMediaUpload.Stage.PUT, error.stage); assertEquals(0, error.status); assertEquals(CosMediaUpload.Category.NETWORK, error.category); assertSame(transport, error.getCause()); assertFalse(error.getMessage().contains("private")); }
		try{ runStage(CosMediaUpload.Stage.PUT, client, call->call.cancel()); fail(); }
		catch(IOException error){ assertFalse(error instanceof CosMediaUpload.UploadException); }
		// A session owner may throw its own runtime or sneaky IOException from the listener; it is never wrapped.
		RuntimeException revoked=new RuntimeException("test-session-revoked");
		try{ runStage(CosMediaUpload.Stage.AUTHORIZE, client, call->{ throw revoked; }); fail(); }
		catch(RuntimeException error){ assertSame(revoked, error); }
	}

	@Test public void malformedSuccessResponseIsStructuredAtItsActualHttpStage() throws Exception{
		for(CosMediaUpload.Stage stage:new CosMediaUpload.Stage[]{CosMediaUpload.Stage.AUTHORIZE, CosMediaUpload.Stage.COMPLETE}){
			for(String body:new String[]{"", "null", "{malformed", "[]"}){
				CosMediaUpload.UploadException error=failure(stage, 200, body);
				assertEquals(CosMediaUpload.Category.INVALID_RESPONSE, error.category); assertNotNull(error.getCause());
			}
		}
	}

	@Test public void redirectsRemainStructuredAndDoNotDispatchAnotherRequest() throws Exception{
		for(CosMediaUpload.Stage stage:CosMediaUpload.Stage.values()){
			CosMediaUpload.UploadException error=failure(stage, 307, "<html>private-token</html>");
			assertEquals(CosMediaUpload.Category.REDIRECT, error.category);
		}
	}

	@Test public void legacyOriginalPreviewFlowKeepsPurposesSignedHeadersAndPreviewLinkWireContract() throws Exception{
		java.util.List<Request> requests=new java.util.ArrayList<>();
		OkHttpClient client=new OkHttpClient.Builder().addInterceptor(chain->{
			Request request=chain.request(); requests.add(request);
			String path=request.url().encodedPath();
			String text=path.endsWith("/authorize") ? "{\"upload_id\":\"original\",\"upload_url\":\"https://cos.example.test/media/original/42/original.png\",\"public_url\":\"https://media.example.test/original.png\",\"required_headers\":{\"Authorization\":\"fixture-signature\",\"Content-Type\":\"image/png\",\"x-cos-forbid-overwrite\":\"true\"}}"
					: path.endsWith("/complete") ? "{\"id\":\"original\",\"type\":\"image\",\"url\":\"https://media.example.test/original.png\"}" : "";
			return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create(MediaType.get("application/json"), text)).build();
		}).build();
		CosUploadAuthorization original=CosMediaUpload.authorize(client, "api.example.test", "private-token", "status_original", PIXELS, 1000, 800, call->{});
		CosMediaUpload.authorize(client, "api.example.test", "private-token", "status_preview", PIXELS, 540, 432, call->{});
		CosMediaUpload.put(client, original, PIXELS, call->{});
		CosMediaUpload.complete(client, "api.example.test", "private-token", "preview", null, call->{});
		assertEquals("https://media.example.test/original.png", CosMediaUpload.complete(client, "api.example.test", "private-token", "original", "preview", call->{}).url);
		Buffer first=new Buffer(); requests.get(0).body().writeTo(first);
		com.google.gson.JsonObject firstJson=MastodonAPIController.gsonWithoutDeserializer.fromJson(first.readUtf8(), com.google.gson.JsonObject.class);
		assertEquals("status_original", firstJson.get("purpose").getAsString()); assertEquals("image/png", firstJson.get("mimeType").getAsString()); assertEquals(3, firstJson.get("declaredSize").getAsInt());
		Buffer preview=new Buffer(); requests.get(1).body().writeTo(preview); assertTrue(preview.readUtf8().contains("status_preview"));
		assertEquals("fixture-signature", requests.get(2).header("Authorization")); assertNull(requests.get(2).header("Host"));
		Buffer complete=new Buffer(); requests.get(4).body().writeTo(complete); assertEquals("{\"preview_upload_id\":\"preview\"}", complete.readUtf8());
		assertEquals("Bearer private-token", requests.get(4).header("Authorization"));
	}

	@Test public void realBackendGoldensMatchActualOkHttpBridgeHostLengthAndMimeForDifferentGenericDirectories() throws Exception{
		// Generated by createCosPutAuthorization at backend 6842ebe, independently checked with Node crypto.
		// Source/command/output: evidence upload-backend-6842ebe/src/routes/novel-cover-contract.test.ts and upload-signing-goldens.json.
		String host="novel-cover-fixture-123.cos.ap-shanghai.myqcloud.com";
		String[][] goldens={
				{"generic/42/2026-10-30/00000000-0000-4000-8000-000000000042.png", "image/png", "1793318400;1793318700", "e9b478e93b8a617ed32f356071c1bb84d143a372"},
				{"generic/7/2026-09-01/00000000-0000-4000-8000-000000000007.jpg", "image/jpeg", "1788220800;1788221100", "2e83c3a00ad23f5b5c42e4c4e60989820d3b340f"},
		};
		for(String[] fixture:goldens){
			CosUploadAuthorization auth=authorization(); auth.uploadUrl="https://"+host+"/"+fixture[0];
			String signed="q-sign-algorithm=sha1&q-ak=AKIDNOVELCOVERFIXTURE&q-sign-time="+fixture[2]+"&q-key-time="+fixture[2]+"&q-header-list=content-type;host;x-cos-forbid-overwrite&q-url-param-list=&q-signature="+fixture[3];
			auth.requiredHeaders=Map.of("Authorization", signed, "Content-Type", fixture[1], "x-cos-forbid-overwrite", "true");
			RequestBody bytes=RequestBody.create(MediaType.get(fixture[1]), new byte[]{1, 2, 3});
			AtomicReference<Request> wire=new AtomicReference<>();
			OkHttpClient client=new OkHttpClient.Builder().addInterceptor(chain->{
				Request request=chain.request();
				// Exercise OkHttp's real bridge, stopping at the network boundary: no TLS, socket or mock Host rewrite.
				Interceptor.Chain terminal=(Interceptor.Chain)Proxy.newProxyInstance(Interceptor.Chain.class.getClassLoader(), new Class<?>[]{Interceptor.Chain.class}, (proxy, method, args)->{
					if(method.getName().equals("request")) return request;
					if(method.getName().equals("proceed")){
						wire.set((Request)args[0]); return new Response.Builder().request((Request)args[0]).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(ResponseBody.create(null, new byte[0])).build();
					}
					throw new AssertionError("Unexpected bridge call "+method.getName());
				});
				return new BridgeInterceptor(CookieJar.NO_COOKIES).intercept(terminal);
			}).build();
			CosMediaUpload.put(client, auth, bytes, call->{});
			Request actual=wire.get(); assertNotNull(actual);
			assertEquals(host, actual.header("Host")); assertEquals("3", actual.header("Content-Length")); assertNull(actual.header("Transfer-Encoding"));
			assertEquals(fixture[1], actual.header("Content-Type")); assertEquals("true", actual.header("x-cos-forbid-overwrite")); assertEquals(signed, actual.header("Authorization"));
			String canonical="content-type="+fixture[1].replace("/", "%2F")+"&host="+actual.header("Host")+"&x-cos-forbid-overwrite="+actual.header("x-cos-forbid-overwrite");
			String http="put\n"+actual.url().encodedPath()+"\n\n"+canonical+"\n";
			String digest=hex(MessageDigest.getInstance("SHA-1").digest(http.getBytes(StandardCharsets.UTF_8)));
			assertEquals(fixture[3], hmac(hmac("novel-cover-signing-fixture-only", fixture[2]), "sha1\n"+fixture[2]+"\n"+digest+"\n"));
		}
	}
	private static String hmac(String key, String value) throws Exception{
		Mac mac=Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1")); return hex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
	}
	private static String hex(byte[] bytes){
		StringBuilder text=new StringBuilder(); for(byte value:bytes) text.append(String.format(java.util.Locale.ROOT, "%02x", value&255)); return text.toString();
	}
}
