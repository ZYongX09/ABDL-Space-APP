package org.joinmastodon.android.ui.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.widget.FrameLayout;

import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.compose.navigation.MapGlassSurface;
import org.joinmastodon.android.ui.utils.UiUtils;

import me.grishka.appkit.utils.V;

/** The low-API and software paths do not construct Compose backdrop or shader objects. */
final class MapGlassMaterial{
	private final FrameLayout view;
	private final int radius;
	private final boolean large;
	private MapGlassSurface liquid;

	MapGlassMaterial(Context context,int radius,boolean large){
		this.radius=radius; this.large=large; view=new FrameLayout(context);
		view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		GradientDrawable base=new GradientDrawable();
		int surface=UiUtils.getThemeColor(context,R.attr.colorM3Surface);
		int outline=UiUtils.getThemeColor(context,R.attr.colorM3OutlineVariant);
		base.setColor((surface&0x00ffffff)|0xf5000000); base.setCornerRadius(V.dp(radius));
		base.setStroke(Math.max(1,V.dp(.75f)),(outline&0x00ffffff)|0xa6000000);
		view.setBackground(base); view.setElevation(V.dp(large?10:5));
	}
	View getView(){ return view; }
	void update(Bitmap bitmap,int left,int top){
		boolean supported=MapGlassPolicy.supportsLiquid(Build.VERSION.SDK_INT,view.isHardwareAccelerated(),
				GlobalUserPreferences.isIosLiquidNavigationEnabled(),bitmap!=null && !bitmap.isRecycled());
		if(supported){
			if(liquid==null){ liquid=new MapGlassSurface(view.getContext(),radius,large); view.addView(liquid.getView(),new FrameLayout.LayoutParams(-1,-1)); }
			view.getBackground().setAlpha(0); liquid.getView().setVisibility(View.VISIBLE); liquid.update(bitmap,left,top);
		}else{
			view.getBackground().setAlpha(255);
			if(liquid!=null){ liquid.update(null,left,top); liquid.getView().setVisibility(View.GONE); }
		}
	}
	void dispose(){ if(liquid!=null){ liquid.dispose(); view.removeAllViews(); liquid=null; } }
}
