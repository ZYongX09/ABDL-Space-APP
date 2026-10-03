package org.joinmastodon.android.ui.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import me.grishka.appkit.utils.V;

public class MediaCameraShutterView extends View{
	public interface Listener{
		void onTap();
		void onHoldStart();
		void onHoldEnd();
	}

	private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
	private Listener listener;
	private boolean holding;
	private boolean recording;
	private float progress;
	private final Runnable holdRunnable=()->{
		holding=true;
		if(listener!=null)
			listener.onHoldStart();
	};

	public MediaCameraShutterView(Context context){
		this(context, null);
	}

	public MediaCameraShutterView(Context context, AttributeSet attrs){
		super(context, attrs);
		setContentDescription("Camera shutter");
	}

	public void setListener(Listener listener){
		this.listener=listener;
	}

	public void setRecording(boolean recording){
		this.recording=recording;
		invalidate();
	}

	public void setProgress(float progress){
		this.progress=Math.max(0f, Math.min(1f, progress));
		invalidate();
	}

	@Override protected void onDraw(Canvas canvas){
		float cx=getWidth()/2f;
		float cy=getHeight()/2f;
		float radius=Math.min(getWidth(), getHeight())/2f-V.dp(5);
		paint.setStyle(Paint.Style.STROKE);
		paint.setStrokeWidth(V.dp(5));
		paint.setColor(Color.WHITE);
		canvas.drawCircle(cx, cy, radius, paint);
		paint.setStyle(Paint.Style.FILL);
		paint.setColor(recording ? 0xffef4444 : Color.WHITE);
		canvas.drawCircle(cx, cy, recording ? radius-V.dp(12) : radius-V.dp(6), paint);
		if(recording){
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(V.dp(4));
			paint.setStrokeCap(Paint.Cap.ROUND);
			paint.setColor(0xffef4444);
			canvas.drawArc(V.dp(2), V.dp(2), getWidth()-V.dp(2), getHeight()-V.dp(2), -90, 360*progress, false, paint);
		}
	}

	@Override public boolean onTouchEvent(MotionEvent event){
		switch(event.getActionMasked()){
			case MotionEvent.ACTION_DOWN -> {
				holding=false;
				postDelayed(holdRunnable, 350);
				setPressed(true);
				return true;
			}
			case MotionEvent.ACTION_UP -> {
				removeCallbacks(holdRunnable);
				setPressed(false);
				if(listener!=null){
					if(holding)
						listener.onHoldEnd();
					else
						listener.onTap();
				}
				holding=false;
				performClick();
				return true;
			}
			case MotionEvent.ACTION_CANCEL -> {
				removeCallbacks(holdRunnable);
				setPressed(false);
				if(holding && listener!=null)
					listener.onHoldEnd();
				holding=false;
				return true;
			}
		}
		return super.onTouchEvent(event);
	}

	@Override public boolean performClick(){
		super.performClick();
		return true;
	}
}
