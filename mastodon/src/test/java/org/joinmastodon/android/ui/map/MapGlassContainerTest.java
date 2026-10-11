package org.joinmastodon.android.ui.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.widget.FrameLayout;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.R;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import me.grishka.appkit.utils.V;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={26,32,35}, application=CompatibilityTestApplication.class)
public class MapGlassContainerTest{
	private Context context;
	@Before public void setup(){
		context=RuntimeEnvironment.getApplication(); context.setTheme(R.style.Theme_Mastodon_Dark);
		V.setApplicationContext(context);
	}

	@Test public void decorativeFillCannotExpandWrapContentCard(){
		View greedyBackground=new View(context){
			@Override protected void onMeasure(int width,int height){
				setMeasuredDimension(MeasureSpec.getSize(width),MeasureSpec.getSize(height));
			}
		};
		MapGlassContainer card=new MapGlassContainer(context,greedyBackground);
		View content=new View(context);
		card.addView(content,new FrameLayout.LayoutParams(150,88));
		card.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(1400,View.MeasureSpec.AT_MOST));
		card.layout(0,0,card.getMeasuredWidth(),card.getMeasuredHeight());
		assertEquals(150,card.getMeasuredWidth()); assertEquals(88,card.getMeasuredHeight());
		assertEquals(150,greedyBackground.getMeasuredWidth()); assertEquals(88,greedyBackground.getMeasuredHeight());
		assertEquals(150,content.getMeasuredWidth()); assertEquals(88,content.getMeasuredHeight());
	}

	@Test public void materialDrawsBelowContentAndSoftwareCaptureNeverVisitsShaderChild(){
		MapGlassMaterial material=new MapGlassMaterial(context,24,false);
		FrameLayout background=(FrameLayout)material.getView();
		background.addView(new View(context){
			@Override public void draw(Canvas canvas){ throw new AssertionError("Software recording entered liquid/shader child"); }
		},new FrameLayout.LayoutParams(-1,-1));
		MapGlassContainer card=new MapGlassContainer(context,background);
		View label=new View(context); label.setBackgroundColor(Color.WHITE);
		card.addView(label,new FrameLayout.LayoutParams(40,20));
		card.measure(View.MeasureSpec.makeMeasureSpec(40,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(20,View.MeasureSpec.EXACTLY));
		card.layout(0,0,40,20);
		Bitmap output=Bitmap.createBitmap(40,20,Bitmap.Config.ARGB_8888);
		try{
			card.draw(new Canvas(output));
			assertEquals("Content stays above the material",Color.WHITE,output.getPixel(20,10));
			assertEquals(0f,background.getElevation(),0f);
			assertEquals(0f,label.getAlpha()-1f,0f);
		}finally{ output.recycle(); material.dispose(); }
	}
}
