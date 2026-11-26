package org.joinmastodon.android.startup;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.LinearInterpolator;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, application=CompatibilityTestApplication.class, qualifiers="w360dp-h740dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class StartupLogoViewTest{
	@Test public void firstFallbackFrameMatchesTheSampledPhaseWithoutGrowingPastTheOldArtwork(){
		boolean reduce=GlobalUserPreferences.reduceMotion;
		GlobalUserPreferences.reduceMotion=false;
		try{
			var context=RuntimeEnvironment.getApplication();
			ShadowLooper.idleMainLooper(2400,TimeUnit.MILLISECONDS);
			long epoch=SystemClock.uptimeMillis();
			StartupLogoView full=new StartupLogoView(context,epoch);
			StartupLogoView small=new StartupLogoView(context,epoch-1200);
			Bitmap image=Bitmap.createBitmap(360,740,Bitmap.Config.ARGB_8888);
			try{
				int bg=context.getColor(R.color.splash_bg);
				Rect maximum=inkBounds(full,image,bg);
				assertTrue(maximum.toString(),maximum.width()>95 && maximum.height()>145);
				assertTrue(maximum.toString(),maximum.width()<=134 && maximum.height()<=175);
				Rect minimum=inkBounds(small,image,bg);
				assertTrue(minimum.width()<maximum.width() && minimum.height()<maximum.height());
				assertEquals(maximum.height()*.88f,minimum.height(),2f);
				assertEquals(maximum,inkBounds(new StartupLogoView(context,epoch-2400),image,bg));
			}finally{ image.recycle(); }
		}finally{ GlobalUserPreferences.reduceMotion=reduce; }
	}

	@Test public void pulseNeverExceedsItsOriginalScale(){
		var linear=new LinearInterpolator();
		assertEquals(1f,StartupLogoView.scaleAt(0,linear),0);
		assertEquals(.88f,StartupLogoView.scaleAt(1200,linear),.001f);
		assertEquals(1f,StartupLogoView.scaleAt(2400,linear),0);
		for(int t=0;t<10000;t+=31){
			float scale=StartupLogoView.scaleAt(t,linear);
			assertTrue(scale>=.879f && scale<=1f);
		}
	}

	@Test public void platformStartIsConvertedToUptimeOnlyForAValidSample(){
		assertEquals(5880,StartupLogoView.pulseEpoch(999_880,1_000_000,6000));
		assertEquals(0,StartupLogoView.pulseEpoch(0,1_000_000,6000));
		assertEquals(0,StartupLogoView.pulseEpoch(1_000_001,1_000_000,6000));
		assertEquals(0,StartupLogoView.pulseEpoch(100,1_000_000,6000));
	}

	private static Rect inkBounds(StartupLogoView logo, Bitmap image, int background){
		logo.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(740,View.MeasureSpec.EXACTLY));
		logo.layout(0,0,360,740);
		logo.draw(new Canvas(image));
		Rect bounds=new Rect();
		for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++){
			if(image.getPixel(x,y)!=background) bounds.union(x,y,x+1,y+1);
		}
		return bounds;
	}
}
