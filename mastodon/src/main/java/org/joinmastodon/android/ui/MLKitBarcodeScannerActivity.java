package org.joinmastodon.android.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.joinmastodon.android.ui.media.MediaPickerConfig;
import org.joinmastodon.android.ui.media.MediaCameraContract;
import org.joinmastodon.android.ui.media.MediaStoreLoader;
import org.joinmastodon.android.ui.sheets.MediaPickerSheet;

import java.util.ArrayList;

import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.camera.CameraSettings;

import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.utils.UiUtils;

public class MLKitBarcodeScannerActivity extends Activity implements SensorEventListener {
    private static final String TAG = "MLKitScanner";
    private static final int CAMERA_PERMISSION_REQUEST = 100;
    private static final int MEDIA_PERMISSION_REQUEST = 101;
    private static final int IMAGE_SCAN_REQUEST = 102;
    private static final float DARK_THRESHOLD = 15f;
    private static final float BRIGHT_THRESHOLD = 60f;
    private DecoratedBarcodeView barcodeView;
    private MediaPickerConfig pendingMediaPickerConfig;
    private SensorManager sensorManager;
    private Sensor lightSensor;
    private LinearLayout torchContainer;
    private ImageButton btnTorch;
    private TextView torchLabel;
    private boolean torchOn;
    private boolean torchVisible;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        getWindow().setStatusBarColor(0x00000000);

        setContentView(R.layout.activity_scanner);

        barcodeView = findViewById(R.id.barcode_scanner);
        View topBar = findViewById(R.id.top_bar);
        ImageButton btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        ImageButton btnGallery = findViewById(R.id.btn_gallery);
        btnGallery.setOnClickListener(v -> openQrImagePicker());

        torchContainer = findViewById(R.id.torch_container);
        btnTorch = findViewById(R.id.btn_torch);
        torchLabel = findViewById(R.id.torch_label);
        btnTorch.setOnClickListener(v -> toggleTorch());

        sensorManager = getSystemService(SensorManager.class);
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);

        int statusBarHeight = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) statusBarHeight = getResources().getDimensionPixelSize(resourceId);
        topBar.setPadding(topBar.getPaddingLeft(), topBar.getPaddingTop() + statusBarHeight,
            topBar.getPaddingRight(), topBar.getPaddingBottom());

        CameraSettings settings = new CameraSettings();
        settings.setRequestedCameraId(0);
        barcodeView.getBarcodeView().setCameraSettings(settings);

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startScanning();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        }
    }

    private void toggleTorch() {
        try {
            CameraManager cm = getSystemService(CameraManager.class);
            String[] ids = cm.getCameraIdList();
            if (ids.length > 0) {
                torchOn = !torchOn;
                cm.setTorchMode(ids[0], torchOn);
                updateTorchUI();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to toggle torch", e);
        }
    }

    private void updateTorchUI() {
        if (torchOn) {
            btnTorch.setImageResource(R.drawable.ic_fluent_flashlight_24_filled);
            torchLabel.setText("关闭手电筒");
            torchLabel.setTextColor(0xFFFFD600);
        } else {
            btnTorch.setImageResource(R.drawable.ic_fluent_flashlight_off_24_filled);
            torchLabel.setText("打开手电筒");
            torchLabel.setTextColor(0xFFFFFFFF);
        }
    }

    private void setTorchVisible(boolean visible) {
        if (torchVisible == visible) return;
        torchVisible = visible;
        torchContainer.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_LIGHT) return;
        float lux = event.values[0];
        if (torchOn) {
            setTorchVisible(true);
        } else if (lux < DARK_THRESHOLD) {
            setTorchVisible(true);
        } else if (lux > BRIGHT_THRESHOLD) {
            setTorchVisible(false);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void startScanning() {
        try {
            barcodeView.setStatusText("");
            barcodeView.decodeContinuous(new BarcodeCallback() {
                @Override
                public void barcodeResult(BarcodeResult result) {
                    if (result != null && result.getText() != null) {
                        Intent data = new Intent();
                        data.putExtra("barcode_result", result.getText());
                        setResult(RESULT_OK, data);
                        finish();
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to start scanning", e);
            Toast.makeText(this, "扫码启动失败", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (barcodeView != null && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try { barcodeView.resume(); } catch (Exception e) { Log.e(TAG, "Resume failed", e); }
        }
        if (lightSensor != null) {
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
        if (barcodeView != null) {
            try { barcodeView.pause(); } catch (Exception e) { Log.e(TAG, "Pause failed", e); }
        }
        if (torchOn) {
            try {
                CameraManager cm = getSystemService(CameraManager.class);
                String[] ids = cm.getCameraIdList();
                if (ids.length > 0) cm.setTorchMode(ids[0], false);
            } catch (Exception ignored) {}
            torchOn = false;
        }
    }

    private void openQrImagePicker(){
        MediaPickerConfig config=new MediaPickerConfig();
        config.allowImages=true;
        config.allowVideos=false;
        config.maxCount=1;
        ArrayList<String> missing=new ArrayList<>();
        if(Build.VERSION.SDK_INT>=33){
            if(checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)!=PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.READ_MEDIA_IMAGES);
        }else if(!new MediaStoreLoader(this).hasPermission(config)){
            missing.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.CAMERA);
        if(!missing.isEmpty()){
            pendingMediaPickerConfig=config;
            requestPermissions(missing.toArray(new String[0]), MEDIA_PERMISSION_REQUEST);
            return;
        }
        showQrImagePicker(config);
    }

    private void showQrImagePicker(MediaPickerConfig config){
        new MediaPickerSheet(this, config, new MediaPickerSheet.Listener(){
            @Override public void onMediaSelected(ArrayList<Uri> uris){
                if(!uris.isEmpty()) decodeQrImage(uris.get(0));
            }
            @Override public void onCameraRequested(){ openCameraForQr(); }
        }).show();
    }

    private void openCameraForQr(){
        startActivityForResult(MediaCameraContract.createIntent(this, false), IMAGE_SCAN_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(requestCode==MEDIA_PERMISSION_REQUEST){
            MediaPickerConfig config=pendingMediaPickerConfig;
            pendingMediaPickerConfig=null;
            if(config!=null && new MediaStoreLoader(this).hasPermission(config))
                showQrImagePicker(config);
        }else if(requestCode == CAMERA_PERMISSION_REQUEST && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED){
            startScanning();
        } else if(requestCode == CAMERA_PERMISSION_REQUEST){
            Toast.makeText(this, "需要相机权限才能扫码", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data){
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode==IMAGE_SCAN_REQUEST && resultCode==RESULT_OK && MediaCameraContract.getUri(data)!=null)
            decodeQrImage(MediaCameraContract.getUri(data));
    }

    private void decodeQrImage(Uri uri){
        new org.joinmastodon.android.fragments.ProfileQrCodeFragment.QrImageDecoder(this, uri, decoded->runOnUiThread(()->{
            if(decoded==null){
                Toast.makeText(this, R.string.qr_code_not_found, Toast.LENGTH_SHORT).show();
                return;
            }
            Intent result=new Intent();
            result.putExtra("barcode_result", decoded);
            setResult(RESULT_OK, result);
            finish();
        })).decode();
    }
}
