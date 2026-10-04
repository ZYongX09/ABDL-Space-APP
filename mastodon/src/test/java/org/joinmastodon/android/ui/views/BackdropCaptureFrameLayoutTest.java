package org.joinmastodon.android.ui.views;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

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
		view.setCaptureListener((top, bottom)->fail("Software window must not capture"));
		view.render(new Canvas(Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888)));
		assertEquals(1, child.softwareDraws); // ordinary UI pass only
		assertStopped(view);
		assertTrue(CompatibilityShadow.supported);

		CompatibilityShadow.supported=false;
		view.setCaptureListener((top, bottom)->fail("Unsupported SDK must not capture"));
		view.render(acceleratedCanvas(20, 40));
		assertEquals(1, child.softwareDraws);
		assertStopped(view);

		CompatibilityShadow.supported=true;
		int[] calls={0};
		view.setCaptureListener((top, bottom)->calls[0]++);
		view.render(acceleratedCanvas(20, 40));
		assertEquals(1, calls[0]);
		assertEquals(2, child.softwareDraws);
	}

	@Test
	public void oversizedAndExtremeBoundsStopLocallyBeforeAllocatingCaptureBuffers() throws Exception{
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
			view.setCaptureListener((top, bottom)->fail("Over-budget or extreme capture must not deliver"));

			view.render(acceleratedCanvas(1, 1));

			assertEquals(0, view.copies);
			assertStopped(view);
			assertTrue(CompatibilityShadow.supported);
			layout(view, 20, 40);
			view.setCaptureHeights(7, 7);
			int[] calls={0};
			view.setCaptureListener((top, bottom)->calls[0]++);
			view.render(acceleratedCanvas(20, 40));
			assertEquals(1, calls[0]);
			assertWithinBudget(view);
		}
		assertTrue(CompatibilityShadow.supported);
	}

	@Test
	public void captureDoesNotDownsampleBeyondTheBoundEvenWhenEighthSamplingWouldFit() throws Exception{
		Harness view=new Harness(context);
		view.layout(0, 0, 1440, 3200);
		view.setCaptureHeights(2000, 850);
		view.setCaptureListener((top, bottom)->fail("Capture cannot shrink without bound"));
		long outputBytes=1440L*(2000+850)*4;
		assertTrue(outputBytes+360L*800*2>BackdropCaptureFrameLayout.CAPTURE_MEMORY_BUDGET_BYTES);
		assertTrue(outputBytes+180L*400*2<BackdropCaptureFrameLayout.CAPTURE_MEMORY_BUDGET_BYTES);

		view.render(acceleratedCanvas(1, 1));

		assertTrue(CompatibilityShadow.supported);
		assertStopped(view);
	}

	@Test
	public void softwareCopiesCountAgainstBudgetBeforeCopying() throws Exception{
		Harness view=new Harness(context);
		TrackingImage image=new TrackingImage(context);
		// The output strips and shared buffer together consume the complete 16 MiB budget.
		view.addView(image);
		view.forceCopies=true;
		image.setOriginal(new BitmapDrawable(context.getResources(), Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)));
		layout(view, 1024, 4096);
		view.setCaptureHeights(1024, 1024);
		view.setCaptureListener((top, bottom)->fail("Copy cannot exceed budget"));

		view.render(acceleratedCanvas(1, 1));

		assertEquals(0, view.copies);
		assertSame(image.original, image.getDrawable());
		assertStopped(view);
		assertTrue(CompatibilityShadow.supported);
	}

	@Test
	public void downsamplingCannotBypassTheBudgetForANewSoftwareCopy() throws Exception{
		Harness view=new Harness(context);
		TrackingImage first=image(view);
		TrackingImage second=new TrackingImage(context);
		second.setOriginal(new BitmapDrawable(context.getResources(), Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)));
		view.addView(second);
		layout(view, 1440, 3200);
		view.setCaptureHeights(2000, 300);
		view.setCaptureListener((top, bottom)->fail("New copy cannot overrun downsampled capture budget"));

		view.render(acceleratedCanvas(1, 1));

		assertEquals(1, view.copies); // The large second copy must be rejected before allocation.
		assertEquals(1, first.restores);
		assertSame(first.original, first.getDrawable());
		assertSame(second.original, second.getDrawable());
		assertStopped(view);
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
		view.setCaptureListener((top, bottom)->fail("Actual copy allocation cannot overrun the budget"));

		view.render(acceleratedCanvas(1, 1));

		assertEquals(2, view.copies);
		assertSame(first.original, first.getDrawable());
		assertSame(second.original, second.getDrawable());
		assertEquals(1, first.restores);
		assertStopped(view);
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
		int copies, copyFailureAt, nullCopyAt, oversizedCopyAt, invalidations;
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
		boolean replaced, failRestore, failReplace;
		int restores;

		TrackingImage(Context context){
			super(context);
			setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
		}

		void setOriginal(Drawable drawable){
			original=drawable;
			super.setImageDrawable(drawable);
		}

		@Override
		public void setImageDrawable(Drawable drawable){
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
