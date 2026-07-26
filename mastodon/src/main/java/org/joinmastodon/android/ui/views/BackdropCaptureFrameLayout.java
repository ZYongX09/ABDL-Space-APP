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

import java.util.ArrayList;
import java.util.WeakHashMap;

public class BackdropCaptureFrameLayout extends FrameLayout{
	public interface CaptureListener{
		void onCaptured(Bitmap top, Bitmap bottom);
	}

	private int topCaptureHeight;
	private int bottomCaptureHeight;
	private Bitmap captureBitmap;
	private Bitmap topCaptureBitmap;
	private Bitmap bottomCaptureBitmap;
	private CaptureListener captureListener;
	private boolean capturing;
	private boolean captureFrameScheduled;
	private final WeakHashMap<Bitmap, Bitmap> softwareBitmapCache=new WeakHashMap<>();
	private final ArrayList<Runnable> restoreDrawables=new ArrayList<>();

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
		captureBitmap=null;
		topCaptureBitmap=null;
		bottomCaptureBitmap=null;
		invalidate();
	}

	public void setCaptureListener(CaptureListener captureListener){
		this.captureListener=captureListener;
		captureFrameScheduled=false;
		if(captureListener!=null)
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
		captureFrameScheduled=true;
		postInvalidateOnAnimation();
	}

	@Override
	protected void dispatchDraw(Canvas canvas){
		captureFrameScheduled=false;
		super.dispatchDraw(canvas);
		if(capturing || captureListener==null || (topCaptureHeight<=0 && bottomCaptureHeight<=0) || getWidth()<=0 || getHeight()<=0)
			return;

		int actualTopHeight=Math.min(topCaptureHeight, getHeight());
		int actualBottomHeight=Math.min(bottomCaptureHeight, getHeight());
		int sharedHeight=actualTopHeight>0 ? getHeight() : actualBottomHeight;
		Bitmap.Config sharedConfig=actualTopHeight>0 ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
		if(captureBitmap==null || captureBitmap.getWidth()!=getWidth() || captureBitmap.getHeight()!=sharedHeight || captureBitmap.getConfig()!=sharedConfig)
			captureBitmap=Bitmap.createBitmap(getWidth(), sharedHeight, sharedConfig);
		if(actualTopHeight>0 && (topCaptureBitmap==null || topCaptureBitmap.getWidth()!=getWidth() || topCaptureBitmap.getHeight()!=actualTopHeight))
			topCaptureBitmap=Bitmap.createBitmap(getWidth(), actualTopHeight, Bitmap.Config.ARGB_8888);
		if(actualBottomHeight>0 && (bottomCaptureBitmap==null || bottomCaptureBitmap.getWidth()!=getWidth() || bottomCaptureBitmap.getHeight()!=actualBottomHeight))
			bottomCaptureBitmap=Bitmap.createBitmap(getWidth(), actualBottomHeight, Bitmap.Config.ARGB_8888);

		capturing=true;
		replaceHardwareBitmaps(this);
		try{
			Canvas captureCanvas=new Canvas(captureBitmap);
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
				Canvas topCanvas=new Canvas(topCaptureBitmap);
				topCanvas.drawColor(0, PorterDuff.Mode.CLEAR);
				topCanvas.drawBitmap(captureBitmap, 0, 0, null);
			}
			if(actualBottomHeight>0){
				Canvas bottomCanvas=new Canvas(bottomCaptureBitmap);
				bottomCanvas.drawColor(0, PorterDuff.Mode.CLEAR);
				bottomCanvas.drawBitmap(captureBitmap, 0, actualTopHeight>0 ? -(getHeight()-actualBottomHeight) : 0, null);
			}
		}finally{
			restoreHardwareBitmaps();
			capturing=false;
		}
		captureListener.onCaptured(actualTopHeight>0 ? topCaptureBitmap : null, actualBottomHeight>0 ? bottomCaptureBitmap : null);
	}

	@Override
	protected void onDetachedFromWindow(){
		captureFrameScheduled=false;
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

	private Drawable getSoftwareDrawable(Drawable drawable){
		if(!(drawable instanceof BitmapDrawable bitmapDrawable))
			return drawable;
		Bitmap bitmap=bitmapDrawable.getBitmap();
		if(bitmap==null || bitmap.getConfig()!=Bitmap.Config.HARDWARE)
			return drawable;
		Bitmap softwareBitmap=softwareBitmapCache.get(bitmap);
		if(softwareBitmap==null || softwareBitmap.isRecycled()){
			softwareBitmap=bitmap.copy(Bitmap.Config.ARGB_8888, false);
			softwareBitmapCache.put(bitmap, softwareBitmap);
		}
		return new BitmapDrawable(getResources(), softwareBitmap);
	}

	private void restoreHardwareBitmaps(){
		for(int i=restoreDrawables.size()-1;i>=0;i--)
			restoreDrawables.get(i).run();
		restoreDrawables.clear();
	}
}
