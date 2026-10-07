package org.joinmastodon.android.fragments.albums;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import org.joinmastodon.android.BuildConfig;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.albums.AlbumUploader;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.model.Account;
import org.joinmastodon.android.model.Token;
import org.joinmastodon.android.model.albums.AlbumModels.StorageQuota;
import org.joinmastodon.android.ui.media.MediaAlbum;
import org.joinmastodon.android.ui.media.MediaCameraContract;
import org.joinmastodon.android.ui.media.MediaItem;
import org.joinmastodon.android.ui.media.MediaPickerConfig;
import org.joinmastodon.android.ui.media.MediaStoreLoader;
import org.joinmastodon.android.ui.sheets.MediaPickerSheet;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import me.grishka.appkit.FragmentStackActivity;
import me.grishka.appkit.utils.V;

/** Attached production fragment and real picker/camera contract, with offline media enumeration. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=Application.class, shadows=AlbumUploadMediaPickerTest.OfflineMediaStoreLoader.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AlbumUploadMediaPickerTest{
	private AccountSessionManager manager;
	private Map<String, AccountSession> sessions;
	private AccountSession session;
	private String accountId, previousActive;
	private ActivityController<FragmentStackActivity> lifecycle;
	private LocalFragment fragment;
	private ShadowApplication application;
	private ShadowActivity activity;
	private File image;
	private Intent cameraLaunch;

	/** Suppress only server refresh; all selection, permissions, draft, metadata and widgets are real. */
	public static class LocalFragment extends AlbumUploadFragment{
		@Override protected void onShown(){ }
	}
	/** Deterministic empty enumeration; hasPermission is deliberately the real production method. */
	@Implements(MediaStoreLoader.class) public static class OfflineMediaStoreLoader{
		@Implementation protected void load(MediaPickerConfig config, MediaStoreLoader.Callback callback){ callback.onLoaded(new ArrayList<>()); }
	}
	private static Field field(Class<?> type, String name) throws Exception{ Field f=type.getDeclaredField(name); f.setAccessible(true); return f; }
	private Object get(String name) throws Exception{ return field(AlbumUploadFragment.class, name).get(fragment); }
	private void set(String name, Object value) throws Exception{ field(AlbumUploadFragment.class, name).set(fragment, value); }
	private static Object invoke(Object target, Class<?> type, String name, Class<?>[] signature, Object... args) throws Exception{
		Method m=type.getDeclaredMethod(name, signature); m.setAccessible(true); return m.invoke(target, args);
	}
	private void pick() throws Exception{ invoke(fragment, AlbumUploadFragment.class, "pickPhotos", new Class<?>[0]); }
	private AlbumUploadDraft draft() throws Exception{ return (AlbumUploadDraft)get("draft"); }
	private MediaPickerSheet sheet() throws Exception{ return (MediaPickerSheet)get("mediaPickerSheet"); }
	private MediaPickerSheet.Listener listener(MediaPickerSheet sheet) throws Exception{ return (MediaPickerSheet.Listener)field(MediaPickerSheet.class, "listener").get(sheet); }
	private void grant(String... permissions){ application.grantPermissions(permissions); }
	private Uri imageUri(){ return Uri.fromFile(image); }
	private Uri cameraUri(){ return UiUtils.getFileProviderUri(lifecycle.get(), image); }
	private ShadowActivity.IntentForResult permissionRequest(int requestCode, String... expected){
		// Native Fragment.requestPermissions bypasses Activity.requestPermissions. ShadowInstrumentation
		// records its real permission-controller intent in the shared activity launch queues instead.
		ShadowActivity.IntentForResult request=activity.getNextStartedActivityForResult();
		assertNotNull("The attached fragment must launch a permission request", request);
		assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", request.intent.getAction());
		assertEquals(requestCode, request.requestCode);
		assertArrayEquals(expected, request.intent.getStringArrayExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES"));
		assertEquals(request.intent, activity.getNextStartedActivity());
		assertNull(activity.getNextStartedActivityForResult());
		assertNull(activity.getNextStartedActivity());
		return request;
	}
	private void permissionResult(ShadowActivity.IntentForResult request, int... results){
		String[] permissions=request.intent.getStringArrayExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES");
		assertEquals(permissions.length, results.length);
		for(int i=0;i<permissions.length;i++){
			if(results[i]==PackageManager.PERMISSION_GRANTED) grant(permissions[i]);
			else application.denyPermissions(permissions[i]);
		}
		// Dispatch through the real Activity/FragmentManager routing, not a fragment override.
		activity.receiveResult(request.intent, Activity.RESULT_OK, new Intent()
				.putExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES", permissions)
				.putExtra("android.content.pm.extra.REQUEST_PERMISSIONS_RESULTS", results));
	}
	private void settleMetadata() throws Exception{
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
		while((boolean)get("checkingSources") && System.nanoTime()<deadline){ ShadowLooper.idleMainLooper(); Thread.sleep(5); }
		ShadowLooper.idleMainLooper(); assertFalse((boolean)get("checkingSources"));
	}
	@Before @SuppressWarnings("unchecked") public void setup() throws Exception{
		MastodonApp.context=RuntimeEnvironment.getApplication(); V.setApplicationContext(MastodonApp.context); application=Shadow.extract(RuntimeEnvironment.getApplication());
		((Map<?,?>)field(org.joinmastodon.android.FileProvider.class, "sCache").get(null)).clear();
		application.denyPermissions(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.CAMERA);
		manager=AccountSessionManager.getInstance(); sessions=(Map<String, AccountSession>)field(AccountSessionManager.class, "sessions").get(manager); previousActive=manager.getLastActiveAccountID();
		var constructor=AccountSession.class.getDeclaredConstructor(); constructor.setAccessible(true); session=constructor.newInstance(); session.domain="album-picker-offline.example.test"; session.self=new Account(); session.self.id="4"; session.token=new Token(); session.token.accessToken="local";
		accountId=session.getID(); sessions.put(accountId, session); field(AccountSessionManager.class, "lastActiveAccountID").set(manager, accountId);
		MastodonApp.context.getSharedPreferences("album_upload_tasks", Context.MODE_PRIVATE).edit().remove(accountId).commit();
		org.robolectric.shadows.ShadowMimeTypeMap mime=Shadow.extract(android.webkit.MimeTypeMap.getSingleton()); mime.addExtensionMimeTypeMapping("jpg", "image/jpeg"); mime.addExtensionMimeTypeMapping("mp4", "video/mp4");
		File imageCache=new File(MastodonApp.context.getCacheDir(), "images"); assertTrue(imageCache.isDirectory() || imageCache.mkdirs());
		image=File.createTempFile("album_picker_", ".jpg", imageCache); Bitmap bitmap=Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888); try(FileOutputStream out=new FileOutputStream(image)){ bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out); } bitmap.recycle();
		lifecycle=Robolectric.buildActivity(FragmentStackActivity.class); lifecycle.get().setTheme(R.style.Theme_Mastodon_Light); lifecycle.create().start().resume().visible(); activity=Shadow.extract(lifecycle.get());
		assertEquals(BuildConfig.APPLICATION_ID, lifecycle.get().getPackageName());
		Uri cameraUri=cameraUri(); assertEquals(BuildConfig.APPLICATION_ID+".fileprovider", cameraUri.getAuthority()); assertEquals("image/jpeg", lifecycle.get().getContentResolver().getType(cameraUri));
		fragment=new LocalFragment(); Bundle args=new Bundle(); args.putString("account", accountId); fragment.setArguments(args); lifecycle.get().showFragment(fragment); lifecycle.get().getFragmentManager().executePendingTransactions(); settleMetadata();
		assertTrue("The real host must resume the attached fragment", fragment.isResumed());
	}
	@After public void cleanup() throws Exception{
		if(fragment!=null && fragment.isAdded()) fragment.onHidden(); if(lifecycle!=null) lifecycle.pause().stop().destroy();
		if(sessions!=null) sessions.remove(accountId); if(manager!=null) field(AccountSessionManager.class, "lastActiveAccountID").set(manager, previousActive); if(image!=null) image.delete();
	}
	@SuppressWarnings("unchecked") private void chooseThroughRealSend(MediaPickerSheet sheet, List<Uri> uris) throws Exception{
		MediaAlbum album=new MediaAlbum(0, "Local photos"); long id=1;
		for(Uri uri:uris) album.items.add(new MediaItem(id++, 0, "Local photos", uri, "image/jpeg", 0, 8, 8, image.length(), 0, false));
		ArrayList<MediaAlbum> albums=(ArrayList<MediaAlbum>)field(MediaPickerSheet.class, "albums").get(sheet); albums.clear(); albums.add(album);
		invoke(sheet, MediaPickerSheet.class, "selectAlbum", new Class<?>[]{int.class}, 0);
		for(int i=0;i<uris.size();i++) invoke(sheet, MediaPickerSheet.class, "toggle", new Class<?>[]{int.class}, i+1);
		assertTrue(((View)field(MediaPickerSheet.class, "send").get(sheet)).performClick());
	}
	@Test public void realSendUsesImagesOnlyRemainingLimitAndRefreshesMetadataWithoutSafGrants() throws Exception{
		grant(Manifest.permission.READ_EXTERNAL_STORAGE); draft().addPhoto("content://media/external/images/media/1"); pick(); MediaPickerSheet sheet=sheet(); assertNotNull(sheet); assertTrue(sheet.isShowing());
		MediaPickerConfig config=(MediaPickerConfig)field(MediaPickerSheet.class, "config").get(sheet); assertTrue(config.allowImages); assertFalse(config.allowVideos); assertEquals(19, config.maxCount); assertNull(activity.getNextStartedActivityForResult());
		chooseThroughRealSend(sheet, List.of(imageUri(), imageUri())); assertEquals(2, draft().photos.size()); assertNull(sheet()); assertFalse(sheet.isShowing()); settleMetadata();
		assertEquals(2, ((List<?>)get("sourceInfo")).size()); assertTrue(((TextView)fragment.getView().findViewById(R.id.album_pick_count)).getText().toString().contains("2"));
		assertTrue(lifecycle.get().getContentResolver().getPersistedUriPermissions().isEmpty());
	}
	@Test @Config(sdk={26, 32}) public void legacyGalleryRequestsOnlyReadStorage() throws Exception{
		ShadowActivity.IntentForResult request=assertGalleryPermissionRequest(Manifest.permission.READ_EXTERNAL_STORAGE); permissionResult(request, PackageManager.PERMISSION_GRANTED); assertNotNull(sheet());
	}
	@Test @Config(sdk=33) public void android13GalleryRequestsOnlyImages() throws Exception{
		ShadowActivity.IntentForResult request=assertGalleryPermissionRequest(Manifest.permission.READ_MEDIA_IMAGES); permissionResult(request, PackageManager.PERMISSION_GRANTED); assertNotNull(sheet());
		assertEquals(PackageManager.PERMISSION_DENIED, lifecycle.get().checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO));
	}
	@Test @Config(sdk=34) public void android14GalleryRequestsImagesAndSelectedPhotosAndAcceptsLimitedGrant() throws Exception{
		ShadowActivity.IntentForResult request=assertGalleryPermissionRequest(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
		permissionResult(request, PackageManager.PERMISSION_DENIED, PackageManager.PERMISSION_GRANTED); assertNotNull(sheet());
		assertEquals(PackageManager.PERMISSION_DENIED, lifecycle.get().checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES));
		assertEquals(PackageManager.PERMISSION_DENIED, lifecycle.get().checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO));
	}
	@Test @Config(sdk=34) public void preexistingLimitedPhotoAccessOpensBuiltinPickerWithoutAnotherRequest() throws Exception{
		grant(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED); pick(); assertNotNull(sheet()); assertEquals(-1, get("galleryPermissionRequest")); assertNull(activity.getNextStartedActivityForResult());
	}
	private ShadowActivity.IntentForResult assertGalleryPermissionRequest(String... expected) throws Exception{
		MediaPickerConfig config=new MediaPickerConfig(); config.allowImages=true; config.allowVideos=false;
		assertFalse("hasPermission must use the actually denied image permissions", new MediaStoreLoader(lifecycle.get()).hasPermission(config));
		for(String permission:expected) assertEquals(PackageManager.PERMISSION_DENIED, lifecycle.get().checkSelfPermission(permission));
		pick(); assertNull(sheet()); int code=(int)get("galleryPermissionRequest"); assertTrue(code>=0);
		ShadowActivity.IntentForResult request=permissionRequest(code, expected);
		assertFalse(Arrays.asList(request.intent.getStringArrayExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES")).contains(Manifest.permission.READ_MEDIA_VIDEO));
		return request;
	}
	@Test public void galleryPermissionSurvivesPromptPauseButStaleHiddenRequestIsIgnored() throws Exception{
		ShadowActivity.IntentForResult prompt=assertGalleryPermissionRequest(Manifest.permission.READ_EXTERNAL_STORAGE); int request=prompt.requestCode; int token=(int)get("photoGeneration");
		lifecycle.pause(); assertFalse(fragment.isResumed()); assertEquals(request, get("galleryPermissionRequest")); assertEquals(token, get("photoGeneration")); lifecycle.resume(); assertTrue(fragment.isResumed());
		permissionResult(prompt, PackageManager.PERMISSION_GRANTED); assertNotNull(sheet()); fragment.onHidden();
		application.denyPermissions(Manifest.permission.READ_EXTERNAL_STORAGE); pick(); int stale=(int)get("galleryPermissionRequest"); fragment.onHidden(); pick(); int current=(int)get("galleryPermissionRequest"); assertNotEquals(stale, current);
		grant(Manifest.permission.READ_EXTERNAL_STORAGE); fragment.onRequestPermissionsResult(stale, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, new int[]{PackageManager.PERMISSION_GRANTED}); assertNull(sheet()); assertEquals(current, get("galleryPermissionRequest"));
		fragment.onRequestPermissionsResult(current, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, new int[]{PackageManager.PERMISSION_GRANTED}); assertNotNull(sheet());
	}
	private void hideAndShowWhilePaused(){
		lifecycle.get().getFragmentManager().beginTransaction().hide(fragment).commit(); lifecycle.get().getFragmentManager().executePendingTransactions(); assertTrue(fragment.isHidden());
		lifecycle.get().getFragmentManager().beginTransaction().show(fragment).commit(); lifecycle.get().getFragmentManager().executePendingTransactions(); assertFalse(fragment.isHidden());
	}
	@Test public void galleryRequestHiddenWhileHostPausedCannotReviveAfterShow() throws Exception{
		ShadowActivity.IntentForResult request=assertGalleryPermissionRequest(Manifest.permission.READ_EXTERNAL_STORAGE);
		lifecycle.pause(); assertFalse(fragment.isResumed()); assertEquals(request.requestCode, get("galleryPermissionRequest"));
		hideAndShowWhilePaused(); assertEquals(-1, get("galleryPermissionRequest")); lifecycle.resume();
		permissionResult(request, PackageManager.PERMISSION_GRANTED); assertNull(sheet()); assertTrue(draft().photos.isEmpty());
		pick(); assertNotNull(sheet());
	}
	@Test public void fullDraftCannotOpenAnotherPicker() throws Exception{
		grant(Manifest.permission.READ_EXTERNAL_STORAGE); for(int i=0;i<20;i++) draft().addPhoto("content://media/external/images/media/"+i); pick(); assertNull(sheet()); assertNull(activity.getNextStartedActivityForResult()); assertEquals(20, draft().photos.size());
	}
	@Test @Config(sdk={26, 32, 33, 34}) public void denialShowsSettingsAndNeverFallsBackToSystemPicker() throws Exception{
		String[] permissions=Build.VERSION.SDK_INT>=34 ? new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}
				: Build.VERSION.SDK_INT>=33 ? new String[]{Manifest.permission.READ_MEDIA_IMAGES} : new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
		ShadowActivity.IntentForResult request=assertGalleryPermissionRequest(permissions); int[] results=new int[permissions.length]; Arrays.fill(results, PackageManager.PERMISSION_DENIED); permissionResult(request, results);
		AlertDialog denied=ShadowAlertDialog.getLatestAlertDialog(); assertNotNull(denied); assertTrue(denied.isShowing()); assertEquals(lifecycle.get().getString(R.string.open_settings), denied.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString()); assertNull(sheet()); assertNull(activity.getNextStartedActivityForResult()); assertTrue(draft().photos.isEmpty());
		ShadowLooper.idleMainLooper(); denied.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); ShadowLooper.idleMainLooper();
		assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, activity.getNextStartedActivity().getAction());
	}
	@Test public void callbackRechecksDuplicatesAndCurrentTwentyCapAndFiltersVideoUris() throws Exception{
		grant(Manifest.permission.READ_EXTERNAL_STORAGE); pick(); MediaPickerSheet.Listener result=listener(sheet());
		for(int i=0;i<19;i++) draft().addPhoto("content://media/external/images/media/"+i);
		ArrayList<Uri> uris=new ArrayList<>(List.of(Uri.parse(draft().photos.get(0)), Uri.parse("content://media/external/video/media/7"), Uri.fromFile(new File(image.getParentFile(), "not-a-photo.mp4")), imageUri(), Uri.parse("content://media/external/images/media/100")));
		result.onMediaSelected(uris); assertEquals(20, draft().photos.size()); assertTrue(draft().photos.contains(imageUri().toString())); assertFalse(draft().photos.contains("content://media/external/images/media/100")); assertFalse(draft().photos.stream().anyMatch(s->s.contains("video") || s.endsWith("mp4"))); settleMetadata();
	}
	@Test public void cancellationHiddenDestroyedAndOldGenerationCallbacksCannotEditDraft() throws Exception{
		grant(Manifest.permission.READ_EXTERNAL_STORAGE); pick(); MediaPickerSheet old=sheet(); MediaPickerSheet.Listener stale=listener(old); old.dismissWithoutAnimation(); ShadowLooper.idleMainLooper(); assertTrue(draft().photos.isEmpty());
		pick(); assertNotNull(sheet()); stale.onMediaSelected(new ArrayList<>(List.of(imageUri()))); assertTrue(draft().photos.isEmpty());
		MediaPickerSheet hidden=sheet(); stale=listener(hidden); fragment.onHidden(); assertNull(sheet()); assertFalse(hidden.isShowing()); stale.onMediaSelected(new ArrayList<>(List.of(imageUri()))); assertTrue(draft().photos.isEmpty());
		pick(); stale=listener(sheet()); lifecycle.get().getFragmentManager().beginTransaction().detach(fragment).commit(); lifecycle.get().getFragmentManager().executePendingTransactions(); assertNull(sheet()); stale.onMediaSelected(new ArrayList<>(List.of(imageUri()))); assertTrue(draft().photos.isEmpty());
	}
	@Test public void callbacksRejectChangedSessionDiscardingAndImmutableUploader() throws Exception{
		grant(Manifest.permission.READ_EXTERNAL_STORAGE); pick(); MediaPickerSheet.Listener result=listener(sheet()); field(AccountSessionManager.class, "lastActiveAccountID").set(manager, "other"); result.onMediaSelected(new ArrayList<>(List.of(imageUri()))); assertTrue(draft().photos.isEmpty());
		assertNull(sheet()); field(AccountSessionManager.class, "lastActiveAccountID").set(manager, accountId); pick(); result=listener(sheet()); set("discarding", true); result.onMediaSelected(new ArrayList<>(List.of(imageUri()))); assertTrue(draft().photos.isEmpty()); assertNull(sheet()); set("discarding", false);
		pick(); result=listener(sheet()); var constructor=AccountSession.class.getDeclaredConstructor(); constructor.setAccessible(true); AccountSession replacement=constructor.newInstance(); sessions.put(accountId, replacement); result.onMediaSelected(new ArrayList<>(List.of(imageUri()))); assertTrue(draft().photos.isEmpty()); assertNull(sheet()); sessions.put(accountId, session);
	}
	@Test public void immutableUploaderPreventsLateSelection() throws Exception{
		grant(Manifest.permission.READ_EXTERNAL_STORAGE); pick(); MediaPickerSheet.Listener result=listener(sheet()); AlbumUploader task=new AlbumUploader(accountId, "album", List.of(imageUri()), "", null, "hd");
		try{ set("uploader", task); result.onMediaSelected(new ArrayList<>(List.of(imageUri()))); assertTrue(draft().photos.isEmpty()); assertEquals(List.of(imageUri().toString()), task.getDraftState().sources); }
		finally{ set("uploader", null); task.discard(); }
	}
	private int launchCameraThroughTile() throws Exception{
		grant(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.CAMERA); pick(); MediaPickerSheet sheet=sheet(); invoke(sheet, MediaPickerSheet.class, "toggle", new Class<?>[]{int.class}, 0);
		var started=activity.getNextStartedActivityForResult(); assertNotNull(started); assertEquals("org.joinmastodon.android.ui.MediaCameraActivity", started.intent.getComponent().getClassName()); assertEquals(BuildConfig.APPLICATION_ID, started.intent.getComponent().getPackageName()); assertFalse(started.intent.getBooleanExtra(MediaCameraContract.EXTRA_ALLOW_VIDEO, true)); assertFalse(MediaCameraContract.isCertification(started.intent)); assertNull(sheet()); assertEquals(get("cameraRequest"), started.requestCode);
		cameraLaunch=started.intent; assertEquals(cameraLaunch, activity.getNextStartedActivity()); assertNull(activity.getNextStartedActivityForResult()); return started.requestCode;
	}
	@Test public void cameraTileRequestsOnlyCameraWhenNeeded() throws Exception{
		grant(Manifest.permission.READ_EXTERNAL_STORAGE); assertEquals(PackageManager.PERMISSION_DENIED, lifecycle.get().checkSelfPermission(Manifest.permission.CAMERA));
		pick(); invoke(sheet(), MediaPickerSheet.class, "toggle", new Class<?>[]{int.class}, 0);
		int code=(int)get("cameraPermissionRequest"); assertTrue(code>=0); ShadowActivity.IntentForResult request=permissionRequest(code, Manifest.permission.CAMERA);
		int token=(int)get("photoGeneration"); lifecycle.pause(); assertFalse(fragment.isResumed()); assertEquals(code, get("cameraPermissionRequest")); assertEquals(token, get("photoGeneration")); lifecycle.resume();
		permissionResult(request, PackageManager.PERMISSION_GRANTED); ShadowActivity.IntentForResult camera=activity.getNextStartedActivityForResult(); assertNotNull(camera);
		assertEquals("org.joinmastodon.android.ui.MediaCameraActivity", camera.intent.getComponent().getClassName()); assertEquals(get("cameraRequest"), camera.requestCode);
	}
	@Test @SuppressWarnings("unchecked") public void cameraPhotoSurvivesHostPauseButVideoCanceledAndStaleResultsDoNotEdit() throws Exception{
		int request=launchCameraThroughTile(); int token=(int)get("photoGeneration"); lifecycle.pause(); assertFalse(fragment.isResumed()); assertEquals(request, get("cameraRequest")); assertEquals(token, get("photoGeneration"));
		Uri cameraUri=cameraUri(); assertEquals("image_cache", cameraUri.getPathSegments().get(0)); assertEquals("image/jpeg", lifecycle.get().getContentResolver().getType(cameraUri));
		// Android delivers the camera result before resuming its host. Exercise real fragment routing.
		activity.receiveResult(cameraLaunch, Activity.RESULT_OK, MediaCameraContract.createResult(cameraUri, false, "image/jpeg")); assertEquals(List.of(cameraUri.toString()), draft().photos);
		lifecycle.resume(); assertTrue(fragment.isResumed()); settleMetadata();
		AlbumUploadDraft.SourceInfo source=((List<AlbumUploadDraft.SourceInfo>)get("sourceInfo")).get(0); assertEquals(image.length(), source.size); assertEquals("image/jpeg", source.mime);
		request=launchCameraThroughTile(); fragment.onActivityResult(request, Activity.RESULT_OK, MediaCameraContract.createResult(imageUri(), true, "video/mp4")); assertEquals(1, draft().photos.size());
		request=launchCameraThroughTile(); fragment.onActivityResult(request, Activity.RESULT_CANCELED, MediaCameraContract.createResult(imageUri(), false, "image/jpeg")); assertEquals(1, draft().photos.size());
		int oldRequest=launchCameraThroughTile(); fragment.onHidden(); int newRequest=launchCameraThroughTile(); assertNotEquals(oldRequest, newRequest); fragment.onActivityResult(oldRequest, Activity.RESULT_OK, MediaCameraContract.createResult(imageUri(), false, "image/jpeg")); assertEquals(1, draft().photos.size());
		field(AccountSessionManager.class, "lastActiveAccountID").set(manager, "other"); fragment.onActivityResult(newRequest, Activity.RESULT_OK, MediaCameraContract.createResult(imageUri(), false, "image/jpeg")); assertEquals(1, draft().photos.size());
	}
	@Test public void cameraRequestHiddenWhileHostPausedCannotReviveAfterShow() throws Exception{
		int request=launchCameraThroughTile(); lifecycle.pause(); assertFalse(fragment.isResumed()); assertEquals(request, get("cameraRequest"));
		hideAndShowWhilePaused(); assertEquals(-1, get("cameraRequest")); lifecycle.resume();
		activity.receiveResult(cameraLaunch, Activity.RESULT_OK, MediaCameraContract.createResult(cameraUri(), false, "image/jpeg")); assertTrue(draft().photos.isEmpty());
		assertNotEquals(request, launchCameraThroughTile());
	}
	@Test public void selectedPhotoMetadataReevaluatesOriginalEligibility() throws Exception{
		StorageQuota quota=new StorageQuota(); quota.sponsorActive=true; quota.originalUploadAllowed=true; set("quota", quota); grant(Manifest.permission.READ_EXTERNAL_STORAGE); pick(); chooseThroughRealSend(sheet(), List.of(imageUri())); settleMetadata();
		assertEquals(true, invoke(fragment, AlbumUploadFragment.class, "originalEligible", new Class<?>[0]));
	}
}
