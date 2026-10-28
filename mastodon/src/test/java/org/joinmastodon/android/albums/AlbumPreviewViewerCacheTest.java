package org.joinmastodon.android.albums;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.MastodonErrorResponse;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Attachment;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.model.albums.AlbumModels;
import org.joinmastodon.android.ui.photoviewer.PhotoViewer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowLooper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import me.grishka.appkit.api.Callback;
import me.grishka.appkit.utils.V;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

/** Real overlay, real ACL callbacks and real bytes/decode over loopback; no mocked cache/get/set path. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk=28, application=Application.class, qualifiers="w360dp-h800dp-port-mdpi", shadows=AlbumPreviewViewerCacheTest.RecordingApiExecution.class)
public class AlbumPreviewViewerCacheTest{
	private static final String A="12345678-1234-1234-1234-123456789abc", B="aaaaaaaa-1234-1234-1234-123456789abc";
	private final List<Overlay> overlays=new ArrayList<>();
	private final List<Request> imageRequests=new java.util.concurrent.CopyOnWriteArrayList<>();
	private MockWebServer server;
	private HttpUrl loopbackBase;
	private volatile HeldPreview heldPreview;
	private OkHttpClient transport;
	private AlbumPreviewMemoryCache cache;
	private ActivityController<Activity> lifecycle;
	private Activity host;
	private AccountSession session, old;
	private String accountID, previous;
	private Map<String,AccountSession> sessions;

	@Before public void setup() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context);
		RecordingApiExecution.requests.clear();
		session=newSession("42","viewer-cache-token"); accountID=session.getID();
		AccountSessionManager manager=AccountSessionManager.getInstance();
		@SuppressWarnings("unchecked") Map<String,AccountSession> map=(Map<String,AccountSession>)field(AccountSessionManager.class,"sessions").get(manager);
		sessions=map; previous=(String)field(AccountSessionManager.class,"lastActiveAccountID").get(manager);
		old=sessions.put(accountID,session); select(accountID);
		cache=AlbumPreviewMemoryCache.getInstance(MastodonApp.context);
		server=new MockWebServer(); server.start(InetAddress.getByName("127.0.0.1"),0); loopbackBase=server.url("/");
		server.setDispatcher(new Dispatcher(){
			@Override public MockResponse dispatch(RecordedRequest request){ return pngResponse(request.getPath().contains(B) ? Color.BLUE : Color.RED); }
		});
		transport=new OkHttpClient.Builder().cache(null).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
				.addInterceptor(chain->{
					Request requested=chain.request(); imageRequests.add(requested);
					assertEquals("no-store",requested.header("Cache-Control"));
					HeldPreview held=heldPreview;
					HttpUrl base=held!=null && requested.url().equals(held.original) ? held.base : loopbackBase;
					HttpUrl target=base.newBuilder().encodedPath(requested.url().encodedPath()).encodedQuery(requested.url().encodedQuery()).build();
					return chain.proceed(requested.newBuilder().url(target).header("Connection","close").build());
				}).build();
		lifecycle=Robolectric.buildActivity(Activity.class); host=lifecycle.get(); host.setTheme(R.style.Theme_Mastodon_Dark); lifecycle.setup().visible();
	}

	@After public void cleanup() throws Exception{
		for(Overlay overlay:overlays) overlay.close();
		cache.invalidateSession(accountID,session);
		if(heldPreview!=null) heldPreview.close();
		lifecycle.close(); server.shutdown(); transport.dispatcher().executorService().shutdown(); transport.connectionPool().evictAll();
		if(old==null) sessions.remove(accountID); else sessions.put(accountID,old); select(previous);
	}

	@Test public void reopenUsesTwoFreshAclsButOneImageAndTheSameDecodedBitmap() throws Exception{
		AlbumModels.Photo photo=photo(A,"https://preview.example.test/"+A+"/preview.jpg?version=1");
		Overlay first=open(List.of(photo)); assertTrue(first.imagesBlank()); assertEquals(1,aclCount()); assertEquals(0,imageRequests.size());
		first.deliver(photo); Bitmap decoded=first.awaitBitmap(); assertEquals(1,imageRequests.size());
		first.close();
		Overlay second=open(List.of(photo)); assertTrue("Warm RAM must not show before fresh ACL",second.imagesBlank());
		assertEquals(2,aclCount()); assertEquals(1,imageRequests.size()); second.deliver(photo);
		assertSame("Cache hit must not decode a second Bitmap",decoded,second.awaitBitmap()); assertEquals(1,imageRequests.size());
		assertNull(field(PhotoViewer.class,"albumSourceDrawable").get(second.viewer)); // Preview never becomes HD/source.
		assertEquals(0,authorizationCount());
	}

	@Test public void cosSignatureRotationHitsOnlyAfterFreshAclAndDoesNotRefetchOrDecode() throws Exception{
		long now=System.currentTimeMillis()/1000;
		AlbumModels.Photo firstPhoto=photo(A,AlbumPreviewMemoryCacheTest.signed(now,"1"));
		Overlay first=open(List.of(firstPhoto)); first.deliver(firstPhoto); Bitmap decoded=first.awaitBitmap(); first.close();
		AlbumModels.Photo renewed=photo(A,AlbumPreviewMemoryCacheTest.signed(now+1,"2"));
		Overlay second=open(List.of(firstPhoto)); assertTrue(second.imagesBlank()); second.deliver(renewed);
		assertSame(decoded,second.awaitBitmap()); assertEquals(2,aclCount()); assertEquals(1,imageRequests.size());
		assertEquals(renewed.previewUrl,second.attachments().get(0).previewUrl);
	}

	@Test public void aToBToAWarmPageStillRenewsAclAndDoesNotDecodeAAgain() throws Exception{
		AlbumModels.Photo a=photo(A,"https://preview.example.test/"+A+"/preview.jpg"), b=photo(B,"https://preview.example.test/"+B+"/preview.jpg");
		Overlay overlay=open(List.of(a,b)); overlay.deliver(a); Bitmap first=overlay.awaitBitmap();
		overlay.page(1); assertTrue(overlay.imagesBlank()); overlay.deliver(b); Bitmap second=overlay.awaitBitmap(); assertNotSame(first,second);
		overlay.page(0); assertTrue(overlay.imagesBlank()); overlay.deliver(a);
		assertSame(first,overlay.awaitBitmap()); assertEquals(3,aclCount()); assertEquals(2,imageRequests.size()); assertEquals(0,authorizationCount());
	}

	@Test public void changedFullContentUrlOrTransformMustMissOnReopen() throws Exception{
		AlbumModels.Photo a=photo(A,"https://preview.example.test/"+A+"/preview.jpg?transform=32");
		Overlay first=open(List.of(a)); first.deliver(a); Bitmap previousBitmap=first.awaitBitmap(); first.close();
		for(String url:List.of(a.previewUrl.replace("transform=32","transform=64"), a.previewUrl.replace("/preview.jpg","/new-content.jpg"))){
			AlbumModels.Photo renewed=photo(A,url); Overlay next=open(List.of(a)); assertTrue(next.imagesBlank()); next.deliver(renewed);
			assertNotSame(previousBitmap,next.awaitBitmap()); next.close();
		}
		assertEquals(3,imageRequests.size()); assertEquals(3,aclCount());
	}

	@Test public void protectedVisitorAndProtectedOwnerNeverHitOrPutAndVisitorStaysSecure() throws Exception{
		AlbumModels.Photo ordinary=photo(A,"https://preview.example.test/"+A+"/preview.jpg");
		Overlay initial=open(List.of(ordinary)); initial.deliver(ordinary); Bitmap warm=initial.awaitBitmap(); initial.close();
		for(boolean owner:new boolean[]{false,true}){
			AlbumModels.Photo protectedPhoto=photo(A,ordinary.previewUrl); protectedPhoto.downloadProtected=true; protectedPhoto.isOwner=owner; protectedPhoto.canDownload=owner;
			Overlay first=open(List.of(protectedPhoto)); assertTrue(first.imagesBlank());
			View root=(View)field(PhotoViewer.class,"windowView").get(first.viewer);
			if(!owner) assertTrue((((WindowManager.LayoutParams)root.getLayoutParams()).flags & WindowManager.LayoutParams.FLAG_SECURE)!=0);
			first.deliver(protectedPhoto); Bitmap loaded=first.awaitBitmap(); assertNotSame(warm,loaded); first.close();
			Overlay reopened=open(List.of(protectedPhoto)); assertTrue(reopened.imagesBlank()); reopened.deliver(protectedPhoto);
			assertNotSame(loaded,reopened.awaitBitmap()); reopened.close();
			assertNull(cache.get(accountID,session,AlbumPhotoViewer.attachment(ordinary),4096,false));
		}
		assertEquals(5,imageRequests.size()); assertEquals(0,authorizationCount());
	}

	@Test public void forbiddenAndMissingAclClosePurgeAndRejectLateSuccess() throws Exception{
		for(int status:new int[]{403,404}){
			AlbumModels.Photo photo=photo(A,"https://preview.example.test/"+A+"/"+status+".jpg");
			Overlay warm=open(List.of(photo)); warm.deliver(photo); warm.awaitBitmap(); warm.close();
			Overlay denied=open(List.of(photo)); assertTrue(denied.imagesBlank()); Callback<AlbumModels.PhotoDetailResponse> pending=callback(denied.renewal());
			pending.onError(new MastodonErrorResponse("denied",status,null)); ShadowLooper.idleMainLooper();
			assertTrue(field(PhotoViewer.class,"dismissed").getBoolean(denied.viewer));
			assertNull(cache.get(accountID,session,AlbumPhotoViewer.attachment(photo),4096,false));
			int before=imageRequests.size(); pending.onSuccess(detail(photo)); ShadowLooper.idleMainLooper(); assertEquals(before,imageRequests.size());
			Overlay retry=open(List.of(photo)); retry.deliver(photo); retry.awaitBitmap(); retry.close(); assertEquals(before+1,imageRequests.size());
		}
	}

	@Test public void missingPreviewAndAdminBlockInvalidateWarmRamBeforeAnyReuse() throws Exception{
		AlbumModels.Photo photo=photo(A,"https://preview.example.test/"+A+"/preview.jpg");
		Overlay warm=open(List.of(photo)); warm.deliver(photo); warm.awaitBitmap(); warm.close();
		Overlay revoked=open(List.of(photo)); AlbumModels.Photo unavailable=photo(A,null); revoked.deliver(unavailable);
		assertTrue(field(PhotoViewer.class,"dismissed").getBoolean(revoked.viewer));
		assertNull(cache.get(accountID,session,AlbumPhotoViewer.attachment(photo),4096,false));
		Overlay restored=open(List.of(photo)); restored.deliver(photo); restored.awaitBitmap();
		method(AlbumPhotoViewer.class,"watchPolicy").invoke(restored.adapter);
		AlbumModels.Photo blocked=photo(A,null); blocked.adminBlocked=true; blocked.canDownload=false; restored.deliver(blocked);
		assertTrue(restored.attachments().get(0).adminBlocked); assertNull(cache.get(accountID,session,AlbumPhotoViewer.attachment(photo),4096,false));
		assertNull(restored.currentBitmap()); assertNotNull(restored.currentImage().getDrawable()); assertEquals(host.getString(R.string.album_photo_admin_blocked),restored.currentImage().getContentDescription());
		assertEquals(2,imageRequests.size());
	}

	@Test public void policyChangeDuringBytesRejectsCompletionAndProtectedRetryRemainsTransient() throws Exception{
		AlbumModels.Photo photo=photo(A,"https://preview.example.test/"+A+"/preview.jpg");
		heldPreview=new HeldPreview(photo.previewUrl);
		Overlay overlay=open(List.of(photo)); overlay.deliver(photo);
		assertTrue(heldPreview.entered.await(3,TimeUnit.SECONDS));
		Call old=overlay.previewCall(); assertNotNull(old);
		method(AlbumPhotoViewer.class,"watchPolicy").invoke(overlay.adapter);
		AlbumModels.Photo protectedPhoto=photo(A,photo.previewUrl); protectedPhoto.downloadProtected=true; protectedPhoto.canDownload=false;
		overlay.deliver(protectedPhoto); assertTrue("Policy change cancels old byte call",old.isCanceled());
		heldPreview.release.countDown();
		assertTrue(heldPreview.returned.await(3,TimeUnit.SECONDS));
		await(()->transport.dispatcher().runningCallsCount()==0); ShadowLooper.idleMainLooper();
		assertNull("Old unprotected bytes cannot install after policy generation changes",overlay.currentBitmap());
		assertNull(cache.get(accountID,session,AlbumPhotoViewer.attachment(photo),4096,false));
		heldPreview.close(); heldPreview=null; // The fresh retry must not share the cancelled socket/held dispatcher.
		overlay.viewer.reloadAlbumPreview(); overlay.deliver(protectedPhoto); overlay.awaitBitmap();
		assertNull(cache.get(accountID,session,AlbumPhotoViewer.attachment(photo),4096,false));
	}

	@Test public void pageChangeCancelsOldHolderBytesSoLateACannotBecomeBOrWarmTheCache() throws Exception{
		AlbumModels.Photo a=photo(A,"https://preview.example.test/"+A+"/preview.jpg"), b=photo(B,"https://preview.example.test/"+B+"/preview.jpg");
		heldPreview=new HeldPreview(a.previewUrl);
		Overlay overlay=open(List.of(a,b)); overlay.deliver(a); assertTrue(heldPreview.entered.await(3,TimeUnit.SECONDS));
		Call old=overlay.previewCall(); assertNotNull(old);
		overlay.page(1); assertTrue("Page change cancels old byte call",old.isCanceled()); overlay.deliver(b);
		Bitmap current=overlay.awaitBitmap(); assertEquals(Color.BLUE,current.getPixel(0,0));
		assertEquals("B must display while old A remains held",1,heldPreview.release.getCount());
		heldPreview.release.countDown(); assertTrue(heldPreview.returned.await(3,TimeUnit.SECONDS));
		await(()->transport.dispatcher().runningCallsCount()==0);
		assertSame(current,overlay.currentBitmap()); assertNull(cache.get(accountID,session,AlbumPhotoViewer.attachment(a),4096,false));
		heldPreview.close(); heldPreview=null;
		overlay.page(0); overlay.deliver(a); assertEquals(Color.RED,overlay.awaitBitmap().getPixel(0,0)); assertEquals(3,imageRequests.size());
	}

	@Test public void tokenReplacementWhileAclPendingPurgesWarmRamAndRejectsOldCallback() throws Exception{
		AlbumModels.Photo photo=photo(A,"https://preview.example.test/"+A+"/preview.jpg");
		Overlay warm=open(List.of(photo)); warm.deliver(photo); warm.awaitBitmap(); warm.close();
		Overlay pending=open(List.of(photo)); assertTrue(pending.imagesBlank());
		session.token=new Token(); session.token.accessToken="replacement-token";
		pending.deliver(photo); assertFalse(pending.viewer.isAlbumHostValid()); assertTrue(pending.imagesBlank()); assertEquals(1,imageRequests.size());
		assertNull(cache.get(accountID,session,AlbumPhotoViewer.attachment(photo),4096,false)); pending.close();
		Overlay fresh=open(List.of(photo)); fresh.deliver(photo); fresh.awaitBitmap(); assertEquals(2,imageRequests.size());
	}

	@Test public void sessionReplacementAndPauseStopNeverExposeOldRamBeforeFreshAcl() throws Exception{
		AlbumModels.Photo photo=photo(A,"https://preview.example.test/"+A+"/preview.jpg");
		Overlay initial=open(List.of(photo)); initial.deliver(photo); initial.awaitBitmap();
		initial.adapter.onActivityPaused(host); assertTrue(initial.imagesBlank());
		assertNull(field(PhotoViewer.class,"albumSourceDrawable").get(initial.viewer));
		initial.adapter.onActivityResumed(host); assertTrue(initial.imagesBlank()); initial.deliver(photo); initial.awaitBitmap(); assertEquals(1,imageRequests.size());
		initial.adapter.onActivityStopped(host); assertNull(cache.get(accountID,session,AlbumPhotoViewer.attachment(photo),4096,false));
		AccountSession replacement=newSession("42","replacement-token"); sessions.put(accountID,replacement);
		Overlay second=open(List.of(photo)); assertTrue(second.imagesBlank()); second.deliver(photo); second.awaitBitmap(); assertEquals(2,imageRequests.size());
		second.close(); cache.invalidateSession(accountID,replacement); sessions.put(accountID,session);
	}

	private Overlay open(List<AlbumModels.Photo> photos) throws Exception{
		Constructor<AlbumPhotoViewer> constructor=AlbumPhotoViewer.class.getDeclaredConstructor(Activity.class,String.class,List.class,int.class); constructor.setAccessible(true);
		AlbumPhotoViewer adapter=constructor.newInstance(host,accountID,photos,0);
		List<Attachment> attachments=new ArrayList<>(); for(AlbumModels.Photo photo:photos) attachments.add(AlbumPhotoViewer.attachment(photo));
		PhotoViewer viewer=new PhotoViewer(host,null,attachments,0,null,accountID,new NoTransition(),adapter);
		field(AlbumPhotoViewer.class,"viewer").set(adapter,viewer); field(PhotoViewer.class,"albumHttpClient").set(viewer,transport);
		Overlay overlay=new Overlay(adapter,viewer); overlays.add(overlay); ShadowLooper.idleMainLooper();
		assertTrue(viewer.isAlbumHostValid()); assertNotNull(overlay.renewal()); assertTrue(overlay.renewal().getURL().getPath().contains("/photos/"));
		assertEquals("GET",overlay.renewal().getMethod()); assertNull(overlay.renewal().getRequestBody()); return overlay;
	}

	private final class Overlay{
		final AlbumPhotoViewer adapter;
		final PhotoViewer viewer;
		Overlay(AlbumPhotoViewer adapter,PhotoViewer viewer){ this.adapter=adapter; this.viewer=viewer; }
		MastodonAPIRequest<?> renewal() throws Exception{ return (MastodonAPIRequest<?>)field(AlbumPhotoViewer.class,"previewRequest").get(adapter); }
		void deliver(AlbumModels.Photo photo) throws Exception{ MastodonAPIRequest<?> request=renewal(); assertNotNull("Fresh ACL must exist",request); callback(request).onSuccess(detail(photo)); ShadowLooper.idleMainLooper(); }
		void page(int position) throws Exception{
			androidx.viewpager2.widget.ViewPager2 pager=(androidx.viewpager2.widget.ViewPager2)field(PhotoViewer.class,"pager").get(viewer);
			pager.setCurrentItem(position,false); ShadowLooper.idleMainLooper();
			assertEquals(position,field(PhotoViewer.class,"currentIndex").getInt(viewer)); assertNotNull(renewal());
		}
		@SuppressWarnings("unchecked") List<Attachment> attachments() throws Exception{ return (List<Attachment>)field(PhotoViewer.class,"attachments").get(viewer); }
		ImageView currentImage() throws Exception{
			int position=field(PhotoViewer.class,"currentIndex").getInt(viewer);
			for(Object holder:holders()){
				if(((androidx.recyclerview.widget.RecyclerView.ViewHolder)holder).getAbsoluteAdapterPosition()==position) return (ImageView)field(holder.getClass(),"imageView").get(holder);
			}
			throw new AssertionError("Real pager must have a holder for current page "+position);
		}
		Set<?> holders() throws Exception{ return (Set<?>)field(PhotoViewer.class,"albumPhotoHolders").get(viewer); }
		Bitmap currentBitmap() throws Exception{ Drawable drawable=currentImage().getDrawable(); return drawable instanceof BitmapDrawable bitmap ? bitmap.getBitmap() : null; }
		Bitmap awaitBitmap() throws Exception{
			try{ await(()->{ try{ return currentBitmap()!=null; }catch(Exception error){ throw new AssertionError(error); } }); }
			catch(AssertionError error){ throw new AssertionError(diagnostic(),error); }
			return currentBitmap();
		}
		Call previewCall() throws Exception{
			Map<?,?> calls=(Map<?,?>)field(PhotoViewer.class,"albumPreviewCalls").get(viewer);
			return calls.isEmpty() ? null : (Call)calls.values().iterator().next();
		}
		String diagnostic() throws Exception{
			StringBuilder text=new StringBuilder("Viewer preview timeout: requests=").append(imageRequests).append(", serverRequests=").append(server.getRequestCount())
					.append(", runningCalls=").append(transport.dispatcher().runningCallsCount()).append(", queuedCalls=").append(transport.dispatcher().queuedCallsCount());
			for(String name:List.of("currentIndex","mediaGeneration","albumPreviewGeneration","albumPreviewRevision","albumPreviewPosition","albumPreviewRefreshing","albumPreviewCalls"))
				text.append(", viewer.").append(name).append('=').append(field(PhotoViewer.class,name).get(viewer));
			for(String name:List.of("generation","previewRevision","currentIndex","previewRefreshing","previewRequest","paused","closed"))
				text.append(", adapter.").append(name).append('=').append(field(AlbumPhotoViewer.class,name).get(adapter));
			return text.append(", hostValid=").append(viewer.isAlbumHostValid()).append(", retryVisible=")
					.append(((View)field(PhotoViewer.class,"albumPreviewRetryButton").get(viewer)).getVisibility()).toString();
		}
		boolean imagesBlank() throws Exception{
			assertFalse("Real overlay must have image holders",holders().isEmpty());
			for(Object holder:holders()) if(((ImageView)field(holder.getClass(),"imageView").get(holder)).getDrawable()!=null) return false;
			return true;
		}
		void close(){ if(!isDismissed()) viewer.onDismissed(); }
		boolean isDismissed(){ try{ return field(PhotoViewer.class,"dismissed").getBoolean(viewer); }catch(Exception error){ throw new AssertionError(error); } }
	}

	/** Separate destination prevents a deliberately held/cancelled socket from starving the new page. */
	private static final class HeldPreview implements AutoCloseable{
		final MockWebServer server=new MockWebServer();
		final CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1), returned=new CountDownLatch(1);
		final HttpUrl original, base;
		HeldPreview(String url) throws IOException{
			original=HttpUrl.parse(url);
			server.setDispatcher(new Dispatcher(){
				@Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException{
					entered.countDown();
					try{ release.await(); return pngResponse(Color.RED); }
					finally{ returned.countDown(); }
				}
			});
			server.start(InetAddress.getByName("127.0.0.1"),0); base=server.url("/");
		}
		@Override public void close() throws IOException{ release.countDown(); server.shutdown(); }
	}

	private static AlbumModels.Photo photo(String id,String url){ AlbumModels.Photo photo=new AlbumModels.Photo(); photo.id=id; photo.previewUrl=url; photo.width=32; photo.height=32; return photo; }
	private static AlbumModels.PhotoDetailResponse detail(AlbumModels.Photo photo){ AlbumModels.PhotoDetailResponse result=new AlbumModels.PhotoDetailResponse(); result.photo=photo; return result; }
	private static int aclCount(){ return (int)RecordingApiExecution.requests.stream().filter(request->"GET".equals(request.getMethod()) && request.getURL().getPath().contains("/photos/")).count(); }
	private static int authorizationCount(){ return (int)RecordingApiExecution.requests.stream().filter(request->request.getURL().getPath().endsWith("/authorize")).count(); }
	private static AccountSession newSession(String id,String access) throws Exception{
		Constructor<AccountSession> constructor=AccountSession.class.getDeclaredConstructor(); constructor.setAccessible(true);
		AccountSession session=constructor.newInstance(); session.domain="album-preview-viewer.example.test"; session.self=new Account(); session.self.id=id;
		session.token=new Token(); session.token.accessToken=access; return session;
	}
	private static MockResponse pngResponse(int color){
		Bitmap bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888); bitmap.eraseColor(color);
		ByteArrayOutputStream output=new ByteArrayOutputStream(); assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output)); bitmap.recycle();
		return new MockResponse().setHeader("Content-Type","image/png").setHeader("Cache-Control","no-store").setHeader("Connection","close").setBody(new Buffer().write(output.toByteArray()));
	}
	private static void await(BooleanSupplier condition) throws Exception{
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
		while(System.nanoTime()<deadline){ ShadowLooper.idleMainLooper(); if(condition.getAsBoolean()) return; Thread.sleep(10); }
		ShadowLooper.idleMainLooper(); assertTrue("Timed out waiting for real viewer/bytes callback",condition.getAsBoolean());
	}
	@SuppressWarnings("unchecked") private static Callback<AlbumModels.PhotoDetailResponse> callback(MastodonAPIRequest<?> request) throws Exception{
		Class<?> type=request.getClass();
		while(type!=null){ try{ return (Callback<AlbumModels.PhotoDetailResponse>)field(type,"callback").get(request); }catch(NoSuchFieldException ignored){ type=type.getSuperclass(); } }
		throw new AssertionError("Real API callback missing");
	}
	private static Field field(Class<?> type,String name) throws Exception{ Field field=type.getDeclaredField(name); field.setAccessible(true); return field; }
	private static Method method(Class<?> type,String name,Class<?>... parameters) throws Exception{ Method method=type.getDeclaredMethod(name,parameters); method.setAccessible(true); return method; }
	private void select(String id) throws Exception{ field(AccountSessionManager.class,"lastActiveAccountID").set(AccountSessionManager.getInstance(),id); }

	@Implements(MastodonAPIRequest.class) public static class RecordingApiExecution{
		static final List<MastodonAPIRequest<?>> requests=new ArrayList<>();
		@RealObject MastodonAPIRequest<?> request;
		@Implementation public MastodonAPIRequest<?> exec(String accountID){ requests.add(request); return request; }
	}
	private static final class NoTransition implements PhotoViewer.Listener{
		@Override public void setPhotoViewVisibility(int index,boolean visible){}
		@Override public boolean startPhotoViewTransition(int index,android.graphics.Rect rect,int[] radius){ return false; }
		@Override public void setTransitioningViewTransform(float x,float y,float scale){}
		@Override public void endPhotoViewTransition(){}
		@Override public Drawable getPhotoViewCurrentDrawable(int index){ return null; }
		@Override public void photoViewerDismissed(){}
		@Override public void onRequestPermissions(String[] permissions){}
	}
}
