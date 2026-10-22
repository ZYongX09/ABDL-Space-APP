package org.joinmastodon.android.ui.text;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.joinmastodon.android.R;
import org.joinmastodon.android.model.Account;

/** The login screen's full-colour NBW logo, sized in ems rather than fixed dp. */
public final class NBWSourceSpan extends ReplacementSpan{
	private final Drawable drawable;

	public NBWSourceSpan(Context context){
		drawable=context.getDrawable(R.drawable.ic_nbw).mutate();
		// This is a source logo, not a monochrome text/action icon.
		drawable.setTintList(null);
		drawable.clearColorFilter();
	}

	public static void appendTo(SpannableStringBuilder name, Account author, Context context){
		if(author==null || !author.isNBWAccount()) return;
		name.append('\u00A0');
		int start=name.length();
		name.append('\uFFFC');
		name.setSpan(new NBWSourceSpan(context), start, name.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
	}

	public Drawable getDrawable(){
		return drawable;
	}

	private int updateSize(Paint paint){
		int size=Math.max(1, Math.round(paint.getTextSize()));
		drawable.setBounds(0, 0, size, size);
		return size;
	}

	@Override
	public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, @Nullable Paint.FontMetricsInt fm){
		// Keep the surrounding name's line metrics unchanged.
		return updateSize(paint);
	}

	@Override
	public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x,
			int top, int y, int bottom, @NonNull Paint paint){
		int size=updateSize(paint);
		float center=y+(paint.ascent()+paint.descent())/2f;
		int save=canvas.save();
		canvas.translate(x, center-size/2f);
		drawable.draw(canvas);
		canvas.restoreToCount(save);
	}
}
