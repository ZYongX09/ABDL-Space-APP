package org.joinmastodon.android.ui.media;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import java.util.List;

public class MediaCameraPreviewView extends TextureView implements TextureView.SurfaceTextureListener{
	private HandlerThread cameraThread;
	private Handler cameraHandler;
	private CameraDevice camera;
	private CameraCaptureSession session;
	private boolean previewEnabled;
	private boolean cameraOpening;
	private Size previewSize;

	public MediaCameraPreviewView(Context context){
		super(context);
		setSurfaceTextureListener(this);
	}

	private void startCamera(){
		if(!previewEnabled || getContext().checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED || camera!=null || cameraOpening)
			return;
		cameraOpening=true;
		cameraThread=new HandlerThread("media-picker-camera");
		cameraThread.start();
		cameraHandler=new Handler(cameraThread.getLooper());
		try{
			CameraManager manager=getContext().getSystemService(CameraManager.class);
			String selected=null;
			for(String id:manager.getCameraIdList()){
				CameraCharacteristics characteristics=manager.getCameraCharacteristics(id);
				Integer facing=characteristics.get(CameraCharacteristics.LENS_FACING);
				if(facing!=null && facing==CameraCharacteristics.LENS_FACING_BACK){
					selected=id;
					previewSize=choosePreviewSize(characteristics);
					break;
				}
			}
			if(selected==null && manager.getCameraIdList().length>0){
				selected=manager.getCameraIdList()[0];
				previewSize=choosePreviewSize(manager.getCameraCharacteristics(selected));
			}
			if(selected!=null)
				manager.openCamera(selected, new CameraDevice.StateCallback(){
					@Override public void onOpened(CameraDevice device){
						cameraOpening=false;
						if(!previewEnabled){
							device.close();
							return;
						}
						camera=device;
						startPreview();
					}
					@Override public void onDisconnected(CameraDevice device){ cameraOpening=false; closeCamera(); }
					@Override public void onError(CameraDevice device, int error){ cameraOpening=false; closeCamera(); }
				}, cameraHandler);
		}catch(Exception ignored){
			cameraOpening=false;
			closeCamera();
		}
	}

	private void startPreview(){
		SurfaceTexture texture=getSurfaceTexture();
		if(camera==null || texture==null)
			return;
		try{
			if(previewSize!=null)
				texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
			Surface surface=new Surface(texture);
			CaptureRequest.Builder request=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
			request.addTarget(surface);
			camera.createCaptureSession(List.of(surface), new CameraCaptureSession.StateCallback(){
				@Override public void onConfigured(CameraCaptureSession captureSession){
					session=captureSession;
					try{
						request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
						session.setRepeatingRequest(request.build(), null, cameraHandler);
					}catch(Exception ignored){ }
				}
				@Override public void onConfigureFailed(CameraCaptureSession captureSession){ }
			}, cameraHandler);
		}catch(Exception ignored){ }
	}

	private Size choosePreviewSize(CameraCharacteristics characteristics){
		StreamConfigurationMap map=characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
		if(map==null)
			return null;
		Size[] sizes=map.getOutputSizes(SurfaceTexture.class);
		if(sizes==null || sizes.length==0)
			return null;
		Size best=sizes[0];
		long target=Math.max(1, getWidth())*(long)Math.max(1, getHeight());
		for(Size size:sizes){
			long area=size.getWidth()*(long)size.getHeight();
			long bestArea=best.getWidth()*(long)best.getHeight();
			if(Math.abs(area-target)<Math.abs(bestArea-target))
				best=size;
		}
		return best;
	}

	public void closeCamera(){
		cameraOpening=false;
		if(session!=null){
			session.close();
			session=null;
		}
		if(camera!=null){
			camera.close();
			camera=null;
		}
		if(cameraThread!=null){
			cameraThread.quitSafely();
			cameraThread=null;
			cameraHandler=null;
		}
	}

	public void setPreviewEnabled(boolean enabled){
		previewEnabled=enabled;
		if(enabled && isAttachedToWindow() && isAvailable())
			startCamera();
		else if(!enabled)
			closeCamera();
	}

	@Override protected void onAttachedToWindow(){
		super.onAttachedToWindow();
		if(previewEnabled && isAvailable())
			startCamera();
	}

	@Override protected void onDetachedFromWindow(){
		closeCamera();
		super.onDetachedFromWindow();
	}

	@Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height){ startCamera(); }
	@Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height){ }
	@Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface){ closeCamera(); return true; }
	@Override public void onSurfaceTextureUpdated(SurfaceTexture surface){ }
}
