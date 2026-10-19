package org.joinmastodon.android.fragments.albums;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.recyclerview.widget.RecyclerView;

import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.albums.AlbumPhotoViewer;
import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.model.albums.AlbumModels.Album;
import org.joinmastodon.android.model.albums.AlbumModels.Photo;
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
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import me.grishka.appkit.utils.V;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Exercises the real detail adapter; only the viewer destination and HTTP transport are replaced. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class, shadows=AlbumDetailBlockedStateTest.RecordingViewer.class)
public class AlbumDetailBlockedStateTest{
	private static final String ALBUM_ID="album", PREVIEW_URL="file:///tmp/album-blocked-state-missing-preview.jpg";
	private AccountSessionManager manager;
	private Map<String, AccountSession> sessions;
	private AccountSession session;
	private String accountId, previousActive;
	private OkHttpClient client;
	private Object originalInterceptors;
	private ActivityController<Activity> lifecycle;
	private AlbumDetailFragment fragment;
	private View content;
	private RecyclerView grid;
	private Album fixtureAlbum;
	private final List<Photo> fixturePhotos=new CopyOnWriteArrayList<>();
	private final List<String> deletedPaths=new CopyOnWriteArrayList<>(), deleteBodies=new CopyOnWriteArrayList<>();
	private final List<View> boundTiles=new ArrayList<>();

	private static Field field(Class<?> type, String name) throws Exception{
		Field result=type.getDeclaredField(name); result.setAccessible(true); return result;
	}

	@Before @SuppressWarnings("unchecked") public void setup() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context);
		RecordingViewer.opens=0;
		manager=AccountSessionManager.getInstance();
		sessions=(Map<String, AccountSession>)field(AccountSessionManager.class, "sessions").get(manager);
		previousActive=manager.getLastActiveAccountID();
		var constructor=AccountSession.class.getDeclaredConstructor(); constructor.setAccessible(true); session=constructor.newInstance();
		session.domain="album-blocked-state-offline.example.test"; session.self=new Account(); session.self.id="42";
		session.token=new Token(); session.token.accessToken="offline-fixture";
		accountId=session.getID(); sessions.put(accountId, session);
		field(AccountSessionManager.class, "lastActiveAccountID").set(manager, accountId);
		client=(OkHttpClient)field(MastodonAPIController.class, "sensitiveHttpClient").get(null);
		originalInterceptors=field(OkHttpClient.class, "interceptors").get(client);
		field(OkHttpClient.class, "interceptors").set(client, List.of((okhttp3.Interceptor)chain->respond(chain.request())));
	}

	@After public void cleanup() throws Exception{
		for(View tile:boundTiles) AlbumUi.clearImages(tile);
		if(fragment!=null) fragment.onDestroyView();
		if(lifecycle!=null) lifecycle.pause().stop().destroy();
		if(client!=null && originalInterceptors!=null) field(OkHttpClient.class, "interceptors").set(client, originalInterceptors);
		if(sessions!=null) sessions.remove(accountId);
		if(manager!=null) field(AccountSessionManager.class, "lastActiveAccountID").set(manager, previousActive);
	}

	private Response respond(Request request) throws IOException{
		if(!session.domain.equals(request.url().host())) throw new IOException("Unexpected outbound host");
		String path=request.url().encodedPath(); Object body;
		if("GET".equals(request.method()) && path.equals("/api/v1/albums/"+ALBUM_ID)){
			body=Map.of("album", fixtureAlbum);
		}else if("GET".equals(request.method()) && path.equals("/api/v1/albums/"+ALBUM_ID+"/photos")){
			body=Map.of("photos", new ArrayList<>(fixturePhotos), "has_more", false);
		}else if("DELETE".equals(request.method()) && path.equals("/api/v1/albums/photos/photo")){
			okio.Buffer buffer=new okio.Buffer(); request.body().writeTo(buffer); deleteBodies.add(buffer.readUtf8()); deletedPaths.add(path);
			fixturePhotos.clear(); fixtureAlbum.photoCount=0; body=Map.of("deleted", true);
		}else throw new IOException("Unexpected fixture route: "+request.method()+" "+path);
		return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Offline fixture")
				.body(ResponseBody.create(MediaType.get("application/json"), MastodonAPIController.gson.toJson(body))).build();
	}

	private void start(boolean albumOwner, boolean downloadProtected, Photo... photos) throws Exception{
		fixtureAlbum=new Album(); fixtureAlbum.id=ALBUM_ID; fixtureAlbum.name="Blocked state fixture"; fixtureAlbum.visibility="shared";
		fixtureAlbum.ownerId=42; fixtureAlbum.isOwner=albumOwner; fixtureAlbum.downloadProtected=downloadProtected; fixtureAlbum.photoCount=photos.length;
		fixturePhotos.addAll(List.of(photos));
		lifecycle=Robolectric.buildActivity(Activity.class).create(); lifecycle.get().setTheme(R.style.Theme_Mastodon_Light); lifecycle.start().resume();
		fragment=new AlbumDetailFragment(); Bundle args=new Bundle(); args.putString("account", accountId); args.putString("albumId", ALBUM_ID); fragment.setArguments(args);
		lifecycle.get().getFragmentManager().beginTransaction().add(fragment, "detail").commit(); lifecycle.get().getFragmentManager().executePendingTransactions();
		content=fragment.onCreateContentView(LayoutInflater.from(lifecycle.get()), null, null); grid=content.findViewById(R.id.album_grid);
		fragment.refresh();
		// All fixtures use one timestamp, so the actual adapter has one group header plus the photos.
		await(()->grid.getAdapter().getItemCount()==photos.length+1 && content.findViewById(R.id.album_settings).isEnabled());
	}

	private static Photo photo(String id, Boolean blocked, boolean photoOwner){
		Photo photo=new Photo(); photo.id=id; photo.albumId=ALBUM_ID; photo.sortAt=1791158401; photo.uploadedAt=photo.sortAt;
		photo.adminBlocked=blocked; photo.isOwner=photoOwner; photo.previewUrl=PREVIEW_URL;
		return photo;
	}

	@SuppressWarnings({"rawtypes", "unchecked"}) private View bindPhoto(int position){
		RecyclerView.Adapter adapter=grid.getAdapter();
		RecyclerView.ViewHolder holder=adapter.createViewHolder(grid, adapter.getItemViewType(position)); adapter.bindViewHolder(holder, position);
		// Long-click denial falls through to showContextMenu, which needs a parent. Do not add a foreign child to RecyclerView.
		FrameLayout parent=new FrameLayout(lifecycle.get()); parent.addView(holder.itemView); boundTiles.add(holder.itemView);
		return holder.itemView;
	}

	private static void await(BooleanSupplier done) throws Exception{
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
		while(!done.getAsBoolean() && System.nanoTime()<deadline){ ShadowLooper.idleMainLooper(); Thread.sleep(5); }
		ShadowLooper.idleMainLooper(); assertTrue("Offline operation must settle", done.getAsBoolean());
	}

	private void assertBlockedClickAndImage(View tile){
		ImageView image=tile.findViewById(R.id.album_photo);
		assertNotNull(image.getDrawable()); assertFalse("Blocked previews must not enter the image loader", AlbumUi.isImageLoading(image));
		assertTrue(tile.getContentDescription().toString().contains(fragment.getString(R.string.album_photo_admin_blocked)));
		assertTrue(tile.performClick()); assertEquals("Blocked clicks must not open the viewer or its interactions", 0, RecordingViewer.opens);
	}

	private void assertCannotDelete(View tile) throws Exception{
		assertFalse(tile.performLongClick()); assertNull(field(AlbumDetailFragment.class, "dialog").get(fragment)); assertTrue(deletedPaths.isEmpty());
	}

	private void confirmDeletionAfterCancel(View tile) throws Exception{
		assertTrue(tile.performLongClick()); ShadowLooper.idleMainLooper();
		AlertDialog confirmation=ShadowAlertDialog.getLatestAlertDialog(); assertNotNull(confirmation); assertTrue(confirmation.isShowing()); assertTrue(deletedPaths.isEmpty());
		confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); ShadowLooper.idleMainLooper();
		assertTrue(deletedPaths.isEmpty()); assertEquals(2, grid.getAdapter().getItemCount());
		assertTrue(tile.performLongClick()); ShadowLooper.idleMainLooper();
		confirmation=ShadowAlertDialog.getLatestAlertDialog(); assertNotNull(confirmation); assertTrue(confirmation.isShowing());
		confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		await(()->deletedPaths.size()==1 && grid.getAdapter().getItemCount()==0 && content.findViewById(R.id.album_settings).isEnabled());
		assertEquals(List.of("/api/v1/albums/photos/photo"), deletedPaths); assertEquals(List.of("{}"), deleteBodies);
	}

	@Test public void blockedOwnerCanDeleteAfterConfirmationButCannotOpenOrLoadPhoto() throws Exception{
		Photo photo=photo("photo", true, true); photo.hdUrl=PREVIEW_URL; photo.canDownload=true;
		start(true, true, photo); View tile=bindPhoto(1);
		assertBlockedClickAndImage(tile); confirmDeletionAfterCancel(tile); assertEquals(0, RecordingViewer.opens);
	}

	@Test public void blockedVisitorCannotDeleteEvenWhenPhotoClaimsOwnership() throws Exception{
		start(false, false, photo("photo", true, true)); View tile=bindPhoto(1);
		assertBlockedClickAndImage(tile); assertCannotDelete(tile);
	}

	@Test public void blockedPhotoNonOwnerCannotDeleteFromOwnedAlbum() throws Exception{
		start(true, false, photo("photo", true, false)); View tile=bindPhoto(1);
		assertBlockedClickAndImage(tile); assertCannotDelete(tile);
	}

	@Test public void unblockedOwnerRetainsViewerAndConfirmedDeletion() throws Exception{
		start(true, false, photo("photo", false, true)); View tile=bindPhoto(1);
		assertTrue(tile.performClick()); assertEquals(1, RecordingViewer.opens); confirmDeletionAfterCancel(tile);
	}

	@Test public void unblockedProtectedVisitorKeepsPreviewProtectionAndCannotDelete() throws Exception{
		start(false, true, photo("photo", false, false)); View tile=bindPhoto(1);
		assertTrue((lifecycle.get().getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE)!=0);
		assertFalse(AlbumUi.isImageLoading(tile.findViewById(R.id.album_photo)));
		assertCannotDelete(tile); assertTrue(tile.performClick()); assertEquals(1, RecordingViewer.opens);
	}

	@Test public void unblockedPhotoNonOwnerStillCannotDelete() throws Exception{
		start(true, false, photo("photo", false, false)); assertCannotDelete(bindPhoto(1));
	}

	@Test public void legacyPhotoWithoutBlockedFlagRetainsOwnerDeletion() throws Exception{
		start(true, false, photo("photo", null, true)); confirmDeletionAfterCancel(bindPhoto(1));
	}

	@Test public void blockedOwnerLongClickStillRequiresFreshActiveSession() throws Exception{
		start(true, false, photo("photo", true, true)); View tile=bindPhoto(1);
		field(AccountSessionManager.class, "lastActiveAccountID").set(manager, "stale-session"); assertCannotDelete(tile);
		field(AccountSessionManager.class, "lastActiveAccountID").set(manager, accountId);
		field(AlbumDetailFragment.class, "fresh").setBoolean(fragment, false); assertCannotDelete(tile);
	}

	@Test public void mixedPhotosAcceptNullPreviewOnlyForBlockedPhoto() throws Exception{
		Photo blocked=photo("blocked", true, true); blocked.previewUrl=null;
		Photo visible=photo("visible", false, true);
		assertTrue(AlbumDetailFragment.validPhoto(blocked, ALBUM_ID)); assertTrue(AlbumDetailFragment.validPhoto(visible, ALBUM_ID));
		start(true, false, blocked, visible); assertEquals(3, grid.getAdapter().getItemCount());
		assertBlockedClickAndImage(bindPhoto(1)); assertTrue(bindPhoto(2).performClick()); assertEquals(1, RecordingViewer.opens);
	}

	@Test public void allBlockedPhotosAcceptNullPreviewsAndRemainNonInteractive() throws Exception{
		Photo first=photo("first", true, false), second=photo("second", true, false); first.previewUrl=null; second.previewUrl=null;
		assertTrue(AlbumDetailFragment.validPhoto(first, ALBUM_ID)); assertTrue(AlbumDetailFragment.validPhoto(second, ALBUM_ID));
		start(false, true, first, second); assertEquals(3, grid.getAdapter().getItemCount());
		for(int position:new int[]{1, 2}){ View tile=bindPhoto(position); assertBlockedClickAndImage(tile); assertCannotDelete(tile); }
	}

	@Test public void nonblockedPhotoWithMissingPreviewIsRejectedIncludingLegacyState(){
		for(Boolean blocked:new Boolean[]{false, null}){
			Photo missing=photo("photo", blocked, true); missing.previewUrl=null;
			assertFalse(AlbumDetailFragment.validPhoto(missing, ALBUM_ID));
			missing.hdUrl=PREVIEW_URL; assertFalse("An HD URL cannot replace the required nonblocked preview", AlbumDetailFragment.validPhoto(missing, ALBUM_ID));
		}
	}

	@Implements(AlbumPhotoViewer.class) public static class RecordingViewer{
		static int opens;
		@Implementation public static void open(Activity activity, String accountID, List<Photo> photos, int index){ opens++; }
	}
}
