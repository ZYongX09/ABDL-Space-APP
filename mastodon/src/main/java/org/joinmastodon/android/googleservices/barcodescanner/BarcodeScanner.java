package org.joinmastodon.android.googleservices.barcodescanner;

import android.content.Context;
import android.content.Intent;

import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.ui.MLKitBarcodeScannerActivity;

public class BarcodeScanner{

	public static Intent createIntent(int formats, boolean allowManualInput, boolean enableAutoZoom){
		return new Intent(MastodonApp.context, MLKitBarcodeScannerActivity.class);
	}

	public static boolean isValidResult(Intent intent){
		return intent != null && intent.hasExtra("barcode_result");
	}

	public static Barcode getResult(Intent intent){
		String rawValue = intent.getStringExtra("barcode_result");
		Barcode barcode = new Barcode();
		barcode.rawValue = rawValue;
		barcode.displayValue = rawValue;
		barcode.format = Barcode.FORMAT_QR_CODE;
		barcode.valueType = Barcode.TYPE_TEXT;
		return barcode;
	}

	public static void installScannerModule(Context context, Runnable onSuccess){
		onSuccess.run();
	}
}
