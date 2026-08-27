package org.joinmastodon.android.ui.text;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.style.ReplacementSpan;

import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.model.Emoji;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import me.grishka.appkit.utils.V;

public class CustomEmojiSpan extends ReplacementSpan{
	public final Emoji emoji;
	// MOSHIDON: we changed this to protected cuz AvatarSpan uses it :D
	protected Drawable drawable;
	// Cached software copy of the last hardware bitmap drawn into a software canvas.
	// Hardware bitmaps cannot be drawn by software-rendered canvases (e.g. backdrop capture,
	// screenshot, or text drawn into a software layer) — see IllegalArgumentException
	// "Software rendering doesn't support hardware bitmaps". Cache one entry per span to avoid
	// re-copying every frame during scroll without unbounded memory growth.
	private Bitmap cachedSoftwareBitmap;
	private Bitmap cachedSoftwareBitmapSource;

	public CustomEmojiSpan(Emoji emoji){
		this.emoji=emoji;
	}

	@Override
	public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, @Nullable Paint.FontMetricsInt fm){
		return Math.round(paint.descent()-paint.ascent());
	}

	@Override
	public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, @NonNull Paint paint){
		int size=Math.round(paint.descent()-paint.ascent());
		if(drawable==null){
			int alpha=paint.getAlpha();
			paint.setAlpha(alpha >> 1);
			canvas.drawRoundRect(x, top, x+size, top+size, V.dp(2), V.dp(2), paint);
			paint.setAlpha(alpha);
		}else{
			// AnimatedImageDrawable doesn't like when its bounds don't start at (0, 0)
			Rect bounds=drawable.getBounds();
			int dw=drawable.getIntrinsicWidth();
			int dh=drawable.getIntrinsicHeight();
			if(bounds.left!=0 || bounds.top!=0 || bounds.right!=dw || bounds.left!=dh){
				drawable.setBounds(0, 0, dw, dh);
			}
			canvas.save();
			canvas.translate(x, top);
			canvas.scale(size/(float)dw, size/(float)dh, 0f, 0f);
			drawableForCanvas(canvas).draw(canvas);
			canvas.restore();
		}
	}

	public void setDrawable(Drawable drawable){
		this.drawable=drawable;
		if(cachedSoftwareBitmapSource!=null && cachedSoftwareBitmapSource!=sourceBitmapOf(drawable)){
			cachedSoftwareBitmap=null;
			cachedSoftwareBitmapSource=null;
		}
	}

	public UrlImageLoaderRequest createImageLoaderRequest(){
		int size=V.dp(20);
		return new UrlImageLoaderRequest(GlobalUserPreferences.playGifs ? emoji.url : emoji.staticUrl, size, size);
	}

	/**
	 * Returns either the original drawable or a software-bitmap copy, depending on whether the
	 * canvas can accept hardware bitmaps. Mirrors the proven pattern from
	 * {@code BackdropCaptureFrameLayout#getSoftwareDrawable}.
	 */
	protected Drawable drawableForCanvas(Canvas canvas){
		Drawable d=drawable;
		if(canvas.isHardwareAccelerated())
			return d;
		if(!(d instanceof BitmapDrawable bitmapDrawable))
			return d;
		Bitmap bitmap=bitmapDrawable.getBitmap();
		if(bitmap==null || bitmap.getConfig()!=Bitmap.Config.HARDWARE)
			return d;
		if(cachedSoftwareBitmap==null || cachedSoftwareBitmap.isRecycled() || cachedSoftwareBitmapSource!=bitmap){
			cachedSoftwareBitmap=bitmap.copy(Bitmap.Config.ARGB_8888, false);
			cachedSoftwareBitmapSource=bitmap;
		}
		// Reuse the original bounds (the caller has already set them to 0,0,dw,dh).
		BitmapDrawable copy=new BitmapDrawable(MastodonApp.context.getResources(), cachedSoftwareBitmap);
		copy.setBounds(bitmapDrawable.getBounds());
		return copy;
	}

	private static Bitmap sourceBitmapOf(Drawable drawable){
		return (drawable instanceof BitmapDrawable bd) ? bd.getBitmap() : null;
	}
}

