package org.joinmastodon.android.ui.views;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.joinmastodon.android.ui.drawables.BlurhashCrossfadeDrawable;
import org.joinmastodon.android.ui.utils.LiquidGlassCompatibility;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.WeakHashMap;

public class BackdropCaptureFrameLayout extends FrameLayout{
	public interface CaptureListener{
		// Either strip may be null. A budget pause delivers (null, null) once to clear
		// stale consumer images, but keeps this registration for the next real draw.
		void onCaptured(Bitmap top, Bitmap bottom);
	}

	// Bound the intermediate, native-size output strips and cached software copies together.
	// Consumers draw at bitmap pixel size, so only the intermediate may be downsampled.
	// This bounds our retained buffers, not bitmaps still held by Compose consumers.
	static final long CAPTURE_MEMORY_BUDGET_BYTES=16L*1024*1024;
	static final int MAX_CAPTURE_DOWNSAMPLE=4;
	// Also reject enormous, skinny views that fit the byte budget but are unsafe to resample.
	static final int MAX_CAPTURE_DIMENSION=8192;

	private int topCaptureHeight;
	private int bottomCaptureHeight;
	private Bitmap captureBitmap;
	private Bitmap topCaptureBitmap;
	private Bitmap bottomCaptureBitmap;
	private CaptureListener captureListener;
	private boolean capturing;
	private boolean budgetPaused;
	private boolean captureFrameScheduled;
	private int captureGeneration;
	private long captureBufferBytes;
	private long softwareBitmapCacheBytes;
	private final WeakHashMap<Bitmap, Bitmap> softwareBitmapCache=new WeakHashMap<>();
	private final ArrayList<Runnable> restoreDrawables=new ArrayList<>();
	private final Runnable captureFrameRunnable=()->{
		captureFrameScheduled=false;
		if(captureListener==null || capturing || !isAttachedToWindow())
			return;
		if(!LiquidGlassCompatibility.isSupported()){
			stopCapture();
			return;
		}
		invalidate();
	};

	public BackdropCaptureFrameLayout(Context context){
		super(context);
	}

	public BackdropCaptureFrameLayout(Context context, AttributeSet attrs){
		super(context, attrs);
	}

	public void setCaptureHeights(int topCaptureHeight, int bottomCaptureHeight){
		int newTopHeight=Math.max(0, topCaptureHeight);
		int newBottomHeight=Math.max(0, bottomCaptureHeight);
		if(this.topCaptureHeight==newTopHeight && this.bottomCaptureHeight==newBottomHeight)
			return;
		this.topCaptureHeight=newTopHeight;
		this.bottomCaptureHeight=newBottomHeight;
		captureGeneration++;
		releaseCaptureResources();
		invalidate();
	}

	public void setCaptureListener(CaptureListener captureListener){
		cancelCaptureFrame();
		captureGeneration++;
		this.captureListener=captureListener;
		budgetPaused=false;
		if(captureListener==null || !LiquidGlassCompatibility.isSupported()){
			stopCapture();
			return;
		}
		scheduleCaptureFrame();
	}

	@Override
	public void onDescendantInvalidated(View child, View target){
		super.onDescendantInvalidated(child, target);
		scheduleCaptureFrame();
	}

	private void scheduleCaptureFrame(){
		if(capturing || captureFrameScheduled || captureListener==null || !isAttachedToWindow())
			return;
		if(!LiquidGlassCompatibility.isSupported()){
			stopCapture();
			return;
		}
		captureFrameScheduled=true;
		postOnAnimation(captureFrameRunnable);
	}

	private void cancelCaptureFrame(){
		removeCallbacks(captureFrameRunnable);
		captureFrameScheduled=false;
	}

	private void releaseCaptureResources(){
		// Never recycle: a delivered bitmap may still be referenced by Compose.
		captureBitmap=null;
		topCaptureBitmap=null;
		bottomCaptureBitmap=null;
		captureBufferBytes=0;
		softwareBitmapCache.clear();
		softwareBitmapCacheBytes=0;
	}

	private void stopCapture(){
		captureGeneration++;
		captureListener=null;
		budgetPaused=false;
		cancelCaptureFrame();
		releaseCaptureResources();
		// An in-flight pass still owns its restoration queue and capturing flag.
	}

	private void pauseCapture(){
		cancelCaptureFrame();
		releaseCaptureResources();
		if(budgetPaused || captureListener==null)
			return;
		budgetPaused=true;
		// A consumer clearing its image can invalidate synchronously. Do not turn that
		// notification (or our temporary drawable changes) into a self-sustaining retry.
		capturing=true;
		try{
			captureListener.onCaptured(null, null);
		}finally{
			capturing=false;
		}
		// No timer: layout, capture-height changes and real content invalidation retry.
	}

	// Only our explicit allocation limits pause this capture, never the graphics session.
	private static class CaptureBudgetExceededException extends RuntimeException{
		CaptureBudgetExceededException(String message){
			super(message);
		}
	}

	private static void rethrow(Throwable error){
		if(error instanceof Error fatal)
			throw fatal;
		throw (RuntimeException)error;
	}

	@Override
	protected void dispatchDraw(Canvas canvas){
		cancelCaptureFrame();
		// Ordinary UI drawing failures must propagate, not be classified as capture failures.
		super.dispatchDraw(canvas);
		if(capturing || captureListener==null)
			return;
		if(!isAttachedToWindow() || !LiquidGlassCompatibility.isSupported()){
			stopCapture();
			return;
		}
		// A one-off software snapshot does not describe the actual window renderer.
		// Keep both the callback and last delivered buffers for its next hardware draw.
		if(!canvas.isHardwareAccelerated())
			return;
		if((topCaptureHeight<=0 && bottomCaptureHeight<=0) || getWidth()<=0 || getHeight()<=0)
			return;

		int generation=captureGeneration;
		CaptureListener listener=captureListener;
		int width=getWidth(), height=getHeight();
		int actualTopHeight=Math.min(topCaptureHeight, height);
		int actualBottomHeight=Math.min(bottomCaptureHeight, height);
		int sharedHeight=actualTopHeight>0 ? height : actualBottomHeight;
		Bitmap.Config sharedConfig=actualTopHeight>0 ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
		Throwable failure=null;
		capturing=true;
		try{
			if(width>MAX_CAPTURE_DIMENSION || height>MAX_CAPTURE_DIMENSION)
				throw new CaptureBudgetExceededException("Backdrop capture exceeds safe dimensions for the 16 MiB bitmap budget");
			// Plan the entire pass before allocating: native strips, intermediate and all
			// new copies (deduplicated by source identity), not just yesterday's cache.
			long outputBytes=(long)width*((long)actualTopHeight+actualBottomHeight)*4;
			int downsample=1, sharedWidth=0, sampledHeight=0;
			long requiredBytes=0;
			ArrayList<ImageView> images=new ArrayList<>();
			collectHardwareImages(this, images);
			IdentityHashMap<Bitmap, Boolean> needed=new IdentityHashMap<>();
			IdentityHashMap<ImageView, Boolean> intersecting=new IdentityHashMap<>();
			for(;downsample<=MAX_CAPTURE_DOWNSAMPLE;downsample*=2){
				needed.clear();
				intersecting.clear();
				int guard=actualTopHeight>0 && downsample>1 ? 2*downsample : 0;
				for(ImageView image:images){
					if(intersectsCapture(image, actualTopHeight, actualBottomHeight, guard)){
						intersecting.put(image, Boolean.TRUE);
						needed.put(((BitmapDrawable)hardwareDrawable(image)).getBitmap(), Boolean.TRUE);
					}
				}
				long copyBytes=0;
				for(Bitmap source:needed.keySet()){
					Bitmap cached=softwareBitmapCache.get(source);
					copyBytes+=cached!=null && !cached.isRecycled() ? cached.getAllocationByteCount()
							: (long)source.getWidth()*source.getHeight()*4;
				}
				int candidateWidth=(width+downsample-1)/downsample;
				int candidateHeight=(sharedHeight+downsample-1)/downsample;
				long candidateBytes=outputBytes+(long)candidateWidth*candidateHeight*(actualTopHeight>0 ? 2 : 4);
				if(candidateBytes<=CAPTURE_MEMORY_BUDGET_BYTES-copyBytes){
					sharedWidth=candidateWidth;
					sampledHeight=candidateHeight;
					requiredBytes=candidateBytes;
					break;
				}
			}
			if(sharedWidth==0)
				throw new CaptureBudgetExceededException("Backdrop capture exceeds the 16 MiB bitmap budget at bounded downsampling");
			// Unrelated old cache entries are evictable, not part of this frame's plan.
			softwareBitmapCache.keySet().removeIf(source->!needed.containsKey(source));
			updateSoftwareBitmapCacheBytes();

			if(captureBitmap==null || captureBitmap.getWidth()!=sharedWidth || captureBitmap.getHeight()!=sampledHeight || captureBitmap.getConfig()!=sharedConfig
					|| (actualTopHeight>0 && (topCaptureBitmap==null || topCaptureBitmap.getWidth()!=width || topCaptureBitmap.getHeight()!=actualTopHeight))
					|| (actualBottomHeight>0 && (bottomCaptureBitmap==null || bottomCaptureBitmap.getWidth()!=width || bottomCaptureBitmap.getHeight()!=actualBottomHeight))){
				captureBitmap=null;
				topCaptureBitmap=null;
				bottomCaptureBitmap=null;
				captureBufferBytes=requiredBytes;
				captureBitmap=Bitmap.createBitmap(sharedWidth, sampledHeight, sharedConfig);
				if(actualTopHeight>0)
					topCaptureBitmap=Bitmap.createBitmap(width, actualTopHeight, Bitmap.Config.ARGB_8888);
				if(actualBottomHeight>0)
					bottomCaptureBitmap=Bitmap.createBitmap(width, actualBottomHeight, Bitmap.Config.ARGB_8888);
			}
			captureBufferBytes=(long)captureBitmap.getAllocationByteCount()
					+(topCaptureBitmap==null ? 0 : topCaptureBitmap.getAllocationByteCount())
					+(bottomCaptureBitmap==null ? 0 : bottomCaptureBitmap.getAllocationByteCount());
			if(captureBufferBytes>CAPTURE_MEMORY_BUDGET_BYTES-softwareBitmapCacheBytes)
				throw new CaptureBudgetExceededException("Backdrop capture exceeds the 16 MiB bitmap budget");
			// Local references survive reentrant disabling/height changes without reviving our fields.
			Bitmap shared=captureBitmap, top=topCaptureBitmap, bottom=bottomCaptureBitmap;
			// ALL conversion must be inside the restoration boundary: a later child may fail.
			replaceHardwareBitmaps(images, intersecting);
			if(generation==captureGeneration){
				Canvas captureCanvas=new Canvas(shared);
				captureCanvas.drawColor(0, PorterDuff.Mode.CLEAR);
				captureCanvas.save();
				// Use the actual rounded dimensions, and the inverse mapping when drawing strips.
				// Scale before translating the bottom-only pass so its origin remains root-relative.
				captureCanvas.scale(sharedWidth/(float)width, sampledHeight/(float)sharedHeight);
				if(actualTopHeight>0){
					Path capturePath=new Path();
					// Bilinear filtering needs both source pixels fully painted. With rounded scale
					// a one-pixel halo can still cut the outer pixel's coverage; reserve two.
					int guard=downsample>1 ? 2*downsample : 0;
					capturePath.addRect(0, 0, width, Math.min(height, actualTopHeight+guard), Path.Direction.CW);
					if(actualBottomHeight>0)
						capturePath.addRect(0, Math.max(0, height-actualBottomHeight-guard), width, height, Path.Direction.CW);
					captureCanvas.clipPath(capturePath);
				}else{
					captureCanvas.translate(0, -(height-actualBottomHeight));
				}
				super.dispatchDraw(captureCanvas);
				captureCanvas.restore();
				Paint resamplePaint=downsample>1 ? new Paint(Paint.FILTER_BITMAP_FLAG) : null;
				if(actualTopHeight>0){
					Canvas topCanvas=new Canvas(top);
					topCanvas.drawColor(0, PorterDuff.Mode.CLEAR);
					if(downsample==1)
						topCanvas.drawBitmap(shared, 0, 0, null);
					else
						topCanvas.drawBitmap(shared, null, new RectF(0, 0, width, sharedHeight), resamplePaint);
				}
				if(actualBottomHeight>0){
					Canvas bottomCanvas=new Canvas(bottom);
					bottomCanvas.drawColor(0, PorterDuff.Mode.CLEAR);
					int offset=actualTopHeight>0 ? height-actualBottomHeight : 0;
					if(downsample==1)
						bottomCanvas.drawBitmap(shared, 0, -offset, null);
					else
						// Map the whole sampled image; no rounded crop or full-height upscaled copy.
						bottomCanvas.drawBitmap(shared, null, new RectF(0, -offset, width, sharedHeight-offset), resamplePaint);
				}
			}
		}catch(RuntimeException | Error error){
			failure=error;
		}finally{
			try{
				Throwable restoreFailure=restoreHardwareBitmaps();
				if(restoreFailure!=null){
					if(failure==null || failure instanceof CaptureBudgetExceededException)
						failure=restoreFailure;
					else if(failure!=restoreFailure)
						failure.addSuppressed(restoreFailure);
				}
			}finally{
				capturing=false;
			}
		}
		if(failure instanceof CaptureBudgetExceededException){
			// A reentrant disable/listener change owns the new generation; do not send
			// a stale pause notification to that registration.
			if(generation==captureGeneration)
				pauseCapture();
			else
				releaseCaptureResources();
			return;
		}
		if(failure!=null){
			releaseCaptureResources();
			rethrow(failure);
		}
		if(generation!=captureGeneration){
			releaseCaptureResources();
			return;
		}
		if(!LiquidGlassCompatibility.isSupported()){
			stopCapture();
			return;
		}
		budgetPaused=false;
		try{
			listener.onCaptured(actualTopHeight>0 ? topCaptureBitmap : null, actualBottomHeight>0 ? bottomCaptureBitmap : null);
		}catch(RuntimeException | Error error){
			releaseCaptureResources();
			throw error;
		}
	}

	@Override
	protected void onDetachedFromWindow(){
		stopCapture();
		super.onDetachedFromWindow();
	}

	private Drawable hardwareDrawable(ImageView image){
		Drawable drawable=image.getDrawable();
		if(drawable instanceof BlurhashCrossfadeDrawable crossfade)
			drawable=crossfade.getImageDrawable();
		return drawable;
	}

	private void collectHardwareImages(View view, ArrayList<ImageView> images){
		// A legacy visibility animation may still draw an otherwise hidden child.
		if(view.getVisibility()!=View.VISIBLE && view.getAnimation()==null)
			return;
		if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O && view instanceof ImageView image){
			Drawable drawable=hardwareDrawable(image);
			if(drawable instanceof BitmapDrawable bitmapDrawable && bitmapDrawable.getBitmap()!=null
					&& requiresSoftwareCopy(bitmapDrawable.getBitmap()))
				images.add(image);
		}
		if(view instanceof ViewGroup group){
			for(int i=0;i<group.getChildCount();i++)
				collectHardwareImages(group.getChildAt(i), images);
		}
	}

	private boolean intersectsCapture(ImageView image, int topHeight, int bottomHeight, int guard){
		// Work in host-local coordinates, never screen/window coordinates. Only prove
		// exclusion for ordinary geometry; transforms/animations/custom boundaries
		// that cannot be proven safe must keep conversion (software Canvas validates
		// hardware bitmap arguments even when the current clip misses the image).
		// Check the whole chain first: an ancestor transform can move an apparently
		// clipped/off-strip descendant back into view.
		for(View current=image;current!=this;){
			if(current.getAnimation()!=null || !current.getMatrix().isIdentity())
				return true;
			if(!(current.getParent() instanceof View parent))
				return true;
			current=parent;
		}
		Rect bounds=image.getDrawable().getBounds();
		if(bounds.isEmpty())
			return true;
		RectF area=new RectF(bounds);
		if(!image.getImageMatrix().isAffine())
			return true;
		image.getImageMatrix().mapRect(area);
		if(!Float.isFinite(area.left) || !Float.isFinite(area.top) || !Float.isFinite(area.right) || !Float.isFinite(area.bottom))
			return true;
		area.offset(image.getPaddingLeft()-image.getScrollX(), image.getPaddingTop()-image.getScrollY());
		if(image.getCropToPadding() && !area.intersect(image.getPaddingLeft(), image.getPaddingTop(),
				image.getWidth()-image.getPaddingRight(), image.getHeight()-image.getPaddingBottom()))
			return false;
		View child=image;
		while(child!=this){
			if(child.getAnimation()!=null || !child.getMatrix().isIdentity())
				return true;
			if(!(child.getParent() instanceof ViewGroup parent))
				return true;
			if(parent.getClipChildren() && !area.intersect(0, 0, child.getWidth(), child.getHeight()))
				return false;
			area.offset(child.getLeft()-parent.getScrollX(), child.getTop()-parent.getScrollY());
			// ViewGroup only clips to padding when its padding-not-null flag is set.
			// With zero padding and clipChildren=false, children may overflow the parent.
			if(parent.getClipToPadding() && (parent.getPaddingLeft()!=0 || parent.getPaddingTop()!=0
					|| parent.getPaddingRight()!=0 || parent.getPaddingBottom()!=0)
					&& !area.intersect(parent.getPaddingLeft(), parent.getPaddingTop(),
					parent.getWidth()-parent.getPaddingRight(), parent.getHeight()-parent.getPaddingBottom()))
				return false;
			child=parent;
		}
		return (topHeight>0 && RectF.intersects(area, new RectF(0, 0, getWidth(), Math.min(getHeight(), topHeight+guard))))
				|| (bottomHeight>0 && RectF.intersects(area, new RectF(0, Math.max(0, getHeight()-bottomHeight-guard), getWidth(), getHeight())));
	}

	private void replaceHardwareBitmaps(ArrayList<ImageView> images, IdentityHashMap<ImageView, Boolean> intersecting){
		for(ImageView image:images){
			Drawable drawable=image.getDrawable();
			Drawable source=hardwareDrawable(image);
			// Never submit off-strip hardware bitmap arguments to a software Canvas.
			// A null drawable would change ImageView's intrinsic dimensions and request
			// another framework layout on both replacement and restoration: use a no-op
			// with identical sizing instead, without suppressing legitimate requestLayout.
			Drawable replacement=intersecting.containsKey(image) ? getSoftwareDrawable(source) : new CapturePlaceholderDrawable(source);
			if(drawable instanceof BlurhashCrossfadeDrawable crossfade){
				restoreDrawables.add(()->crossfade.setImageDrawable(source));
				crossfade.setImageDrawable(replacement);
			}else{
				restoreDrawables.add(()->image.setImageDrawable(drawable));
				image.setImageDrawable(replacement);
			}
		}
	}

	private static class CapturePlaceholderDrawable extends Drawable{
		private final int intrinsicWidth, intrinsicHeight, minimumWidth, minimumHeight;

		CapturePlaceholderDrawable(Drawable source){
			intrinsicWidth=source.getIntrinsicWidth();
			intrinsicHeight=source.getIntrinsicHeight();
			minimumWidth=source.getMinimumWidth();
			minimumHeight=source.getMinimumHeight();
			setBounds(source.getBounds());
		}

		@Override
		public void draw(Canvas canvas){
			// Intentionally no bitmap draw, even on a software Canvas.
		}

		@Override
		public void setAlpha(int alpha){
		}

		@Override
		public void setColorFilter(ColorFilter colorFilter){
		}

		@Override
		public int getOpacity(){
			return PixelFormat.TRANSPARENT;
		}

		@Override
		public int getIntrinsicWidth(){
			return intrinsicWidth;
		}

		@Override
		public int getIntrinsicHeight(){
			return intrinsicHeight;
		}

		@Override
		public int getMinimumWidth(){
			return minimumWidth;
		}

		@Override
		public int getMinimumHeight(){
			return minimumHeight;
		}
	}

	// Package-private seams let Robolectric use software-backed hardware stand-ins;
	// traversal, cache accounting, bitmap copying, software drawing and restoration stay real.
	boolean requiresSoftwareCopy(Bitmap bitmap){
		return bitmap.getConfig()==Bitmap.Config.HARDWARE;
	}

	Bitmap copyHardwareBitmap(Bitmap bitmap){
		return bitmap.copy(Bitmap.Config.ARGB_8888, false);
	}

	private void updateSoftwareBitmapCacheBytes(){
		// UI-thread confined; iteration expunges weak keys so only retained copies count.
		long retainedBytes=0;
		for(var iterator=softwareBitmapCache.values().iterator();iterator.hasNext();){
			Bitmap bitmap=iterator.next();
			if(bitmap==null || bitmap.isRecycled())
				iterator.remove();
			else
				retainedBytes+=bitmap.getAllocationByteCount();
		}
		softwareBitmapCacheBytes=retainedBytes;
	}

	private Drawable getSoftwareDrawable(Drawable drawable){
		if(!(drawable instanceof BitmapDrawable bitmapDrawable))
			return drawable;
		Bitmap bitmap=bitmapDrawable.getBitmap();
		if(bitmap==null || !requiresSoftwareCopy(bitmap))
			return drawable;
		updateSoftwareBitmapCacheBytes();
		Bitmap softwareBitmap=softwareBitmapCache.get(bitmap);
		if(softwareBitmap==null || softwareBitmap.isRecycled()){
			long available=CAPTURE_MEMORY_BUDGET_BYTES-captureBufferBytes-softwareBitmapCacheBytes;
			if((long)bitmap.getWidth()*bitmap.getHeight()>available/4)
				throw new CaptureBudgetExceededException("Backdrop software copy exceeds the 16 MiB bitmap budget");
			softwareBitmap=copyHardwareBitmap(bitmap);
			if(softwareBitmap==null)
				throw new IllegalStateException("Hardware bitmap software copy returned null");
			long allocatedBytes=softwareBitmap.getAllocationByteCount();
			if(allocatedBytes>available)
				throw new CaptureBudgetExceededException("Backdrop software copy exceeds the 16 MiB bitmap budget");
			softwareBitmapCache.put(bitmap, softwareBitmap);
			softwareBitmapCacheBytes+=allocatedBytes;
		}
		return new BitmapDrawable(getResources(), softwareBitmap);
	}

	private Throwable restoreHardwareBitmaps(){
		Throwable failure=null;
		try{
			for(int i=restoreDrawables.size()-1;i>=0;i--){
				try{
					restoreDrawables.get(i).run();
				}catch(RuntimeException | Error error){
					// One broken setter must not strand the other children on software drawables.
					if(failure==null)
						failure=error;
					else if(failure!=error)
						failure.addSuppressed(error);
				}
			}
		}finally{
			restoreDrawables.clear();
		}
		return failure;
	}
}
