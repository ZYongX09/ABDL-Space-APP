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
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.fragments.HomeTimelineFragment;
import org.joinmastodon.android.model.Status;
import org.joinmastodon.android.model.StatusPrivacy;
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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;

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
		assertEquals("view", first.get("intent"));
		Map<String,Object> saving=AlbumPhotoViewer.authorizationBody(download.variant, download.operationId, null, true);
		Map<String,Object> savingRetry=AlbumPhotoViewer.authorizationBody(download.variant, download.operationId, 4, true);
		assertEquals("download", saving.get("intent")); assertEquals("download", savingRetry.get("intent"));
		assertEquals(saving.get("operation_id"), savingRetry.get("operation_id"));
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

	@Test public void albumReplyStaysGoneAndProgrammaticClickNeverCallsDelegate() throws Exception{
		try(ActivityController<Activity> lifecycle=Robolectric.buildActivity(Activity.class).setup()){
			Activity host=lifecycle.get(); host.setTheme(R.style.Theme_Mastodon_Dark);
			ProbeDelegate delegate=new ProbeDelegate();
			PhotoViewer viewer=new PhotoViewer(host, null, List.of(AlbumPhotoViewer.attachment(photo()), AlbumPhotoViewer.attachment(photo())), 0, null, "missing", new NoTransition(), delegate);
			try{
				View reply=(View)field(PhotoViewer.class, "replyBtn").get(viewer);
				for(int position=0; position<2; position++){
					method(PhotoViewer.class, "onPageChanged", int.class).invoke(viewer, position);
					method(PhotoViewer.class, "updateBackgroundColor", int.class, float.class).invoke(viewer, position, 0f);
					viewer.refreshAlbumControls();
					assertEquals(View.GONE, reply.getVisibility()); assertFalse(reply.isEnabled()); assertFalse(reply.isFocusable());
					reply.performClick(); method(PhotoViewer.class, "openAlbumComments").invoke(viewer);
					assertEquals(0, delegate.commentEntries);
					assertFalse(field(PhotoViewer.class, "albumCommentsVisible").getBoolean(viewer));
					assertEquals(View.VISIBLE, ((View)field(PhotoViewer.class, "favoriteBtn").get(viewer)).getVisibility());
				}
			}finally{ viewer.onDismissed(); }
		}
	}

	@Test @Config(shadows=RecordingApiExecution.class) public void albumAdapterRejectsEntryAndAutomaticCommentRequestsWithValidSession() throws Exception{
		RecordingApiExecution.routes.clear();
		withSession((accountID, session)->{
			try(ActivityController<Activity> lifecycle=Robolectric.buildActivity(Activity.class).setup().visible()){
				Activity host=lifecycle.get(); host.setTheme(R.style.Theme_Mastodon_Dark);
				Constructor<AlbumPhotoViewer> constructor=AlbumPhotoViewer.class.getDeclaredConstructor(Activity.class, String.class, List.class, int.class); constructor.setAccessible(true);
				AlbumPhotoViewer adapter=constructor.newInstance(host, accountID, List.of(photo(), photo()), 0);
				PhotoViewer viewer=new PhotoViewer(host, null, List.of(AlbumPhotoViewer.attachment(photo()), AlbumPhotoViewer.attachment(photo())), 0, null, accountID, new NoTransition(), adapter);
				field(AlbumPhotoViewer.class, "viewer").set(adapter, viewer);
				try{
					View overlay=(View)field(PhotoViewer.class, "windowView").get(viewer);
					org.robolectric.shadows.ShadowLooper.idleMainLooper();
					assertTrue("Overlay must be attached", overlay.isAttachedToWindow());
					assertSame("Viewer must retain selected session", session, field(PhotoViewer.class, "albumSession").get(viewer));
					assertSame(session, AccountSessionManager.getInstance().tryGetAccount(accountID));
					assertEquals(accountID, AccountSessionManager.getInstance().getLastActiveAccountID());
					assertFalse(host.isFinishing()); assertFalse(host.isDestroyed());
					assertFalse(field(PhotoViewer.class, "closing").getBoolean(viewer)); assertFalse(field(PhotoViewer.class, "dismissed").getBoolean(viewer));
					assertTrue(viewer.isAlbumHostValid()); assertEquals(true, method(AlbumPhotoViewer.class, "live", long.class).invoke(adapter, 0L));
					assertFalse(adapter.supportsComments());
					// Verify this fixture observes API execution, rather than accepting a dead network seam.
					int beforePositiveControl=RecordingApiExecution.routes.size();
					AlbumPhotoViewer.previewRequest("photo-test").exec(accountID);
					assertEquals(beforePositiveControl+1, RecordingApiExecution.routes.size()); RecordingApiExecution.routes.clear();
					adapter.onComments(0);
					assertFalse(field(AlbumPhotoViewer.class, "commentsOpen").getBoolean(adapter));
					assertNull(field(AlbumPhotoViewer.class, "commentsPanel").get(adapter));
					// A stale/manual internal flag must not revive automatic pagination on a page callback.
					field(AlbumPhotoViewer.class, "commentsOpen").setBoolean(adapter, true);
					adapter.onPhotoChanged(1);
					method(AlbumPhotoViewer.class, "loadComments", boolean.class).invoke(adapter, true);
					method(AlbumPhotoViewer.class, "addComment").invoke(adapter);
					adapter.onComments(1);
					((View)field(PhotoViewer.class, "replyBtn").get(viewer)).performClick();
					assertFalse(field(PhotoViewer.class, "albumCommentsVisible").getBoolean(viewer));
					assertNull(field(AlbumPhotoViewer.class, "commentsRequest").get(adapter));
					assertTrue(((Set<?>)field(AlbumPhotoViewer.class, "requests").get(adapter)).isEmpty());
					assertTrue("No comment request may reach the API execution boundary", RecordingApiExecution.routes.stream().noneMatch(route->route.contains("/comments")));
				}finally{ viewer.onDismissed(); }
			}
		});
	}

	@Test public void ordinaryPostImageReplyRemainsVisibleAndCallsExistingPreReplyFlow() throws Exception{
		withSession((accountID, session)->{
			try(ActivityController<Activity> lifecycle=Robolectric.buildActivity(Activity.class).setup()){
				Activity host=lifecycle.get(); host.setTheme(R.style.Theme_Mastodon_Dark);
				Status status=new Status(); status.id="post-test"; status.account=session.self; status.visibility=StatusPrivacy.PUBLIC; status.repliesCount=3;
				ReplyProbeFragment parent=new ReplyProbeFragment();
				Attachment attachment=AlbumPhotoViewer.attachment(photo()); attachment.url=attachment.previewUrl="file:///nonexistent-post-reply-fixture.jpg";
				PhotoViewer viewer=new PhotoViewer(host, parent, List.of(attachment), 0, status, accountID, new NoTransition());
				try{
					assertNull(field(PhotoViewer.class, "albumDelegate").get(viewer));
					View overlay=(View)field(PhotoViewer.class, "windowView").get(viewer);
					assertEquals(0, ((WindowManager.LayoutParams)overlay.getLayoutParams()).flags & WindowManager.LayoutParams.FLAG_SECURE);
					viewer.refreshAlbumControls(); // Album-only refresh must not touch post controls.
					View reply=(View)field(PhotoViewer.class, "replyBtn").get(viewer);
					assertEquals(View.VISIBLE, reply.getVisibility()); assertTrue(reply.isEnabled());
					reply.performClick(); assertEquals(1, parent.replyEntries); assertSame(status, parent.replyStatus); assertNotNull(parent.proceed);
				}finally{ viewer.onDismissed(); }
			}
		});
	}

	@Test public void protectedPermissionFactsKeepOwnerAndNullableLegacySemantics(){
		AlbumModels.Photo photo=photo();
		assertNull(photo.canDownload); assertTrue(AlbumPhotoViewer.mayDownload(photo));
		photo.downloadProtected=true; photo.canDownload=true;
		assertTrue(AlbumPhotoViewer.protectedNonowner(photo)); assertFalse(AlbumPhotoViewer.mayDownload(photo));
		photo.ownerSponsor=true; assertFalse(AlbumPhotoViewer.mayDownload(photo));
		photo.isOwner=true; photo.canDownload=false;
		assertFalse(AlbumPhotoViewer.protectedNonowner(photo)); assertTrue(AlbumPhotoViewer.mayDownload(photo));
		photo.isOwner=false; photo.downloadProtected=false;
		assertFalse(AlbumPhotoViewer.mayDownload(photo));
		photo.canDownload=null; assertTrue(AlbumPhotoViewer.mayDownload(photo));
	}

	@Test public void actualSecureOverlayPreservesOtherFlagsAndNeverChangesHostWindow() throws Exception{
		try(ActivityController<Activity> lifecycle=Robolectric.buildActivity(Activity.class).setup().visible()){
			Activity host=lifecycle.get(); host.setTheme(R.style.Theme_Mastodon_Dark);
			host.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
			int hostFlags=host.getWindow().getAttributes().flags;
			ProbeDelegate delegate=new ProbeDelegate(); delegate.info.downloadProtected=true; delegate.info.canDownload=false;
			PhotoViewer viewer=new PhotoViewer(host, null, List.of(AlbumPhotoViewer.attachment(photo()), AlbumPhotoViewer.attachment(photo())), 0, null, "missing", new NoTransition(), delegate);
			try{
				attachOverlay(viewer); // Attached window first: flag transitions ride real updateViewLayout calls.
				View overlay=(View)field(PhotoViewer.class, "windowView").get(viewer);
				WindowManager.LayoutParams params=(WindowManager.LayoutParams)overlay.getLayoutParams();
				assertTrue((params.flags & WindowManager.LayoutParams.FLAG_SECURE)!=0);
				assertEquals(0, delegate.previewRenewals); // Initial attachment is secured even before renewal is possible.
				params.flags|=WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
				for(String name:List.of("downloadButton", "viewOriginalBtn")){
					View button=(View)field(PhotoViewer.class, name).get(viewer);
					assertEquals(View.GONE, button.getVisibility()); assertFalse(button.isEnabled()); assertFalse(button.isFocusable());
				}
				delegate.info.downloadProtected=false; delegate.info.canDownload=true;
				method(PhotoViewer.class, "onPageChanged", int.class).invoke(viewer, 1);
				params=(WindowManager.LayoutParams)overlay.getLayoutParams();
				assertEquals(0, params.flags & WindowManager.LayoutParams.FLAG_SECURE);
				assertTrue((params.flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)!=0);
				assertEquals(hostFlags, host.getWindow().getAttributes().flags);
				// A secure bit this viewer did not install must not be cleared.
				params.flags|=WindowManager.LayoutParams.FLAG_SECURE;
				delegate.info.downloadProtected=true; viewer.refreshAlbumControls();
				delegate.info.downloadProtected=false; viewer.refreshAlbumControls();
				assertTrue((((WindowManager.LayoutParams)overlay.getLayoutParams()).flags & WindowManager.LayoutParams.FLAG_SECURE)!=0);
			}finally{ viewer.onDismissed(); }
			assertEquals(hostFlags, host.getWindow().getAttributes().flags);
		}
	}

	@Test public void initialProtectedWindowIsSecureBeforeDelegatePreviewRenewal() throws Exception{
		withSession((accountID, session)->{
			try(ActivityController<Activity> lifecycle=Robolectric.buildActivity(Activity.class).setup().visible()){
				Activity host=lifecycle.get(); host.setTheme(R.style.Theme_Mastodon_Dark);
				ProbeDelegate delegate=new ProbeDelegate(); delegate.info.downloadProtected=true; delegate.info.canDownload=false;
				PhotoViewer viewer=new PhotoViewer(host, null, List.of(AlbumPhotoViewer.attachment(photo())), 0, null, accountID, new NoTransition(), delegate);
				try{
					attachOverlay(viewer);
					assertTrue(viewer.isAlbumHostValid());
					View overlay=(View)field(PhotoViewer.class, "windowView").get(viewer);
					delegate.beforePreview=()->{
						assertTrue((((WindowManager.LayoutParams)overlay.getLayoutParams()).flags & WindowManager.LayoutParams.FLAG_SECURE)!=0);
						try{ assertTrue(((Map<?,?>)field(PhotoViewer.class, "albumPreviewCalls").get(viewer)).isEmpty()); }
						catch(Exception error){ throw new AssertionError(error); }
					};
					int beforeRenewal=delegate.previewRenewals;
					viewer.reloadAlbumPreview();
					assertEquals(beforeRenewal+1, delegate.previewRenewals);
					assertNull(field(PhotoViewer.class, "albumMediaCall").get(viewer));
				}finally{ viewer.onDismissed(); }
			}
		});
	}

	@Test @Config(shadows=RecordingApiExecution.class) public void protectedAdapterAndViewerDenyProgrammaticDownloadAndHdAtNetworkBoundary() throws Exception{
		RecordingApiExecution.routes.clear();
		withSession((accountID, session)->{
			try(ActivityController<Activity> lifecycle=Robolectric.buildActivity(Activity.class).setup().visible()){
				Activity host=lifecycle.get(); host.setTheme(R.style.Theme_Mastodon_Dark);
				AlbumModels.Photo photo=photo(); photo.downloadProtected=true; photo.canDownload=true;
				Constructor<AlbumPhotoViewer> constructor=AlbumPhotoViewer.class.getDeclaredConstructor(Activity.class, String.class, List.class, int.class); constructor.setAccessible(true);
				AlbumPhotoViewer adapter=constructor.newInstance(host, accountID, List.of(photo), 0);
				PhotoViewer viewer=new PhotoViewer(host, null, List.of(AlbumPhotoViewer.attachment(photo)), 0, null, accountID, new NoTransition(), adapter);
				field(AlbumPhotoViewer.class, "viewer").set(adapter, viewer);
				try{
					attachOverlay(viewer);
					assertTrue(viewer.isAlbumHostValid());
					// The attached overlay already renewed once at the API boundary; complete that cycle before denies.
					assertFalse("Attached overlay renews its preview", RecordingApiExecution.routes.isEmpty());
					deliverPreviewRenewal(photo); // Same DTO: no policy change, so the deny path starts settled.
					assertTrue(adapter.getInfo(0).downloadProtected); assertFalse(adapter.getInfo(0).canDownload);
					int before=RecordingApiExecution.routes.size();
					adapter.onDownload(0); adapter.onView(0, "hd"); adapter.onView(0, "original");
					method(AlbumPhotoViewer.class, "requestDownload", int.class, String.class).invoke(adapter, 0, "hd");
					method(AlbumPhotoViewer.class, "beginMedia", int.class, String.class, boolean.class).invoke(adapter, 0, "hd", true);
					((View)field(PhotoViewer.class, "downloadButton").get(viewer)).performClick();
					((View)field(PhotoViewer.class, "viewOriginalBtn").get(viewer)).performClick();
					int[] failures={0}; PhotoViewer.AlbumSourceCallback denied=new PhotoViewer.AlbumSourceCallback(){
						@Override public void onLoaded(){ fail("Protected source cannot load/save"); }
						@Override public void onFailed(){ failures[0]++; }
					};
					viewer.saveAlbumSource(0, photo.hdUrl, denied); viewer.updateAlbumSource(0, photo.hdUrl, denied);
					assertEquals(2, failures[0]); assertNull(field(PhotoViewer.class, "albumMediaCall").get(viewer));
					assertNull(field(AlbumPhotoViewer.class, "mediaAction").get(adapter));
					assertEquals(before, RecordingApiExecution.routes.size());
					// Positive control: a protected owner still authorizes a free/paid HD download normally.
					photo.isOwner=true; photo.canDownload=true; viewer.refreshAlbumControls();
					method(AlbumPhotoViewer.class, "beginMedia", int.class, String.class, boolean.class).invoke(adapter, 0, "hd", true);
					assertEquals(before+1, RecordingApiExecution.routes.size());
					assertTrue(RecordingApiExecution.routes.get(before).contains("/authorize"));
					okio.Buffer body=new okio.Buffer(); RecordingApiExecution.last.getRequestBody().writeTo(body);
					assertTrue(body.readUtf8().contains("\"intent\":\"download\""));
				}finally{ viewer.onDismissed(); }
			}
		});
	}

	@Test @Config(shadows=RecordingApiExecution.class) public void renewedProtectionCancelsAuthorizedGenerationBeforePreviewCallback() throws Exception{
		RecordingApiExecution.routes.clear();
		withSession((accountID, session)->{
			try(ActivityController<Activity> lifecycle=Robolectric.buildActivity(Activity.class).setup().visible()){
				Activity host=lifecycle.get(); host.setTheme(R.style.Theme_Mastodon_Dark);
				AlbumModels.Photo previous=photo();
				Constructor<AlbumPhotoViewer> constructor=AlbumPhotoViewer.class.getDeclaredConstructor(Activity.class, String.class, List.class, int.class); constructor.setAccessible(true);
				AlbumPhotoViewer adapter=constructor.newInstance(host, accountID, List.of(previous), 0);
				PhotoViewer viewer=new PhotoViewer(host, null, List.of(AlbumPhotoViewer.attachment(previous)), 0, null, accountID, new NoTransition(), adapter);
				field(AlbumPhotoViewer.class, "viewer").set(adapter, viewer);
				try{
					attachOverlay(viewer); // Fail-closed viewer only turns live after the overlay window attaches.
					AlbumPhotoViewer.MediaAction action=new AlbumPhotoViewer.MediaAction(0, "hd", true, 0);
					field(AlbumPhotoViewer.class, "mediaAction").set(adapter, action);
					TrackedRequest pending=new TrackedRequest();
					@SuppressWarnings("unchecked") Set<MastodonAPIRequest<?>> requests=(Set<MastodonAPIRequest<?>>)field(AlbumPhotoViewer.class, "requests").get(adapter);
					requests.add(pending);
					okhttp3.Call bytes=new okhttp3.OkHttpClient().newCall(new okhttp3.Request.Builder().url(previous.hdUrl).build());
					field(PhotoViewer.class, "albumMediaCall").set(viewer, bytes);
					field(PhotoViewer.class, "albumSourceDrawable").set(viewer, new android.graphics.drawable.ColorDrawable(0xff112233));
					field(PhotoViewer.class, "albumSourcePosition").setInt(viewer, 0);
					field(AlbumPhotoViewer.class, "permissionDownload").set(adapter, (Runnable)()->fail("Revoked permission must not authorize"));
					final boolean[] refreshed={false};
					adapter.refreshPreview(0, new PhotoViewer.AlbumPreviewCallback(){
						@Override public void onRefreshed(String url, String description, int width, int height){
							refreshed[0]=true;
							try{
								View overlay=(View)field(PhotoViewer.class, "windowView").get(viewer);
								assertTrue((((WindowManager.LayoutParams)overlay.getLayoutParams()).flags & WindowManager.LayoutParams.FLAG_SECURE)!=0);
								assertTrue(pending.canceled); assertTrue(bytes.isCanceled());
								assertNull(field(PhotoViewer.class, "albumMediaCall").get(viewer));
								assertNull(field(PhotoViewer.class, "albumSourceDrawable").get(viewer));
								assertFalse((Boolean)method(AlbumPhotoViewer.class, "mediaLive", AlbumPhotoViewer.MediaAction.class).invoke(adapter, action));
								assertNull(field(AlbumPhotoViewer.class, "permissionDownload").get(adapter));
							}catch(Exception error){ throw new AssertionError(error); }
						}
						@Override public void onFailed(){ fail("Valid refreshed photo"); }
						@Override public void onAccessDenied(){ fail("Preview remains viewable"); }
					});
					MastodonAPIRequest<?> renewal=RecordingApiExecution.last;
					AlbumModels.PhotoDetailResponse result=new AlbumModels.PhotoDetailResponse(); result.photo=photo(); result.photo.downloadProtected=true; result.photo.canDownload=false;
					Class<?> base=renewal.getClass(); Field callback=null;
					while(base!=null && callback==null){ try{ callback=field(base, "callback"); }catch(NoSuchFieldException ignored){ base=base.getSuperclass(); } }
					assertNotNull("Fixture must deliver the real API callback", callback);
					@SuppressWarnings("unchecked") me.grishka.appkit.api.Callback<AlbumModels.PhotoDetailResponse> apiCallback=(me.grishka.appkit.api.Callback<AlbumModels.PhotoDetailResponse>)callback.get(renewal);
					apiCallback.onSuccess(result); org.robolectric.shadows.ShadowLooper.idleMainLooper();
					assertTrue(refreshed[0]); assertEquals(1, field(AlbumPhotoViewer.class, "generation").getLong(adapter));
					assertTrue(RecordingApiExecution.routes.stream().noneMatch(route->route.contains("/authorize")));
					adapter.onDownload(0); assertNull(field(AlbumPhotoViewer.class, "mediaAction").get(adapter));
				}finally{ viewer.onDismissed(); }
			}
		});
	}

	private interface SessionTest{ void run(String accountID, AccountSession session) throws Exception; }
	private void withSession(SessionTest test) throws Exception{
		Constructor<AccountSession> constructor=AccountSession.class.getDeclaredConstructor(); constructor.setAccessible(true);
		AccountSession session=constructor.newInstance(); session.domain="album-comment-gate.example.test"; session.self=new Account(); session.self.id="42"; session.token=new Token();
		String accountID=session.getID(); AccountSessionManager manager=AccountSessionManager.getInstance();
		@SuppressWarnings("unchecked") Map<String,AccountSession> sessions=(Map<String,AccountSession>)field(AccountSessionManager.class, "sessions").get(manager);
		String previous=(String)field(AccountSessionManager.class, "lastActiveAccountID").get(manager); AccountSession old=sessions.put(accountID, session);
		field(AccountSessionManager.class, "lastActiveAccountID").set(manager, accountID);
		try{ test.run(accountID, session); }
		finally{ if(old==null) sessions.remove(accountID); else sessions.put(accountID, old); field(AccountSessionManager.class, "lastActiveAccountID").set(manager, previous); }
	}

	/** A freshly constructed overlay is only attached after its first traversal; the viewer is fail-closed until then. */
	private static void attachOverlay(PhotoViewer viewer) throws Exception{
		View overlay=(View)field(PhotoViewer.class, "windowView").get(viewer);
		org.robolectric.shadows.ShadowLooper.idleMainLooper();
		assertTrue("Overlay must be attached", overlay.isAttachedToWindow());
	}

	/** The exec seam records the real preview renewal; the fixture delivers its genuine API callback. */
	private static void deliverPreviewRenewal(AlbumModels.Photo renewed) throws Exception{
		MastodonAPIRequest<?> renewal=RecordingApiExecution.last;
		AlbumModels.PhotoDetailResponse result=new AlbumModels.PhotoDetailResponse(); result.photo=renewed;
		Class<?> base=renewal.getClass(); Field callback=null;
		while(base!=null && callback==null){ try{ callback=field(base, "callback"); }catch(NoSuchFieldException ignored){ base=base.getSuperclass(); } }
		assertNotNull("Fixture must deliver the real API callback", callback);
		@SuppressWarnings("unchecked") me.grishka.appkit.api.Callback<AlbumModels.PhotoDetailResponse> apiCallback=(me.grishka.appkit.api.Callback<AlbumModels.PhotoDetailResponse>)callback.get(renewal);
		apiCallback.onSuccess(result); org.robolectric.shadows.ShadowLooper.idleMainLooper();
	}

	/** Stop at the real API execution boundary so a missing gate is observable without external network. */
	@Implements(MastodonAPIRequest.class) public static class RecordingApiExecution{
		static final List<String> routes=new java.util.ArrayList<>();
		static MastodonAPIRequest<?> last;
		@RealObject MastodonAPIRequest<?> request;
		@Implementation public MastodonAPIRequest<?> exec(String accountID){ last=request; routes.add(request.getMethod()+" "+request.getURL()); return request; }
	}

	public static final class ReplyProbeFragment extends HomeTimelineFragment{
		int replyEntries; Status replyStatus; Runnable proceed;
		@Override public void maybeShowPreReplySheet(Status status, Runnable proceed){ replyEntries++; replyStatus=status; this.proceed=proceed; }
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
		int canceled, position, previewRenewals, commentEntries;
		final PhotoViewer.AlbumInfo info=new PhotoViewer.AlbumInfo();
		Runnable beforePreview;
		@Override public PhotoViewer.AlbumInfo getInfo(int position){ return info; }
		@Override public void onPhotoChanged(int position){ this.position=position; }
		@Override public void refreshPreview(int position, PhotoViewer.AlbumPreviewCallback callback){ if(beforePreview!=null) beforePreview.run(); previewRenewals++; callback.onFailed(); }
		@Override public void onView(int position, String variant){}
		@Override public void onDownload(int position){}
		@Override public void onLike(int position){}
		@Override public void onComments(int position){ commentEntries++; }
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
