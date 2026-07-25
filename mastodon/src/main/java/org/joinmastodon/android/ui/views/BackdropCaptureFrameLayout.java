package org.joinmastodon.android.ui.views;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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
import java.util.function.Consumer;

public class BackdropCaptureFrameLayout extends FrameLayout{
	private int captureHeight;
	private Bitmap captureBitmap;
	private Consumer<Bitmap> captureListener;
	private boolean capturing;
	private final WeakHashMap<Bitmap, Bitmap> softwareBitmapCache=new WeakHashMap<>();
	private final ArrayList<Runnable> restoreDrawables=new ArrayList<>();

	public BackdropCaptureFrameLayout(Context context){
		super(context);
	}

	public BackdropCaptureFrameLayout(Context context, AttributeSet attrs){
		super(context, attrs);
	}

	public void setCaptureHeight(int captureHeight){
		int newHeight=Math.max(0, captureHeight);
		if(this.captureHeight==newHeight)
			return;
		this.captureHeight=newHeight;
		captureBitmap=null;
		invalidate();
	}

	public void setCaptureListener(Consumer<Bitmap> captureListener){
		this.captureListener=captureListener;
	}

	@Override
	protected void dispatchDraw(Canvas canvas){
		super.dispatchDraw(canvas);
		if(capturing || captureListener==null || captureHeight<=0 || getWidth()<=0 || getHeight()<=0)
			return;

		int actualHeight=Math.min(captureHeight, getHeight());
		if(captureBitmap==null || captureBitmap.getWidth()!=getWidth() || captureBitmap.getHeight()!=actualHeight)
			captureBitmap=Bitmap.createBitmap(getWidth(), actualHeight, Bitmap.Config.ARGB_8888);

		capturing=true;
		replaceHardwareBitmaps(this);
		try{
			Canvas captureCanvas=new Canvas(captureBitmap);
			captureCanvas.drawColor(0, PorterDuff.Mode.CLEAR);
			captureCanvas.save();
			captureCanvas.translate(0, -(getHeight()-actualHeight));
			super.dispatchDraw(captureCanvas);
			captureCanvas.restore();
		}finally{
			restoreHardwareBitmaps();
			capturing=false;
		}
		captureListener.accept(captureBitmap);
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
