package org.joinmastodon.android.sponsors;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Matrix;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.utils.UiUtils;

import me.grishka.appkit.utils.V;

/** Local payment instructions only: no account, photo viewer, quota, or network dependency. */
public final class SponsorPaymentGuide{
	private SponsorPaymentGuide(){}

	static int imageResource(int step){
		return step==0 ? R.drawable.sponsor_payment_guide_1 : R.drawable.sponsor_payment_guide_2;
	}
	static int captionResource(int step){
		return step==0 ? R.string.sponsor_payment_guide_step_1 : R.string.sponsor_payment_guide_step_2;
	}

	/** Opens the zero-based step. The themed wrapper is retained, but a live Activity is required. */
	public static Dialog show(Context context, int initialStep){
		Activity host=activity(context);
		if(host==null || host.isFinishing() || host.isDestroyed()) return null;
		Dialog dialog=new Dialog(context);
		dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
		dialog.setOwnerActivity(host);
		dialog.setCancelable(true);
		dialog.setCanceledOnTouchOutside(true);
		LinearLayout root=new LinearLayout(context);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setFitsSystemWindows(true);
		root.setBackgroundColor(SponsorUi.surface(context));
		LinearLayout toolbar=new LinearLayout(context);
		toolbar.setGravity(Gravity.CENTER_VERTICAL);
		Button back=control(toolbar, R.string.sponsor_payment_guide_back);
		back.setOnClickListener(v->dialog.dismiss());
		TextView title=new TextView(context);
		title.setTextAppearance(R.style.m3_title_medium);
		title.setTextColor(UiUtils.getThemeColor(context, R.attr.colorM3OnSurface));
		title.setText(R.string.sponsor_payment_guide_title);
		toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
		root.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
		TextView caption=SponsorUi.text(root, "", true);
		caption.setTextAppearance(R.style.m3_title_medium);
		caption.setPadding(V.dp(16), V.dp(8), V.dp(16), V.dp(8));
		TextView hint=SponsorUi.label(root, context.getString(R.string.sponsor_payment_guide_zoom_hint));
		hint.setPadding(V.dp(16), 0, V.dp(16), V.dp(8));
		GuideImage image=new GuideImage(context);
		root.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
		TextView note=SponsorUi.label(root, context.getString(R.string.sponsor_payment_guide_example_note));
		note.setPadding(V.dp(16), V.dp(4), V.dp(16), V.dp(4));
		LinearLayout navigation=new LinearLayout(context);
		navigation.setGravity(Gravity.CENTER);
		Button previous=control(navigation, R.string.sponsor_payment_guide_previous);
		Button zoomOut=control(navigation, R.string.sponsor_payment_guide_zoom_out);
		Button zoomIn=control(navigation, R.string.sponsor_payment_guide_zoom_in);
		Button next=control(navigation, R.string.sponsor_payment_guide_next);
		root.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
		int[] step={Math.max(0, Math.min(1, initialStep))};
		Runnable render=()->{
			caption.setText(captionResource(step[0]));
			image.setContentDescription(context.getString(captionResource(step[0])));
			image.setImageResource(imageResource(step[0]));
			image.resourceId=imageResource(step[0]);
			image.reset();
			previous.setEnabled(step[0]>0);
			next.setEnabled(step[0]<1);
		};
		previous.setOnClickListener(v->{ if(step[0]>0){ step[0]--; render.run(); } });
		next.setOnClickListener(v->{ if(step[0]<1){ step[0]++; render.run(); } });
		zoomIn.setOnClickListener(v->image.zoom(1.5f, image.getWidth()/2f, image.getHeight()/2f));
		zoomOut.setOnClickListener(v->image.zoom(1/1.5f, image.getWidth()/2f, image.getHeight()/2f));
		render.run();
		dialog.setContentView(root);
		// Registration is local to this dialog, and is removed for every dismissal path.
		Application.ActivityLifecycleCallbacks lifecycle=new Application.ActivityLifecycleCallbacks(){
			@Override public void onActivityDestroyed(Activity activity){ if(activity==host) dialog.dismiss(); }
			@Override public void onActivityCreated(Activity a, Bundle b){}
			@Override public void onActivityStarted(Activity a){}
			@Override public void onActivityResumed(Activity a){}
			@Override public void onActivityPaused(Activity a){}
			@Override public void onActivityStopped(Activity a){}
			@Override public void onActivitySaveInstanceState(Activity a, Bundle b){}
		};
		host.getApplication().registerActivityLifecycleCallbacks(lifecycle);
		dialog.setOnDismissListener(d->{
			host.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
			image.setImageDrawable(null);
		});
		try{
			dialog.show();
			Window window=dialog.getWindow();
			if(window!=null){
				window.setBackgroundDrawableResource(android.R.color.transparent);
				window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
			}
		}catch(WindowManager.BadTokenException error){
			host.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
			image.setImageDrawable(null);
			return null;
		}
		return dialog;
	}

	private static Activity activity(Context context){
		while(context instanceof ContextWrapper){
			if(context instanceof Activity activity) return activity;
			Context base=((ContextWrapper)context).getBaseContext();
			if(base==context) break;
			context=base;
		}
		return null;
	}

	private static Button control(LinearLayout parent, int label){
		Button button=new Button(parent.getContext(), null, 0, R.style.Widget_Mastodon_M3_Button_Text);
		button.setText(label);
		button.setContentDescription(parent.getContext().getString(label));
		button.setAllCaps(false);
		button.setMinWidth(V.dp(48));
		button.setMinimumWidth(V.dp(48));
		button.setMinHeight(V.dp(48));
		button.setPadding(V.dp(8), V.dp(4), V.dp(8), V.dp(4));
		parent.addView(button, new LinearLayout.LayoutParams(-2, -2));
		return button;
	}

	/** Width-fit, top-aligned image with bounded vertical scrolling and zoom/pan on any Canvas. */
	static final class GuideImage extends ImageView{
		private final Matrix transform=new Matrix();
		private final ScaleGestureDetector pinch;
		private final GestureDetector gestures;
		private float zoom=1, x, y;
		private boolean scalingSequence;
		private int resourceId;

		int getResourceId(){ return resourceId; }

		GuideImage(Context context){
			super(context);
			setScaleType(ScaleType.MATRIX);
			setFocusable(true);
			setClickable(true);
			pinch=new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener(){
				@Override public boolean onScale(ScaleGestureDetector detector){
					zoom(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
					return true;
				}
			});
			gestures=new GestureDetector(context, new GestureDetector.SimpleOnGestureListener(){
				@Override public boolean onDown(MotionEvent e){ return true; }
				@Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy){
					if(!scalingSequence){ x-=dx; y-=dy; apply(); }
					return true;
				}
				@Override public boolean onDoubleTap(MotionEvent e){
					if(zoom>1) reset(); else zoom(2, e.getX(), e.getY());
					return true;
				}
				@Override public boolean onSingleTapConfirmed(MotionEvent e){ return performClick(); }
			});
		}
		@Override public boolean performClick(){ super.performClick(); return true; }
		@Override public boolean onTouchEvent(MotionEvent event){
			if(event.getActionMasked()==MotionEvent.ACTION_DOWN) scalingSequence=false;
			if(event.getPointerCount()>1) scalingSequence=true;
			pinch.onTouchEvent(event);
			gestures.onTouchEvent(event);
			return true;
		}
		@Override protected void onSizeChanged(int w, int h, int oldw, int oldh){ super.onSizeChanged(w, h, oldw, oldh); reset(); }
		void reset(){ zoom=1; x=y=0; apply(); }
		void zoom(float factor, float focusX, float focusY){
			float next=Math.max(1, Math.min(5, zoom*factor));
			float ratio=next/zoom;
			x=focusX-(focusX-x)*ratio;
			y=focusY-(focusY-y)*ratio;
			zoom=next;
			apply();
		}
		private void apply(){
			if(getDrawable()==null || getWidth()==0) return;
			float scale=(float)getWidth()/getDrawable().getIntrinsicWidth()*zoom;
			float width=getDrawable().getIntrinsicWidth()*scale;
			float height=getDrawable().getIntrinsicHeight()*scale;
			x=Math.max(Math.min(0, getWidth()-width), Math.min(0, x));
			y=Math.max(Math.min(0, getHeight()-height), Math.min(0, y));
			transform.setScale(scale, scale);
			transform.postTranslate(x, y);
			setImageMatrix(transform);
		}
	}
}
