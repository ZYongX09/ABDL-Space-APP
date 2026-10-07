package org.joinmastodon.android.albums;

import static org.junit.Assert.*;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Instance;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.model.albums.AlbumModels;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/** Every request is intercepted locally; an unexpected origin fails instead of using the network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AlbumUploaderBehaviorTest{
	private static final String COS="fixture-123.cos.ap-shanghai.myqcloud.com";
	private AccountSessionManager manager;
	private Map<String, AccountSession> sessions;
	private Map<String, Instance> instances;
	private Instance previousInstance;
	private AccountSession session;
	private String accountId, previousActive;
	private OkHttpClient originalClient;
	private File source;
	private final List<AlbumUploader> tasks=new ArrayList<>();
	private final List<String> authorizeBodies=new ArrayList<>();
	private final Map<String, byte[]> puts=new HashMap<>();
	private final Set<String> completed=new java.util.HashSet<>();
	private int putCount, publishCount;
	private boolean failCompleteOnce, losePublishOnce, published;
	private CountDownLatch quotaEntered, releaseQuota;

	private static Field field(Class<?> type, String name) throws Exception{ Field value=type.getDeclaredField(name); value.setAccessible(true); return value; }
	@Before @SuppressWarnings("unchecked") public void setup() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication();
		manager=AccountSessionManager.getInstance(); sessions=(Map<String, AccountSession>)field(AccountSessionManager.class, "sessions").get(manager);
		previousActive=manager.getLastActiveAccountID();
		var constructor=AccountSession.class.getDeclaredConstructor(); constructor.setAccessible(true); session=constructor.newInstance();
		session.domain="album-uploader-offline.example.test"; session.self=new Account(); session.self.id="42";
		session.token=new Token(); session.token.accessToken="synthetic-uploader-token"; accountId=session.getID();
		sessions.put(accountId, session); field(AccountSessionManager.class, "lastActiveAccountID").set(manager, accountId);
		instances=(Map<String, Instance>)field(AccountSessionManager.class, "instances").get(manager);
		org.joinmastodon.android.model.InstanceV1 fixture=new org.joinmastodon.android.model.InstanceV1();
		fixture.uri=session.domain; fixture.normalizedUri=session.domain; fixture.version="4.5.3"; fixture.title="Offline album fixture"; fixture.description="Local uploader tests"; fixture.email="fixture@example.test";
		fixture.configuration=new Instance.Configuration(); fixture.configuration.mediaAttachments=new Instance.MediaAttachmentsConfiguration();
		fixture.configuration.mediaAttachments.imageMatrixLimit=2_073_600; fixture.configuration.mediaAttachments.imageSizeLimit=10*1024*1024;
		fixture.configuration.mediaAttachments.supportedMimeTypes=List.of("image/jpeg", "image/png", "image/webp", "image/gif");
		previousInstance=instances.put(session.domain, fixture);
		assertSame(fixture, session.getInstanceInfo());
		// Robolectric's MIME map starts empty. Real Android resolves staged .jpg files here.
		org.robolectric.shadows.ShadowMimeTypeMap mime=org.robolectric.shadow.api.Shadow.extract(android.webkit.MimeTypeMap.getSingleton());
		mime.addExtensionMimeTypeMapping("jpg", "image/jpeg"); mime.addExtensionMimeTypeMapping("jpeg", "image/jpeg");
		mime.addExtensionMimeTypeMapping("png", "image/png"); mime.addExtensionMimeTypeMapping("webp", "image/webp"); mime.addExtensionMimeTypeMapping("gif", "image/gif");
		assertEquals("image/jpeg", android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension("jpg"));
		originalClient=MastodonAPIController.getHttpClient();
		field(MastodonAPIController.class, "httpClient").set(null, new OkHttpClient.Builder().addInterceptor(chain->respond(chain.request())).build());
		source=File.createTempFile("album_source", ".jpg", MastodonApp.context.getCacheDir());
		Bitmap bitmap=Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.rgb(45, 120, 180));
		try(FileOutputStream output=new FileOutputStream(source)){ assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)); } bitmap.recycle();
	}
	@After public void cleanup() throws Exception{
		if(releaseQuota!=null) releaseQuota.countDown();
		for(AlbumUploader task:tasks){ task.setListener(null); task.cancel(); long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3); while(task.isRunning() && System.nanoTime()<deadline) Thread.sleep(5); if(!task.isRunning()) task.discard(); }
		if(originalClient!=null) field(MastodonAPIController.class, "httpClient").set(null, originalClient);
		if(instances!=null && session!=null){ if(previousInstance==null) instances.remove(session.domain); else instances.put(session.domain, previousInstance); }
		if(sessions!=null) sessions.remove(accountId); if(manager!=null) field(AccountSessionManager.class, "lastActiveAccountID").set(manager, previousActive);
		if(source!=null) source.delete();
	}
	private Response respond(Request request) throws IOException{
		String path=request.url().encodedPath();
		if(COS.equals(request.url().host())){
			assertEquals("PUT", request.method()); assertNull(request.header("User-Agent"));
			assertEquals("private", request.header("x-cos-acl")); assertEquals("true", request.header("x-cos-forbid-overwrite"));
			Buffer buffer=new Buffer(); request.body().writeTo(buffer); byte[] bytes=buffer.readByteArray();
			assertEquals(bytes.length, Long.parseLong(request.header("Content-Length")));
			try{ assertEquals(Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes)), request.header("Content-MD5")); }catch(Exception impossible){ throw new AssertionError(impossible); }
			putCount++; puts.put(path.substring(path.lastIndexOf('/')+1), bytes); return json(request, 200, "{}");
		}
		if(!session.domain.equals(request.url().host())) throw new IOException("Unexpected outbound host");
		assertTrue(path.startsWith("/api/v1/albums")); assertEquals("Bearer synthetic-uploader-token", request.header("Authorization")); assertEquals("no-store", request.header("Cache-Control"));
		if(path.endsWith("/quota")){
			if(quotaEntered!=null){ quotaEntered.countDown(); try{ releaseQuota.await(5, TimeUnit.SECONDS); }catch(InterruptedException canceled){ Thread.currentThread().interrupt(); throw new IOException("Canceled"); } }
			return json(request, 200, "{\"limit_bytes\":1073741824,\"remaining_bytes\":1073741824,\"sponsor_active\":true,\"original_upload_allowed\":true}");
		}
		if(path.endsWith("/authorize")){
			Buffer body=new Buffer(); request.body().writeTo(body); String text=body.readUtf8(); authorizeBodies.add(text);
			JsonObject input=JsonParser.parseString(text).getAsJsonObject(); JsonArray uploads=new JsonArray();
			for(var item:input.getAsJsonArray("photos")){
				JsonObject photo=item.getAsJsonObject(); String client=photo.get("client_id").getAsString();
				for(var entry:photo.getAsJsonArray("variants")){
					JsonObject variant=entry.getAsJsonObject(); String kind=variant.get("kind").getAsString(); String id="upload-"+client+"-"+kind;
					long now=System.currentTimeMillis()/1000;
					JsonObject upload=new JsonObject(); upload.addProperty("photo_id", "photo-"+client); upload.addProperty("client_id", client); upload.addProperty("kind", kind); upload.addProperty("upload_id", id);
					upload.addProperty("upload_url", "https://"+COS+"/albums/42/batch/"+id); upload.addProperty("expires_at", now+900);
					JsonObject headers=new JsonObject(); headers.addProperty("Content-Length", variant.get("size").getAsString()); headers.addProperty("Content-MD5", variant.get("content_md5").getAsString()); headers.addProperty("Content-Type", variant.get("mime_type").getAsString());
					headers.addProperty("x-cos-acl", "private"); headers.addProperty("x-cos-forbid-overwrite", "true"); headers.addProperty("Authorization", signature(now));
					upload.add("required_headers", headers); uploads.add(upload);
				}
			}
			JsonObject response=new JsonObject(); response.addProperty("batch_id", "batch"); response.add("uploads", uploads); return json(request, 200, response.toString());
		}
		if(path.contains("/uploads/") && path.endsWith("/complete")){
			if(failCompleteOnce){ failCompleteOnce=false; return json(request, 503, "{\"error\":\"Offline completion\",\"code\":\"upload_verification_unavailable\"}"); }
			String id=path.substring(path.indexOf("/uploads/")+9, path.length()-9);
			if(!puts.containsKey(id)) return json(request, 409, "{\"error\":\"Missing object\",\"code\":\"upload_object_missing\"}");
			completed.add(id); return json(request, 200, "{\"complete\":true}");
		}
		if(path.endsWith("/publish")){
			publishCount++;
			if(!published && (puts.isEmpty() || completed.size()!=puts.size() || completed.size()<2)) return json(request, 409, "{\"error\":\"Incomplete batch\",\"code\":\"album_batch_incomplete\"}");
			published=true;
			if(losePublishOnce){ losePublishOnce=false; throw new IOException("Publication response lost"); }
			return json(request, 200, "{\"album_id\":\"album\",\"batch_id\":\"batch\",\"post_id\":null}");
		}
		if(path.endsWith("/cancel")) return json(request, 200, "{\"cancelled\":true}");
		throw new IOException("Unexpected fixture route: "+path);
	}
	private static String signature(long now){ return "q-sign-algorithm=sha1&q-ak=fixture&q-sign-time="+(now-10)+";"+(now+900)+"&q-key-time="+(now-10)+";"+(now+900)+"&q-header-list=content-length;content-md5;content-type;host;x-cos-acl;x-cos-forbid-overwrite&q-url-param-list=&q-signature="+"a".repeat(40); }
	private static Response json(Request request, int code, String body){ return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(ResponseBody.create(MediaType.get("application/json"), body)).build(); }
	private AlbumUploader create(String quality) throws Exception{
		AlbumUploader task=new AlbumUploader(accountId, "album", List.of(Uri.fromFile(source)), "宝宝的一天", 1791158401L, quality); tasks.add(task); return task;
	}
	private static final class Result implements AlbumUploader.Listener{
		AlbumModels.PublishResponse published; AlbumUploader.Failure error; boolean canceled, done; int last=-1;
		@Override public void onProgress(AlbumUploader.Stage stage, int percent, long transferred, long total){ assertTrue(percent>=last); last=percent; }
		@Override public void onSuccess(AlbumModels.PublishResponse value){ published=value; done=true; }
		@Override public void onError(AlbumUploader.Failure value){ error=value; done=true; }
		@Override public void onCanceled(){ canceled=true; done=true; }
	}
	private Result execute(AlbumUploader task) throws Exception{
		Result result=new Result(); task.setListener(result); task.start(); await(result); return result;
	}
	private void await(Result result) throws Exception{
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
		while(!result.done && System.nanoTime()<deadline){ ShadowLooper.idleMainLooper(); Thread.sleep(5); }
		ShadowLooper.idleMainLooper(); assertTrue("Uploader must finish offline", result.done);
	}
	private File taskDir(AlbumUploader task){ return new File(MastodonApp.context.getNoBackupFilesDir(), "album_uploads/"+task.getOperationId()); }

	@Test public void originalUploadsExactRawBytesWithMd5AndNoBackups() throws Exception{
		byte[] original=Files.readAllBytes(source.toPath()); AlbumUploader task=create("original"); File directory=taskDir(task);
		assertTrue(new File(directory, "manifest.json").isFile()); assertFalse(new File(MastodonApp.context.getFilesDir(), "album_uploads").exists());
		Result result=execute(task); assertNull(result.error); assertNotNull(result.published); assertEquals(3, putCount);
		assertArrayEquals(original, puts.get("upload-0000-original"));
		String receipt=Files.readString(new File(directory, "manifest.json").toPath()); assertFalse(receipt.contains("synthetic-uploader-token")); assertFalse(receipt.contains("q-signature"));
		assertEquals(1, directory.listFiles().length);
	}
	@Test public void automaticUploadTimeSendsExplicitNullCapturedAt() throws Exception{
		AlbumUploader task=new AlbumUploader(accountId, "album", List.of(Uri.fromFile(source)), "默认上传时间", null, "hd"); tasks.add(task);
		Result result=execute(task); assertNull(result.error); assertNotNull(result.published);
		JsonObject sent=JsonParser.parseString(authorizeBodies.get(0)).getAsJsonObject(); assertTrue(sent.has("captured_at")); assertTrue(sent.get("captured_at").isJsonNull());
	}
	@Test public void completionFailureResumesImmutableBytesWithoutRepeatedPut() throws Exception{
		failCompleteOnce=true; AlbumUploader first=create("hd"); Result failed=execute(first); assertNotNull(failed.error); assertEquals(1, putCount);
		String operation=first.getOperationId(); String body=authorizeBodies.get(0);
		Files.write(source.toPath(), new byte[]{1,2,3}); // Original URI changed; retries must use immutable staged bytes.
		AlbumUploader resumed=AlbumUploader.resume(accountId, operation); tasks.add(resumed); Result result=execute(resumed);
		assertNull(result.error); assertNotNull(result.published); assertEquals(operation, resumed.getOperationId()); assertEquals(body, authorizeBodies.get(1)); assertEquals(2, putCount);
	}
	@Test public void verificationUnavailableKeepsOutcomeKnownAndResumeRetriesCompleteFirstWithoutSecondPut() throws Exception{
		failCompleteOnce=true; AlbumUploader first=create("hd"); Result failed=execute(first);
		assertNotNull(failed.error); assertEquals("upload_verification_unavailable", failed.error.code);
		assertTrue(failed.error.retryable);
		assertFalse("Verification unavailability is a known not-complete outcome, not an unknown one", failed.error.outcomeUnknown);
		assertEquals(1, putCount); // The preview PUT succeeded; the refusal consumed no bytes.
		AlbumUploader resumed=AlbumUploader.resume(accountId, first.getOperationId()); tasks.add(resumed); Result result=execute(resumed);
		assertNull(result.error); assertNotNull(result.published);
		assertEquals(2, putCount); // Resume PUTs only the missing hd variant; the existing preview is verified complete-first.
	}
	@Test public void lostPublicationResponseRecoversBeforeAuthorization() throws Exception{
		losePublishOnce=true; AlbumUploader task=create("hd"); Result failed=execute(task); assertTrue(published); assertNotNull(failed.error); assertTrue(failed.error.outcomeUnknown);
		AlbumUploader resumed=AlbumUploader.resume(accountId, task.getOperationId()); tasks.add(resumed); Result success=execute(resumed);
		assertNotNull(success.published); assertNull(success.error); assertEquals(1, authorizeBodies.size()); assertEquals(2, putCount); assertEquals(2, publishCount);
	}
	@Test public void cancelDetachesBackgroundAndKeepsSameOperationForResume() throws Exception{
		quotaEntered=new CountDownLatch(1); releaseQuota=new CountDownLatch(1); AlbumUploader task=create("hd"); Result result=new Result(); task.setListener(result); task.start();
		assertTrue(quotaEntered.await(5, TimeUnit.SECONDS)); assertSame(task, AlbumUploader.resume(accountId, task.getOperationId())); task.cancel(); releaseQuota.countDown(); await(result);
		assertTrue(result.canceled); assertFalse(task.isRunning()); assertTrue(new File(taskDir(task), "manifest.json").exists()); assertEquals(0, putCount);
		quotaEntered=null; releaseQuota=null; AlbumUploader resumed=AlbumUploader.resume(accountId, task.getOperationId()); tasks.add(resumed); Result recovered=execute(resumed);
		assertFalse(recovered.canceled); assertNull(recovered.error); assertNotNull(recovered.published);
	}
	@Test public void originalExactTwentyMiBRejectedBeforeAuthorizationButHdSourceAllowed() throws Exception{
		assertFalse(AlbumUploader.sourceSizeAllowed(20L*1024*1024, "original")); assertTrue(AlbumUploader.sourceSizeAllowed(21L*1024*1024, "hd"));
		try(RandomAccessFile file=new RandomAccessFile(source, "rw")){ file.setLength(20L*1024*1024); }
		Result rejected=execute(create("original")); assertNotNull(rejected.error); assertEquals("source_too_large", rejected.error.code); assertTrue(authorizeBodies.isEmpty());
	}
	@Test public void singlePhotoRefreshDtoUsesSnakeCaseAndMissingPhotoIsNull(){
		AlbumModels.PhotoDetailResponse detail=MastodonAPIController.gson.fromJson("{\"photo\":{\"id\":\"p\",\"album_id\":\"album\",\"preview_url\":\"https://fixture.test/preview\",\"captured_at\":null,\"is_owner\":true}}", AlbumModels.PhotoDetailResponse.class);
		assertEquals("album", detail.photo.albumId); assertTrue(detail.photo.isOwner); assertNull(detail.photo.capturedAt);
		assertNull(MastodonAPIController.gson.fromJson("{}", AlbumModels.PhotoDetailResponse.class).photo);
	}
	@Test public void signatureMustBindAclDigestLengthHostAndCannotRedirect() throws Exception{
		long now=System.currentTimeMillis()/1000; AlbumModels.UploadAuthorization auth=new AlbumModels.UploadAuthorization(); auth.uploadUrl="https://"+COS+"/albums/42/image"; auth.expiresAt=now+900;
		auth.requiredHeaders=new HashMap<>(Map.of("Content-Type", "image/jpeg", "Content-Length", "1", "Content-MD5", "1B2M2Y8AsgTpgAmY7PhCfg==", "x-cos-acl", "private", "x-cos-forbid-overwrite", "true", "Authorization", signature(now)));
		AlbumUploader.validateUploadAuthorization(auth, "image/jpeg", 1, "1B2M2Y8AsgTpgAmY7PhCfg==", now);
		auth.requiredHeaders.put("x-cos-acl", "public-read");
		try{ AlbumUploader.validateUploadAuthorization(auth, "image/jpeg", 1, "1B2M2Y8AsgTpgAmY7PhCfg==", now); fail(); }catch(IOException expected){}
		for(String value:List.of("http://"+COS+"/albums/42/image", "https://evil.test/albums/42/image", "https://"+COS+"/albums/42/image?x=1", "https://"+COS+"/albums/42/%2e%2e/image", "https://"+COS+"/posts/image")){
			try{ AlbumUploader.validateCosUrl(value); fail(value); }catch(IOException expected){}
		}
	}
}
