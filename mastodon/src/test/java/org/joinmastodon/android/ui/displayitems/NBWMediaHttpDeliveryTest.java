package org.joinmastodon.android.ui.displayitems;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.NBWEndpointFixtures;
import org.joinmastodon.android.api.NBWV2RepositoryTest;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.api.session.NBWEndpointsRepository;
import org.joinmastodon.android.api.session.NBWForumOptionsRepository;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Attachment;
import org.joinmastodon.android.model.NBWCapabilities;
import org.joinmastodon.android.model.NBWEndpoints;
import org.joinmastodon.android.model.NBWForumOptions;
import org.joinmastodon.android.model.NBWSelection;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.ui.PhotoLayoutHelper;
import org.joinmastodon.android.ui.drawables.BlurhashCrossfadeDrawable;
import org.joinmastodon.android.ui.utils.NBWImageRequests;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import me.grishka.appkit.imageloader.ImageCache;
import me.grishka.appkit.imageloader.ImageLoaderCallback;
import me.grishka.appkit.imageloader.ListImageLoader;
import me.grishka.appkit.imageloader.ListImageLoaderAdapter;
import me.grishka.appkit.imageloader.downloaders.HTTPImageDownloader;
import me.grishka.appkit.imageloader.downloaders.ImageDownloader;
import me.grishka.appkit.imageloader.requests.ImageLoaderRequest;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.NetworkUtils;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

/** Real AppKit dispatch, HTTP, disk write and native PNG decode; only the socket destination is local. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class NBWMediaHttpDeliveryTest{
	private NBWMediaEndpointTest policyFixture;
	private NBWEndpointFixtures endpoints;
	private MockWebServer server;
	private ImageCache cache;
	private HTTPImageDownloader downloader;
	private OkHttpClient previousClient, localClient;
	private String previousUserAgent;
	private final AtomicReference<Request> logicalHttpRequest=new AtomicReference<>();
	private byte[] png;

	@Before public void setup() throws Exception{
		// Reuse the real public/session repository fixtures, not hand-built request scopes.
		policyFixture=new NBWMediaEndpointTest(); policyFixture.setup();
		endpoints=(NBWEndpointFixtures)field(NBWMediaEndpointTest.class,"endpoints").get(policyFixture);
		server=new MockWebServer(); server.start();
		cache=ImageCache.getInstance(MastodonApp.context);
		// getDiskCache() deliberately does not wait on the main thread; don't queue HTTP behind an unopened cache.
		await(()->cache.getDiskCache()!=null,"Real ImageCache must finish opening its disk cache");
		cache.clearTopLevel();
		@SuppressWarnings("unchecked") List<ImageDownloader> registered=(List<ImageDownloader>)field(ImageCache.class,"downloaders").get(null);
		downloader=(HTTPImageDownloader)registered.stream().filter(HTTPImageDownloader.class::isInstance).findFirst().orElseThrow();
		previousClient=(OkHttpClient)field(HTTPImageDownloader.class,"httpClient").get(downloader);
		localClient=new OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).writeTimeout(15,TimeUnit.SECONDS)
				.addInterceptor(chain->{
					Request logical=chain.request(); logicalHttpRequest.set(logical);
					// Keep ScopedRequest and its trusted logical HTTPS URI unchanged. No fake downloader/callback/decoder.
					return chain.proceed(logical.newBuilder().url(server.url(logical.url().encodedPath()))
							.header("Host",logical.url().host()).build());
				}).build();
		field(HTTPImageDownloader.class,"httpClient").set(downloader,localClient);
		previousUserAgent=NetworkUtils.getUserAgent(); NetworkUtils.setUserAgent("MastodonAndroid/3.3.0");
		Bitmap bitmap=Bitmap.createBitmap(3,2,Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.CYAN);
		try(ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
			assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes)); png=bytes.toByteArray();
		}finally{ bitmap.recycle(); }
	}

	@After public void cleanup() throws Exception{
		if(downloader!=null) field(HTTPImageDownloader.class,"httpClient").set(downloader,previousClient);
		if(previousUserAgent!=null) NetworkUtils.setUserAgent(previousUserAgent);
		if(localClient!=null){ localClient.dispatcher().executorService().shutdownNow(); localClient.connectionPool().evictAll(); }
		if(server!=null) server.shutdown();
		if(policyFixture!=null) policyFixture.cleanup();
	}

	@Test public void actualImageCacheHttpAndPngDecodeDeliverPrimaryAndBackupWithFlatScopedDiskFiles() throws Exception{
		for(String mode:List.of("primary","backup")){
			endpoints.publish(mode,"http-delivery-"+mode,"full");
			String path="/data/attachment/forum/20261105/controlled-"+UUID.randomUUID()+".png";
			UrlImageLoaderRequest source=new UrlImageLoaderRequest(Bitmap.Config.ARGB_8888,0,0,List.of(),Uri.parse(NBWEndpoints.PRIMARY+path));
			ImageLoaderRequest request=NBWImageRequests.current(source);
			assertNotNull(request); assertNotEquals(source.getClass(),request.getClass());
			assertTrue("AppKit HTTP downloader must support the real scope subclass",downloader.canHandleRequest(request));
			assertEquals(request,NBWImageRequests.current(source)); assertEquals(request.hashCode(),NBWImageRequests.current(source).hashCode());
			assertTrue(request.sourcesEqual(NBWImageRequests.current(source)));
			server.enqueue(new MockResponse().setHeader("Content-Type","image/png").setBody(new Buffer().write(png)));
			Drawable result=load(request);
			RecordedRequest received=server.takeRequest(2,TimeUnit.SECONDS); assertNotNull(received);
			String origin="backup".equals(mode) ? NBWEndpoints.BACKUP : NBWEndpoints.PRIMARY;
			assertEquals(origin+path,logicalHttpRequest.get().url().toString()); assertEquals("https",logicalHttpRequest.get().url().scheme());
			assertEquals("GET",received.getMethod()); assertEquals(path,received.getPath());
			assertEquals(Uri.parse(origin).getAuthority(),received.getHeader("Host"));
			assertEquals("MastodonAndroid/3.3.0",received.getHeader("User-Agent"));
			for(String header:List.of("Authorization","Cookie","Referer","X-NBW-Protocol","X-NBW-Key")) assertNull(received.getHeader(header));
			assertTrue(result instanceof BitmapDrawable);
			Bitmap decoded=((BitmapDrawable)result).getBitmap();
			assertEquals(3,decoded.getWidth()); assertEquals(2,decoded.getHeight()); assertEquals(Color.CYAN,decoded.getPixel(1,1));
			File diskFile=cache.getFile(request); assertNotNull(diskFile); assertTrue(diskFile.isFile());
			assertEquals("Scoped disk key must be a flat file name, not an uncreated hash/nbw subdirectory",
					cache.getDiskCache().getDirectory().getCanonicalFile(),diskFile.getParentFile().getCanonicalFile());
			assertTrue(request.getDiskCacheKey().matches("[a-f0-9]{32}"));
			assertNotEquals(source.getDiskCacheKey(),request.getDiskCacheKey());
			assertTrue("Memory cache still carries the full scope",request.getMemoryCacheKey().startsWith(sourceFor(request).getMemoryCacheKey()+"/nbw-"));
		}
	}

	@Test public void realViewAndListLoaderDeliverIntoSensitiveMediaGridInBothRoutingDirections() throws Exception{
		for(String mode:List.of("primary","backup")){
			// Construct a cached display item with the other origin, then route only at its real loader sink.
			String storedOrigin="primary".equals(mode) ? NBWEndpoints.BACKUP : NBWEndpoints.PRIMARY;
			try(DeliveryProbe probe=new DeliveryProbe(storedOrigin+uniquePath(),true)){
				endpoints.publish(mode,"grid-"+mode,"full");
				server.enqueue(pngResponse()); probe.start(); probe.awaitDecode(); probe.assertDelivered();
				assertEquals(storedOrigin,Uri.parse(probe.attachment.url).getScheme()+"://"+Uri.parse(probe.attachment.url).getAuthority());
				String expectedOrigin="primary".equals(mode) ? NBWEndpoints.PRIMARY : NBWEndpoints.BACKUP;
				assertEquals(Uri.parse(expectedOrigin).getAuthority(),logicalHttpRequest.get().url().host());
				assertEquals(1,probe.delivered); assertFalse(probe.item.sensitiveRevealed);
				assertEquals(View.VISIBLE,probe.holder.getSensitiveOverlay().getVisibility());
				assertEquals(View.INVISIBLE,probe.holder.getLayout().getVisibility());
			}
		}
		assertEquals("View, list and decode witness share each real scoped HTTP transfer",2,server.getRequestCount());
	}

	@Test public void inFlightRealHttpDecodeCannotDeliverAfterSameUriRevisionRoutingOrPolicyRevocation() throws Exception{
		assertLateDeliveryRejected("same-uri-revision",()->endpoints.publish("primary","changed-revision","full"));
		assertLateDeliveryRejected("changed-origin",()->endpoints.publish("backup","changed-origin","full"));
		assertLateDeliveryRejected("public-local-only",()->endpoints.publish("primary","read-revoked","local_only"));
	}

	@Test public void inFlightRealHttpDecodeCannotDeliverAfterTokenSessionAccountSwitchBackOrAbsoluteExpiry() throws Exception{
		AccountSession session=currentSession();
		assertLateDeliveryRejected("token-rotation",()->session.token.accessToken="fictional-rotated-media-token");
		assertLateDeliveryRejected("session-generation",()->{ session.deactivateNBW(); session.getNBWForumOptionsRepository().activate(); });
		var ctor=AccountSession.class.getDeclaredConstructor(); ctor.setAccessible(true); AccountSession other=ctor.newInstance();
		other.domain="second-media.offline.example.test"; other.self=new Account(); other.self.id="52";
		other.token=new Token(); other.token.accessToken="fictional-second-media-token";
		NBWForumOptionsRepository otherPolicy=policy(new RenewalTransport()); field(AccountSession.class,"nbwForumOptionsRepository").set(other,otherPolicy);
		@SuppressWarnings("unchecked") Map<String,AccountSession> sessions=(Map<String,AccountSession>)field(AccountSessionManager.class,"sessions").get(AccountSessionManager.getInstance());
		sessions.put(other.getID(),other);
		try{
			assertLateDeliveryRejected("account-switch-back",()->{
				AccountSessionManager.getInstance().setLastActiveAccountID(other.getID());
				AccountSessionManager.getInstance().setLastActiveAccountID(session.getID());
			});
		}finally{ other.deactivateNBW(); sessions.remove(other.getID()); }
		assertLateDeliveryRejected("absolute-lease-expiry",()->ShadowSystemClock.advanceBy(31,TimeUnit.SECONDS));
	}

	@Test public void sameEndpointStaleRenewalKeepsLiveScopeAndNonNbwOriginalStillDeliversWhenNbwIsDenied() throws Exception{
		endpoints.publish("primary","unchanged-lease","full");
		AccountSession session=currentSession(); session.deactivateNBW();
		RenewalTransport renewal=new RenewalTransport(); NBWForumOptionsRepository repository=policy(renewal);
		field(AccountSession.class,"nbwForumOptionsRepository").set(session,repository); repository.activate();
		GatedPng gate=new GatedPng(); server.setDispatcher(gate);
		try(DeliveryProbe probe=new DeliveryProbe(NBWEndpoints.PRIMARY+uniquePath(),false)){
			probe.start(); gate.awaitRequest();
			long publicGeneration=endpoints.repository.getSnapshot().generation, sessionGeneration=repository.getSnapshot().generation;
			renewal.deferCapabilities=true; endpoints.publish("primary","unchanged-lease","full");
			assertNotNull(renewal.pending); assertEquals(NBWForumOptionsRepository.State.STALE,repository.getSnapshot().state);
			assertTrue(repository.getSnapshot().canRead()); assertTrue(NBWImageRequests.accepts(probe.requested));
			assertEquals(publicGeneration,endpoints.repository.getSnapshot().generation); assertEquals(sessionGeneration,repository.getSnapshot().generation);
			assertEquals(probe.requested,probe.item.getImageRequest(0));
			gate.release.countDown(); probe.awaitDecode(); probe.assertDelivered();
			renewal.pending.success(NBWV2RepositoryTest.capabilities("full"));
			assertEquals(NBWForumOptionsRepository.State.READY,repository.getSnapshot().state);
		}finally{ gate.release.countDown(); }
		endpoints.publish("backup","nbw-denied","local_only");
		assertNull(NBWImageRequests.current(new UrlImageLoaderRequest(NBWEndpoints.PRIMARY+uniquePath())));
		GatedPng localGate=new GatedPng(); server.setDispatcher(localGate);
		try(DeliveryProbe local=new DeliveryProbe("https://firstparty.offline.example.test"+uniquePath(),false)){
			ImageLoaderRequest original=local.item.getImageRequest(0);
			assertEquals(UrlImageLoaderRequest.class,original.getClass()); assertSame(original,NBWImageRequests.current(original));
			String memoryKey=original.getMemoryCacheKey(), diskKey=original.getDiskCacheKey();
			local.start(); assertSame(original,local.requested); localGate.awaitRequest(); localGate.release.countDown();
			local.awaitDecode(); local.assertDelivered();
			assertEquals(memoryKey,local.requested.getMemoryCacheKey()); assertEquals(diskKey,local.requested.getDiskCacheKey());
			assertEquals("firstparty.offline.example.test",logicalHttpRequest.get().url().host());
		}finally{ localGate.release.countDown(); }
	}

	@FunctionalInterface private interface Revocation{ void run() throws Exception; }
	private void assertLateDeliveryRejected(String reason,Revocation revoke) throws Exception{
		endpoints.publish("primary","before-"+reason,"full");
		GatedPng gate=new GatedPng(); server.setDispatcher(gate); int before=server.getRequestCount();
		try(DeliveryProbe probe=new DeliveryProbe(NBWEndpoints.PRIMARY+uniquePath(),false)){
			probe.start(); gate.awaitRequest(); revoke.run();
			assertFalse(reason,NBWImageRequests.accepts(probe.requested));
			assertNotEquals(reason,probe.requested,probe.item.getImageRequest(0));
			gate.release.countDown(); probe.awaitDecode();
			// Success is an actual decoded HTTP bitmap in the old scoped cache, not a hand-fired callback.
			assertTrue(reason,cache.isInTopCache(probe.requested)); assertNotNull(cache.getFile(probe.requested));
			assertEquals(reason,0,probe.delivered); assertNull(reason,probe.gridImage()); assertNull(reason,probe.view.getDrawable());
			assertEquals("No fallback or duplicate download after "+reason,before+1,server.getRequestCount());
		}finally{ gate.release.countDown(); }
	}

	/** Uses the same adapter contract as a status row, with a real bound holder and real ImageViews. */
	private final class DeliveryProbe implements AutoCloseable{
		final org.robolectric.android.controller.ActivityController<Activity> lifecycle=Robolectric.buildActivity(Activity.class).setup();
		final Attachment attachment=new Attachment();
		final MediaGridStatusDisplayItem item;
		final MediaGridStatusDisplayItem.Holder holder;
		final ImageView view;
		final ListImageLoader list=new ListImageLoader();
		final AtomicReference<Drawable> decoded=new AtomicReference<>();
		final AtomicReference<Throwable> failed=new AtomicReference<>();
		ImageLoaderRequest requested;
		ImageCache.PendingImageRequest witness;
		int delivered;
		DeliveryProbe(String source,boolean sensitive) throws Exception{
			Activity activity=lifecycle.get(); activity.setTheme(org.joinmastodon.android.R.style.Theme_Mastodon_Light);
			attachment.id="controlled-http-image"; attachment.type=Attachment.Type.IMAGE;
			attachment.url=attachment.previewUrl=attachment.remoteUrl=source;
			attachment.meta=new Attachment.Metadata(); attachment.meta.width=3; attachment.meta.height=2;
			Status status=Status.ofFake("nbw_controlled_media","",Instant.EPOCH); status.account=currentSession().self;
			status.sensitive=sensitive; status.mediaAttachments=List.of(attachment);
			var callbacks=new StatusDisplayItem.NoOpCallbacks(activity);
			item=new MediaGridStatusDisplayItem(status.id,callbacks,activity,PhotoLayoutHelper.processThumbs(status.mediaAttachments),status.mediaAttachments,status);
			FrameLayout root=new FrameLayout(activity); activity.setContentView(root);
			holder=new MediaGridStatusDisplayItem.Holder(activity,root); holder.bind(item); root.addView(holder.itemView);
			view=new ImageView(activity); root.addView(view);
			list.setAdapter(new ListImageLoaderAdapter(){
				@Override public int getCount(){ return 1; }
				@Override public int getImageCountForItem(int position){ return item.getImageCount(); }
				@Override public ImageLoaderRequest getImageRequest(int position,int image){ return item.getImageRequest(image); }
				@Override public void imageLoaded(int position,int image,Drawable drawable){ delivered++; holder.setImage(image,drawable); }
				@Override public void imageLoadingFailed(int position,int image,Throwable error){ failed.set(error); holder.onImageLoadingFailed(image,error); }
			});
		}
		void start(){
			requested=item.getImageRequest(0); assertNotNull(requested);
			list.loadSingleItem(0,MastodonApp.context,false);
			NBWImageRequests.load(view,null,requested);
			witness=cache.get(requested,null,new ImageLoaderCallback(){
				@Override public void onImageLoaded(ImageLoaderRequest actual,Drawable drawable){ decoded.set(drawable); }
				@Override public void onImageLoadingFailed(ImageLoaderRequest actual,Throwable error){ failed.set(error); }
			},true);
		}
		void awaitDecode() throws Exception{
			await(()->failed.get()!=null || decoded.get()!=null && !list.isLoading(0,0),"Real HTTP PNG decode and list completion");
			if(failed.get()!=null) throw new AssertionError("Actual list/View HTTP pipeline failed",failed.get());
			assertPixels(decoded.get()); ShadowLooper.idleMainLooper();
		}
		Drawable gridImage(){ return ((BlurhashCrossfadeDrawable)holder.getViewController(0).photo.getDrawable()).getImageDrawable(); }
		void assertDelivered(){ assertEquals(1,delivered); assertPixels(gridImage()); assertPixels(view.getDrawable()); assertFalse(holder.getViewController(0).isFailedOverlayShown()); }
		@Override public void close(){ list.cancelAll(); if(witness!=null) witness.cancel(); lifecycle.close(); }
	}

	private final class GatedPng extends Dispatcher{
		final CountDownLatch arrived=new CountDownLatch(1), release=new CountDownLatch(1);
		@Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException{
			arrived.countDown(); return release.await(10,TimeUnit.SECONDS) ? pngResponse() : new MockResponse().setResponseCode(500);
		}
		void awaitRequest() throws Exception{ await(()->arrived.getCount()==0,"Real HTTP transfer must start before revoking its scope"); }
	}
	private static final class RenewalTransport implements NBWForumOptionsRepository.Transport{
		boolean deferCapabilities;
		NBWForumOptionsRepository.Result<NBWCapabilities> pending;
		@Override public NBWForumOptionsRepository.Cancelable capabilities(NBWForumOptionsRepository.Result<NBWCapabilities> result){
			if(deferCapabilities) pending=result; else result.success(NBWV2RepositoryTest.capabilities("full")); return ()->{};
		}
		@Override public NBWForumOptionsRepository.Cancelable options(NBWForumOptionsRepository.Result<NBWForumOptions> result){ result.success(NBWV2RepositoryTest.options()); return ()->{}; }
		@Override public NBWForumOptionsRepository.Cancelable consent(boolean value,NBWForumOptionsRepository.Result<Object> result){ throw new AssertionError("No consent write in an image test"); }
	}
	private static NBWForumOptionsRepository policy(RenewalTransport transport){
		return new NBWForumOptionsRepository(transport,android.os.SystemClock::elapsedRealtime,new NBWForumOptionsRepository.SelectionStore(){
			@Override public NBWSelection load(){ return NBWSelection.ai(); }
			@Override public void save(NBWSelection selection){}
		},()->{},Runnable::run);
	}
	private AccountSession currentSession() throws Exception{ return (AccountSession)field(NBWMediaEndpointTest.class,"session").get(policyFixture); }
	private MockResponse pngResponse(){ return new MockResponse().setHeader("Content-Type","image/png").setBody(new Buffer().write(png)); }
	private static String uniquePath(){ return "/data/attachment/forum/20261105/delivery-"+UUID.randomUUID()+".png"; }
	private static void assertPixels(Drawable drawable){
		assertTrue("Native decoded PNG must reach the actual display target",drawable instanceof BitmapDrawable);
		Bitmap bitmap=((BitmapDrawable)drawable).getBitmap(); assertEquals(3,bitmap.getWidth()); assertEquals(2,bitmap.getHeight()); assertEquals(Color.CYAN,bitmap.getPixel(1,1));
	}
	private static void await(BooleanSupplier complete,String reason) throws Exception{
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
		while(!complete.getAsBoolean() && System.nanoTime()<deadline){ ShadowLooper.idleMainLooper(); Thread.sleep(5); }
		ShadowLooper.idleMainLooper();
		assertTrue(reason+"; Android logs="+org.robolectric.shadows.ShadowLog.getLogs(),complete.getAsBoolean());
	}

	private Drawable load(ImageLoaderRequest request) throws Exception{
		AtomicReference<Drawable> loaded=new AtomicReference<>(); AtomicReference<Throwable> failed=new AtomicReference<>();
		ImageCache.PendingImageRequest pending=cache.get(request,null,new ImageLoaderCallback(){
			@Override public void onImageLoaded(ImageLoaderRequest actual,Drawable drawable){ loaded.set(drawable); }
			@Override public void onImageLoadingFailed(ImageLoaderRequest actual,Throwable error){ failed.set(error); }
		},true);
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
		try{
			while(loaded.get()==null && failed.get()==null && System.nanoTime()<deadline){ ShadowLooper.idleMainLooper(); Thread.sleep(5); }
			if(failed.get()!=null) throw new AssertionError("Real HTTP ImageCache delivery failed; disk key="+request.getDiskCacheKey(),failed.get());
			assertNotNull("Actual HTTP response must be written, decoded and delivered by AppKit",loaded.get()); return loaded.get();
		}finally{ pending.cancel(); }
	}

	private static UrlImageLoaderRequest sourceFor(ImageLoaderRequest request){
		return new UrlImageLoaderRequest(request.desiredConfig,request.desiredMaxWidth,request.desiredMaxHeight,request.processingSteps,((UrlImageLoaderRequest)request).uri);
	}
	private static Field field(Class<?> owner,String name) throws Exception{ Field field=owner.getDeclaredField(name); field.setAccessible(true); return field; }
}
