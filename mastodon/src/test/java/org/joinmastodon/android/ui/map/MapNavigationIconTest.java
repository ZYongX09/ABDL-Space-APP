package org.joinmastodon.android.ui.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk=35, application=CompatibilityTestApplication.class)
public class MapNavigationIconTest{
	@Test public void mapIconHasVisiblePixelsOnBothNavigationThemes(){
		Context context=RuntimeEnvironment.getApplication();
		for(int theme:new int[]{R.style.Theme_Mastodon_Light,R.style.Theme_Mastodon_Dark}){
			context.setTheme(theme);
			Drawable icon=context.getDrawable(R.drawable.ic_tab_map).mutate();
			int tint=theme==R.style.Theme_Mastodon_Dark ? Color.WHITE : Color.BLACK;
			icon.setTint(tint);
			Bitmap bitmap=Bitmap.createBitmap(72,72,Bitmap.Config.ARGB_8888);
			icon.setBounds(0,0,72,72);
			icon.draw(new Canvas(bitmap));
			int visible=0;
			for(int y=0;y<72;y++) for(int x=0;x<72;x++){
				int pixel=bitmap.getPixel(x,y);
				if(Color.alpha(pixel)>128){
					visible++;
					assertEquals(tint&0x00ffffff,pixel&0x00ffffff);
				}
			}
			assertTrue("map icon must not be transparent",visible>100);
			assertTrue("map icon must retain open map folds",visible<72*72/2);
			bitmap.recycle();
		}
	}
}
