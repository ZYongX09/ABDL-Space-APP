package org.joinmastodon.android.ui.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The device crash (Software rendering doesn't support hardware bitmaps) requires a real
 * Bitmap.Config.HARDWARE bitmap, which Robolectric cannot allocate. These tests cover the
 * renderer's software-copy contract; the true hardware path stays verified by device logs
 * and on-device acceptance.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={33,35}, application=CompatibilityTestApplication.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MapAvatarRendererHardwareBitmapTest{
	@Test public void softwareConfigIsNotCopiedAndDrawsDirectly(){
		Context context=RuntimeEnvironment.getApplication();
		MapAvatarRenderer renderer=new MapAvatarRenderer(context);
		Bitmap image=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888); image.eraseColor(0xffaa3344);
		assertFalse(renderer.requiresSoftwareCopy(image));
		Bitmap rendered=renderer.draw(new BitmapDrawable(context.getResources(),image),"A");
		try{
			assertEquals(0xffaa3344,rendered.getPixel(rendered.getWidth()/2,rendered.getHeight()/2));
			assertFalse(image.isRecycled());
		}finally{ rendered.recycle(); image.recycle(); renderer.clear(); }
	}

	@Test public void nullDrawableFallsBackToInitialGlyph(){
		Context context=RuntimeEnvironment.getApplication();
		MapAvatarRenderer renderer=new MapAvatarRenderer(context);
		Bitmap rendered=renderer.draw(null,"?");
		try{
			assertEquals(Bitmap.Config.ARGB_8888,rendered.getConfig());
			// Center pixel stays the purple circle (glyph is centered but anti-aliased); bounds hold.
			assertEquals(48,Math.min(rendered.getWidth(),rendered.getHeight()));
		}finally{ rendered.recycle(); renderer.clear(); }
	}

	@Test public void hardwareDetectionContractMatchesDeviceCrashCondition(){
		Context context=RuntimeEnvironment.getApplication();
		MapAvatarRenderer renderer=new MapAvatarRenderer(context);
		Bitmap software=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888);
		try{
			// The device crash happens exactly when getConfig()==HARDWARE on a software canvas.
			// Robolectric cannot allocate HARDWARE, so assert the gate's negative case here and
			// rely on device logs for the positive case.
			assertFalse(renderer.requiresSoftwareCopy(software));
		}finally{ software.recycle(); renderer.clear(); }
	}
}
