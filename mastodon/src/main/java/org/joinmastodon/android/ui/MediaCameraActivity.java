package org.joinmastodon.android.ui;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.TextureView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.widget.VideoView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.media.MediaCameraContract;
import org.joinmastodon.android.ui.media.MediaCameraController;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.joinmastodon.android.ui.views.MediaCameraShutterView;

import java.io.File;

public class MediaCameraActivity extends Activity implements MediaCameraController.Callback{
	private static final int AUDIO_PERMISSION_REQUEST=733;
	private static final String STATE_REVIEW_FILE="review_file";
	private static final String STATE_REVIEW_VIDEO="review_video";

	private TextureView preview;
	private ImageView reviewImage;
	private VideoView reviewVideo;
	private View captureControls;
	private LinearLayout reviewControls;
	private View focusView;
	private ImageButton flashButton;
	private ImageButton switchButton;
	private Button useButton;
	private MediaCameraShutterView shutter;
	private android.widget.TextView recordingTimer;
	private MediaCameraController controller;
	private ScaleGestureDetector scaleDetector;
	private float zoom=1f;
	private boolean scaling;
	private File reviewFile;
	private boolean reviewIsVideo;
	private long recordingStartedAt;
	private boolean recording;
	private boolean recordingStarting;
	private final Runnable recordingTick=new Runnable(){
		@Override public void run(){
			if(!recording)
				return;
			long elapsed=System.currentTimeMillis()-recordingStartedAt;
			recordingTimer.setText(String.format(java.util.Locale.US, "%02d:%02d", elapsed/60_000, elapsed/1000%60));
			shutter.setProgress(elapsed/60_000f);
			if(elapsed>=60_000)
				stopRecording(true);
			else
				recordingTimer.postDelayed(this, 200);
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		getWindow().setStatusBarColor(0x00000000);
		getWindow().setNavigationBarColor(0xff000000);
		getWindow().setDecorFitsSystemWindows(false);
		WindowInsetsController insetsController=getWindow().getInsetsController();
		if(insetsController!=null)
			insetsController.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
		setContentView(R.layout.activity_media_camera);

		preview=findViewById(R.id.camera_preview);
		reviewImage=findViewById(R.id.camera_review_image);
		reviewVideo=findViewById(R.id.camera_review_video);
		captureControls=findViewById(R.id.camera_capture_controls);
		reviewControls=findViewById(R.id.camera_review_controls);
		focusView=findViewById(R.id.camera_focus);
		flashButton=findViewById(R.id.camera_flash);
		switchButton=findViewById(R.id.camera_switch);
		useButton=findViewById(R.id.camera_use);
		shutter=findViewById(R.id.camera_shutter);
		recordingTimer=findViewById(R.id.recording_timer);
		controller=new MediaCameraController(this, this);

		View topControls=findViewById(R.id.camera_top_controls);
		topControls.setOnApplyWindowInsetsListener((view, insets)->{
			int top=insets.getInsets(WindowInsets.Type.statusBars()).top;
			view.setPadding(view.getPaddingLeft(), top+dp(12), view.getPaddingRight(), view.getPaddingBottom());
			return insets;
		});
		findViewById(R.id.camera_back).setOnClickListener(v->handleBack());
		findViewById(R.id.camera_gallery).setOnClickListener(v->handleBack());
		findViewById(R.id.camera_retake).setOnClickListener(v->retake());
		useButton.setOnClickListener(v->useMedia());
		flashButton.setOnClickListener(v->updateFlashButton(controller.cycleFlashMode()));
		switchButton.setOnClickListener(v->{
			zoom=1f;
			controller.switchCamera();
		});
		shutter.setListener(new MediaCameraShutterView.Listener(){
			@Override public void onTap(){ capturePhoto(); }
			@Override public void onHoldStart(){ startRecording(); }
			@Override public void onHoldEnd(){ stopRecording(true); }
		});

		scaleDetector=new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener(){
			@Override public boolean onScaleBegin(ScaleGestureDetector detector){ scaling=true; return true; }
			@Override public boolean onScale(ScaleGestureDetector detector){
				zoom=Math.max(1f, Math.min(controller.getMaxZoom(), zoom*detector.getScaleFactor()));
				controller.setZoom(zoom);
				return true;
			}
			@Override public void onScaleEnd(ScaleGestureDetector detector){ preview.postDelayed(()->scaling=false, 80); }
		});
		preview.setOnTouchListener((view, event)->handlePreviewTouch(event));

		if(savedInstanceState!=null){
			String path=savedInstanceState.getString(STATE_REVIEW_FILE);
			if(path!=null){
				reviewFile=new File(path);
				reviewIsVideo=savedInstanceState.getBoolean(STATE_REVIEW_VIDEO);
				if(reviewFile.exists())
					showReview();
				else
					reviewFile=null;
			}
		}
	}

	private boolean handlePreviewTouch(MotionEvent event){
		scaleDetector.onTouchEvent(event);
		if(event.getActionMasked()==MotionEvent.ACTION_UP && !scaling && event.getPointerCount()==1){
			controller.focus(event.getX(), event.getY());
			showFocus(event.getX(), event.getY());
		}
		return true;
	}

	private void showFocus(float x, float y){
		focusView.setX(x-focusView.getWidth()/2f);
		focusView.setY(y-focusView.getHeight()/2f);
		focusView.setScaleX(1.35f);
		focusView.setScaleY(1.35f);
		focusView.setAlpha(1f);
		focusView.setVisibility(View.VISIBLE);
		focusView.animate().scaleX(1f).scaleY(1f).alpha(0f).setDuration(700).withEndAction(()->focusView.setVisibility(View.GONE)).start();
	}

	@Override protected void onResume(){
		super.onResume();
		if(reviewFile==null){
			if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
				Toast.makeText(this, R.string.media_picker_camera_failed, Toast.LENGTH_SHORT).show();
				finish();
				return;
			}
			if(preview.isAvailable())
				controller.open(preview);
			else
				preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
					@Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surface, int width, int height){ controller.open(preview); }
					@Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surface, int width, int height){ }
					@Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surface){ return true; }
					@Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surface){ }
				});
		}
	}

	@Override protected void onPause(){
		if(recording)
			stopRecording(false);
		controller.close();
		super.onPause();
	}

	@Override protected void onSaveInstanceState(Bundle outState){
		super.onSaveInstanceState(outState);
		if(reviewFile!=null){
			outState.putString(STATE_REVIEW_FILE, reviewFile.getAbsolutePath());
			outState.putBoolean(STATE_REVIEW_VIDEO, reviewIsVideo);
		}
	}

	private void capturePhoto(){
		if(controller.getState()!=MediaCameraController.State.PREVIEW)
			return;
		try{
			File dir=new File(getCacheDir(), "images");
			dir.mkdirs();
			controller.takePhoto(File.createTempFile("camera_", ".jpg", dir));
		}catch(Exception x){
			onError(R.string.media_picker_camera_failed);
		}
	}

	@Override public void onCameraReady(boolean frontFacing, boolean flashAvailable, boolean switchAvailable){
		flashButton.setVisibility(flashAvailable ? View.VISIBLE : View.INVISIBLE);
		switchButton.setVisibility(switchAvailable ? View.VISIBLE : View.INVISIBLE);
	}

	@Override public void onPhotoCaptured(File file){
		reviewFile=file;
		reviewIsVideo=false;
		showReview();
	}

	@Override public void onVideoRecorded(File file){
		recording=false;
		recordingTimer.removeCallbacks(recordingTick);
		reviewFile=file;
		reviewIsVideo=true;
		showReview();
	}

	private void startRecording(){
		if(!getIntent().getBooleanExtra(MediaCameraContract.EXTRA_ALLOW_VIDEO, false) || recordingStarting || controller.getState()!=MediaCameraController.State.PREVIEW)
			return;
		if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
			requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_REQUEST);
			return;
		}
		try{
			File dir=new File(getCacheDir(), "videos");
			dir.mkdirs();
			controller.startRecording(File.createTempFile("camera_", ".mp4", dir));
			recordingStarting=true;
		}catch(Exception x){
			onError(R.string.media_picker_camera_failed);
		}
	}

	private void stopRecording(boolean keep){
		if(!recording && !recordingStarting)
			return;
		recording=false;
		recordingStarting=false;
		recordingTimer.removeCallbacks(recordingTick);
		recordingTimer.setVisibility(View.GONE);
		shutter.setRecording(false);
		shutter.setProgress(0f);
		controller.stopRecording(keep);
	}

	@Override public void onRecordingStarted(){
		recordingStarting=false;
		recording=true;
		recordingStartedAt=System.currentTimeMillis();
		recordingTimer.setVisibility(View.VISIBLE);
		shutter.setRecording(true);
		recordingTick.run();
	}

	@Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults){
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if(requestCode==AUDIO_PERMISSION_REQUEST && (grantResults.length==0 || grantResults[0]!=PackageManager.PERMISSION_GRANTED))
			Toast.makeText(this, R.string.media_camera_audio_required, Toast.LENGTH_SHORT).show();
	}

	private void showReview(){
		controller.close();
		preview.setVisibility(View.GONE);
		captureControls.setVisibility(View.GONE);
		flashButton.setVisibility(View.GONE);
		switchButton.setVisibility(View.GONE);
		reviewControls.setVisibility(View.VISIBLE);
		useButton.setText(reviewIsVideo ? R.string.media_camera_use_video : R.string.media_camera_use_photo);
		if(reviewIsVideo){
			reviewVideo.setVisibility(View.VISIBLE);
			reviewVideo.setVideoPath(reviewFile.getAbsolutePath());
			reviewVideo.setOnPreparedListener(player->{ player.setLooping(true); player.setVolume(0f, 0f); reviewVideo.start(); });
		}else{
			reviewImage.setVisibility(View.VISIBLE);
			reviewImage.setImageBitmap(BitmapFactory.decodeFile(reviewFile.getAbsolutePath()));
		}
	}

	private void retake(){
		deleteReviewFile();
		reviewImage.setImageDrawable(null);
		reviewImage.setVisibility(View.GONE);
		reviewVideo.stopPlayback();
		reviewVideo.setVisibility(View.GONE);
		reviewControls.setVisibility(View.GONE);
		preview.setVisibility(View.VISIBLE);
		captureControls.setVisibility(View.VISIBLE);
		controller.open(preview);
	}

	private void useMedia(){
		if(reviewFile==null)
			return;
		Uri uri=UiUtils.getFileProviderUri(this, reviewFile);
		setResult(RESULT_OK, MediaCameraContract.createResult(uri, reviewIsVideo, reviewIsVideo ? "video/mp4" : "image/jpeg"));
		reviewFile=null;
		finish();
	}

	private void handleBack(){
		if(reviewFile!=null)
			retake();
		else{
			setResult(RESULT_CANCELED);
			finish();
		}
	}

	@Override public void onBackPressed(){
		handleBack();
	}

	@Override public void onError(int messageRes){
		Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show();
	}

	@Override protected void onDestroy(){
		controller.close();
		if(isFinishing())
			deleteReviewFile();
		super.onDestroy();
	}

	private void deleteReviewFile(){
		if(reviewFile!=null){
			reviewFile.delete();
			reviewFile=null;
		}
	}

	private int dp(int value){
		return Math.round(value*getResources().getDisplayMetrics().density);
	}

	private void updateFlashButton(MediaCameraController.FlashMode mode){
		switch(mode){
			case OFF -> {
				flashButton.setImageResource(R.drawable.ic_fluent_flash_off_24_filled);
				flashButton.setContentDescription(getString(R.string.media_camera_flash_off));
			}
			case AUTO -> {
				flashButton.setImageResource(R.drawable.ic_fluent_flash_auto_24_filled);
				flashButton.setContentDescription(getString(R.string.media_camera_flash_auto));
			}
			case ON -> {
				flashButton.setImageResource(R.drawable.ic_fluent_flash_24_filled);
				flashButton.setContentDescription(getString(R.string.media_camera_flash_on));
			}
		}
	}
}
