package org.joinmastodon.android.ui.verification;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

public final class VerificationQrCode{
	private VerificationQrCode(){}

	public static Bitmap render(String value, int size) throws Exception{
		if(value==null || value.isBlank() || size<64) throw new IllegalArgumentException("二维码内容无效");
		Map<EncodeHintType, Object> hints=new EnumMap<>(EncodeHintType.class);
		hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
		hints.put(EncodeHintType.MARGIN, 2);
		BitMatrix matrix=new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, size, size, hints);
		Bitmap bitmap=Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
		for(int y=0;y<size;y++) for(int x=0;x<size;x++) bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
		return bitmap;
	}
}
