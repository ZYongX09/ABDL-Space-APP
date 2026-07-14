package org.joinmastodon.android.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

import org.joinmastodon.android.ui.media.MediaPickerConfig;
import org.joinmastodon.android.ui.media.MediaCameraContract;
import org.joinmastodon.android.ui.media.MediaStoreLoader;
import org.joinmastodon.android.ui.sheets.MediaPickerSheet;

import java.util.ArrayList;

import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.camera.CameraInstance;
import com.journeyapps.barcodescanner.camera.CameraSettings;

import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.utils.UiUtils;

public class MLKitBarcodeScannerActivity extends Activity {
    private static final String TAG = "MLKitScanner";
    private static final int CAMERA_PERMISSION_REQUEST = 100;
    private static final int MEDIA_PERMISSION_REQUEST = 101;
    private static final int IMAGE_SCAN_REQUEST = 102;
    private DecoratedBarcodeView barcodeView;
    private MediaPickerConfig pendingMediaPickerConfig;

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

    @Override
    protected void onPause() {
        super.onPause();
        if (barcodeView != null) {
            try { barcodeView.pause(); } catch (Exception e) { Log.e(TAG, "Pause failed", e); }
        }
    }
}
