package org.joinmastodon.android.ui.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.widget.FrameLayout;

import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.compose.navigation.MapGlassSurface;
import org.joinmastodon.android.ui.utils.UiUtils;

import me.grishka.appkit.utils.V;

/**
 * The liquid surface uses RuntimeShader, so it must never be recorded on a software canvas
 * (BackdropCaptureFrameLayout re-draws this subtree into a bitmap for the navigation backdrop).
 * GlassFrame branches on the per-canvas capability; this is a capability check, not an
 * exception fuse — genuine draw errors still propagate on the hardware path.
 */
final class MapGlassMaterial{
	private final GlassFrame view;
	private final int radius;
	private final boolean large;
	private final int scrimColor;
	private MapGlassSurface liquid;

	MapGlassMaterial(Context context,int radius,boolean large){
		this.radius=radius;
		this.large=large;
		int surface=UiUtils.getThemeColor(context,R.attr.colorM3Surface);
		int outline=UiUtils.getThemeColor(context,R.attr.colorM3OutlineVariant);
		GradientDrawable fallback=org.joinmastodon.android.sponsors.SponsorUi.rounded((surface&0x00ffffff)|0xf5000000,radius);
		fallback.setStroke(Math.max(1,V.dp(.75f)),(outline&0x00ffffff)|0xa6000000);
		scrimColor=(surface&0x00ffffff)|0x8c000000;
		view=new GlassFrame(context);
		view.setBackground(fallback);
	}

	View getView(){ return view; }

	void update(Bitmap bitmap,int left,int top){
		boolean supported=MapGlassPolicy.supportsLiquid(Build.VERSION.SDK_INT,view.isHardwareAccelerated(),
				GlobalUserPreferences.isIosLiquidNavigationEnabled(),bitmap!=null && !bitmap.isRecycled());
		if(supported){
			if(liquid==null){
				liquid=new MapGlassSurface(view.getContext(),radius,large);
				view.addView(liquid.getView(),new FrameLayout.LayoutParams(-1,-1));
			}
			view.fallbackVisible=false;
			liquid.getView().setVisibility(View.VISIBLE);
			liquid.update(darken(bitmap),left,top);
		}else{
			view.fallbackVisible=true;
			if(liquid!=null){
				liquid.update(null,left,top);
				liquid.getView().setVisibility(View.GONE);
			}
		}
	}

	void dispose(){
		if(liquid!=null){
			liquid.dispose();
			view.removeAllViews();
			liquid=null;
		}
	}

	/** Downscale for blur cost and darken so dark-theme text stays readable over light map tiles. */
	private Bitmap darken(Bitmap source){
		int targetWidth=Math.max(1,source.getWidth()/4);
		int targetHeight=Math.max(1,source.getHeight()/4);
		Bitmap scaled=Bitmap.createScaledBitmap(source,targetWidth,targetHeight,true);
		Bitmap out=Bitmap.createBitmap(targetWidth,targetHeight,Bitmap.Config.ARGB_8888);
		Canvas canvas=new Canvas(out);
		canvas.drawBitmap(scaled,0,0,null);
		canvas.drawColor(scrimColor);
		return out;
	}

	private static final class GlassFrame extends FrameLayout{
		boolean fallbackVisible=true;

		GlassFrame(Context context){ super(context); }

		@Override public void draw(Canvas canvas){
			if(!canvas.isHardwareAccelerated()){
				// Liquid path would use RuntimeShader. On a software canvas (backdrop capture)
				// liquid mode draws nothing so captures stay clean; fallback draws its solid shape.
				if(fallbackVisible){
					Drawable background=getBackground();
					if(background!=null) background.draw(canvas);
				}
				return;
			}
			super.draw(canvas);
		}
	}
}
