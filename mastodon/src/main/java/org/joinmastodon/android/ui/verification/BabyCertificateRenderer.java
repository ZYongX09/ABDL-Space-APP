package org.joinmastodon.android.ui.verification;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import org.joinmastodon.android.model.verification.VerificationModels.Certificate;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Locale;

/** Portrait credential card matching the 2026-11 design (light and dark variants). */
public final class BabyCertificateRenderer{
	public static final int WIDTH=720;
	public static final int HEIGHT=1020;

	private static final int LIGHT_BG=Color.rgb(248,246,252);
	private static final int LIGHT_BORDER=Color.rgb(195,183,215);
	private static final int LIGHT_TITLE=Color.rgb(43,36,64);
	private static final int LIGHT_BODY=Color.rgb(75,65,101);
	private static final int LIGHT_MUTED=Color.rgb(109,98,128);

	private static final int DARK_BG=Color.rgb(26,23,35);
	private static final int DARK_BORDER=Color.rgb(74,63,99);
	private static final int DARK_TITLE=Color.rgb(236,230,248);
	private static final int DARK_BODY=Color.rgb(168,158,192);
	private static final int DARK_MUTED=Color.rgb(168,158,192);

	private BabyCertificateRenderer(){}

	/** Kept for callers that do not pick a palette; the saved image uses the light design. */
	public static Bitmap render(Certificate certificate, String[] ignoredNotes) throws Exception{
		return render(certificate, false);
	}

	public static Bitmap render(Certificate certificate, boolean dark) throws Exception{
		if(certificate==null) throw new IllegalArgumentException("证书为空");
		String url=certificate.publicUrl();
		Bitmap qr=VerificationQrCode.render(url, 236);
		Bitmap bitmap=Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
		Canvas canvas=new Canvas(bitmap);
		int bg=dark ? DARK_BG : LIGHT_BG, border=dark ? DARK_BORDER : LIGHT_BORDER;
		int title=dark ? DARK_TITLE : LIGHT_TITLE, body=dark ? DARK_BODY : LIGHT_BODY, muted=dark ? DARK_MUTED : LIGHT_MUTED;
		canvas.drawColor(bg);
		Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
		paint.setSubpixelText(true);
		paint.setStyle(Paint.Style.STROKE);
		paint.setStrokeWidth(3);
		paint.setColor(border);
		RectF frame=new RectF(2,2,WIDTH-2,HEIGHT-2);
		canvas.drawRoundRect(frame, 36, 36, paint);
		paint.setStyle(Paint.Style.FILL);

		paint.setTextAlign(Paint.Align.CENTER);
		paint.setColor(title);
		paint.setTypeface(Typeface.create("serif", Typeface.NORMAL));
		paint.setTextSize(58);
		canvas.drawText("ABDL Space", WIDTH/2f, 152, paint);
		paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
		paint.setTextSize(31);
		paint.setColor(body);
		canvas.drawText("宝宝认证证书", WIDTH/2f, 222, paint);

		drawFieldCentered(canvas, paint, 21, 392, "证书 ID", certificate.id, muted, body);
		drawFieldCentered(canvas, paint, 21, 446, "签发时间", formatIssued(certificate.issuedAt), muted, body);
		drawFieldCentered(canvas, paint, 21, 500, "凭据代次", Integer.toString(certificate.generation), muted, body);

		canvas.drawBitmap(qr, (WIDTH-236)/2f, 590, paint);
		qr.recycle();

		paint.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
		paint.setTextSize(15);
		paint.setColor(muted);
		canvas.drawText("abdl-space.top/c/"+certificate.verificationToken, WIDTH/2f, 878, paint);
		paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
		paint.setTextSize(17);
		canvas.drawText("请使用 ABDL Space 原生验真", WIDTH/2f, 922, paint);
		return bitmap;
	}

	private static void drawFieldCentered(Canvas canvas, Paint paint, float size, float y, String label, String value, int labelColor, int valueColor){
		paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
		paint.setTextSize(size);
		float gap=size*0.6f;
		String text=value==null ? "" : value;
		float labelWidth=paint.measureText(label), valueWidth=paint.measureText(text);
		float left=(WIDTH-(labelWidth+gap+valueWidth))/2f;
		paint.setTextAlign(Paint.Align.LEFT);
		paint.setColor(labelColor);
		canvas.drawText(label, left, y, paint);
		paint.setColor(valueColor);
		canvas.drawText(text, left+labelWidth+gap, y, paint);
		paint.setTextAlign(Paint.Align.CENTER);
	}

	private static String formatIssued(long seconds){
		if(seconds<=0) return "—";
		DateTimeFormatter formatter=new DateTimeFormatterBuilder().appendValue(ChronoField.YEAR,4).appendLiteral('年')
				.appendValue(ChronoField.MONTH_OF_YEAR).appendLiteral('月').appendValue(ChronoField.DAY_OF_MONTH).appendLiteral("日 ")
				.appendPattern("HH:mm:ss").toFormatter(Locale.CHINA).withZone(ZoneId.systemDefault());
		return formatter.format(Instant.ofEpochSecond(seconds));
	}
}
