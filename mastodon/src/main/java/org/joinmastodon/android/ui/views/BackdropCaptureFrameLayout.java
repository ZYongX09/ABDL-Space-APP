package org.joinmastodon.android.ui.views;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.PorterDuff;
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

	// Keep the existing full-height RGB565 sampling layout, but fall back to classic
	// above 16 MiB for our intermediate, output strips and cached software copies.
	// This bounds our retained buffers, not bitmaps still held by Compose consumers.
	static final long CAPTURE_MEMORY_BUDGET_BYTES=16L*1024*1024;

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
		int actualTopHeight=Math.min(topCaptureHeight, getHeight());
		int actualBottomHeight=Math.min(bottomCaptureHeight, getHeight());
		int sharedHeight=actualTopHeight>0 ? getHeight() : actualBottomHeight;
		Bitmap.Config sharedConfig=actualTopHeight>0 ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
		Throwable failure=null;
		capturing=true;
		try{
			updateSoftwareBitmapCacheBytes();
			// Divide before multiplying by width so even extreme view dimensions cannot overflow.
			long bytesPerColumn=(long)sharedHeight*(actualTopHeight>0 ? 2 : 4)
					+(long)actualTopHeight*4+(long)actualBottomHeight*4;
			if(getWidth()>CAPTURE_MEMORY_BUDGET_BYTES/bytesPerColumn)
				throw new IllegalStateException("Backdrop capture exceeds the 16 MiB bitmap budget");
			long requiredBytes=getWidth()*bytesPerColumn;
			if(requiredBytes>CAPTURE_MEMORY_BUDGET_BYTES-softwareBitmapCacheBytes)
				throw new IllegalStateException("Backdrop capture exceeds the 16 MiB bitmap budget");

			if(captureBitmap==null || captureBitmap.getWidth()!=getWidth() || captureBitmap.getHeight()!=sharedHeight || captureBitmap.getConfig()!=sharedConfig
					|| (actualTopHeight>0 && (topCaptureBitmap==null || topCaptureBitmap.getHeight()!=actualTopHeight))
					|| (actualBottomHeight>0 && (bottomCaptureBitmap==null || bottomCaptureBitmap.getHeight()!=actualBottomHeight))){
				captureBitmap=null;
				topCaptureBitmap=null;
				bottomCaptureBitmap=null;
				captureBufferBytes=requiredBytes;
				captureBitmap=Bitmap.createBitmap(getWidth(), sharedHeight, sharedConfig);
				if(actualTopHeight>0)
					topCaptureBitmap=Bitmap.createBitmap(getWidth(), actualTopHeight, Bitmap.Config.ARGB_8888);
				if(actualBottomHeight>0)
					bottomCaptureBitmap=Bitmap.createBitmap(getWidth(), actualBottomHeight, Bitmap.Config.ARGB_8888);
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
				if(actualTopHeight>0){
					Path capturePath=new Path();
					capturePath.addRect(0, 0, getWidth(), actualTopHeight, Path.Direction.CW);
					if(actualBottomHeight>0)
						capturePath.addRect(0, getHeight()-actualBottomHeight, getWidth(), getHeight(), Path.Direction.CW);
					captureCanvas.clipPath(capturePath);
				}else{
					captureCanvas.translate(0, -(getHeight()-actualBottomHeight));
				}
				super.dispatchDraw(captureCanvas);
				captureCanvas.restore();
				if(actualTopHeight>0){
					Canvas topCanvas=new Canvas(top);
					topCanvas.drawColor(0, PorterDuff.Mode.CLEAR);
					topCanvas.drawBitmap(shared, 0, 0, null);
				}
				if(actualBottomHeight>0){
					Canvas bottomCanvas=new Canvas(bottom);
					bottomCanvas.drawColor(0, PorterDuff.Mode.CLEAR);
					bottomCanvas.drawBitmap(shared, 0, actualTopHeight>0 ? -(getHeight()-actualBottomHeight) : 0, null);
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
