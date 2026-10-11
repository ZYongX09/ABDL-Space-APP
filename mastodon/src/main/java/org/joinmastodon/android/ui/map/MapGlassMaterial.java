package org.joinmastodon.android.ui.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.widget.FrameLayout;

import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.R;
import org.joinmastodon.android.sponsors.SponsorUi;
import org.joinmastodon.android.ui.compose.navigation.MapGlassSurface;
import org.joinmastodon.android.ui.utils.UiUtils;

import me.grishka.appkit.utils.V;

/** Shader content is hardware-only; software backdrop capture receives the ordinary material. */
final class MapGlassMaterial{
	private final GlassFrame view;
	private final int radius;
	private final boolean large;
	private MapGlassSurface liquid;

	MapGlassMaterial(Context context,int radius,boolean large){
		this.radius=radius; this.large=large;
		int surface=UiUtils.getThemeColor(context,R.attr.colorM3Surface);
		int outline=UiUtils.getThemeColor(context,R.attr.colorM3OutlineVariant);
		GradientDrawable fallback=SponsorUi.rounded((surface&0x00ffffff)|0xf5000000,radius);
		fallback.setStroke(Math.max(1,V.dp(.75f)),(outline&0x00ffffff)|0xa6000000);
		view=new GlassFrame(context,fallback);
	}

	View getView(){ return view; }

	void update(Bitmap bitmap,int left,int top){
		boolean supported=MapGlassPolicy.supportsLiquid(Build.VERSION.SDK_INT,view.isHardwareAccelerated(),
				GlobalUserPreferences.isIosLiquidNavigationEnabled(),bitmap!=null && !bitmap.isRecycled());
		if(supported){
			if(liquid==null){ liquid=new MapGlassSurface(view.getContext(),radius,large); view.addView(liquid.getView(),new FrameLayout.LayoutParams(-1,-1)); }
			view.setLiquidVisible(true);
			liquid.getView().setVisibility(View.VISIBLE); liquid.update(bitmap,left,top);
		}else{
			view.setLiquidVisible(false);
			if(liquid!=null){ liquid.update(null,left,top); liquid.getView().setVisibility(View.GONE); }
		}
		view.invalidate();
	}

	void dispose(){
		if(liquid!=null){ liquid.dispose(); view.removeAllViews(); liquid=null; }
		view.liquidVisible=false;
	}

	private static final class GlassFrame extends FrameLayout{
		private final GradientDrawable fallback;
		boolean liquidVisible;
		GlassFrame(Context context,GradientDrawable fallback){
			super(context); this.fallback=fallback;
			setWillNotDraw(false);
			setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		}
		void setLiquidVisible(boolean visible){ liquidVisible=visible; invalidate(); }
		@Override protected void onDraw(Canvas canvas){
			super.onDraw(canvas);
			if(!canvas.isHardwareAccelerated() || !liquidVisible){
				fallback.setBounds(0,0,getWidth(),getHeight()); fallback.draw(canvas);
			}
		}
		@Override protected void dispatchDraw(Canvas canvas){
			// ViewGroup can bypass public draw() when recording a nested child.
			if(canvas.isHardwareAccelerated() && liquidVisible) super.dispatchDraw(canvas);
		}
	}
}
