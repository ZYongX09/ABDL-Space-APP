package org.joinmastodon.android.ui.verification;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import org.joinmastodon.android.R;
import org.joinmastodon.android.model.verification.VerificationModels.Certificate;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Locale;

public final class BabyCertificateRenderer{
	public static final int WIDTH=763;
	public static final int HEIGHT=1080;
	private static final int BLUE=Color.rgb(46, 95, 134);
	private static final int LIGHT_BLUE=Color.rgb(138, 169, 190);
	private static final int TEXT=Color.rgb(51, 51, 51);
	private static final int MUTED=Color.rgb(107, 120, 128);
	private static final int WATERMARK=Color.rgb(230, 237, 242);
	private BabyCertificateRenderer(){}

	public static Bitmap render(Certificate certificate, String[] notes) throws Exception{
		if(certificate==null) throw new IllegalArgumentException("证书为空");
		String url=certificate.publicUrl();
		Bitmap qr=VerificationQrCode.render(url, 154);
		Bitmap bitmap=Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
		Canvas canvas=new Canvas(bitmap);
		canvas.drawColor(Color.WHITE);
		Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
		paint.setSubpixelText(true);
		drawBorders(canvas, paint);
		drawWatermarks(canvas, paint);
		paint.setTextAlign(Paint.Align.CENTER);
		paint.setColor(Color.BLACK);
		paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
		paint.setTextSize(38);
		canvas.drawText("宝宝认证证书", WIDTH/2f, 128, paint);
		paint.setColor(Color.rgb(132,167,196));
		paint.setTextSize(14);
		canvas.drawText("ABDL Space 认证凭证", WIDTH/2f, 163, paint);

		paint.setTextAlign(Paint.Align.LEFT);
		paint.setColor(TEXT);
		paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
		paint.setTextSize(15);
		String issued=formatIssued(certificate.issuedAt);
		drawField(canvas, paint, 103, 251, "证书 ID：", certificate.id, true);
		drawField(canvas, paint, 103, 288, "签发时间：", issued, false);
		drawField(canvas, paint, 103, 325, "凭据代次：", Integer.toString(certificate.generation), false);
		drawField(canvas, paint, 103, 362, "认证状态：", "已通过 ABDL Space 宝宝认证", false);
		drawField(canvas, paint, 103, 399, "验真方式：", "扫描下方二维码在线验证", false);
		drawField(canvas, paint, 103, 436, "证书说明：", "证书状态以 ABDL Space 官方验真页面最新结果为准", false);
		drawField(canvas, paint, 103, 491, "认证记录：", "ABDL Space 宝宝认证审核记录", false);

		paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
		paint.setTextSize(15);
		canvas.drawText("说明", 103, 528, paint);
		paint.setTypeface(Typeface.create("serif", Typeface.NORMAL));
		paint.setTextSize(11);
		float y=559;
		for(String note:notes){
			for(String line:wrap(note, 570, paint)){
				canvas.drawText(line, 103, y, paint);
				y+=15;
			}
			y+=2;
		}
		paint.setColor(Color.rgb(203,215,223));
		paint.setStrokeWidth(1);
		canvas.drawLine(103, 772, 656, 772, paint);

		paint.setColor(TEXT);
		paint.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
		paint.setTextSize(10);
		canvas.drawText("在线验真地址：  "+url, 103, 792, paint);
		paint.setTextAlign(Paint.Align.CENTER);
		paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
		paint.setTextSize(13);
		canvas.drawText("请扫描二维码在线验真", WIDTH/2f, 824, paint);
		paint.setStyle(Paint.Style.FILL);
		paint.setColor(Color.WHITE);
		canvas.drawRect(288, 840, 468, 985, paint);
		paint.setStyle(Paint.Style.STROKE);
		paint.setStrokeWidth(1);
		paint.setColor(Color.rgb(170,187,199));
		canvas.drawRect(288, 840, 468, 985, paint);
		paint.setStyle(Paint.Style.FILL);
		canvas.drawBitmap(qr, 301, 850, paint);
		qr.recycle();
		paint.setTextAlign(Paint.Align.LEFT);
		paint.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
		paint.setTextSize(9);
		canvas.drawText("证书 ID：  "+certificate.id, 103, 997, paint);
		paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
		paint.setTextSize(10);
		paint.setColor(MUTED);
		canvas.drawText("请使用 ABDL Space 原生验真  ·  证书状态以在线结果为准", 103, 1020, paint);
		return bitmap;
	}

	private static void drawBorders(Canvas canvas, Paint paint){
		paint.setStyle(Paint.Style.STROKE);
		paint.setStrokeWidth(2);
		paint.setColor(BLUE);
		canvas.drawRect(16,14,746,1066,paint);
		paint.setStrokeWidth(1);
		paint.setColor(LIGHT_BLUE);
		canvas.drawRect(24,20,739,1060,paint);
		paint.setStrokeWidth(3);
		paint.setColor(BLUE);
		canvas.drawRect(54,43,709,1037,paint);
		paint.setStrokeWidth(1);
		paint.setColor(LIGHT_BLUE);
		canvas.drawRect(64,51,699,1029,paint);
		paint.setStyle(Paint.Style.FILL);
	}

	private static void drawWatermarks(Canvas canvas, Paint paint){
		canvas.save();
		canvas.rotate(-16, 300, 260);
		paint.setColor(WATERMARK);
		paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
		paint.setTextSize(12);
		canvas.drawText("ABDL Space   宝宝认证   ABDL Space   宝宝认证",108,260,paint);
		canvas.restore();
		canvas.save();
		canvas.rotate(-16, 300, 690);
		paint.setTextSize(13);
		canvas.drawText("ABDL Space   AUTHENTIC CREDENTIAL   ABDL Space",88,690,paint);
		canvas.restore();
	}

	private static void drawField(Canvas canvas, Paint paint, float x, float y, String label, String value, boolean mono){
		paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
		paint.setColor(TEXT);
		paint.setTextSize(15);
		canvas.drawText(label, x, y, paint);
		paint.setTypeface(Typeface.create(mono ? "monospace" : "sans-serif", Typeface.NORMAL));
		canvas.drawText(value==null ? "" : value, 236, y, paint);
	}

	private static String formatIssued(long seconds){
		if(seconds<=0) return "—";
		DateTimeFormatter formatter=new DateTimeFormatterBuilder().appendValue(ChronoField.YEAR,4).appendLiteral('年')
				.appendValue(ChronoField.MONTH_OF_YEAR).appendLiteral('月').appendValue(ChronoField.DAY_OF_MONTH).appendLiteral("日 ")
				.appendPattern("HH:mm:ss").toFormatter(Locale.CHINA).withZone(ZoneId.systemDefault());
		return formatter.format(Instant.ofEpochSecond(seconds));
	}

	private static String[] wrap(String value, int width, Paint paint){
		if(value==null || value.isBlank()) return new String[]{""};
		int max=Math.max(18, (int)(width/paint.measureText("字")));
		java.util.ArrayList<String> lines=new java.util.ArrayList<>();
		StringBuilder line=new StringBuilder();
		for(int i=0;i<value.length();i++){
			line.append(value.charAt(i));
			if(line.length()>=max){ lines.add(line.toString()); line.setLength(0); }
		}
		if(line.length()>0) lines.add(line.toString());
		return lines.toArray(new String[0]);
	}
}
