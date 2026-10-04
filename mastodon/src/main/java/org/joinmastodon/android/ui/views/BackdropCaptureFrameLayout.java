package org.joinmastodon.android.ui.views;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
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
import java.util.WeakHashMap;

public class BackdropCaptureFrameLayout extends FrameLayout{
	public interface CaptureListener{
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
		cancelCaptureFrame();
		releaseCaptureResources();
		// An in-flight pass still owns its restoration queue and capturing flag.
	}

	private void failCapture(Throwable error){
		stopCapture();
		LiquidGlassCompatibility.reportFailure("backdrop capture", error);
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
		if(!canvas.isHardwareAccelerated()){
			failCapture(new IllegalStateException("Backdrop capture requires a hardware-accelerated window canvas"));
			return;
		}
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
				throw new IllegalStateException("Backdrop capture exceeds safe dimensions for the 16 MiB bitmap budget");
			updateSoftwareBitmapCacheBytes();
			long available=CAPTURE_MEMORY_BUDGET_BYTES-softwareBitmapCacheBytes;
			// Outputs must retain their root-pixel size: setBackdropBitmap has no scale metadata.
			// Divide before multiplying, and reserve cached software copies before choosing a scale.
			long outputBytesPerColumn=((long)actualTopHeight+actualBottomHeight)*4;
			if(available<=0 || width>available/outputBytesPerColumn)
				throw new IllegalStateException("Backdrop capture exceeds the 16 MiB bitmap budget");
			long outputBytes=width*outputBytesPerColumn;
			long sharedBudget=available-outputBytes;
			int downsample=1, sharedWidth=0, sampledHeight=0;
			long requiredBytes=0;
			// At most half or quarter resolution, not an unbounded shrink-until-it-fits loop.
			for(;downsample<=MAX_CAPTURE_DOWNSAMPLE;downsample*=2){
				int candidateWidth=(width+downsample-1)/downsample;
				int candidateHeight=(sharedHeight+downsample-1)/downsample;
				long sharedBytesPerColumn=(long)candidateHeight*(actualTopHeight>0 ? 2 : 4);
				if(candidateWidth<=sharedBudget/sharedBytesPerColumn){
					sharedWidth=candidateWidth;
					sampledHeight=candidateHeight;
					requiredBytes=outputBytes+candidateWidth*sharedBytesPerColumn;
					break;
				}
			}
			if(sharedWidth==0)
				throw new IllegalStateException("Backdrop capture exceeds the 16 MiB bitmap budget at bounded downsampling");

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
				throw new IllegalStateException("Backdrop capture exceeds the 16 MiB bitmap budget");
			// Local references survive reentrant disabling/height changes without reviving our fields.
			Bitmap shared=captureBitmap, top=topCaptureBitmap, bottom=bottomCaptureBitmap;
			// ALL conversion must be inside the restoration boundary: a later child may fail.
			replaceHardwareBitmaps(this);
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
		}catch(RuntimeException | LinkageError | OutOfMemoryError error){
			failure=error;
		}finally{
			try{
				Throwable restoreFailure=restoreHardwareBitmaps();
				if(failure==null)
					failure=restoreFailure;
			}finally{
				capturing=false;
			}
		}
		if(failure!=null){
			failCapture(failure);
			return;
		}
		if(generation!=captureGeneration){
			releaseCaptureResources();
			return;
		}
		if(!LiquidGlassCompatibility.isSupported()){
			stopCapture();
			return;
		}
		try{
			listener.onCaptured(actualTopHeight>0 ? topCaptureBitmap : null, actualBottomHeight>0 ? bottomCaptureBitmap : null);
		}catch(RuntimeException | LinkageError | OutOfMemoryError error){
			failCapture(error);
		}
	}

	@Override
	protected void onDetachedFromWindow(){
		stopCapture();
		super.onDetachedFromWindow();
	}

	private void replaceHardwareBitmaps(View view){
		if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O && view instanceof ImageView imageView){
			Drawable drawable=imageView.getDrawable();
			if(drawable instanceof BlurhashCrossfadeDrawable crossfadeDrawable){
				Drawable imageDrawable=crossfadeDrawable.getImageDrawable();
				Drawable replacement=getSoftwareDrawable(imageDrawable);
				if(replacement!=imageDrawable){
					restoreDrawables.add(()->crossfadeDrawable.setImageDrawable(imageDrawable));
					crossfadeDrawable.setImageDrawable(replacement);
				}
			}else{
				Drawable replacement=getSoftwareDrawable(drawable);
				if(replacement!=drawable){
					restoreDrawables.add(()->imageView.setImageDrawable(drawable));
					imageView.setImageDrawable(replacement);
				}
			}
		}
		if(view instanceof ViewGroup group){
			for(int i=0;i<group.getChildCount();i++)
				replaceHardwareBitmaps(group.getChildAt(i));
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
				throw new IllegalStateException("Backdrop software copy exceeds the 16 MiB bitmap budget");
			softwareBitmap=copyHardwareBitmap(bitmap);
			if(softwareBitmap==null)
				throw new IllegalStateException("Hardware bitmap software copy returned null");
			long allocatedBytes=softwareBitmap.getAllocationByteCount();
			if(allocatedBytes>available)
				throw new IllegalStateException("Backdrop software copy exceeds the 16 MiB bitmap budget");
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
				}catch(RuntimeException | LinkageError | OutOfMemoryError error){
					// One broken setter must not strand the other children on software drawables.
					if(failure==null)
						failure=error;
				}
			}
		}finally{
			restoreDrawables.clear();
		}
		return failure;
	}
}
