package org.joinmastodon.android.ui.views;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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

	// Predicate and main-thread notification behavior belong to the helper's own tests.
	// Only the capability gate is simulated here; capture uses real native software canvases.
	@Implements(LiquidGlassCompatibility.class)
	public static class CompatibilityShadow{
		static boolean supported;
		static int reports;
		static Throwable failure;

		@Implementation
		protected static boolean isSupported(){
			return supported;
		}

		@Implementation
		protected static void reportFailure(String operation, Throwable error){
			assertEquals("backdrop capture", operation);
			reports++;
			failure=error;
			supported=false;
		}
	}

	@Before
	public void setUp(){
		context=RuntimeEnvironment.getApplication();
		CompatibilityShadow.supported=true;
		CompatibilityShadow.reports=0;
		CompatibilityShadow.failure=null;
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
	public void customChildSoftwareDrawFailuresStopCaptureWithoutBreakingNormalUi() throws Exception{
		Throwable[] errors={new IllegalArgumentException("software draw"),
				new NoClassDefFoundError("software effect"), new OutOfMemoryError("software draw")};
		for(Throwable error:errors){
			CompatibilityShadow.supported=true;
			Harness view=new Harness(context);
			Bands child=new Bands(context);
			child.softwareFailure=error;
			view.addView(child);
			layout(view, 20, 40);
			view.setCaptureHeights(7, 7);
			int[] calls={0};
			view.setCaptureListener((top, bottom)->calls[0]++);

			view.render(acceleratedCanvas(20, 40));

			assertSame(error, CompatibilityShadow.failure);
			assertEquals(0, calls[0]);
			assertEquals(1, child.softwareDraws);
			assertStopped(view);
			view.render(acceleratedCanvas(20, 40));
			assertEquals(2, child.hardwareDraws);
			assertEquals(1, child.softwareDraws);
		}
		assertEquals(3, CompatibilityShadow.reports);
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
		assertEquals(0, CompatibilityShadow.reports);
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

		view.render(acceleratedCanvas(20, 40));

		assertEquals(2, view.copies);
		assertEquals(1, first.restores);
		assertSame(firstOriginal, first.getDrawable());
		assertSame(secondOriginal, second.getDrawable());
		assertEquals(0, calls[0]);
		assertSame(view.copyFailure, CompatibilityShadow.failure);
		assertStopped(view);
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

		view.render(acceleratedCanvas(20, 40));

		assertEquals(1, first.restores);
		assertEquals(1, second.restores);
		assertSame(first.original, first.getDrawable());
		assertSame(second.original, second.getDrawable());
		assertEquals(0, calls[0]);
		assertTrue(CompatibilityShadow.failure instanceof IllegalStateException);
		assertStopped(view);
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

		view.render(acceleratedCanvas(20, 40));

		assertSame(first.original, first.getDrawable());
		assertSame(second.original, second.getDrawable());
		assertEquals(1, first.restores);
		assertEquals(1, second.restores);
		assertStopped(view);
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
		assertTrue(((List<?>)field(view, "restoreDrawables")).isEmpty());
		assertFalse((boolean)field(view, "capturing"));
		assertEquals(0, CompatibilityShadow.reports);
	}

	@Test
	public void nullSoftwareCopyFailsCleanlyAndRestoresEarlierReplacement() throws Exception{
		Harness view=new Harness(context);
		TrackingImage first=image(view);
		image(view);
		view.nullCopyAt=2;
		layout(view, 20, 40);
		view.setCaptureHeights(7, 7);
		view.setCaptureListener((top, bottom)->fail("Null copy must not be delivered"));

		view.render(acceleratedCanvas(20, 40));

		assertSame(first.original, first.getDrawable());
		assertEquals(1, first.restores);
		assertTrue(CompatibilityShadow.failure.getMessage().contains("returned null"));
		assertStopped(view);
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
		assertEquals(0, CompatibilityShadow.reports);
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
		assertEquals(0, CompatibilityShadow.reports);
	}

	@Test
	public void listenerFailureDropsOwnRefsButDoesNotRecycleDeliveredBitmap() throws Exception{
		Harness view=new Harness(context);
		view.addView(new Bands(context));
		layout(view, 20, 40);
		view.setCaptureHeights(7, 7);
		Bitmap[] delivered=new Bitmap[1];
		view.setCaptureListener((top, bottom)->{
			delivered[0]=top;
			throw new OutOfMemoryError("Compose consumer");
		});

		view.render(acceleratedCanvas(20, 40));

		assertNotNull(delivered[0]);
		assertFalse(delivered[0].isRecycled());
		assertEquals(Color.RED, delivered[0].getPixel(10, 1));
		assertStopped(view);
		assertTrue(CompatibilityShadow.failure instanceof OutOfMemoryError);
	}

	@Test
	public void softwareWindowCanvasAndUnsupportedSessionDoNotStartCapture() throws Exception{
		Harness view=new Harness(context);
		Bands child=new Bands(context);
		view.addView(child);
		layout(view, 20, 40);
		view.setCaptureHeights(7, 7);
		view.setCaptureListener((top, bottom)->fail("Software window must not capture"));
		view.render(new Canvas(Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888)));
		assertEquals(1, child.softwareDraws); // ordinary UI pass only
		assertStopped(view);
		assertEquals(1, CompatibilityShadow.reports);

		view.setCaptureListener((top, bottom)->fail("Disabled session must not capture"));
		view.render(acceleratedCanvas(20, 40));
		assertEquals(1, child.softwareDraws);
		assertStopped(view);
		assertEquals(1, CompatibilityShadow.reports);
	}

	@Test
	public void oversizedAndExtremeBoundsFailBeforeAllocatingCaptureBuffers() throws Exception{
		int[][] sizes={{2048, 4096}, {Integer.MAX_VALUE, Integer.MAX_VALUE}};
		for(int[] size:sizes){
			CompatibilityShadow.supported=true;
			Harness view=new Harness(context);
			view.layout(0, 0, size[0], size[1]);
			view.setCaptureHeights(Integer.MAX_VALUE, Integer.MAX_VALUE);
			view.setCaptureListener((top, bottom)->fail("Over-budget capture must not deliver"));

			view.render(acceleratedCanvas(1, 1));

			assertTrue(CompatibilityShadow.failure.getMessage().contains("16 MiB"));
			assertStopped(view);
		}
		assertEquals(2, CompatibilityShadow.reports);
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
		assertTrue(CompatibilityShadow.failure.getMessage().contains("software copy"));
		assertStopped(view);
	}

	@Test
	public void ordinaryDispatchDrawFailureIsNotMaskedOrReported() throws Exception{
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
		assertEquals(0, CompatibilityShadow.reports);
		assertEquals(0, child.softwareDraws);
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
		int copies, copyFailureAt, nullCopyAt, invalidations;
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
