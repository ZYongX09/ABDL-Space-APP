package org.joinmastodon.android.api;

import android.graphics.Bitmap;
import android.net.Uri;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.api.session.ContentAccessCoordinator;
import org.joinmastodon.android.model.ContentAccess;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.ui.utils.ContentMediaImageDownloader;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import me.grishka.appkit.imageloader.requests.ImageLoaderRequest;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;

import static org.junit.Assert.*;

/** Loopback transport, real owner guard + downloader + image decoding; never real credentials/hosts. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=CompatibilityTestApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ContentMediaHttpTest{
	private MockWebServer server, foreign;
	private AccountSession session, previous;
	private Map<String,AccountSession> sessions;
	private String previousActive;
	private ContentAccessCoordinator coordinator;
	private ContentMediaImageDownloader downloader;
	private NBWEndpointFixtures endpoints;
	@Before @SuppressWarnings("unchecked") public void setup() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication(); me.grishka.appkit.utils.V.setApplicationContext(MastodonApp.context); endpoints=new NBWEndpointFixtures();
		var manager=AccountSessionManager.getInstance(); sessions=(Map<String,AccountSession>)HomeTimelineSnapshotCacheTest.field(AccountSessionManager.class,"sessions").get(manager);
		previousActive=manager.getLastActiveAccountID(); session=HomeTimelineSnapshotCacheTest.session("api.abdl-space.top","media-access-992"); previous=sessions.put(session.getID(),session);
		HomeTimelineSnapshotCacheTest.field(AccountSessionManager.class,"lastActiveAccountID").set(manager,session.getID());
		coordinator=new ContentAccessCoordinator(session,(id,result)->{ result.success(ContentAccessCoordinatorTest.access(ContentAccess.State.OPEN)); return ()->{}; },Runnable::run);
		HomeTimelineSnapshotCacheTest.field(AccountSession.class,"contentAccessCoordinator").set(session,coordinator); coordinator.refresh();
		server=new MockWebServer(); foreign=new MockWebServer(); server.start(); foreign.start();
		OkHttpClient client=new OkHttpClient.Builder().addInterceptor(chain->{
			var request=chain.request(); assertTrue("Only a validated first-party media URL may reach the transport",ContentMediaImageDownloader.isMedia(Uri.parse(request.url().toString())));
			// Preserve the logical origin in the returned response for the production validation.
			var response=chain.proceed(request.newBuilder().url(server.url(request.url().encodedPath())).build());
			return response.newBuilder().request(request).build();
		}).build();
		downloader=new ContentMediaImageDownloader(client);
	}
	@After public void cleanup() throws Exception{
		server.shutdown(); foreign.shutdown(); HomeTimelineSnapshotCacheTest.drain(); session.deactivateNBW(); session.getCacheController().closeDatabase(); MastodonApp.context.deleteDatabase(session.getID()+".db");
		if(previous==null) sessions.remove(session.getID()); else sessions.put(session.getID(),previous);
		HomeTimelineSnapshotCacheTest.field(AccountSessionManager.class,"lastActiveAccountID").set(AccountSessionManager.getInstance(),previousActive); endpoints.close();
	}
	private ImageLoaderRequest request(){ return ContentMediaImageDownloader.scoped(new UrlImageLoaderRequest("https://api.abdl-space.top/api/v1/media/fictional/preview",32,32)); }
	private MockResponse image(){
		Bitmap bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888); ByteArrayOutputStream bytes=new ByteArrayOutputStream(); assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes)); bitmap.recycle();
		return new MockResponse().setHeader("Content-Type","image/png").setBody(new Buffer().write(bytes.toByteArray()));
	}
	@Test public void verifiedMediaUsesHeaderOnlyAndNoDiskOrUnscopedGlobalCache() throws Exception{
		ImageLoaderRequest request=request(); assertNotNull(request); assertFalse(downloader.needsDiskCache()); server.enqueue(image());
		assertNotNull(downloader.getDrawable(request,true,null)); var received=server.takeRequest(2,TimeUnit.SECONDS); assertNotNull(received);
		assertEquals("Bearer "+session.token.accessToken,received.getHeader("Authorization")); assertFalse(received.getPath().contains(session.token.accessToken));
		assertFalse(request.getMemoryCacheKey().contains(session.token.accessToken)); assertNotEquals(new UrlImageLoaderRequest("https://api.abdl-space.top/api/v1/media/fictional/preview",32,32).getMemoryCacheKey(),request.getMemoryCacheKey());
		try{ downloader.getDrawable(new UrlImageLoaderRequest("https://api.abdl-space.top/api/v1/media/fictional/preview"),true,null); fail("Unscoped URL must not fetch authenticated bytes"); }catch(IOException expected){}
	}
	@Test public void foreignCosNbwAndCrossOriginRedirectNeverReceiveBearer() throws Exception{
		for(String url:java.util.List.of("https://cos.example/api/v1/media/a","https://www.newbabyworld.top/api/v1/media/a","http://api.abdl-space.top/api/v1/media/a","https://api.abdl-space.top@evil.test/api/v1/media/a")) assertFalse(downloader.canHandleRequest(new UrlImageLoaderRequest(url)));
		server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location",foreign.url("/steal")));
		try{ downloader.getDrawable(request(),true,null); fail("Redirects must fail closed"); }catch(IOException expected){}
		assertEquals(1,server.getRequestCount()); assertEquals(0,foreign.getRequestCount());
	}
	@Test public void replacedCredentialOrRequiredPolicyInvalidatesMediaAndMemoryScope() throws Exception{
		ImageLoaderRequest old=request(); Token replacement=new Token(); replacement.accessToken=session.token.accessToken; session.token=replacement;
		assertFalse(ContentMediaImageDownloader.accepts(old));
		try{ downloader.getDrawable(old,true,null); fail("Old owner cannot fetch"); }catch(IOException expected){}
		coordinator.refresh(); ImageLoaderRequest fresh=request(); assertNotNull(fresh); assertNotEquals(old.getMemoryCacheKey(),fresh.getMemoryCacheKey());
		coordinator.capture().receiveHeader("v1:baby_verification_required"); assertFalse(ContentMediaImageDownloader.accepts(fresh)); assertNull(request()); assertEquals(0,server.getRequestCount());
	}
}
