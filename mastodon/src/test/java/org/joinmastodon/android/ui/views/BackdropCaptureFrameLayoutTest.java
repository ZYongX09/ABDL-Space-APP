package org.joinmastodon.android.ui.views;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.joinmastodon.android.ui.drawables.BlurhashCrossfadeDrawable;
import org.joinmastodon.android.ui.utils.LiquidGlassCompatibility;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, application=Application.class,
		shadows=BackdropCaptureFrameLayoutTest.CompatibilityShadow.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BackdropCaptureFrameLayoutTest{
	private Context context;

	// SDK capability behavior belongs to the helper's own tests.
	// Only the capability gate is simulated here; capture uses real native software canvases.
	@Implements(LiquidGlassCompatibility.class)
	public static class CompatibilityShadow{
		static boolean supported;

		@Implementation
		protected static boolean isSupported(){
			return supported;
		}
	}

	@Before
	public void setUp(){
		context=RuntimeEnvironment.getApplication();
		CompatibilityShadow.supported=true;
	}

	@Test
	public void realSoftwareCaptureClampsStripsAndPreservesRootCoordinates() throws Exception{
		Harness view=new Harness(context);
		Bands child=new Bands(context);
		view.addView(child);
		layout(view, 20, 40);
		view.setCaptureHeights(100, 7);
		Bitmap[] delivered=new Bitmap[2];
		view.setCaptureListener((top, bottom)->{
			delivered[0]=top;
			delivered[1]=bottom;
		});

		view.render(acceleratedCanvas(20, 40));

		assertEquals(1, child.hardwareDraws);
		assertEquals(1, child.softwareDraws);
		assertEquals(40, delivered[0].getHeight());
		assertEquals(7, delivered[1].getHeight());
		assertEquals(20, delivered[0].getWidth());
		assertEquals(Color.RED, delivered[0].getPixel(10, 2));
		assertEquals(Color.BLUE, delivered[0].getPixel(10, 38));
		assertEquals(Color.BLUE, delivered[1].getPixel(10, 0));
		assertEquals(Bitmap.Config.RGB_565, ((Bitmap)field(view, "captureBitmap")).getConfig());
		assertFalse((boolean)field(view, "capturing"));
	}

	@Test
	public void bottomOnlyCaptureUsesSmallArgbIntermediateAndCorrectTranslation() throws Exception{
		Harness view=new Harness(context);
		view.addView(new Bands(context));
		layout(view, 20, 40);
		view.setCaptureHeights(-5, 7);
		Bitmap[] delivered=new Bitmap[2];
		view.setCaptureListener((top, bottom)->{
			delivered[0]=top;
			delivered[1]=bottom;
		});

		view.render(acceleratedCanvas(20, 40));

		assertNull(delivered[0]);
		assertEquals(Color.BLUE, delivered[1].getPixel(10, 0));
		Bitmap shared=(Bitmap)field(view, "captureBitmap");
		assertEquals(7, shared.getHeight());
		assertEquals(Bitmap.Config.ARGB_8888, shared.getConfig());
	}

	@Test
	public void highResolutionCaptureDownsamplesOnlyIntermediateAndKeepsNativeStripGeometry() throws Exception{
		Harness view=new Harness(context);
		view.addView(new SamplingGrid(context));
		layout(view, 1440, 3200);
		view.setCaptureHeights(2000, 300);
		Bitmap[] delivered=new Bitmap[2];
		int[] calls={0};
		view.setCaptureListener((top, bottom)->{
			delivered[0]=top;
			delivered[1]=bottom;
			calls[0]++;
		});

		view.render(acceleratedCanvas(1, 1));

		Bitmap shared=(Bitmap)field(view, "captureBitmap");
		assertEquals(720, shared.getWidth());
		assertEquals(1600, shared.getHeight());
		assertEquals(Bitmap.Config.RGB_565, shared.getConfig());
		assertStripGeometryAndColors(delivered, 1440, 2000, 300);
		assertEquals(15_552_000L, field(view, "captureBufferBytes"));
		assertWithinBudget(view);
		Bitmap top=delivered[0], bottom=delivered[1];

		view.render(acceleratedCanvas(1, 1));

		assertEquals(2, calls[0]);
		assertSame(shared, field(view, "captureBitmap"));
		assertSame(top, delivered[0]);
		assertSame(bottom, delivered[1]);
		assertTrue(CompatibilityShadow.supported);
	}

	@Test
	public void roundedSamplingDimensionsDoNotStretchOrShiftStripCoordinates() throws Exception{
		Harness view=new Harness(context);
		SamplingGrid child=new SamplingGrid(context);
		view.addView(child);
		layout(view, 1441, 3201);
		view.setCaptureHeights(2001, 301);
		Bitmap[] delivered=new Bitmap[2];
		view.setCaptureListener((top, bottom)->{
			delivered[0]=top;
			delivered[1]=bottom;
		});

		view.render(acceleratedCanvas(1, 1));

		Bitmap shared=(Bitmap)field(view, "captureBitmap");
		assertEquals(721, shared.getWidth());
		assertEquals(1601, shared.getHeight());
		assertStripGeometryAndColors(delivered, 1441, 2001, 301);
		// Compare strip edges with an unclipped native RGB565 reference. Quantization may
		// change a channel slightly, but tolerating it must not hide a cleared-gap blend.
		Bitmap reference=Bitmap.createBitmap(shared.getWidth(), shared.getHeight(), Bitmap.Config.RGB_565);
		Canvas referenceCanvas=new Canvas(reference);
		referenceCanvas.scale(shared.getWidth()/1441f, shared.getHeight()/3201f);
		child.draw(referenceCanvas);
		Paint filter=new Paint(Paint.FILTER_BITMAP_FLAG);
		Bitmap edge=Bitmap.createBitmap(1441, 1, Bitmap.Config.ARGB_8888);
		Canvas edgeCanvas=new Canvas(edge);
		edgeCanvas.drawBitmap(reference, null, new RectF(0, -2000, 1441, 1201), filter);
		for(int x:new int[]{1441/4, 1440})
			assertEquals("Top boundary must not sample the cleared gap", edge.getPixel(x, 0), delivered[0].getPixel(x, 2000));
		edge.eraseColor(Color.TRANSPARENT);
		edgeCanvas.drawBitmap(reference, null, new RectF(0, -2900, 1441, 301), filter);
		for(int x:new int[]{1441/4, 1440})
			assertEquals("Bottom boundary must not sample the cleared gap", edge.getPixel(x, 0), delivered[1].getPixel(x, 0));
		assertWithinBudget(view);
		assertTrue(CompatibilityShadow.supported);
	}

	@Test
	public void largerStripsUseQuarterSamplingWithNoExtraFullHeightResamplingBitmap() throws Exception{
		Harness view=new Harness(context);
		view.addView(new SamplingGrid(context));
		layout(view, 1440, 3200);
		view.setCaptureHeights(2000, 800);
		Bitmap[] delivered=new Bitmap[2];
		view.setCaptureListener((top, bottom)->{
			delivered[0]=top;
			delivered[1]=bottom;
		});

		view.render(acceleratedCanvas(1, 1));

		Bitmap shared=(Bitmap)field(view, "captureBitmap");
		assertEquals(360, shared.getWidth());
		assertEquals(800, shared.getHeight());
		assertStripGeometryAndColors(delivered, 1440, 2000, 800);
		assertEquals(16_704_000L, field(view, "captureBufferBytes"));
		assertWithinBudget(view);
		assertTrue(CompatibilityShadow.supported);
	}

	@Test
	public void highResolutionBottomOnlyStillUsesASmallNativeArgbIntermediate() throws Exception{
		Harness view=new Harness(context);
		view.addView(new SamplingGrid(context));
		layout(view, 1440, 3200);
		view.setCaptureHeights(0, 300);
		Bitmap[] delivered=new Bitmap[2];
		view.setCaptureListener((top, bottom)->{
			delivered[0]=top;
			delivered[1]=bottom;
		});

		view.render(acceleratedCanvas(1, 1));

		assertNull(delivered[0]);
		assertBottomGeometryAndColors(delivered[1], 1440, 300, false);
		Bitmap shared=(Bitmap)field(view, "captureBitmap");
		assertEquals(1440, shared.getWidth());
		assertEquals(300, shared.getHeight());
		assertEquals(Bitmap.Config.ARGB_8888, shared.getConfig());
		assertEquals(3_456_000L, field(view, "captureBufferBytes"));
		assertWithinBudget(view);
		assertTrue(CompatibilityShadow.supported);
	}

	@Test
	public void downsampledBottomOnlyScalesBeforeRootRelativeTranslation() throws Exception{
		Harness view=new Harness(context);
		view.addView(new SamplingGrid(context));
		layout(view, 2048, 4096);
		view.setCaptureHeights(0, 1800);
		Bitmap[] delivered=new Bitmap[2];
		view.setCaptureListener((top, bottom)->{
			delivered[0]=top;
			delivered[1]=bottom;
		});

		view.render(acceleratedCanvas(1, 1));

		assertNull(delivered[0]);
		assertBottomGeometryAndColors(delivered[1], 2048, 1800, false);
		Bitmap shared=(Bitmap)field(view, "captureBitmap");
		assertEquals(512, shared.getWidth());
		assertEquals(450, shared.getHeight());
		assertEquals(Bitmap.Config.ARGB_8888, shared.getConfig());
		assertEquals(15_667_200L, field(view, "captureBufferBytes"));
		assertWithinBudget(view);
		assertTrue(CompatibilityShadow.supported);
	}

	@Test
	public void exactBudgetUsesNativeSamplingAndOneColumnOverUsesHalfSampling() throws Exception{
		assertEquals(16L*1024*1024, BackdropCaptureFrameLayout.CAPTURE_MEMORY_BUDGET_BYTES);
		for(int width:new int[]{1024, 1025}){
			Harness view=new Harness(context);
			view.addView(new Bands(context));
			layout(view, width, 4096);
			view.setCaptureHeights(1024, 1024);
			Bitmap[] delivered=new Bitmap[2];
			view.setCaptureListener((top, bottom)->{
				delivered[0]=top;
				delivered[1]=bottom;
			});

			view.render(acceleratedCanvas(1, 1));

			Bitmap shared=(Bitmap)field(view, "captureBitmap");
			assertEquals(width==1024 ? 1024 : 513, shared.getWidth());
			assertEquals(width==1024 ? 4096 : 2048, shared.getHeight());
			assertEquals(width, delivered[0].getWidth());
			assertEquals(1024, delivered[0].getHeight());
			assertEquals(width, delivered[1].getWidth());
			assertEquals(1024, delivered[1].getHeight());
			assertEquals(Color.RED, delivered[0].getPixel(width-1, 1023));
			assertEquals(Color.BLUE, delivered[1].getPixel(width-1, 0));
			if(width==1024)
				assertEquals(BackdropCaptureFrameLayout.CAPTURE_MEMORY_BUDGET_BYTES, field(view, "captureBufferBytes"));
			assertWithinBudget(view);
			view.setCaptureListener(null);
		}
		assertTrue(CompatibilityShadow.supported);
	}

	@Test
	public void retainedSoftwareCopyIsReservedBeforeSelectingSamplingScale() throws Exception{
		Harness view=new Harness(context);
		TrackingImage image=image(view);
		view.addView(new Bands(context));
		layout(view, 1024, 4096);
		view.setCaptureHeights(1024, 1024);
		Bitmap original=((BitmapDrawable)image.original).getBitmap();
		Bitmap retained=original.copy(Bitmap.Config.ARGB_8888, false);
		@SuppressWarnings("unchecked")
		Map<Bitmap, Bitmap> cache=(Map<Bitmap, Bitmap>)field(view, "softwareBitmapCache");
		cache.put(original, retained);
		Bitmap[] delivered=new Bitmap[2];
		view.setCaptureListener((top, bottom)->{
			delivered[0]=top;
			delivered[1]=bottom;
		});

		view.render(acceleratedCanvas(1, 1));

		Bitmap shared=(Bitmap)field(view, "captureBitmap");
		assertEquals(512, shared.getWidth());
		assertEquals(2048, shared.getHeight());
		assertNotNull(delivered[0]);
		assertNotNull(delivered[1]);
		assertSame(retained, cache.get(original));
		assertEquals((long)retained.getAllocationByteCount(), field(view, "softwareBitmapCacheBytes"));
		assertEquals(0, view.copies);
		assertSame(image.original, image.getDrawable());
		assertWithinBudget(view);
		assertTrue(CompatibilityShadow.supported);
	}

	@Test
	public void customChildSoftwareDrawFailuresPropagateRestoreResourcesAndAllowRetry() throws Exception{
		Throwable[] errors={new IllegalArgumentException("software draw"),
				new NoClassDefFoundError("software effect"), new OutOfMemoryError("software draw")};
		for(Throwable error:errors){
			Harness view=new Harness(context);
			TrackingImage image=image(view);
			Bands child=new Bands(context);
			child.softwareFailure=error;
			view.addView(child);
			layout(view, 20, 40);
			view.setCaptureHeights(7, 7);
			int[] calls={0};
			view.setCaptureListener((top, bottom)->calls[0]++);

			assertSame(error, assertThrows(error.getClass(), ()->view.render(acceleratedCanvas(20, 40))));

			assertSame(image.original, image.getDrawable());
			assertEquals(0, calls[0]);
			assertEquals(1, child.softwareDraws);
			assertRetryableAfterFailure(view);
			child.softwareFailure=null;
			view.render(acceleratedCanvas(20, 40));
			assertEquals(2, child.hardwareDraws);
			assertEquals(2, child.softwareDraws);
			assertEquals(1, calls[0]);
		}
		assertTrue(CompatibilityShadow.supported);
	}

	@Test
	public void cacheBudgetRecountsRetainedValuesInsteadOfLifetimeAllocations() throws Exception{
		Harness view=new Harness(context);
		TrackingImage first=image(view);
		TrackingImage second=image(view);
		TrackingImage third=image(view);
		layout(view, 20, 40);
		view.setCaptureHeights(7, 7);
		int[] deliveries={0};
		view.setCaptureListener((top, bottom)->deliveries[0]++);
		view.render(acceleratedCanvas(20, 40));
		assertEquals(3, view.copies);

		@SuppressWarnings("unchecked")
		Map<Bitmap, Bitmap> cache=(Map<Bitmap, Bitmap>)field(view, "softwareBitmapCache");
		Bitmap firstKey=((BitmapDrawable)first.original).getBitmap();
		Bitmap secondKey=((BitmapDrawable)second.original).getBitmap();
		Bitmap thirdKey=((BitmapDrawable)third.original).getBitmap();
		Bitmap retained=cache.get(thirdKey);
		// Model weak-entry expunging deterministically, without relying on GC timing.
		cache.remove(firstKey);
		Bitmap recycled=cache.get(secondKey);
		recycled.recycle();
		Field bytes=BackdropCaptureFrameLayout.class.getDeclaredField("softwareBitmapCacheBytes");
		bytes.setAccessible(true);
		bytes.setLong(view, BackdropCaptureFrameLayout.CAPTURE_MEMORY_BUDGET_BYTES);

		view.render(acceleratedCanvas(20, 40));

		assertEquals(2, deliveries[0]);
		assertEquals(5, view.copies);
		assertEquals(3, cache.size());
		assertSame(retained, cache.get(thirdKey));
		assertNotSame(recycled, cache.get(secondKey));
		long actualBytes=0;
		for(Bitmap bitmap:cache.values()){
			assertFalse(bitmap.isRecycled());
			actualBytes+=bitmap.getAllocationByteCount();
		}
		assertEquals(actualBytes, bytes.getLong(view));
		assertTrue(CompatibilityShadow.supported);
	}

	@Test
	public void conversionFailureRestoresAlreadyModifiedChildrenAndResetsCapturing() throws Exception{
		Harness view=new Harness(context);
		TrackingImage first=image(view);
		TrackingImage second=image(view);
		Drawable firstOriginal=first.original;
		Drawable secondOriginal=second.original;
		view.copyFailureAt=2;
		view.copyFailure=new UnsatisfiedLinkError("copy failed after first replacement");
		layout(view, 20, 40);
		view.setCaptureHeights(7, 7);
		int[] calls={0};
		view.setCaptureListener((top, bottom)->calls[0]++);

		UnsatisfiedLinkError actual=assertThrows(UnsatisfiedLinkError.class, ()->view.render(acceleratedCanvas(20, 40)));

		assertEquals(2, view.copies);
		assertEquals(1, first.restores);
		assertSame(firstOriginal, first.getDrawable());
		assertSame(secondOriginal, second.getDrawable());
		assertEquals(0, calls[0]);
		assertSame(view.copyFailure, actual);
		assertRetryableAfterFailure(view);
	}

	@Test
	public void restorationFailureStillAttemptsEveryOtherDrawable() throws Exception{
		Harness view=new Harness(context);
		TrackingImage first=image(view);
		TrackingImage second=image(view);
		second.failRestore=true;
		layout(view, 20, 40);
		view.setCaptureHeights(7, 7);
		int[] calls={0};
		view.setCaptureListener((top, bottom)->calls[0]++);

		IllegalStateException actual=assertThrows(IllegalStateException.class, ()->view.render(acceleratedCanvas(20, 40)));

		assertEquals(1, first.restores);
		assertEquals(1, second.restores);
		assertSame(first.original, first.getDrawable());
		assertSame(second.original, second.getDrawable());
		assertEquals(0, calls[0]);
		assertRetryableAfterFailure(view);
		assertTrue(actual.getMessage().contains("setter failed while restoring"));
	}

	@Test
	public void softwareFailureRemainsPrimaryWhenDrawableRestorationAlsoFails() throws Exception{
		Harness view=new Harness(context);
		TrackingImage first=image(view);
		TrackingImage second=image(view);
		second.failRestore=true;
		Bands child=new Bands(context);
		IllegalArgumentException error=new IllegalArgumentException("original software draw");
		child.softwareFailure=error;
		view.addView(child);
		layout(view, 20, 40);
		view.setCaptureHeights(7, 7);
		view.setCaptureListener((top, bottom)->fail("Failed draw must not deliver"));

		assertSame(error, assertThrows(IllegalArgumentException.class, ()->view.render(acceleratedCanvas(20, 40))));

		assertEquals(1, first.restores);
		assertEquals(1, second.restores);
		assertSame(first.original, first.getDrawable());
		assertSame(second.original, second.getDrawable());
		assertEquals(1, error.getSuppressed().length);
		assertEquals("setter failed while restoring", error.getSuppressed()[0].getMessage());
		assertRetryableAfterFailure(view);
	}

	@Test
	public void replacementSetterFailureAlsoRestoresTheThrowingChild() throws Exception{
		Harness view=new Harness(context);
		TrackingImage first=image(view);
		TrackingImage second=image(view);
		second.failReplace=true;
		layout(view, 20, 40);
		view.setCaptureHeights(7, 7);
		view.setCaptureListener((top, bottom)->fail("Replacement failure must not deliver"));

		IllegalStateException actual=assertThrows(IllegalStateException.class, ()->view.render(acceleratedCanvas(20, 40)));

		assertSame(first.original, first.getDrawable());
		assertSame(second.original, second.getDrawable());
		assertEquals(1, first.restores);
		assertEquals(1, second.restores);
		assertRetryableAfterFailure(view);
		assertTrue(actual.getMessage().contains("setter failed after replacement"));
	}

	@Test
	public void fatalSoftwareDrawingErrorIsNotSwallowedButStillRestoresDrawables() throws Exception{
		Harness view=new Harness(context);
		TrackingImage image=image(view);
		Bands child=new Bands(context);
		AssertionError error=new AssertionError("not a recoverable graphics failure");
		child.softwareFailure=error;
		view.addView(child);
		layout(view, 20, 40);
		view.setCaptureHeights(7, 7);
		view.setCaptureListener((top, bottom)->fail("Fatal draw failed"));

		try{
			view.render(acceleratedCanvas(20, 40));
			fail("Must not catch all Throwable");
		}catch(AssertionError actual){
			assertSame(error, actual);
		}
		assertSame(image.original, image.getDrawable());
		assertRetryableAfterFailure(view);
	}

	@Test
	public void nullSoftwareCopyPropagatesAndRestoresEarlierReplacement() throws Exception{
		Harness view=new Harness(context);
		TrackingImage first=image(view);
		image(view);
		view.nullCopyAt=2;
		layout(view, 20, 40);
		view.setCaptureHeights(7, 7);
		view.setCaptureListener((top, bottom)->fail("Null copy must not be delivered"));

		IllegalStateException actual=assertThrows(IllegalStateException.class, ()->view.render(acceleratedCanvas(20, 40)));

		assertSame(first.original, first.getDrawable());
		assertEquals(1, first.restores);
		assertRetryableAfterFailure(view);
		assertTrue(actual.getMessage().contains("returned null"));
	}

	@Test
	public void disablingAndDetachingDropBuffersCacheListenerAndScheduledWorkWithoutRecycling() throws Exception{
		for(boolean detach:new boolean[]{false, true}){
			Harness view=new Harness(context);
			image(view);
			layout(view, 20, 40);
			view.setCaptureHeights(7, 7);
			int[] calls={0};
			BackdropCaptureFrameLayout.CaptureListener listener=(top, bottom)->calls[0]++;
			view.setCaptureListener(listener);
			view.render(acceleratedCanvas(20, 40));
			Bitmap output=(Bitmap)field(view, "topCaptureBitmap");
			Bitmap cached=(Bitmap)((Map<?, ?>)field(view, "softwareBitmapCache")).values().iterator().next();
			view.setCaptureListener(listener);
			assertTrue((boolean)field(view, "captureFrameScheduled"));
			Runnable staleFrame=(Runnable)field(view, "captureFrameRunnable");
			int invalidations=view.invalidations;

			if(detach)
				view.detach();
			else
				view.setCaptureListener(null);
			staleFrame.run();
			view.render(acceleratedCanvas(20, 40));

			assertStopped(view);
			assertFalse(output.isRecycled());
			assertFalse(cached.isRecycled());
			assertEquals(1, calls[0]);
			assertEquals(invalidations, view.invalidations);
		}
		assertTrue(CompatibilityShadow.supported);
	}

	@Test
	public void reentrantDisableDuringSoftwareDrawCannotDeliverStaleCapture() throws Exception{
		Harness view=new Harness(context);
		TrackingImage image=image(view);
		Bands child=new Bands(context);
		child.softwareAction=()->view.setCaptureListener(null);
		view.addView(child);
		layout(view, 20, 40);
		view.setCaptureHeights(7, 7);
		view.setCaptureListener((top, bottom)->fail("Disabled in-flight capture must not deliver"));

		view.render(acceleratedCanvas(20, 40));

		assertEquals(1, child.softwareDraws);
		assertSame(image.original, image.getDrawable());
		assertStopped(view);
		assertTrue(CompatibilityShadow.supported);
	}

	@Test
	public void listenerFailurePropagatesDropsOwnRefsAndDoesNotRecycleDeliveredBitmap() throws Exception{
		Harness view=new Harness(context);
		TrackingImage image=image(view);
		view.addView(new Bands(context));
		layout(view, 20, 40);
		view.setCaptureHeights(7, 7);
		Bitmap[] delivered=new Bitmap[1];
		OutOfMemoryError error=new OutOfMemoryError("Compose consumer");
		view.setCaptureListener((top, bottom)->{
			delivered[0]=top;
			throw error;
		});

		assertSame(error, assertThrows(OutOfMemoryError.class, ()->view.render(acceleratedCanvas(20, 40))));

		assertNotNull(delivered[0]);
		assertFalse(delivered[0].isRecycled());
		assertEquals(Color.RED, delivered[0].getPixel(10, 1));
		assertSame(image.original, image.getDrawable());
		assertRetryableAfterFailure(view);
		int[] calls={0};
		view.setCaptureListener((top, bottom)->calls[0]++);
		view.render(acceleratedCanvas(20, 40));
		assertEquals(1, calls[0]);
	}

	@Test
	public void softwareWindowAndUnsupportedSdkDoNotCaptureOrPermanentlyDisableIt() throws Exception{
		Harness view=new Harness(context);
		Bands child=new Bands(context);
		view.addView(child);
		layout(view, 20, 40);
		view.setCaptureHeights(7, 7);
		int[] softwareWindowCalls={0};
		BackdropCaptureFrameLayout.CaptureListener listener=(top, bottom)->softwareWindowCalls[0]++;
		view.setCaptureListener(listener);
		view.render(new Canvas(Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888)));
		assertEquals(1, child.softwareDraws); // ordinary UI pass only
		assertSame(listener, field(view, "captureListener"));
		assertCaptureResourcesReleased(view);
		view.render(acceleratedCanvas(20, 40));
		assertEquals(1, softwareWindowCalls[0]);
		assertTrue(CompatibilityShadow.supported);

		CompatibilityShadow.supported=false;
		view.setCaptureListener((top, bottom)->fail("Unsupported SDK must not capture"));
		view.render(acceleratedCanvas(20, 40));
		assertEquals(2, child.softwareDraws);
		assertStopped(view);

		CompatibilityShadow.supported=true;
		int[] calls={0};
		view.setCaptureListener((top, bottom)->calls[0]++);
		view.render(acceleratedCanvas(20, 40));
		assertEquals(1, calls[0]);
		assertEquals(3, child.softwareDraws);
	}

	@Test
	public void oversizedAndExtremeBoundsPauseLocallyBeforeAllocatingCaptureBuffers() throws Exception{
		int[][] sizes={{2048, 4096, Integer.MAX_VALUE, Integer.MAX_VALUE},
				{Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE},
				{Integer.MAX_VALUE, 40, 1, 0}, {20, Integer.MAX_VALUE, 0, 7},
				{BackdropCaptureFrameLayout.MAX_CAPTURE_DIMENSION+1, 40, 1, 0},
				{20, BackdropCaptureFrameLayout.MAX_CAPTURE_DIMENSION+1, 0, 7}};
		for(int[] size:sizes){
			CompatibilityShadow.supported=true;
			Harness view=new Harness(context);
			view.layout(0, 0, size[0], size[1]);
			view.setCaptureHeights(size[2], size[3]);
			int[] clears={0}, calls={0};
			BackdropCaptureFrameLayout.CaptureListener listener=(top, bottom)->{
				if(top==null && bottom==null)
					clears[0]++;
				else
					calls[0]++;
			};
			view.setCaptureListener(listener);

			view.render(acceleratedCanvas(1, 1));

			assertEquals(0, view.copies);
			assertPaused(view);
			assertSame(listener, field(view, "captureListener"));
			assertEquals(1, clears[0]);
			assertTrue(CompatibilityShadow.supported);
			layout(view, 20, 40);
			view.setCaptureHeights(7, 7);
			view.render(acceleratedCanvas(20, 40));
			assertEquals(1, calls[0]);
			assertFalse((boolean)field(view, "budgetPaused"));
			assertWithinBudget(view);
		}
		assertTrue(CompatibilityShadow.supported);
	}

	@Test
	public void captureDoesNotDownsampleBeyondTheBoundEvenWhenEighthSamplingWouldFit() throws Exception{
		Harness view=new Harness(context);
		view.layout(0, 0, 1440, 3200);
		view.setCaptureHeights(2000, 850);
		view.setCaptureListener((top, bottom)->{
			assertNull(top);
			assertNull(bottom);
		});
		long outputBytes=1440L*(2000+850)*4;
		assertTrue(outputBytes+360L*800*2>BackdropCaptureFrameLayout.CAPTURE_MEMORY_BUDGET_BYTES);
		assertTrue(outputBytes+180L*400*2<BackdropCaptureFrameLayout.CAPTURE_MEMORY_BUDGET_BYTES);

		view.render(acceleratedCanvas(1, 1));

		assertTrue(CompatibilityShadow.supported);
		assertPaused(view);
	}

	@Test
	public void softwareCopiesArePlannedBeforeChoosingHalfSampling() throws Exception{
		Harness view=new Harness(context);
		TrackingImage image=new TrackingImage(context);
		// At 1x the strips and shared buffer consume 16 MiB. Reserve the new copy
		// before choosing the scale, so 2x succeeds without relaxing the budget.
		view.addView(image);
		view.forceCopies=true;
		image.setOriginal(new BitmapDrawable(context.getResources(), Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)));
		layout(view, 1024, 4096);
		view.setCaptureHeights(1024, 1024);
		int[] calls={0};
		view.setCaptureListener((top, bottom)->{
			assertNotNull(top);
			calls[0]++;
		});

		view.render(acceleratedCanvas(1, 1));

		assertEquals(1, calls[0]);
		assertEquals(1, view.copies);
		assertEquals(512, ((Bitmap)field(view, "captureBitmap")).getWidth());
		assertSame(image.original, image.getDrawable());
		assertWithinBudget(view);
		assertTrue(CompatibilityShadow.supported);
	}

	@Test
	public void newSoftwareCopyCanSelectQuarterSamplingInsteadOfStoppingAfterPartialConversion() throws Exception{
		Harness view=new Harness(context);
		TrackingImage first=image(view);
		TrackingImage second=new TrackingImage(context);
		second.setOriginal(new BitmapDrawable(context.getResources(), Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)));
		view.addView(second);
		layout(view, 1440, 3200);
		view.setCaptureHeights(2000, 300);
		view.setCaptureListener((top, bottom)->assertNotNull(top));

		view.render(acceleratedCanvas(1, 1));

		assertEquals(2, view.copies);
		assertEquals(360, ((Bitmap)field(view, "captureBitmap")).getWidth());
		assertEquals(1, first.restores);
		assertSame(first.original, first.getDrawable());
		assertSame(second.original, second.getDrawable());
		assertWithinBudget(view);
		assertTrue(CompatibilityShadow.supported);
	}

	@Test
	public void unexpectedlyLargeSoftwareCopyAllocationIsCheckedAndNeverCached() throws Exception{
		Harness view=new Harness(context);
		TrackingImage first=image(view);
		TrackingImage second=image(view);
		view.oversizedCopyAt=2;
		layout(view, 1440, 3200);
		view.setCaptureHeights(2000, 300);
		view.setCaptureListener((top, bottom)->{
			assertNull(top);
			assertNull(bottom);
		});

		view.render(acceleratedCanvas(1, 1));

		assertEquals(2, view.copies);
		assertSame(first.original, first.getDrawable());
		assertSame(second.original, second.getDrawable());
		assertEquals(1, first.restores);
		assertPaused(view);
		assertTrue(CompatibilityShadow.supported);
	}

	@Test
	public void ordinaryDispatchDrawFailureIsNotMasked() throws Exception{
		Harness view=new Harness(context);
		Bands child=new Bands(context);
		IllegalStateException error=new IllegalStateException("ordinary UI draw");
		child.hardwareFailure=error;
		view.addView(child);
		layout(view, 20, 40);
		view.setCaptureHeights(7, 7);
		view.setCaptureListener((top, bottom)->fail("Normal draw failed"));

		try{
			view.render(acceleratedCanvas(20, 40));
			fail("Normal UI exceptions must propagate");
		}catch(IllegalStateException actual){
			assertSame(error, actual);
		}
		assertTrue(CompatibilityShadow.supported);
		assertEquals(0, child.softwareDraws);
		assertFalse((boolean)field(view, "capturing"));
	}

	@Test
	public void new1080SquareCopySelectsHalfSamplingWithTheRealBudget() throws Exception{
		Harness view=new Harness(context);
		TrackingImage image=image(view);
		image.setOriginal(new BitmapDrawable(context.getResources(), Bitmap.createBitmap(1080, 1080, Bitmap.Config.ARGB_8888)));
		layout(view, 1440, 3200);
		view.setCaptureHeights(300, 300);
		view.setCaptureListener((top, bottom)->assertNotNull(top));
		assertEquals(17_337_600L, 1440L*3200*2+1440L*600*4+1080L*1080*4);

		view.render(acceleratedCanvas(1, 1));

		assertEquals(720, ((Bitmap)field(view, "captureBitmap")).getWidth());
		assertEquals(1, view.copies);
		assertEquals(10_425_600L, (long)field(view, "captureBufferBytes")+(long)field(view, "softwareBitmapCacheBytes"));
		assertWithinBudget(view);
	}

	@Test
	public void duplicateBitmapIdentityIsPlannedAndCopiedOnlyOnce() throws Exception{
		Harness view=new Harness(context);
		TrackingImage first=image(view), second=image(view);
		Bitmap bitmap=Bitmap.createBitmap(1080, 1080, Bitmap.Config.ARGB_8888);
		first.setOriginal(new BitmapDrawable(context.getResources(), bitmap));
		second.setOriginal(new BitmapDrawable(context.getResources(), bitmap));
		layout(view, 1440, 3200);
		view.setCaptureHeights(300, 300);
		view.setCaptureListener((top, bottom)->assertNotNull(top));

		view.render(acceleratedCanvas(1, 1));

		assertEquals(1, view.copies);
		assertEquals(720, ((Bitmap)field(view, "captureBitmap")).getWidth());
		assertEquals(1, ((Map<?, ?>)field(view, "softwareBitmapCache")).size());
		assertWithinBudget(view);
	}

	@Test
	public void unrelatedCachedCopyIsEvictedInsteadOfForcingDownsampling() throws Exception{
		Harness view=new Harness(context);
		layout(view, 1024, 4096);
		view.setCaptureHeights(1024, 1024);
		Bitmap old=Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
		@SuppressWarnings("unchecked")
		Map<Bitmap, Bitmap> cache=(Map<Bitmap, Bitmap>)field(view, "softwareBitmapCache");
		Bitmap cached=old.copy(Bitmap.Config.ARGB_8888, false);
		cache.put(old, cached);
		view.setCaptureListener((top, bottom)->assertNotNull(top));

		view.render(acceleratedCanvas(1, 1));

		assertEquals(1024, ((Bitmap)field(view, "captureBitmap")).getWidth());
		assertTrue(cache.isEmpty());
		assertFalse(cached.isRecycled());
		assertWithinBudget(view);
	}

	@Test
	public void pauseClearsStaleImageOnceAndHeightChangeRetriesSameCallbackWithoutSpin() throws Exception{
		Harness view=new Harness(context);
		layout(view, 1440, 3200);
		view.setCaptureHeights(300, 300);
		Bitmap[] delivered=new Bitmap[2];
		int[] calls={0}, clears={0};
		BackdropCaptureFrameLayout.CaptureListener listener=(top, bottom)->{
			delivered[0]=top;
			delivered[1]=bottom;
			if(top==null && bottom==null){
				clears[0]++;
				// Simulate consumer invalidation caused by clearing its image.
				view.onDescendantInvalidated(view, view);
			}else{
				calls[0]++;
			}
		};
		view.setCaptureListener(listener);
		view.render(acceleratedCanvas(1, 1));
		Bitmap old=delivered[0];
		view.setCaptureHeights(2000, 850);
		view.render(acceleratedCanvas(1, 1));
		assertPaused(view);
		assertNull(delivered[0]);
		assertNull(delivered[1]);
		assertFalse(old.isRecycled());
		int posts=view.framePosts;
		for(int i=0;i<5;i++)
			view.render(acceleratedCanvas(1, 1));
		assertEquals(1, clears[0]);
		assertEquals(posts, view.framePosts);
		assertFalse((boolean)field(view, "captureFrameScheduled"));

		view.setCaptureHeights(300, 300);
		view.render(acceleratedCanvas(1, 1));

		assertSame(listener, field(view, "captureListener"));
		assertEquals(2, calls[0]);
		assertNotNull(delivered[0]);
		assertFalse((boolean)field(view, "budgetPaused"));
		assertWithinBudget(view);
	}

	@Test
	public void movingOverBudgetImageOutOfTheStripsRetriesOnRealContentInvalidation() throws Exception{
		Harness view=new Harness(context);
		TrackingImage image=image(view);
		image.setOriginal(new BitmapDrawable(context.getResources(), Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888)));
		image.setLayoutParams(new FrameLayout.LayoutParams(100, 100));
		layout(view, 1440, 3200);
		view.setCaptureHeights(300, 300);
		int[] calls={0}, clears={0};
		BackdropCaptureFrameLayout.CaptureListener listener=(top, bottom)->{
			if(top==null && bottom==null)
				clears[0]++;
			else
				calls[0]++;
		};
		view.setCaptureListener(listener);
		view.render(acceleratedCanvas(1, 1));
		assertPaused(view);
		assertEquals(0, view.copies);
		assertEquals(1, clears[0]);
		image.layout(0, 1000, 100, 1100);
		image.invalidate();
		view.onDescendantInvalidated(image, image);
		assertTrue((boolean)field(view, "captureFrameScheduled"));
		((Runnable)field(view, "captureFrameRunnable")).run();

		view.render(acceleratedCanvas(1, 1));

		assertSame(listener, field(view, "captureListener"));
		assertEquals(1, calls[0]);
		assertEquals(0, view.copies);
		assertSame(image.original, image.getDrawable());
		assertWithinBudget(view);
	}

	@Test
	public void softwareSnapshotKeepsLastCaptureAndNextHardwareFrameContinues() throws Exception{
		Harness view=new Harness(context);
		TrackingImage image=image(view);
		layout(view, 20, 40);
		view.setCaptureHeights(7, 7);
		int[] calls={0};
		BackdropCaptureFrameLayout.CaptureListener listener=(top, bottom)->calls[0]++;
		view.setCaptureListener(listener);
		view.render(acceleratedCanvas(20, 40));
		Bitmap shared=(Bitmap)field(view, "captureBitmap"), top=(Bitmap)field(view, "topCaptureBitmap");
		int copies=view.copies;

		view.render(new Canvas(Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888)));

		assertSame(listener, field(view, "captureListener"));
		assertSame(shared, field(view, "captureBitmap"));
		assertSame(top, field(view, "topCaptureBitmap"));
		assertEquals(1, calls[0]);
		assertEquals(copies, view.copies);
		assertSame(image.original, image.getDrawable());
		view.render(acceleratedCanvas(20, 40));
		assertEquals(2, calls[0]);
		assertWithinBudget(view);
	}

	@Test
	public void hiddenTabsAndCentralHugeImagesDoNotCopyOrPauseVisibleStrips() throws Exception{
		Harness view=new Harness(context);
		TrackingImage visible=image(view), central=image(view), gone=image(view), hidden=image(view);
		Bitmap large=Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888);
		for(TrackingImage image:new TrackingImage[]{central, gone, hidden})
			image.setOriginal(new BitmapDrawable(context.getResources(), large));
		central.setLayoutParams(new FrameLayout.LayoutParams(100, 100));
		gone.setVisibility(View.GONE);
		FrameLayout tab=new FrameLayout(context);
		view.removeView(hidden);
		tab.addView(hidden);
		tab.setVisibility(View.INVISIBLE);
		view.addView(tab);
		layout(view, 1440, 3200);
		central.layout(0, 1000, 100, 1100);
		view.setCaptureHeights(300, 300);
		int[] calls={0};
		view.setCaptureListener((top, bottom)->{
			assertNotNull(top);
			calls[0]++;
		});

		view.render(acceleratedCanvas(1, 1));

		assertEquals(1, calls[0]);
		assertEquals(1, view.copies);
		assertEquals(1, visible.restores);
		assertEquals(0, gone.restores);
		assertEquals(0, hidden.restores);
		assertSame(central.original, central.getDrawable());
		assertFalse((boolean)field(view, "budgetPaused"));
		assertWithinBudget(view);
	}

	@Test
	public void centralPlaceholderAndRestoreDoNotRequestFrameworkLayoutButRealChangesStillDo() throws Exception{
		Harness view=new Harness(context);
		TrackingImage image=image(view);
		Bitmap bitmap=Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888);
		image.setOriginal(new BitmapDrawable(context.getResources(), bitmap));
		image.setLayoutParams(new FrameLayout.LayoutParams(50, 50));
		layout(view, 200, 400);
		image.layout(0, 150, 50, 200);
		view.setCaptureHeights(20, 20);
		view.setCaptureListener((top, bottom)->assertNotNull(top));
		image.checkPlaceholderDimensions=true;
		int imageLayouts=image.layoutRequests, hostLayouts=view.layoutRequests;

		for(int i=0;i<5;i++)
			view.render(acceleratedCanvas(1, 1));

		assertEquals(0, view.copies);
		assertEquals(5, image.restores);
		assertEquals(imageLayouts, image.layoutRequests);
		assertEquals(hostLayouts, view.layoutRequests);
		assertFalse(image.isLayoutRequested());
		assertFalse(view.isLayoutRequested());
		assertFalse((boolean)field(view, "captureFrameScheduled"));
		assertSame(image.original, image.getDrawable());
		image.checkPlaceholderDimensions=false;
		image.setImageDrawable(new BitmapDrawable(context.getResources(), Bitmap.createBitmap(60, 70, Bitmap.Config.ARGB_8888)));
		assertTrue(image.layoutRequests>imageLayouts);
		assertTrue(image.isLayoutRequested());
		image.invalidate();
		view.onDescendantInvalidated(image, image);
		assertTrue((boolean)field(view, "captureFrameScheduled"));
	}

	@Test
	public void centralCrossfadePlaceholderPreservesSourceAndWrapperDimensionsWithoutLayout() throws Exception{
		Harness view=new Harness(context);
		TrackingImage image=image(view);
		BitmapDrawable source=new BitmapDrawable(context.getResources(), Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888));
		BlurhashCrossfadeDrawable crossfade=new BlurhashCrossfadeDrawable();
		crossfade.setImageDrawable(source);
		crossfade.setCrossfadeAlpha(0);
		image.setOriginal(crossfade);
		image.setLayoutParams(new FrameLayout.LayoutParams(50, 50));
		layout(view, 200, 400);
		image.layout(0, 150, 50, 200);
		// Draw the image itself first, outside the host's tiny test-canvas clip:
		// crossfade.draw initializes its inner source bounds from the outer bounds.
		image.draw(acceleratedCanvas(50, 50));
		Rect sourceBounds=new Rect(source.getBounds());
		assertEquals(new Rect(0, 0, 50, 50), sourceBounds);
		Bands observer=new Bands(context);
		observer.softwareAction=()->{
			Drawable placeholder=crossfade.getImageDrawable();
			assertNotSame(source, placeholder);
			assertEquals(source.getIntrinsicWidth(), placeholder.getIntrinsicWidth());
			assertEquals(source.getIntrinsicHeight(), placeholder.getIntrinsicHeight());
			assertEquals(source.getMinimumWidth(), placeholder.getMinimumWidth());
			assertEquals(source.getMinimumHeight(), placeholder.getMinimumHeight());
			assertEquals(sourceBounds, source.getBounds());
			assertEquals(sourceBounds, placeholder.getBounds());
			assertEquals(source.getIntrinsicWidth(), crossfade.getIntrinsicWidth());
			assertEquals(source.getIntrinsicHeight(), crossfade.getIntrinsicHeight());
		};
		view.addView(observer);
		layout(view, 200, 400);
		image.layout(0, 150, 50, 200);
		view.setCaptureHeights(20, 20);
		view.setCaptureListener((top, bottom)->assertNotNull(top));
		int imageLayouts=image.layoutRequests, hostLayouts=view.layoutRequests;

		for(int i=0;i<5;i++)
			view.render(acceleratedCanvas(1, 1));

		assertEquals(0, view.copies);
		assertEquals(imageLayouts, image.layoutRequests);
		assertEquals(hostLayouts, view.layoutRequests);
		assertSame(crossfade, image.getDrawable());
		assertSame(source, crossfade.getImageDrawable());
		assertEquals(sourceBounds, source.getBounds());
		assertWithinBudget(view);
	}

	@Test
	public void zeroPaddingDoesNotClipOverflowWhenClipChildrenIsDisabled() throws Exception{
		Harness view=new Harness(context);
		// The host must also allow the parent's overflow; otherwise it clips the
		// entire container to its own bounds before the inner child can draw.
		view.setClipChildren(false);
		view.forceCopies=true;
		FrameLayout overflow=new FrameLayout(context);
		overflow.setClipChildren(false);
		overflow.setClipToPadding(true); // Zero padding means no padding clip at draw time.
		view.addView(overflow, new FrameLayout.LayoutParams(100, 100));
		TrackingImage image=new TrackingImage(context);
		Bitmap bitmap=Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
		bitmap.eraseColor(Color.MAGENTA);
		image.setOriginal(new BitmapDrawable(context.getResources(), bitmap));
		overflow.addView(image, new FrameLayout.LayoutParams(50, 50));
		layout(view, 200, 400);
		overflow.layout(0, 100, 100, 200);
		image.layout(0, -100, 50, -50); // Outside parent bounds, but inside host top strip.
		view.setCaptureHeights(20, 20);
		Bitmap[] delivered=new Bitmap[2];
		view.setCaptureListener((top, bottom)->{
			delivered[0]=top;
			delivered[1]=bottom;
		});

		view.render(acceleratedCanvas(1, 1));

		assertEquals(1, view.copies);
		assertEquals(Color.MAGENTA, delivered[0].getPixel(10, 10));
		assertSame(image.original, image.getDrawable());
		assertWithinBudget(view);
	}

	@Test
	public void nonzeroPaddingStillClipsOverflowWhenClipChildrenIsDisabled() throws Exception{
		Harness view=new Harness(context);
		view.setClipChildren(false); // Isolate the parent's nonzero padding clip.
		view.forceCopies=true;
		FrameLayout overflow=new FrameLayout(context);
		overflow.setClipChildren(false);
		overflow.setClipToPadding(true);
		overflow.setPadding(1, 1, 1, 1);
		view.addView(overflow, new FrameLayout.LayoutParams(100, 100));
		TrackingImage image=new TrackingImage(context);
		image.setOriginal(new BitmapDrawable(context.getResources(), Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888)));
		overflow.addView(image, new FrameLayout.LayoutParams(50, 50));
		layout(view, 200, 400);
		overflow.layout(0, 100, 100, 200);
		image.layout(0, -100, 50, -50);
		view.setCaptureHeights(20, 20);
		view.setCaptureListener((top, bottom)->assertNotNull(top));

		view.render(acceleratedCanvas(1, 1));

		assertEquals(0, view.copies);
		assertSame(image.original, image.getDrawable());
		assertWithinBudget(view);
	}

	@Test
	public void ancestorScrollIsIncludedInHostRelativeImageIntersection() throws Exception{
		Harness view=new Harness(context);
		view.forceCopies=true;
		FrameLayout scroller=new FrameLayout(context);
		view.addView(scroller, new FrameLayout.LayoutParams(-1, -1));
		TrackingImage image=new TrackingImage(context);
		image.setOriginal(new BitmapDrawable(context.getResources(), Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)));
		scroller.addView(image, new FrameLayout.LayoutParams(100, 100));
		layout(view, 200, 400);
		image.layout(0, 150, 100, 250);
		scroller.scrollTo(0, 150);
		view.setCaptureHeights(20, 20);
		view.setCaptureListener((top, bottom)->assertNotNull(top));

		view.render(acceleratedCanvas(1, 1));

		assertEquals(1, view.copies);
		assertSame(image.original, image.getDrawable());
		assertWithinBudget(view);
	}

	@Test
	public void transformedImageUsesConservativeConversionInsteadOfMissingHardwareArguments() throws Exception{
		Harness view=new Harness(context);
		TrackingImage image=image(view);
		image.setLayoutParams(new FrameLayout.LayoutParams(100, 100));
		layout(view, 200, 400);
		image.layout(0, 150, 100, 250);
		image.setTranslationY(-150);
		view.setCaptureHeights(20, 20);
		view.setCaptureListener((top, bottom)->assertNotNull(top));

		view.render(acceleratedCanvas(1, 1));

		assertEquals(1, view.copies);
		assertSame(image.original, image.getDrawable());
		assertWithinBudget(view);
	}

	@Test
	public void clippedOffscreenHugeImageDoesNotConsumeCopyBudget() throws Exception{
		Harness view=new Harness(context);
		view.forceCopies=true;
		FrameLayout clipped=new FrameLayout(context);
		view.addView(clipped, new FrameLayout.LayoutParams(100, 100));
		TrackingImage image=new TrackingImage(context);
		image.setOriginal(new BitmapDrawable(context.getResources(), Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888)));
		clipped.addView(image, new FrameLayout.LayoutParams(100, 100));
		layout(view, 200, 400);
		image.layout(0, 200, 100, 300);
		view.setCaptureHeights(300, 20);
		view.setCaptureListener((top, bottom)->assertNotNull(top));

		view.render(acceleratedCanvas(1, 1));

		assertEquals(0, view.copies);
		assertSame(image.original, image.getDrawable());
		assertWithinBudget(view);
	}

	@Test
	public void bottomIntersectingImageStillCopiesAndKeepsHostRelativeStripPixels() throws Exception{
		Harness view=new Harness(context);
		TrackingImage image=image(view);
		Bitmap source=((BitmapDrawable)image.original).getBitmap();
		source.eraseColor(Color.MAGENTA);
		image.setLayoutParams(new FrameLayout.LayoutParams(10, 10));
		layout(view, 20, 40);
		image.layout(5, 30, 15, 40);
		view.setCaptureHeights(7, 7);
		Bitmap[] delivered=new Bitmap[2];
		view.setCaptureListener((top, bottom)->{
			delivered[0]=top;
			delivered[1]=bottom;
		});

		view.render(acceleratedCanvas(20, 40));

		assertEquals(1, view.copies);
		assertEquals(Color.MAGENTA, delivered[1].getPixel(10, 3));
		assertEquals(Color.BLACK, delivered[0].getPixel(10, 3)); // untouched RGB565 strip
		assertSame(image.original, image.getDrawable());
		assertWithinBudget(view);
	}

	@Test
	public void shrinkingWindowAloneRecoversBudgetPauseWithoutRegisteringAgain() throws Exception{
		Harness view=new Harness(context);
		layout(view, 1440, 3200);
		view.setCaptureHeights(2000, 850);
		int[] captures={0}, clears={0};
		BackdropCaptureFrameLayout.CaptureListener listener=(top, bottom)->{
			if(top==null && bottom==null)
				clears[0]++;
			else
				captures[0]++;
		};
		view.setCaptureListener(listener);
		view.render(acceleratedCanvas(1, 1));
		assertPaused(view);

		layout(view, 720, 1600);
		view.render(acceleratedCanvas(1, 1));

		assertSame(listener, field(view, "captureListener"));
		assertEquals(1, clears[0]);
		assertEquals(1, captures[0]);
		assertWithinBudget(view);
	}

	@Test
	public void reentrantHeightChangeDuringCaptureDropsOldGenerationAndNextDrawRecovers() throws Exception{
		Harness view=new Harness(context);
		TrackingImage image=image(view);
		Bands child=new Bands(context);
		child.softwareAction=()->view.setCaptureHeights(3, 4);
		view.addView(child);
		layout(view, 20, 40);
		view.setCaptureHeights(7, 7);
		int[] calls={0};
		view.setCaptureListener((top, bottom)->{
			assertEquals(3, top.getHeight());
			assertEquals(4, bottom.getHeight());
			calls[0]++;
		});
		view.render(acceleratedCanvas(20, 40));
		assertEquals(0, calls[0]);
		assertRetryableAfterFailure(view);
		assertSame(image.original, image.getDrawable());
		child.softwareAction=null;

		view.render(acceleratedCanvas(20, 40));

		assertEquals(1, calls[0]);
		assertWithinBudget(view);
	}

	@Test
	public void reentrantDisposeOnPauseCannotReviveListenerOrScheduledWork() throws Exception{
		Harness view=new Harness(context);
		layout(view, 1440, 3200);
		view.setCaptureHeights(2000, 850);
		view.setCaptureListener((top, bottom)->{
			assertNull(top);
			assertNull(bottom);
			view.setCaptureListener(null);
		});

		view.render(acceleratedCanvas(1, 1));

		assertStopped(view);
		assertFalse((boolean)field(view, "budgetPaused"));
	}

	private static void assertStripGeometryAndColors(Bitmap[] delivered, int width, int topHeight, int bottomHeight){
		Bitmap top=delivered[0];
		assertNotNull(top);
		assertEquals(width, top.getWidth());
		assertEquals(topHeight, top.getHeight());
		assertEquals(Bitmap.Config.ARGB_8888, top.getConfig());
		assertSampledColor(Color.RED, top, width/4, 20, true);
		assertSampledColor(Color.RED, top, width/4, 399, true);
		assertSampledColor(Color.GREEN, top, width/4, 601, true);
		assertSampledColor(Color.BLUE, top, width/4, 1800, true);
		assertSampledColor(Color.BLUE, top, width/4, topHeight-1, true);
		assertSampledColor(Color.CYAN, top, width-1, 1800, true);
		assertSampledColor(Color.CYAN, top, width-1, topHeight-1, true);
		// Original-size marker detects an unscaled, stretched or vertically shifted output.
		assertSampledColor(Color.WHITE, top, 260, 860, true);
		assertSampledColor(Color.GREEN, top, 380, 860, true);
		assertSampledColor(Color.GREEN, top, 260, 1000, true);
		assertBottomGeometryAndColors(delivered[1], width, bottomHeight, true);
	}

	private static void assertBottomGeometryAndColors(Bitmap bottom, int width, int height, boolean rgb565){
		assertNotNull(bottom);
		assertEquals(width, bottom.getWidth());
		assertEquals(height, bottom.getHeight());
		assertEquals(Bitmap.Config.ARGB_8888, bottom.getConfig());
		assertSampledColor(Color.BLUE, bottom, width/4, 0, rgb565);
		assertSampledColor(Color.CYAN, bottom, width-1, 0, rgb565);
		assertSampledColor(Color.YELLOW, bottom, width/4, height-1, rgb565);
		assertSampledColor(Color.MAGENTA, bottom, width-1, height-1, rgb565);
		assertSampledColor(Color.BLUE, bottom, width/4, height-150, rgb565);
		assertSampledColor(Color.YELLOW, bottom, width/4, height-50, rgb565);
		assertSampledColor(Color.CYAN, bottom, width-1, height-150, rgb565);
		assertSampledColor(Color.MAGENTA, bottom, width-1, height-50, rgb565);
	}

	private static void assertSampledColor(int expected, Bitmap bitmap, int x, int y, boolean rgb565){
		int actual=bitmap.getPixel(x, y);
		String position="Sample at ("+x+", "+y+")";
		// RGB565 quantization plus native bilinear rounding may vary within one channel step.
		// Alpha, geometry and ARGB-only samples remain exact; no general color-distance slack.
		assertEquals(position+" alpha", Color.alpha(expected), Color.alpha(actual));
		if(!rgb565){
			assertEquals(position, expected, actual);
			return;
		}
		assertEquals(position+" red", (double)Color.red(expected), Color.red(actual), 8);
		assertEquals(position+" green", (double)Color.green(expected), Color.green(actual), 4);
		assertEquals(position+" blue", (double)Color.blue(expected), Color.blue(actual), 8);
	}

	private static void assertWithinBudget(Harness view) throws Exception{
		long bufferBytes=0;
		for(String name:new String[]{"captureBitmap", "topCaptureBitmap", "bottomCaptureBitmap"}){
			Bitmap bitmap=(Bitmap)field(view, name);
			if(bitmap!=null)
				bufferBytes+=bitmap.getAllocationByteCount();
		}
		long cacheBytes=0;
		for(Object value:((Map<?, ?>)field(view, "softwareBitmapCache")).values())
			cacheBytes+=((Bitmap)value).getAllocationByteCount();
		assertEquals(bufferBytes, field(view, "captureBufferBytes"));
		assertEquals(cacheBytes, field(view, "softwareBitmapCacheBytes"));
		assertTrue(bufferBytes+cacheBytes<=BackdropCaptureFrameLayout.CAPTURE_MEMORY_BUDGET_BYTES);
		assertFalse((boolean)field(view, "capturing"));
	}

	private TrackingImage image(Harness view){
		view.forceCopies=true;
		TrackingImage image=new TrackingImage(context);
		image.setOriginal(new BitmapDrawable(context.getResources(), Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)));
		view.addView(image);
		return image;
	}

	private static void layout(Harness view, int width, int height){
		view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
		view.layout(0, 0, width, height);
	}

	private static Canvas acceleratedCanvas(int width, int height){
		return new Canvas(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)){
			@Override
			public boolean isHardwareAccelerated(){
				return true;
			}
		};
	}

	private static Object field(Harness view, String name) throws Exception{
		Field field=BackdropCaptureFrameLayout.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(view);
	}

	private static void assertPaused(Harness view) throws Exception{
		assertNotNull(field(view, "captureListener"));
		assertTrue((boolean)field(view, "budgetPaused"));
		assertCaptureResourcesReleased(view);
	}

	private static void assertStopped(Harness view) throws Exception{
		assertNull(field(view, "captureListener"));
		assertCaptureResourcesReleased(view);
	}

	private static void assertRetryableAfterFailure(Harness view) throws Exception{
		assertTrue(CompatibilityShadow.supported);
		assertNotNull(field(view, "captureListener"));
		assertCaptureResourcesReleased(view);
	}

	private static void assertCaptureResourcesReleased(Harness view) throws Exception{
		assertNull(field(view, "captureBitmap"));
		assertNull(field(view, "topCaptureBitmap"));
		assertNull(field(view, "bottomCaptureBitmap"));
		assertFalse((boolean)field(view, "capturing"));
		assertFalse((boolean)field(view, "captureFrameScheduled"));
		assertTrue(((Map<?, ?>)field(view, "softwareBitmapCache")).isEmpty());
		assertTrue(((List<?>)field(view, "restoreDrawables")).isEmpty());
		assertEquals(0L, field(view, "captureBufferBytes"));
		assertEquals(0L, field(view, "softwareBitmapCacheBytes"));
	}

	private static class Harness extends BackdropCaptureFrameLayout{
		boolean attached=true;
		boolean forceCopies;
		int copies, copyFailureAt, nullCopyAt, oversizedCopyAt, invalidations, framePosts, layoutRequests;
		Throwable copyFailure;

		Harness(Context context){
			super(context);
		}

		@Override
		public boolean isAttachedToWindow(){
			return attached;
		}

		@Override
		public void invalidate(){
			invalidations++;
			super.invalidate();
		}

		@Override
		public void requestLayout(){
			layoutRequests++;
			super.requestLayout();
		}

		@Override
		public void postOnAnimation(Runnable action){
			framePosts++;
			super.postOnAnimation(action);
		}

		@Override
		boolean requiresSoftwareCopy(Bitmap bitmap){
			return forceCopies || super.requiresSoftwareCopy(bitmap);
		}

		@Override
		Bitmap copyHardwareBitmap(Bitmap bitmap){
			copies++;
			if(copies==copyFailureAt)
				raise(copyFailure);
			if(copies==nullCopyAt)
				return null;
			if(copies==oversizedCopyAt)
				return Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888);
			return super.copyHardwareBitmap(bitmap);
		}

		void render(Canvas canvas){
			dispatchDraw(canvas);
		}

		void detach(){
			attached=false;
			onDetachedFromWindow();
		}
	}

	private static class Bands extends View{
		int hardwareDraws, softwareDraws;
		Throwable softwareFailure, hardwareFailure;
		Runnable softwareAction;

		Bands(Context context){
			super(context);
			setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
			setWillNotDraw(false);
		}

		@Override
		protected void onDraw(Canvas canvas){
			if(canvas.isHardwareAccelerated()){
				hardwareDraws++;
				if(hardwareFailure!=null)
					raise(hardwareFailure);
			}else{
				softwareDraws++;
				if(softwareAction!=null)
					softwareAction.run();
				if(softwareFailure!=null)
					raise(softwareFailure);
			}
			Paint paint=new Paint();
			paint.setColor(Color.RED);
			canvas.drawRect(0, 0, getWidth(), getHeight()/2f, paint);
			paint.setColor(Color.BLUE);
			canvas.drawRect(0, getHeight()/2f, getWidth(), getHeight(), paint);
		}
	}

	private static class SamplingGrid extends View{
		SamplingGrid(Context context){
			super(context);
			setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
			setWillNotDraw(false);
		}

		@Override
		protected void onDraw(Canvas canvas){
			Paint paint=new Paint();
			float split=getWidth()/2f;
			paint.setColor(Color.BLUE);
			canvas.drawRect(0, 0, split, getHeight(), paint);
			paint.setColor(Color.CYAN);
			canvas.drawRect(split, 0, getWidth(), getHeight(), paint);
			paint.setColor(Color.RED);
			canvas.drawRect(0, 0, split, 500, paint);
			paint.setColor(Color.MAGENTA);
			canvas.drawRect(split, 0, getWidth(), 500, paint);
			paint.setColor(Color.GREEN);
			canvas.drawRect(0, 500, getWidth(), 1600, paint);
			paint.setColor(Color.YELLOW);
			canvas.drawRect(0, getHeight()-100, split, getHeight(), paint);
			paint.setColor(Color.MAGENTA);
			canvas.drawRect(split, getHeight()-100, getWidth(), getHeight(), paint);
			paint.setColor(Color.WHITE);
			canvas.drawRect(200, 800, 320, 920, paint);
		}
	}

	private static class TrackingImage extends ImageView{
		Drawable original;
		boolean replaced, failRestore, failReplace, checkPlaceholderDimensions;
		int restores, layoutRequests;

		TrackingImage(Context context){
			super(context);
			// These fixtures intentionally fill both capture strips; geometry-specific
			// tests override the size/scale type rather than relying on FIT_CENTER.
			setScaleType(ScaleType.FIT_XY);
			setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
		}

		void setOriginal(Drawable drawable){
			original=drawable;
			super.setImageDrawable(drawable);
		}

		@Override
		public void requestLayout(){
			layoutRequests++;
			super.requestLayout();
		}

		@Override
		public void setImageDrawable(Drawable drawable){
			if(checkPlaceholderDimensions && drawable!=original){
				assertNotNull(drawable);
				assertEquals(original.getIntrinsicWidth(), drawable.getIntrinsicWidth());
				assertEquals(original.getIntrinsicHeight(), drawable.getIntrinsicHeight());
				assertEquals(original.getMinimumWidth(), drawable.getMinimumWidth());
				assertEquals(original.getMinimumHeight(), drawable.getMinimumHeight());
				assertEquals(original.getBounds(), drawable.getBounds());
			}
			boolean restoring=replaced && drawable==original;
			if(drawable!=original)
				replaced=true;
			super.setImageDrawable(drawable);
			if(!restoring && drawable!=original && failReplace)
				throw new IllegalStateException("setter failed after replacement");
			if(restoring){
				restores++;
				if(failRestore)
					throw new IllegalStateException("setter failed while restoring");
			}
		}
	}

	private static void raise(Throwable error){
		if(error instanceof RuntimeException runtime)
			throw runtime;
		if(error instanceof Error fatal)
			throw fatal;
		throw new AssertionError("Unexpected test failure type", error);
	}
}
