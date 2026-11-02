package org.joinmastodon.android.ui.views;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.utils.UiUtils;

/** Two decorative reply rows, one accessible loading node, and at most one animator.
 * No bitmap/layer, per-frame allocation, delayed runnable, or third-party animation dependency.
 */
public class ThreadSkeletonView extends View{
	private static final int ROWS=2;
	private final Paint basePaint=new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint shimmerPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Matrix shimmerMatrix=new Matrix();
	private LinearGradient shimmer;
	private ValueAnimator animator;
	private boolean loading, hostActive;
	private float phase;

	public ThreadSkeletonView(Context context){
		this(context, null);
	}

	public ThreadSkeletonView(Context context, AttributeSet attrs){
		this(context, attrs, 0);
	}

	public ThreadSkeletonView(Context context, AttributeSet attrs, int defStyleAttr){
		super(context, attrs, defStyleAttr);
		setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
		setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
		setFocusable(false);
		setClickable(false);
		updateColors();
	}

	public void setLoading(boolean loading){
		if(this.loading!=loading){
			this.loading=loading;
			setContentDescription(loading ? getContext().getString(R.string.thread_loading_replies) : null);
			setImportantForAccessibility(loading ? IMPORTANT_FOR_ACCESSIBILITY_YES : IMPORTANT_FOR_ACCESSIBILITY_NO);
		}
		if(loading) updateAnimation(); else stopAnimation();
	}

	/** AppKit can hide a fragment without detaching its view. */
	public void setHostActive(boolean active){
		hostActive=active;
		updateColors();
		if(active) updateAnimation(); else stopAnimation();
	}

	private float dp(float value){
		return value*getResources().getDisplayMetrics().density;
	}

	private void updateColors(){
		int surface=UiUtils.getThemeColor(getContext(), R.attr.colorM3Surface);
		int foreground=UiUtils.getThemeColor(getContext(), R.attr.colorM3OnSurface);
		// Neutral grays derived from the current theme, not a hard-coded white flash in dark mode.
		int gray=Math.round(Color.red(foreground)*.299f+Color.green(foreground)*.587f+Color.blue(foreground)*.114f);
		int neutral=Color.rgb(gray, gray, gray);
		basePaint.setColor(UiUtils.alphaBlendColors(surface, neutral, .12f));
		boolean dark=Color.red(surface)+Color.green(surface)+Color.blue(surface)<384;
		int highlight=UiUtils.alphaBlendColors(surface, neutral, dark ? .24f : .045f);
		float band=Math.max(dp(80), getWidth()*.45f);
		shimmer=new LinearGradient(0, 0, band, 0,
				new int[]{highlight & 0x00FFFFFF, highlight, highlight & 0x00FFFFFF},
				new float[]{0, .5f, 1}, Shader.TileMode.CLAMP);
		shimmerPaint.setShader(shimmer);
		invalidate();
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec){
		setMeasuredDimension(resolveSize(Math.round(dp(320)), widthMeasureSpec),
				resolveSize(Math.round(dp(264)), heightMeasureSpec));
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh){
		super.onSizeChanged(w, h, oldw, oldh);
		updateColors();
		updateAnimation();
	}

	/** A footer may become visible and laid out in either order; re-evaluate once geometry is final. */
	@Override
	protected void onLayout(boolean changed, int left, int top, int right, int bottom){
		super.onLayout(changed, left, top, right, bottom);
		updateAnimation();
	}

	@Override
	protected void onConfigurationChanged(Configuration config){
		super.onConfigurationChanged(config);
		updateColors();
		updateAnimation();
	}

	@Override
	protected void onDraw(Canvas canvas){
		super.onDraw(canvas);
		drawRows(canvas, basePaint);
		if(animator!=null && shimmer!=null){
			float band=Math.max(dp(80), getWidth()*.45f);
			shimmerMatrix.setTranslate(-band+(getWidth()+band)*phase, 0);
			shimmer.setLocalMatrix(shimmerMatrix);
			drawRows(canvas, shimmerPaint);
		}
	}

	private void drawRows(Canvas canvas, Paint paint){
		float inset=dp(16), textStart=dp(72), right=getWidth()-inset;
		if(right<=textStart) return;
		for(int row=0;row<ROWS;row++){
			float top=dp(16+row*124);
			canvas.drawCircle(dp(36), top+dp(20), dp(20), paint);
			bar(canvas, paint, textStart, top+dp(3), Math.min(right, textStart+dp(96)), dp(12));
			bar(canvas, paint, textStart, top+dp(23), Math.min(right, textStart+dp(64)), dp(8));
			bar(canvas, paint, textStart, top+dp(51), right, dp(11));
			bar(canvas, paint, textStart, top+dp(72), textStart+(right-textStart)*.86f, dp(11));
			bar(canvas, paint, textStart, top+dp(93), textStart+(right-textStart)*.56f, dp(11));
		}
	}

	private void bar(Canvas canvas, Paint paint, float left, float top, float right, float height){
		canvas.drawRoundRect(left, top, right, top+height, dp(4), dp(4), paint);
	}

	private boolean canAnimate(){
		// A laid-out footer is not necessarily attached: RecyclerView may retain it between
		// traversals or in its cache. Only a real, visible window may own the animator.
		return loading && hostActive && isAttachedToWindow() && getWindowVisibility()==View.VISIBLE
				&& isShown() && getWidth()>0 && getHeight()>0
				&& !GlobalUserPreferences.reduceMotion && ValueAnimator.areAnimatorsEnabled();
	}

	private void updateAnimation(){
		if(!canAnimate()){
			stopAnimation();
			return;
		}
		if(animator!=null) return;
		animator=ValueAnimator.ofFloat(0, 1);
		animator.setDuration(1400);
		animator.setRepeatCount(ValueAnimator.INFINITE);
		animator.setInterpolator(new LinearInterpolator());
		animator.addUpdateListener(animation->{
			if(!canAnimate()){
				stopAnimation();
				return;
			}
			phase=animation.getAnimatedFraction();
			invalidate();
		});
		animator.start();
	}

	private void stopAnimation(){
		if(animator==null) return;
		ValueAnimator previous=animator;
		animator=null;
		previous.removeAllUpdateListeners();
		previous.removeAllListeners();
		previous.cancel();
		phase=0;
		invalidate();
	}

	@Override
	protected void onAttachedToWindow(){
		super.onAttachedToWindow();
		updateAnimation();
	}

	@Override
	protected void onDetachedFromWindow(){
		stopAnimation();
		super.onDetachedFromWindow();
	}

	@Override
	protected void onVisibilityChanged(View changedView, int visibility){
		super.onVisibilityChanged(changedView, visibility);
		updateAnimation();
	}

	@Override
	public void onVisibilityAggregated(boolean visible){
		super.onVisibilityAggregated(visible);
		if(visible) updateAnimation(); else stopAnimation();
	}

	@Override
	protected void onWindowVisibilityChanged(int visibility){
		super.onWindowVisibilityChanged(visibility);
		updateAnimation();
	}
}
