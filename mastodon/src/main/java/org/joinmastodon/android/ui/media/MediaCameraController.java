package org.joinmastodon.android.ui.media;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import org.joinmastodon.android.R;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class MediaCameraController{
	public interface Callback{
		void onCameraReady(boolean frontFacing, boolean flashAvailable, boolean switchAvailable);
		void onPhotoCaptured(File file);
		void onRecordingStarted();
		void onVideoRecorded(File file);
		void onError(int messageRes);
	}

	public enum State{ CLOSED, OPENING, PREVIEW, CAPTURING, RECORDING, CLOSING }
	public enum FlashMode{ OFF, AUTO, ON }

	private final Activity activity;
	private final Callback callback;
	private final Handler mainHandler=new Handler(Looper.getMainLooper());
	private final CameraManager cameraManager;
	private HandlerThread cameraThread;
	private Handler cameraHandler;
	private CameraDevice camera;
	private CameraCaptureSession session;
	private ImageReader imageReader;
	private MediaRecorder mediaRecorder;
	private TextureView textureView;
	private Surface previewSurface;
	private CaptureRequest.Builder previewRequest;
	private String backCameraId;
	private String frontCameraId;
	private String cameraId;
	private CameraCharacteristics characteristics;
	private State state=State.CLOSED;
	private FlashMode flashMode=FlashMode.OFF;
	private float zoom=1f;
	private int generation;
	private File pendingPhoto;
	private File pendingVideo;
	private boolean recordingStarted;
	private boolean recordingStopRequested;
	private boolean keepRequestedRecording;

	public MediaCameraController(Activity activity, Callback callback){
		this.activity=activity;
		this.callback=callback;
		cameraManager=activity.getSystemService(CameraManager.class);
	}

	public State getState(){
		return state;
	}

	public float getMaxZoom(){
		if(characteristics==null)
			return 1f;
		Float value=characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
		return value==null ? 1f : Math.max(1f, value);
	}

	public void open(TextureView view){
		if(state!=State.CLOSED)
			return;
		textureView=view;
		state=State.OPENING;
		int currentGeneration=++generation;
		cameraThread=new HandlerThread("media-camera");
		cameraThread.start();
		cameraHandler=new Handler(cameraThread.getLooper());
		cameraHandler.post(()->openCamera(currentGeneration));
	}

	private void openCamera(int currentGeneration){
		try{
			findCameras();
			if(cameraId==null)
				cameraId=backCameraId!=null ? backCameraId : frontCameraId;
			if(cameraId==null || activity.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
				fail(R.string.media_picker_camera_failed);
				return;
			}
			characteristics=cameraManager.getCameraCharacteristics(cameraId);
			prepareImageReader();
			cameraManager.openCamera(cameraId, new CameraDevice.StateCallback(){
				@Override public void onOpened(CameraDevice device){
					if(currentGeneration!=generation || state!=State.OPENING){
						device.close();
						return;
					}
					camera=device;
					createPreviewSession(currentGeneration);
				}
				@Override public void onDisconnected(CameraDevice device){
					device.close();
					if(currentGeneration==generation)
						fail(R.string.media_picker_camera_failed);
				}
				@Override public void onError(CameraDevice device, int error){
					onDisconnected(device);
				}
			}, cameraHandler);
		}catch(Exception x){
			fail(R.string.media_picker_camera_failed);
		}
	}

	private void findCameras() throws CameraAccessException{
		backCameraId=null;
		frontCameraId=null;
		for(String id:cameraManager.getCameraIdList()){
			Integer facing=cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
			if(facing!=null && facing==CameraCharacteristics.LENS_FACING_BACK && backCameraId==null)
				backCameraId=id;
			else if(facing!=null && facing==CameraCharacteristics.LENS_FACING_FRONT && frontCameraId==null)
				frontCameraId=id;
		}
	}

	private void prepareImageReader(){
		StreamConfigurationMap map=characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
		Size[] sizes=map==null ? null : map.getOutputSizes(ImageFormat.JPEG);
		Size size=chooseSize(sizes, 2560, 2560, 4f/3f);
		if(size==null)
			size=new Size(1920, 1080);
		imageReader=ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 2);
		imageReader.setOnImageAvailableListener(this::savePhoto, cameraHandler);
	}

	private void createPreviewSession(int currentGeneration){
		try{
			SurfaceTexture texture=textureView.getSurfaceTexture();
			if(texture==null){
				fail(R.string.media_picker_camera_failed);
				return;
			}
			StreamConfigurationMap map=characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
			Size[] sizes=map==null ? null : map.getOutputSizes(SurfaceTexture.class);
			Size previewSize=chooseSize(sizes, 1920, 1080, textureView.getWidth()/(float)Math.max(1, textureView.getHeight()));
			if(previewSize!=null)
				texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
			previewSurface=new Surface(texture);
			previewRequest=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
			previewRequest.addTarget(previewSurface);
			applyPreviewSettings();
			List<Surface> surfaces=new ArrayList<>();
			surfaces.add(previewSurface);
			surfaces.add(imageReader.getSurface());
			camera.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback(){
				@Override public void onConfigured(CameraCaptureSession captureSession){
					if(currentGeneration!=generation || camera==null){
						captureSession.close();
						return;
					}
					session=captureSession;
					try{
						session.setRepeatingRequest(previewRequest.build(), null, cameraHandler);
						state=State.PREVIEW;
						Boolean flash=characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
						boolean front=isFrontFacing();
						mainHandler.post(()->callback.onCameraReady(front, Boolean.TRUE.equals(flash), backCameraId!=null && frontCameraId!=null));
					}catch(CameraAccessException x){
						fail(R.string.media_picker_camera_failed);
					}
				}
				@Override public void onConfigureFailed(CameraCaptureSession captureSession){
					fail(R.string.media_picker_camera_failed);
				}
			}, cameraHandler);
		}catch(Exception x){
			fail(R.string.media_picker_camera_failed);
		}
	}

	public void takePhoto(File file){
		if(state!=State.PREVIEW || cameraHandler==null)
			return;
		state=State.CAPTURING;
		pendingPhoto=file;
		cameraHandler.post(()->{
			try{
				CaptureRequest.Builder request=camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
				request.addTarget(imageReader.getSurface());
				request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
				applyFlash(request);
				applyCrop(request);
				Integer sensor=characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
				request.set(CaptureRequest.JPEG_ORIENTATION, MediaCameraContract.jpegOrientation(sensor==null ? 0 : sensor, activity.getDisplay().getRotation(), isFrontFacing()));
				session.capture(request.build(), new CameraCaptureSession.CaptureCallback(){
					@Override public void onCaptureCompleted(CameraCaptureSession captureSession, CaptureRequest captureRequest, TotalCaptureResult result){
						state=State.PREVIEW;
					}
				}, cameraHandler);
			}catch(Exception x){
				state=State.PREVIEW;
				deletePendingPhoto();
				fail(R.string.media_picker_camera_failed);
			}
		});
	}

	public void startRecording(File file){
		if(state!=State.PREVIEW || cameraHandler==null)
			return;
		state=State.OPENING;
		pendingVideo=file;
		recordingStopRequested=false;
		keepRequestedRecording=false;
		cameraHandler.post(()->{
			try{
				if(session!=null){ session.close(); session=null; }
				prepareRecorder(file);
				CaptureRequest.Builder request=camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
				request.addTarget(previewSurface);
				request.addTarget(mediaRecorder.getSurface());
				request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
				applyCrop(request);
				camera.createCaptureSession(List.of(previewSurface, mediaRecorder.getSurface()), new CameraCaptureSession.StateCallback(){
					@Override public void onConfigured(CameraCaptureSession captureSession){
						session=captureSession;
						try{
							session.setRepeatingRequest(request.build(), null, cameraHandler);
							mediaRecorder.start();
							recordingStarted=true;
							state=State.RECORDING;
							mainHandler.post(callback::onRecordingStarted);
							if(recordingStopRequested)
								stopRecordingInternal(keepRequestedRecording);
						}catch(Exception x){
							stopRecordingInternal(false);
							fail(R.string.media_picker_camera_failed);
						}
					}
					@Override public void onConfigureFailed(CameraCaptureSession captureSession){
						stopRecordingInternal(false);
						fail(R.string.media_picker_camera_failed);
					}
				}, cameraHandler);
			}catch(Exception x){
				stopRecordingInternal(false);
				fail(R.string.media_picker_camera_failed);
			}
		});
	}

	private void prepareRecorder(File file) throws Exception{
		mediaRecorder=new MediaRecorder();
		mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
		mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
		mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
		mediaRecorder.setOutputFile(file.getAbsolutePath());
		mediaRecorder.setVideoEncodingBitRate(8_000_000);
		mediaRecorder.setVideoFrameRate(30);
		mediaRecorder.setVideoSize(1280, 720);
		mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
		mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
		mediaRecorder.setMaxDuration(60_000);
		Integer sensor=characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
		mediaRecorder.setOrientationHint(MediaCameraContract.jpegOrientation(sensor==null ? 0 : sensor, activity.getDisplay().getRotation(), isFrontFacing()));
		mediaRecorder.setOnInfoListener((recorder, what, extra)->{
			if(what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED)
				stopRecording(true);
		});
		mediaRecorder.prepare();
	}

	public void stopRecording(boolean keep){
		if(state==State.OPENING && pendingVideo!=null){
			recordingStopRequested=true;
			keepRequestedRecording=keep;
			return;
		}
		if(state!=State.RECORDING)
			return;
		if(cameraHandler!=null)
			cameraHandler.post(()->stopRecordingInternal(keep));
	}

	private void stopRecordingInternal(boolean keep){
		recordingStopRequested=false;
		keepRequestedRecording=false;
		File file=pendingVideo;
		pendingVideo=null;
		try{
			if(recordingStarted && mediaRecorder!=null)
				mediaRecorder.stop();
		}catch(Exception x){
			keep=false;
		}finally{
			recordingStarted=false;
			if(mediaRecorder!=null){
				mediaRecorder.reset();
				mediaRecorder.release();
				mediaRecorder=null;
			}
		}
		if(!keep || file==null || file.length()==0){
			if(file!=null)
				file.delete();
			state=State.OPENING;
			createPreviewSession(generation);
			return;
		}
		state=State.PREVIEW;
		File result=file;
		mainHandler.post(()->callback.onVideoRecorded(result));
	}

	private void savePhoto(ImageReader reader){
		Image image=null;
		File file=pendingPhoto;
		pendingPhoto=null;
		try{
			image=reader.acquireNextImage();
			if(image==null || file==null)
				return;
			ByteBuffer buffer=image.getPlanes()[0].getBuffer();
			byte[] bytes=new byte[buffer.remaining()];
			buffer.get(bytes);
			try(FileOutputStream output=new FileOutputStream(file)){
				output.write(bytes);
			}
			mainHandler.post(()->callback.onPhotoCaptured(file));
		}catch(Exception x){
			if(file!=null)
				file.delete();
			fail(R.string.media_picker_camera_failed);
		}finally{
			if(image!=null)
				image.close();
		}
	}

	public FlashMode cycleFlashMode(){
		flashMode=switch(flashMode){
			case OFF -> FlashMode.AUTO;
			case AUTO -> FlashMode.ON;
			case ON -> FlashMode.OFF;
		};
		updatePreview();
		return flashMode;
	}

	public void setZoom(float value){
		zoom=Math.max(1f, Math.min(value, getMaxZoom()));
		updatePreview();
	}

	public void focus(float x, float y){
		if(state!=State.PREVIEW || characteristics==null || previewRequest==null)
			return;
		Rect sensor=characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
		if(sensor==null)
			return;
		int centerX=sensor.left+(int)(sensor.width()*x/Math.max(1, textureView.getWidth()));
		int centerY=sensor.top+(int)(sensor.height()*y/Math.max(1, textureView.getHeight()));
		int half=Math.max(40, Math.min(sensor.width(), sensor.height())/20);
		Rect area=new Rect(Math.max(sensor.left, centerX-half), Math.max(sensor.top, centerY-half), Math.min(sensor.right, centerX+half), Math.min(sensor.bottom, centerY+half));
		try{
			previewRequest.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[]{new MeteringRectangle(area, MeteringRectangle.METERING_WEIGHT_MAX)});
			previewRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
			previewRequest.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
			session.capture(previewRequest.build(), null, cameraHandler);
			previewRequest.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
		}catch(Exception ignored){ }
	}

	public void switchCamera(){
		if(state!=State.PREVIEW || backCameraId==null || frontCameraId==null)
			return;
		cameraId=isFrontFacing() ? backCameraId : frontCameraId;
		flashMode=FlashMode.OFF;
		zoom=1f;
		reopen();
	}

	private void reopen(){
		int currentGeneration=++generation;
		closeCameraObjects();
		state=State.OPENING;
		cameraHandler.post(()->openCamera(currentGeneration));
	}

	private void updatePreview(){
		if(state!=State.PREVIEW || session==null || previewRequest==null)
			return;
		try{
			applyPreviewSettings();
			session.setRepeatingRequest(previewRequest.build(), null, cameraHandler);
		}catch(Exception ignored){ }
	}

	private void applyPreviewSettings(){
		previewRequest.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
		applyFlash(previewRequest);
		applyCrop(previewRequest);
	}

	private void applyFlash(CaptureRequest.Builder request){
		switch(flashMode){
			case OFF -> {
				request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
				request.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
			}
			case AUTO -> request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
			case ON -> request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
		}
	}

	private void applyCrop(CaptureRequest.Builder request){
		Rect sensor=characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
		if(sensor==null || zoom<=1f){
			request.set(CaptureRequest.SCALER_CROP_REGION, sensor);
			return;
		}
		int width=(int)(sensor.width()/zoom);
		int height=(int)(sensor.height()/zoom);
		int left=sensor.centerX()-width/2;
		int top=sensor.centerY()-height/2;
		request.set(CaptureRequest.SCALER_CROP_REGION, new Rect(left, top, left+width, top+height));
	}

	private boolean isFrontFacing(){
		return cameraId!=null && cameraId.equals(frontCameraId);
	}

	public void close(){
		if(state==State.CLOSED || state==State.CLOSING)
			return;
		state=State.CLOSING;
		generation++;
		if(cameraHandler!=null)
			cameraHandler.post(()->{
				if(mediaRecorder!=null)
					stopRecordingInternal(false);
				closeCameraObjects();
				state=State.CLOSED;
				HandlerThread thread=cameraThread;
				cameraThread=null;
				cameraHandler=null;
				if(thread!=null)
					thread.quitSafely();
			});
	}

	private void closeCameraObjects(){
		if(session!=null){ session.close(); session=null; }
		if(camera!=null){ camera.close(); camera=null; }
		if(imageReader!=null){ imageReader.close(); imageReader=null; }
		if(previewSurface!=null){ previewSurface.release(); previewSurface=null; }
		if(mediaRecorder!=null){
			try{ mediaRecorder.reset(); }catch(Exception ignored){ }
			mediaRecorder.release();
			mediaRecorder=null;
		}
		previewRequest=null;
	}

	private void deletePendingPhoto(){
		if(pendingPhoto!=null){ pendingPhoto.delete(); pendingPhoto=null; }
	}

	private void fail(int messageRes){
		mainHandler.post(()->callback.onError(messageRes));
	}

	public static Size chooseSize(Size[] choices, int maxWidth, int maxHeight, float targetRatio){
		if(choices==null || choices.length==0)
			return null;
		return Arrays.stream(choices)
				.filter(size->size.getWidth()<=maxWidth && size.getHeight()<=maxHeight)
				.sorted(Comparator.comparingLong((Size size)->-(long)size.getWidth()*size.getHeight()))
				.filter(size->Math.abs(size.getWidth()/(float)size.getHeight()-targetRatio)<0.08f)
				.findFirst()
				.orElseGet(()->Arrays.stream(choices)
						.filter(size->size.getWidth()<=maxWidth && size.getHeight()<=maxHeight)
						.max(Comparator.comparingLong(size->(long)size.getWidth()*size.getHeight()))
						.orElse(choices[0]));
	}
}
