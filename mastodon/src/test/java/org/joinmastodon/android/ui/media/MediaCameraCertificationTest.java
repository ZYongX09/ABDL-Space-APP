package org.joinmastodon.android.ui.media;

import static org.junit.Assert.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.MediaCameraActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class MediaCameraCertificationTest{
	private static final String SESSION="123e4567-e89b-42d3-a456-426614174000";

	private ShadowActivity shadowOf(Activity activity){ return Shadow.extract(activity); }

	private Intent request(){
		return MediaCameraContract.createCertificationIntent(RuntimeEnvironment.getApplication(), SESSION, 0, "手写验证码 123456，完整展示拍摄要求", 60_000);
	}

	private ActivityController<MediaCameraActivity> launch(Intent intent){
		org.robolectric.shadows.ShadowApplication application=Shadow.extract(RuntimeEnvironment.getApplication());
		application.grantPermissions(Manifest.permission.CAMERA);
		return Robolectric.buildActivity(MediaCameraActivity.class, intent).setup();
	}

	private File photo(Intent intent) throws Exception{
		File file=new File(MediaCameraContract.getControlledPath(intent));
		assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
		Bitmap bitmap=Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
		try(FileOutputStream output=new FileOutputStream(file)){
			assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output));
		}
		return file;
	}

	private void invoke(MediaCameraActivity activity, String name) throws Exception{
		Method method=MediaCameraActivity.class.getDeclaredMethod(name);
		method.setAccessible(true);
		method.invoke(activity);
	}

	@Test public void requestAndResultRoundTripRealProtocol() throws Exception{
		Intent intent=request();
		MediaCameraContract.CertificationRequest parsed=MediaCameraContract.readCertificationRequest(RuntimeEnvironment.getApplication(), intent);
		assertEquals(SESSION, parsed.sessionId());
		assertEquals(0, parsed.slot());
		assertEquals(intent.getStringExtra(MediaCameraContract.EXTRA_CERTIFICATION_REQUIREMENT), parsed.requirement());
		assertFalse(intent.getBooleanExtra(MediaCameraContract.EXTRA_ALLOW_VIDEO, true));
		assertEquals(intent.getLongExtra(MediaCameraContract.EXTRA_CERTIFICATION_DEADLINE, 0), parsed.deadline());
		Intent result=MediaCameraContract.createCertificationResult(parsed.output().getAbsolutePath(), parsed.sessionId(), parsed.slot());
		assertEquals(parsed.output().getAbsolutePath(), MediaCameraContract.getControlledPath(result));
		assertEquals(SESSION, MediaCameraContract.getCertificationSession(result));
		assertEquals(0, MediaCameraContract.getCertificationSlot(result));
		assertNull(MediaCameraContract.getUri(result));
	}

	@Test public void actualUiDisplaysRequirementHidesGalleryAndReturnsCertificationPhoto() throws Exception{
		Intent intent=request().putExtra(MediaCameraContract.EXTRA_ALLOW_VIDEO, true);
		ActivityController<MediaCameraActivity> lifecycle=launch(intent);
		MediaCameraActivity activity=lifecycle.get();
		assertEquals(View.VISIBLE, activity.findViewById(R.id.camera_certification_panel).getVisibility());
		assertEquals(intent.getStringExtra(MediaCameraContract.EXTRA_CERTIFICATION_REQUIREMENT), ((TextView)activity.findViewById(R.id.camera_certification_requirement)).getText().toString());
		assertEquals(View.GONE, activity.findViewById(R.id.camera_gallery).getVisibility());
		assertEquals(activity.getString(R.string.verification_camera_capture_hint), ((TextView)activity.findViewById(R.id.camera_capture_hint)).getText().toString());
		activity.findViewById(R.id.camera_gallery).performClick();
		assertFalse(activity.isFinishing());
		MediaCameraController.State before=controller(activity).getState();
		invoke(activity, "startRecording");
		assertEquals(before, controller(activity).getState());
		File raw=photo(intent);
		activity.onPhotoCaptured(raw);
		activity.findViewById(R.id.camera_use).performClick();
		assertEquals(Activity.RESULT_OK, shadowOf(activity).getResultCode());
		Intent result=shadowOf(activity).getResultIntent();
		assertEquals(raw.getAbsolutePath(), MediaCameraContract.getControlledPath(result));
		assertEquals(SESSION, MediaCameraContract.getCertificationSession(result));
		assertEquals(0, MediaCameraContract.getCertificationSlot(result));
		lifecycle.pause().stop().destroy();
		assertTrue(raw.isFile()); // ownership transferred to the Fragment's processing/upload chain
		raw.delete();
	}

	private MediaCameraController controller(MediaCameraActivity activity) throws Exception{
		var field=MediaCameraActivity.class.getDeclaredField("controller");
		field.setAccessible(true);
		return (MediaCameraController)field.get(activity);
	}

	@Test public void pausedAndRecreatedCameraDoesNotRestartDeadlineAndCannotAcceptExpiredReview() throws Exception{
		Intent intent=request().putExtra(MediaCameraContract.EXTRA_CERTIFICATION_DEADLINE, System.currentTimeMillis()+60_000);
		ActivityController<MediaCameraActivity> lifecycle=launch(intent);
		MediaCameraActivity activity=lifecycle.get();
		File raw=photo(intent);
		activity.onPhotoCaptured(raw);
		Bundle saved=new Bundle();
		lifecycle.saveInstanceState(saved).pause().stop().destroy();
		assertTrue(raw.exists());
		// Expire the saved absolute deadline deterministically; slow CI setup must not expire the initial capture.
		intent.putExtra(MediaCameraContract.EXTRA_CERTIFICATION_DEADLINE, System.currentTimeMillis()-1);
		ActivityController<MediaCameraActivity> restored=Robolectric.buildActivity(MediaCameraActivity.class, intent).create(saved).start().resume();
		MediaCameraActivity second=restored.get();
		assertFalse(second.findViewById(R.id.camera_use).isEnabled());
		assertFalse(second.findViewById(R.id.camera_shutter).isEnabled());
		assertEquals(second.getString(R.string.verification_capture_expired), ((TextView)second.findViewById(R.id.camera_certification_timer)).getText().toString());
		invoke(second, "useMedia"); // guard works independently of disabled UI
		assertNotEquals(Activity.RESULT_OK, shadowOf(second).getResultCode());
		second.finish();
		restored.pause().stop().destroy();
		assertFalse(raw.exists());
	}

	@Test public void retakeDeletesOnlyControlledPhotoAndExpiredCallbackCannotCreateReview() throws Exception{
		Intent intent=request();
		ActivityController<MediaCameraActivity> lifecycle=launch(intent);
		MediaCameraActivity activity=lifecycle.get();
		File raw=photo(intent);
		activity.onPhotoCaptured(raw);
		activity.findViewById(R.id.camera_retake).performClick();
		assertFalse(raw.exists());
		assertEquals(View.VISIBLE, activity.findViewById(R.id.camera_capture_controls).getVisibility());
		activity.finish();
		lifecycle.pause().stop().destroy();
		raw=photo(intent);
		activity.onPhotoCaptured(raw);
		assertFalse(raw.exists());
	}

	@Test public void invalidPathFailsClosedWithoutDeletingCallerFile() throws Exception{
		File unrelated=File.createTempFile("unrelated", ".jpg", RuntimeEnvironment.getApplication().getCacheDir());
		Intent intent=request().putExtra(MediaCameraContract.EXTRA_CONTROLLED_PATH, unrelated.getAbsolutePath());
		ActivityController<MediaCameraActivity> lifecycle=launch(intent);
		assertTrue(lifecycle.get().isFinishing());
		assertNotEquals(Activity.RESULT_OK, shadowOf(lifecycle.get()).getResultCode());
		lifecycle.pause().stop().destroy();
		assertTrue(unrelated.exists());
		unrelated.delete();
	}

	@Test public void symlinkOutputIsRejectedWithoutTouchingItsTarget() throws Exception{
		Intent intent=request();
		File expected=new File(MediaCameraContract.getControlledPath(intent));
		assertTrue(expected.getParentFile().isDirectory() || expected.getParentFile().mkdirs());
		expected.delete();
		File target=File.createTempFile("outside", ".jpg", RuntimeEnvironment.getApplication().getCacheDir());
		try{
			java.nio.file.Files.createSymbolicLink(expected.toPath(), target.toPath());
			assertThrows(IllegalArgumentException.class, ()->MediaCameraContract.readCertificationRequest(RuntimeEnvironment.getApplication(), intent));
			assertTrue(target.exists());
		}finally{
			expected.delete();
			target.delete();
		}
	}

	@Test public void malformedProtocolIsRejected() throws Exception{
		for(Intent invalid:new Intent[]{
			request().putExtra(MediaCameraContract.EXTRA_CERTIFICATION_SESSION, "../escape"),
			request().putExtra(MediaCameraContract.EXTRA_CERTIFICATION_SLOT, 1),
			request().putExtra(MediaCameraContract.EXTRA_CERTIFICATION_REQUIREMENT, " "),
			request().putExtra(MediaCameraContract.EXTRA_CERTIFICATION_DURATION, 0L),
			request().putExtra(MediaCameraContract.EXTRA_CERTIFICATION_DEADLINE, 0L),
			request().putExtra(MediaCameraContract.EXTRA_CONTROLLED_PATH, "/sdcard/photo.jpg")
		}){
			assertThrows(IllegalArgumentException.class, ()->MediaCameraContract.readCertificationRequest(RuntimeEnvironment.getApplication(), invalid));
		}
	}

	@Test public void captureErrorOffersRetryAndExitWithoutAcceptingMedia(){
		ActivityController<MediaCameraActivity> lifecycle=launch(request());
		MediaCameraActivity activity=lifecycle.get();
		activity.onError(R.string.media_picker_camera_failed);
		android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
		assertTrue(dialog.isShowing());
		assertEquals(activity.getString(R.string.verification_camera_exit), dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).getText().toString());
		dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();
		org.robolectric.shadows.ShadowLooper.idleMainLooper();
		assertTrue(activity.isFinishing());
		assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).getResultCode());
		lifecycle.pause().stop().destroy();
	}

	@Test public void foregroundCountdownExpiresAndRejectsLatePhoto() throws Exception{
		Intent intent=request().putExtra(MediaCameraContract.EXTRA_CERTIFICATION_DEADLINE, System.currentTimeMillis()+1500);
		ActivityController<MediaCameraActivity> lifecycle=launch(intent);
		MediaCameraActivity activity=lifecycle.get();
		Thread.sleep(1600);
		org.robolectric.shadows.ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS);
		assertFalse(activity.findViewById(R.id.camera_shutter).isEnabled());
		File raw=photo(intent);
		activity.onPhotoCaptured(raw);
		assertFalse(raw.exists());
		assertEquals(View.GONE, activity.findViewById(R.id.camera_review_controls).getVisibility());
		activity.finish();
		lifecycle.pause().stop().destroy();
	}

	@Test public void normalVideoProtocolRemainsIndependentOfCertification(){
		android.net.Uri uri=android.net.Uri.parse("content://camera/video.mp4");
		Intent result=MediaCameraContract.createResult(uri, true, "video/mp4");
		assertEquals(uri, MediaCameraContract.getUri(result));
		assertTrue(MediaCameraContract.isVideo(result));
		assertEquals("video/mp4", MediaCameraContract.getMimeType(result));
		assertNull(MediaCameraContract.getControlledPath(result));
		assertFalse(MediaCameraContract.isCertification(result));
	}

	@Test public void normalCameraKeepsGalleryAndGenericPhotoResult() throws Exception{
		Intent intent=MediaCameraContract.createIntent(RuntimeEnvironment.getApplication(), true);
		ActivityController<MediaCameraActivity> lifecycle=launch(intent);
		MediaCameraActivity activity=lifecycle.get();
		assertEquals(View.GONE, activity.findViewById(R.id.camera_certification_panel).getVisibility());
		assertEquals(View.VISIBLE, activity.findViewById(R.id.camera_gallery).getVisibility());
		assertTrue(intent.getBooleanExtra(MediaCameraContract.EXTRA_ALLOW_VIDEO, false));
		File dir=new File(activity.getCacheDir(), "images");
		assertTrue(dir.isDirectory() || dir.mkdirs());
		File file=File.createTempFile("camera_", ".jpg", dir);
		activity.onPhotoCaptured(file);
		activity.findViewById(R.id.camera_use).performClick();
		assertEquals(Activity.RESULT_OK, shadowOf(activity).getResultCode());
		assertNotNull(MediaCameraContract.getUri(shadowOf(activity).getResultIntent()));
		assertNull(MediaCameraContract.getControlledPath(shadowOf(activity).getResultIntent()));
		assertEquals("image/jpeg", MediaCameraContract.getMimeType(shadowOf(activity).getResultIntent()));
		lifecycle.pause().stop().destroy();
		file.delete();
	}
}
