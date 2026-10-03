package org.joinmastodon.android.ui.text;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.joinmastodon.android.model.Account;

import me.grishka.appkit.utils.V;

/**
 * 圆角矩形徽章：用户名后展示的文字徽章（背景色由后台按徽章种类指定，
 * 文字颜色按背景亮度自动取黑/白）。用于帖子列表头部、详情页与个人中心。
 */
public class BadgeSpan extends ReplacementSpan{
	private final String text;
	private final int bgColor;
	private final float textSizePx;
	private final float hPadding;
	private final float cornerRadius;
	private final RectF rect=new RectF();
	private final Paint bgPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint textPaint=new Paint(Paint.ANTI_ALIAS_FLAG);

	public BadgeSpan(Account.Badge badge){
		this(badge, V.dp(11));
	}

	public BadgeSpan(Account.Badge badge, float textSizePx){
		this.text=badge.name==null ? "" : badge.name;
		this.textSizePx=textSizePx;
		this.bgColor=parseColor(badge.color);
		this.hPadding=textSizePx*0.45f;
		this.cornerRadius=textSizePx*0.32f;
		textPaint.setTextSize(textSizePx);
		textPaint.setColor(foregroundColorFor(bgColor));
		textPaint.setFakeBoldText(true);
		bgPaint.setColor(bgColor);
	}

	private static int parseColor(String color){
		if(color==null || color.length()!=7 || color.charAt(0)!='#')
			return 0xFF7C4DFF;
		try{
			return (int)(0xFF000000L | Long.parseLong(color.substring(1), 16));
		}catch(NumberFormatException e){
			return 0xFF7C4DFF;
		}
	}

	public static int foregroundColorFor(int bgColor){
		int r=(bgColor >> 16) & 0xFF, g=(bgColor >> 8) & 0xFF, b=bgColor & 0xFF;
		// 相对亮度（YIQ）：亮背景配黑字，暗背景配白字
		return (r*299+g*587+b*114)/1000 >= 160 ? 0xFF1B1B1B : 0xFFFFFFFF;
	}

	@Override
	public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, @Nullable Paint.FontMetricsInt fm){
		float width=textPaint.measureText(this.text)+hPadding*2;
		if(fm!=null){
			Paint.FontMetricsInt metrics=textPaint.getFontMetricsInt();
			fm.ascent=metrics.ascent;
			fm.descent=metrics.descent;
			fm.top=metrics.top;
			fm.bottom=metrics.bottom;
		}
		return Math.round(width);
	}

	@Override
	public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int baseline, int bottom, @NonNull Paint paint){
		Paint.FontMetricsInt metrics=textPaint.getFontMetricsInt();
		float vPad=textSizePx*0.28f;
		float rectTop=baseline+metrics.ascent-vPad;
		float rectBottom=baseline+metrics.descent+vPad;
		rect.set(x, rectTop, x+getSize(paint, text, start, end, null), rectBottom);
		canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint);
		float textBaseline=(rectTop+rectBottom)/2f-(metrics.ascent+metrics.descent)/2f;
		canvas.drawText(this.text, x+hPadding, textBaseline, textPaint);
	}
}
