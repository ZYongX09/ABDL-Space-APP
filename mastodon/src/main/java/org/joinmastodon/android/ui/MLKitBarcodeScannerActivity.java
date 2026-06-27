package org.joinmastodon.android.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.camera.CameraInstance;
import com.journeyapps.barcodescanner.camera.CameraSettings;

import org.joinmastodon.android.R;

public class MLKitBarcodeScannerActivity extends Activity {
    private static final String TAG = "MLKitScanner";
    private static final int CAMERA_PERMISSION_REQUEST = 100;
    private DecoratedBarcodeView barcodeView;

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

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startScanning();
        } else {
            Toast.makeText(this, "需要相机权限才能扫码", Toast.LENGTH_SHORT).show();
            finish();
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

    @Override
    protected void onPause() {
        super.onPause();
        if (barcodeView != null) {
            try { barcodeView.pause(); } catch (Exception e) { Log.e(TAG, "Pause failed", e); }
        }
    }
}
