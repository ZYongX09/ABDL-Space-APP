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
import android.util.AttributeSet;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import java.util.Collections;

public class MediaCameraPreviewView extends TextureView implements TextureView.SurfaceTextureListener {
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private Surface previewSurface;
    private boolean previewEnabled;
    private int generation;

    public MediaCameraPreviewView(Context context) {
        this(context, null);
    }

    public MediaCameraPreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setSurfaceTextureListener(this);
    }

    public void setPreviewEnabled(boolean enabled) {
        if (previewEnabled == enabled) return;
        previewEnabled = enabled;
        if (enabled) {
            if (isAvailable()) openCamera();
        } else {
            closeCamera();
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (previewEnabled) openCamera();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        closeCamera();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {}

    private void openCamera() {
        if (cameraThread != null) return;
        if (getContext().checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        int currentGeneration = ++generation;
        cameraThread = new HandlerThread("media-picker-preview");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        cameraHandler.post(() -> {
            try {
                CameraManager cm = getContext().getSystemService(CameraManager.class);
                String backId = null;
                for (String id : cm.getCameraIdList()) {
                    Integer facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        backId = id;
                        break;
                    }
                }
                if (backId == null) return;
                cm.openCamera(backId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(CameraDevice device) {
                        if (currentGeneration != generation) {
                            device.close();
                            return;
                        }
                        cameraDevice = device;
                        createPreview(currentGeneration);
                    }

                    @Override
                    public void onDisconnected(CameraDevice device) {
                        device.close();
                    }

                    @Override
                    public void onError(CameraDevice device, int error) {
                        device.close();
                    }
                }, cameraHandler);
            } catch (Exception ignored) {}
        });
    }

    private void createPreview(int currentGeneration) {
        try {
            SurfaceTexture texture = getSurfaceTexture();
            if (texture == null || cameraDevice == null) return;
            CameraManager cm = getContext().getSystemService(CameraManager.class);
            StreamConfigurationMap map = cm.getCameraCharacteristics(cameraDevice.getId())
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size size = null;
            if (map != null) {
                for (Size s : map.getOutputSizes(SurfaceTexture.class)) {
                    if (s.getWidth() <= 1280 && s.getHeight() <= 720) {
                        size = s;
                        break;
                    }
                }
                if (size == null) size = map.getOutputSizes(SurfaceTexture.class)[0];
            }
            if (size != null) texture.setDefaultBufferSize(size.getWidth(), size.getHeight());
            previewSurface = new Surface(texture);
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);
            cameraDevice.createCaptureSession(Collections.singletonList(previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (currentGeneration != generation || cameraDevice == null) {
                                session.close();
                                return;
                            }
                            captureSession = session;
                            try {
                                builder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                session.setRepeatingRequest(builder.build(), null, cameraHandler);
                            } catch (Exception ignored) {}
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {}
                    }, cameraHandler);
        } catch (Exception ignored) {}
    }

    private void closeCamera() {
        generation++;
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
            cameraHandler = null;
        }
    }
}
