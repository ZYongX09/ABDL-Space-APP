package org.joinmastodon.android.albums;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Insets;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Looper;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.MastodonAPIRequest;
import org.joinmastodon.android.api.requests.albums.AlbumRequest;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Attachment;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.model.albums.AlbumModels;
import org.joinmastodon.android.ui.photoviewer.PhotoViewer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import me.grishka.appkit.utils.V;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class)
public class AlbumPhotoViewerTest{
	@Before public void setup(){
		MastodonApp.context=RuntimeEnvironment.getApplication();
		V.setApplicationContext(MastodonApp.context);
	}

	private static Field field(Class<?> type, String name) throws Exception{
		Field field=type.getDeclaredField(name); field.setAccessible(true); return field;
	}
	private static Method method(Class<?> type, String name, Class<?>... args) throws Exception{
		Method method=type.getDeclaredMethod(name, args); method.setAccessible(true); return method;
	}
	private AlbumModels.Photo photo(){
		AlbumModels.Photo photo=new AlbumModels.Photo(); photo.id="photo-test";
		photo.previewUrl="https://preview.example.test/signed"; photo.hdUrl="https://hd.example.test/signed";
		photo.width=2000; photo.height=3000; photo.description="照片描述";
		return photo;
	}

	@Test public void attachmentNeverStoresAuthorizedHdOrLossless(){
		AlbumModels.Photo photo=photo(); photo.isOwner=true; photo.ownerSponsor=true;
		Attachment attachment=AlbumPhotoViewer.attachment(photo);
		assertEquals(Attachment.Type.IMAGE, attachment.type);
		assertEquals(photo.previewUrl, attachment.url);
		assertEquals(photo.previewUrl, attachment.previewUrl);
		assertEquals(photo.description, attachment.description);
		assertNull(attachment.remoteUrl);
		assertEquals(2000, attachment.getWidth()); assertEquals(3000, attachment.getHeight());
	}

	@Test public void expiredPreviewRenewalIsGetWithoutQuotaBody() throws Exception{
		AlbumRequest<AlbumModels.PhotoDetailResponse> request=AlbumPhotoViewer.previewRequest("photo-test");
		assertEquals("GET", request.getMethod());
		assertNull(request.getRequestBody());
		assertTrue(request.isSensitiveRequest());
		field(MastodonAPIRequest.class, "domain").set(request, "selected-session.example.test");
		assertEquals("https://selected-session.example.test/api/v1/albums/photos/photo-test", request.getURL().toString());
		assertFalse(request.getURL().getPath().contains("authorize"));
	}

	@Test public void renewedMetadataUpdatesOnlyPreviewAttachmentAndLiveOwnerFacts() throws Exception{
		AlbumModels.Photo old=photo();
		Attachment attachment=AlbumPhotoViewer.attachment(old);
		String fresh="https://preview.example.test/fresh-signature";
		method(PhotoViewer.class, "setAlbumPreviewMetadata", Attachment.class, String.class, String.class, int.class, int.class)
				.invoke(null, attachment, fresh, "renewed description", 640, 960);
		assertEquals(fresh, attachment.url); assertEquals(fresh, attachment.previewUrl);
		assertEquals("renewed description", attachment.description);
		assertEquals(640, attachment.getWidth()); assertEquals(960, attachment.getHeight());
		assertNull(attachment.remoteUrl); assertNotEquals(old.hdUrl, attachment.url);
		try(ActivityController<Activity> lifecycle=Robolectric.buildActivity(Activity.class).setup()){
			Constructor<AlbumPhotoViewer> constructor=AlbumPhotoViewer.class.getDeclaredConstructor(Activity.class, String.class, List.class, int.class); constructor.setAccessible(true);
			AlbumPhotoViewer adapter=constructor.newInstance(lifecycle.get(), "missing", List.of(old), 0);
			AlbumModels.Photo renewed=photo(); renewed.previewUrl=fresh; renewed.isOwner=true; renewed.ownerSponsor=true; renewed.originalAvailable=true; renewed.liked=true; renewed.likesCount=9;
			@SuppressWarnings("unchecked") List<AlbumModels.Photo> photos=(List<AlbumModels.Photo>)field(AlbumPhotoViewer.class, "photos").get(adapter);
			photos.set(0, renewed);
			PhotoViewer.AlbumInfo info=adapter.getInfo(0);
			assertTrue(info.isOwner); assertTrue(info.originalAvailable); assertTrue(info.liked); assertEquals(9, info.likesCount);
			assertTrue(AlbumPhotoViewer.mayAutoLoadHd(renewed));
			adapter.onDismissed();
		}
	}

	@Test public void automaticHdRequiresAllServerOwnerSponsorFacts(){
		AlbumModels.Photo photo=photo();
		assertFalse(AlbumPhotoViewer.mayAutoLoadHd(photo));
		photo.ownerSponsor=true; assertFalse(AlbumPhotoViewer.mayAutoLoadHd(photo));
		photo.isOwner=true; assertTrue(AlbumPhotoViewer.mayAutoLoadHd(photo));
		photo.hdUrl=null; assertFalse(AlbumPhotoViewer.mayAutoLoadHd(photo));
	}

	@Test public void losslessAvailabilityIsOwnerOnly(){
		AlbumModels.Photo photo=photo(); photo.originalAvailable=true;
		assertFalse(AlbumPhotoViewer.mayUseLossless(photo));
		photo.isOwner=true; assertTrue(AlbumPhotoViewer.mayUseLossless(photo));
		photo.originalAvailable=false; assertFalse(AlbumPhotoViewer.mayUseLossless(photo));
	}

	@Test public void explicitActionsHaveNewUuidButRetriesRetainSameOperation(){
		AlbumPhotoViewer.MediaAction view=new AlbumPhotoViewer.MediaAction(0, "original", false, 10);
		AlbumPhotoViewer.MediaAction download=new AlbumPhotoViewer.MediaAction(0, "original", true, 10);
		UUID.fromString(view.operationId); UUID.fromString(download.operationId);
		assertNotEquals(view.operationId, download.operationId);
		Map<String,Object> first=AlbumPhotoViewer.authorizationBody(view.variant, view.operationId, null);
		Map<String,Object> retry=AlbumPhotoViewer.authorizationBody(view.variant, view.operationId, 4);
		assertEquals(first.get("operation_id"), retry.get("operation_id"));
		assertEquals("original", retry.get("variant"));
		assertFalse(first.containsKey("notice_version")); assertEquals(4, retry.get("notice_version"));
		assertFalse(first.containsKey("media_key"));
	}

	@Test public void viewportShrinksAboveCommentsAndKeyboardWithoutCropping(){
		PhotoViewer.AlbumViewport initial=PhotoViewer.albumViewport(1080, 1920, 96, 1800, 0);
		PhotoViewer.AlbumViewport comments=PhotoViewer.albumViewport(1080, 1920, 96, 1200, 0);
		PhotoViewer.AlbumViewport keyboard=PhotoViewer.albumViewport(1080, 1920, 96, 900, 680);
		assertEquals(1704, initial.height);
		assertEquals(1104, comments.height);
		assertEquals(804, keyboard.height);
		assertEquals(1020, keyboard.bottomInset);
		for(PhotoViewer.AlbumViewport viewport:List.of(initial, comments, keyboard)){
			for(int[] image:new int[][]{{2000,3000}, {4000,1000}, {1000,4000}}){
				float scale=viewport.fitCenterScale(image[0], image[1]);
				assertTrue(image[0]*scale<=viewport.width+.01f);
				assertTrue(image[1]*scale<=viewport.height+.01f);
			}
		}
		PhotoViewer.AlbumViewport pendingInsets=PhotoViewer.albumViewport(1080, 1920, 96, 1600, 1000);
		assertEquals(920, pendingInsets.top+pendingInsets.height); // IME clamps even before panel layout catches up.
		PhotoViewer.AlbumViewport compact=PhotoViewer.albumViewport(800, 320, 90, 60, 200);
		assertEquals(0, compact.height); assertEquals(60, compact.top);
	}

	@Test public void realCancellationDropsAllTrackedRequestsAndTransientActions() throws Exception{
		try(ActivityController<Activity> lifecycle=Robolectric.buildActivity(Activity.class).setup()){
			Activity host=lifecycle.get();
			Constructor<AlbumPhotoViewer> constructor=AlbumPhotoViewer.class.getDeclaredConstructor(Activity.class, String.class, List.class, int.class);
			constructor.setAccessible(true);
			AlbumPhotoViewer adapter=constructor.newInstance(host, "missing-test-account", List.of(photo()), 0);
			TrackedRequest request=new TrackedRequest();
			@SuppressWarnings("unchecked") Set<MastodonAPIRequest<?>> requests=(Set<MastodonAPIRequest<?>>)field(AlbumPhotoViewer.class, "requests").get(adapter);
			requests.add(request);
			field(AlbumPhotoViewer.class, "previewRequest").set(adapter, request);
			field(AlbumPhotoViewer.class, "previewRefreshing").setBoolean(adapter, true);
			field(AlbumPhotoViewer.class, "mediaAction").set(adapter, new AlbumPhotoViewer.MediaAction(0, "original", true, 0));
			field(AlbumPhotoViewer.class, "commentAction").set(adapter, new AlbumPhotoViewer.CommentAction("test"));
			for(String name:List.of("hdLoaded", "originalLoaded", "likeBusy", "commentsLoading", "commentBusy")) field(AlbumPhotoViewer.class, name).setBoolean(adapter, true);
			adapter.cancelPending();
			assertTrue(request.canceled); assertTrue(requests.isEmpty());
			assertNull(field(AlbumPhotoViewer.class, "previewRequest").get(adapter));
			assertFalse(field(AlbumPhotoViewer.class, "previewRefreshing").getBoolean(adapter));
			assertEquals(1, field(AlbumPhotoViewer.class, "previewRevision").getLong(adapter));
			assertNull(field(AlbumPhotoViewer.class, "mediaAction").get(adapter));
			assertNull(field(AlbumPhotoViewer.class, "commentAction").get(adapter));
			assertEquals(1, field(AlbumPhotoViewer.class, "generation").getLong(adapter));
			for(String name:List.of("hdLoaded", "originalLoaded", "likeBusy", "commentsLoading", "commentBusy")) assertFalse(field(AlbumPhotoViewer.class, name).getBoolean(adapter));
			adapter.onDismissed();
		}
	}

	@Test public void exactSessionReplacementIsNotAValidAuthorization() throws Exception{
		try(ActivityController<Activity> lifecycle=Robolectric.buildActivity(Activity.class).setup()){
			Constructor<AccountSession> sessionConstructor=AccountSession.class.getDeclaredConstructor(); sessionConstructor.setAccessible(true);
			AccountSession session=sessionConstructor.newInstance(); session.domain="album-test.example"; session.self=new Account(); session.self.id="42"; session.token=new Token();
			String accountID="album-test.example_42";
			AccountSessionManager manager=AccountSessionManager.getInstance();
			@SuppressWarnings("unchecked") HashMap<String,AccountSession> sessions=(HashMap<String,AccountSession>)field(AccountSessionManager.class, "sessions").get(manager);
			String previous=(String)field(AccountSessionManager.class, "lastActiveAccountID").get(manager);
			AccountSession old=sessions.put(accountID, session);
			field(AccountSessionManager.class, "lastActiveAccountID").set(manager, accountID);
			try{
				Constructor<AlbumPhotoViewer> constructor=AlbumPhotoViewer.class.getDeclaredConstructor(Activity.class, String.class, List.class, int.class); constructor.setAccessible(true);
				AlbumPhotoViewer adapter=constructor.newInstance(lifecycle.get(), accountID, List.of(photo()), 0);
				Method valid=method(AlbumPhotoViewer.class, "sessionValid");
				assertEquals(true, valid.invoke(adapter));
				sessions.put(accountID, sessionConstructor.newInstance()); assertEquals(false, valid.invoke(adapter));
				sessions.put(accountID, session); field(AccountSessionManager.class, "lastActiveAccountID").set(manager, "other"); assertEquals(false, valid.invoke(adapter));
				adapter.onDismissed(); assertEquals(false, valid.invoke(adapter));
			}finally{
				if(old==null) sessions.remove(accountID); else sessions.put(accountID, old);
				field(AccountSessionManager.class, "lastActiveAccountID").set(manager, previous);
			}
		}
	}

	@Test @Config(sdk=30, qualifiers="w360dp-h800dp-port-mdpi") public void actualOverlayResizesPagerFitsImageAndCancelsOnPageChange() throws Exception{
		try(ActivityController<DisplayAssociatedActivity> lifecycle=Robolectric.buildActivity(DisplayAssociatedActivity.class)){
			DisplayAssociatedActivity host=lifecycle.get(); host.setTheme(org.joinmastodon.android.R.style.Theme_Mastodon_Dark); lifecycle.setup().visible();
			assertNotNull(host.getDisplay());
			Point displaySize=new Point(); host.getDisplay().getRealSize(displaySize);
			assertTrue("Display must fit a comments viewport", displaySize.y>V.dp(480));
			ProbeDelegate delegate=new ProbeDelegate();
			AlbumModels.Photo photo=photo();
			PhotoViewer viewer=new PhotoViewer(host, null, List.of(AlbumPhotoViewer.attachment(photo), AlbumPhotoViewer.attachment(photo)), 0, null, "missing", new NoTransition(), delegate);
			try{
				assertNull(field(PhotoViewer.class, "albumSession").get(viewer)); // Missing session prevents all external fetches.
				assertFalse(viewer.isAlbumHostValid());
				View root=(View)field(PhotoViewer.class, "windowView").get(viewer);
				View pager=(View)field(PhotoViewer.class, "pager").get(viewer);
				View bottom=(View)field(PhotoViewer.class, "bottomBar").get(viewer);
				View toolbar=(View)field(PhotoViewer.class, "toolbarWrap").get(viewer);
				FrameLayout panel=new FrameLayout(host);
				viewer.setAlbumCommentsPanel(panel);
				viewer.setAlbumCommentsVisible(true);
				settleOverlay(viewer, root, displaySize.x, displaySize.y);
				assertSame(bottom, panel.getParent());
				assertOverlayBounds("comments", root, pager, toolbar, bottom, 0);
				int imeHeight=V.dp(280);
				WindowInsets ime=new WindowInsets.Builder().setInsets(WindowInsets.Type.systemBars(), Insets.of(0, V.dp(24), 0, V.dp(24)))
						.setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, imeHeight)).build();
				method(PhotoViewer.class, "applyAlbumInsets", WindowInsets.class).invoke(viewer, ime);
				settleOverlay(viewer, root, displaySize.x, displaySize.y);
				assertOverlayBounds("comments and IME", root, pager, toolbar, bottom, imeHeight);
				assertTrue("IME must keep the comments bottom above keyboard: "+bounds(root, pager, toolbar, bottom),
						rootTop(root, bottom)+bottom.getHeight()<=root.getHeight()-imeHeight);
				assertTrue("Comments+IME must leave image space: "+bounds(root, pager, toolbar, bottom), pager.getHeight()>0);
				@SuppressWarnings("unchecked") Set<Object> holders=(Set<Object>)field(PhotoViewer.class, "albumPhotoHolders").get(viewer);
				assertFalse(holders.isEmpty());
				for(Object holder:holders){
					ImageView image=(ImageView)field(holder.getClass(), "imageView").get(holder);
					assertEquals(ImageView.ScaleType.FIT_CENTER, image.getScaleType());
				}
				int canceled=delegate.canceled;
				method(PhotoViewer.class, "onPageChanged", int.class).invoke(viewer, 1);
				assertTrue(delegate.canceled>canceled);
				assertEquals(1, delegate.position);
				viewer.onPause(); assertTrue(delegate.canceled>canceled+1);
				assertNull(field(PhotoViewer.class, "albumSourceDrawable").get(viewer));
				assertNull(field(PhotoViewer.class, "albumMediaCall").get(viewer));
				assertTrue(((Map<?,?>)field(PhotoViewer.class, "albumPreviewCalls").get(viewer)).isEmpty());
				assertEquals(0, delegate.previewRenewals);
			}finally{ viewer.onDismissed(); }
		}
	}

	private static int windowTop(View view){ int[] location=new int[2]; view.getLocationInWindow(location); return location[1]; }

	/** Independent hierarchy calculation: unlike production's descendant-rect mapper, no window/global shadow state. */
	private static int rootTop(View root, View child){
		float top=0;
		View current=child;
		while(current!=root){
			assertTrue("View must belong to overlay root", current.getParent() instanceof View);
			View parent=(View)current.getParent();
			top+=current.getTop()+current.getTranslationY()-parent.getScrollY();
			current=parent;
		}
		return Math.round(top);
	}

	private static void settleOverlay(PhotoViewer viewer, View root, int width, int height) throws Exception{
		// ViewRootImpl normally repeats traversal after listeners change nested panel height/pager margins.
		// This manual fixture must invalidate measure caches throughout the tree between those passes.
		for(int pass=0; pass<8; pass++){
			forceLayoutTree(root);
			root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
			root.layout(0, 0, width, height);
			method(PhotoViewer.class, "updateAlbumViewport").invoke(viewer);
		}
		assertEquals("Overlay width must match its associated display", width, root.getWidth());
		assertEquals("Overlay height must match its associated display", height, root.getHeight());
	}

	private static void forceLayoutTree(View view){
		view.forceLayout();
		if(view instanceof ViewGroup group) for(int i=0;i<group.getChildCount();i++) forceLayoutTree(group.getChildAt(i));
	}

	private static void assertOverlayBounds(String phase, View root, View pager, View toolbar, View bottom, int imeHeight){
		String evidence=phase+": "+bounds(root, pager, toolbar, bottom);
		assertTrue("Pager top cannot be negative: "+evidence, pager.getTop()>=0);
		assertTrue("Image must not extend below comments: "+evidence, rootTop(root, pager)+pager.getHeight()<=rootTop(root, bottom));
		assertTrue("Image must start below toolbar: "+evidence, rootTop(root, pager)>=rootTop(root, toolbar)+toolbar.getHeight());
		PhotoViewer.AlbumViewport expected=PhotoViewer.albumViewport(root.getWidth(), root.getHeight(),
				rootTop(root, toolbar)+toolbar.getHeight(), rootTop(root, bottom), imeHeight);
		assertEquals("Pager top must equal geometry result: "+evidence, expected.top, pager.getTop());
		assertEquals("Pager height must equal geometry result: "+evidence, expected.height, pager.getHeight());
	}

	private static String bounds(View root, View pager, View toolbar, View bottom){
		FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)pager.getLayoutParams();
		return "root="+viewBounds(root)+", pager="+viewBounds(pager)+", toolbar="+viewBounds(toolbar)+", bottom="+viewBounds(bottom)
				+", hierarchy tops="+rootTop(root, pager)+"/"+rootTop(root, toolbar)+"/"+rootTop(root, bottom)
				+", pager margins="+params.topMargin+"/"+params.bottomMargin;
	}

	private static String viewBounds(View view){
		return "[windowTop="+windowTop(view)+", localTop="+view.getTop()+", size="+view.getWidth()+"x"+view.getHeight()
				+", measured="+view.getMeasuredWidth()+"x"+view.getMeasuredHeight()+", translationY="+view.getTranslationY()+"]";
	}

	/** SDK30 Robolectric does not attach a display to plain Activity contexts. Supply only that fixture association. */
	public static final class DisplayAssociatedActivity extends Activity{
		private Display display;
		@Override protected void attachBaseContext(Context base){
			display=base.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
			if(display==null) throw new AssertionError("Robolectric default display missing");
			super.attachBaseContext(base.createDisplayContext(display));
		}
		@Override public Display getDisplay(){ return display; }
	}

	private static final class ProbeDelegate implements PhotoViewer.AlbumDelegate{
		int canceled, position, previewRenewals;
		@Override public PhotoViewer.AlbumInfo getInfo(int position){ return new PhotoViewer.AlbumInfo(); }
		@Override public void onPhotoChanged(int position){ this.position=position; }
		@Override public void refreshPreview(int position, PhotoViewer.AlbumPreviewCallback callback){ previewRenewals++; callback.onFailed(); }
		@Override public void onView(int position, String variant){}
		@Override public void onDownload(int position){}
		@Override public void onLike(int position){}
		@Override public void onComments(int position){}
		@Override public void onCommentsClosed(){}
		@Override public void cancelPending(){ canceled++; }
		@Override public void onDismissed(){}
	}

	private static final class NoTransition implements PhotoViewer.Listener{
		@Override public void setPhotoViewVisibility(int index, boolean visible){}
		@Override public boolean startPhotoViewTransition(int index, android.graphics.Rect rect, int[] radius){ return false; }
		@Override public void setTransitioningViewTransform(float x, float y, float scale){}
		@Override public void endPhotoViewTransition(){}
		@Override public android.graphics.drawable.Drawable getPhotoViewCurrentDrawable(int index){ return null; }
		@Override public void photoViewerDismissed(){}
		@Override public void onRequestPermissions(String[] permissions){}
	}

	@Test public void sourceContractKeepsOrdinaryGateAndAlbumCacheBranchesSeparate() throws Exception{
		Path root=Path.of(System.getProperty("user.dir"));
		String viewer=Files.readString(root.resolve("src/main/java/org/joinmastodon/android/ui/photoviewer/PhotoViewer.java"));
		String album=Files.readString(root.resolve("src/main/java/org/joinmastodon/android/albums/AlbumPhotoViewer.java"));
		assertTrue(viewer.contains("if(albumDelegate==null){\n\t\t\toriginalGate=new SponsorOriginalGate"));
		assertTrue(viewer.contains("if(albumDelegate!=null){\n\t\t\t\talbumPhotoHolders.add(this)"));
		assertTrue(viewer.contains("ImageView.ScaleType.FIT_CENTER"));
		assertTrue(viewer.contains(".cache(null).followRedirects(false).followSslRedirects(false)"));
		assertTrue(viewer.contains("if(currentIndex!=index) invalidateOriginalWork()"));
		assertTrue(viewer.contains("albumDelegate.cancelPending()"));
		assertTrue(viewer.contains("holder.imageView.setImageDrawable(null)"));
		assertTrue(viewer.contains("IS_PENDING, 1")); assertTrue(viewer.contains("target.discard()"));
		assertFalse(album.contains("SponsorOriginalGate"));
		assertFalse(album.contains("SponsorRequest.authorize("));
		String automaticFailure=album.substring(album.indexOf("if(automatic){"), album.indexOf("}else retryMedia", album.indexOf("if(automatic){")));
		assertTrue(automaticFailure.contains("finishMedia(action)"));
		assertFalse(automaticFailure.contains("authorize(")); // Stale sponsor DTO failure never silently spends quota.
		assertFalse(album.contains("ImageCache"+".getInstance"));
		assertFalse(album.contains("execNoAuth"));
		assertTrue(album.contains("viewer.onPause()")); assertTrue(album.contains("onActivityStopped(Activity host)"));
		String renewal=album.substring(album.indexOf("@Override public void refreshPreview"), album.indexOf("@Override public void onView"));
		assertFalse(renewal.contains("authorize(")); assertFalse(renewal.contains("beginMedia("));
		assertTrue(renewal.contains("photos.set(position, photo)"));
		assertTrue(renewal.contains("revision!=previewRevision || position!=currentIndex"));
		assertTrue(renewal.contains("response.httpStatus==403 || response.httpStatus==404"));
		assertTrue(renewal.contains("callback.onAccessDenied()"));
		assertTrue(viewer.contains("albumPreviewGeneration!=mediaGeneration || albumPreviewPosition!=position"));
		assertTrue(viewer.contains("expired && albumPreviewRenewals++<1"));
		assertTrue(viewer.contains("albumDelegate.refreshPreview(position"));
		assertTrue(viewer.contains("setAlbumPreviewMetadata(attachment, previewUrl"));
		assertTrue(album.contains("query(\"offset\", requestedOffset)"));
		assertTrue(album.contains("photo.liked=result.liked"));
	}

	private static final class TrackedRequest extends MastodonAPIRequest<Object>{
		boolean canceled;
		TrackedRequest(){ super(HttpMethod.GET, "/offline-test", Object.class); }
		@Override public synchronized void cancel(){ canceled=true; }
	}
}
