package org.joinmastodon.android.ui.drawables;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import androidx.core.content.res.ResourcesCompat;
import org.joinmastodon.android.R;
import me.grishka.appkit.utils.V;

/** Local, metadata-only book jacket; never starts an image request or impersonates uploaded art. */
public final class NovelCoverDrawable extends Drawable{
	// The same paper/ink pairs as the novel library's typographic covers, not application UI colors.
	private static final int[][] PAPERS={{0xFFA64D34,0xFFFFF4DF},{0xFFDADDCA,0xFF384D36},{0xFF2D4854,0xFFEADFC4},{0xFFD8BFA0,0xFF543F31},{0xFF697460,0xFFF6F0E0}};
	private final Paint paper=new Paint(Paint.ANTI_ALIAS_FLAG);
	private final TextPaint titlePaint=new TextPaint(Paint.ANTI_ALIAS_FLAG),authorPaint=new TextPaint(Paint.ANTI_ALIAS_FLAG);
	private final StaticLayout title;
	private final int titleHeight;
	private final CharSequence author;

	public NovelCoverDrawable(Context context,String workID,String name,String authorName){
		int[] colors=PAPERS[(int)(Math.abs((long)(workID==null ? 0 : workID.hashCode()))%PAPERS.length)];
		paper.setColor(colors[0]);titlePaint.setColor(colors[1]);authorPaint.setColor(colors[1]);
		titlePaint.setTypeface(ResourcesCompat.getFont(context,R.font.noto_serif_sc_semibold));titlePaint.setTextSize(172);
		authorPaint.setTextSize(80);
		String label=TextUtils.isEmpty(name) ? context.getString(R.string.novel_card_cover_placeholder) : name;
		// Font line metrics can be much taller than textSize: reserve the author band before ellipsizing.
		titleHeight=Math.max(1,Math.min(620,(int)(900+authorPaint.getFontMetrics().top-100-72)));
		StaticLayout layout=null;
		for(int lines=3;lines>=1;lines--){
			layout=StaticLayout.Builder.obtain(label,0,label.length(),titlePaint,560).setAlignment(Layout.Alignment.ALIGN_NORMAL)
					.setIncludePad(false).setLineSpacing(12,1).setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build();
			if(layout.getHeight()<=titleHeight)break;
		}
		title=layout;
		author=TextUtils.ellipsize(authorName==null ? "" : authorName,authorPaint,560,TextUtils.TruncateAt.END);
	}
	@Override public void draw(Canvas canvas){
		Rect bounds=getBounds();int save=canvas.save();canvas.translate(bounds.left,bounds.top);canvas.scale(bounds.width()/720f,bounds.height()/1000f);
		canvas.drawRoundRect(0,0,720,1000,28,28,paper);canvas.save();canvas.translate(80,100);canvas.clipRect(0,0,560,titleHeight);title.draw(canvas);canvas.restore();
		canvas.drawText(author,0,author.length(),80,900,authorPaint);canvas.restoreToCount(save);
	}
	@Override public int getIntrinsicWidth(){return V.dp(72);}
	@Override public int getIntrinsicHeight(){return V.dp(100);}
	@Override public void setAlpha(int alpha){paper.setAlpha(alpha);titlePaint.setAlpha(alpha);authorPaint.setAlpha(alpha);invalidateSelf();}
	@Override public void setColorFilter(ColorFilter filter){paper.setColorFilter(filter);titlePaint.setColorFilter(filter);authorPaint.setColorFilter(filter);invalidateSelf();}
	@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
