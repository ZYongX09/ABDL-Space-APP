package org.joinmastodon.android.startup;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.R;

/** Fallback for devices/entries with no transferable platform splash surface. */
public final class StartupLogoView extends FrameLayout{
	public static final long MIN_DURATION_MS=3000;
	static final long PULSE_LEG_MS=1200;
	private static final long COLD_ENTRY_WINDOW_MS=10_000;
	private static boolean launcherSeen;
	public static final String LAUNCHER_ALIAS="org.joinmastodon.android.ui.SplashActivity";
	private final ImageView logo;
	private final long epoch;
	private ValueAnimator pulse;

	public interface SurfaceOwner{
		View createStartupLogo();
		default void onStartupLockOpening(){}
	}

	public StartupLogoView(Context context, long epoch){
		super(context);
		this.epoch=epoch;
		setBackgroundColor(context.getColor(R.color.splash_bg));
		setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
		setClickable(true);
		logo=new ImageView(context);
		logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
		logo.setImageResource(R.drawable.abdl_icon);
		int side=getResources().getDimensionPixelSize(R.dimen.startup_handoff_logo_size);
		addView(logo, new LayoutParams(side,side,Gravity.CENTER));
		if(epoch>0 && !GlobalUserPreferences.reduceMotion && ValueAnimator.areAnimatorsEnabled())
			applyScale(scaleAt(SystemClock.uptimeMillis()-epoch, easing()));
	}

	/** A warm tap or restored back stack never restarts the three-second cold entrance. */
	public static long coldEpoch(){
		if(launcherSeen) return 0;
		launcherSeen=true;
		long now=SystemClock.uptimeMillis();
		long start=Build.VERSION.SDK_INT>=33 ? Process.getStartRequestedUptimeMillis() : Process.getStartUptimeMillis();
		return start>0 && now>=start && now-start<=COLD_ENTRY_WINDOW_MS ? start : 0;
	}

	/** Convert the platform wall-clock sample once; duration and callbacks then use uptime. */
	public static long pulseEpoch(long wallStartMs, long wallNowMs, long uptimeNowMs){
		long elapsed=wallNowMs-wallStartMs;
		return wallStartMs>0 && elapsed>=0 && elapsed<=COLD_ENTRY_WINDOW_MS && elapsed<uptimeNowMs
				? uptimeNowMs-elapsed : 0;
	}

	static float scaleAt(long elapsedMs, Interpolator easing){
		long cycle=Math.max(0, elapsedMs)%(PULSE_LEG_MS*2);
		float progress=(cycle<=PULSE_LEG_MS ? cycle : PULSE_LEG_MS*2-cycle)/(float)PULSE_LEG_MS;
		return 1f-.12f*easing.getInterpolation(progress);
	}

	public static boolean mayReveal(long now, long epoch, boolean destinationDrawReady, boolean ordinaryHome){
		return destinationDrawReady && (!ordinaryHome || epoch==0 || now-epoch>=MIN_DURATION_MS);
	}

	@Override protected void onAttachedToWindow(){
		super.onAttachedToWindow();
		updatePulse();
	}

	@Override protected void onWindowVisibilityChanged(int visibility){
		super.onWindowVisibilityChanged(visibility);
		updatePulse();
	}

	@Override protected void onDetachedFromWindow(){
		stopPulse();
		super.onDetachedFromWindow();
	}

	private Interpolator easing(){ return AnimationUtils.loadInterpolator(getContext(),android.R.interpolator.fast_out_slow_in); }

	private void updatePulse(){
		if(epoch<=0 || GlobalUserPreferences.reduceMotion || !ValueAnimator.areAnimatorsEnabled()
				|| !isAttachedToWindow() || getWindowVisibility()!=VISIBLE){
			stopPulse();
			return;
		}
		if(pulse!=null) return;
		// The fallback seeks the same cycle; only a small ImageView's render properties change.
		ValueAnimator animator=ValueAnimator.ofFloat(1f,.88f);
		animator.setDuration(PULSE_LEG_MS);
		animator.setInterpolator(easing());
		animator.setRepeatCount(ValueAnimator.INFINITE);
		animator.setRepeatMode(ValueAnimator.REVERSE);
		animator.addUpdateListener(a->applyScale((float)a.getAnimatedValue()));
		pulse=animator;
		animator.start();
		animator.setCurrentPlayTime(Math.max(0,SystemClock.uptimeMillis()-epoch)%(PULSE_LEG_MS*2));
	}

	private void applyScale(float scale){
		logo.setScaleX(scale);
		logo.setScaleY(scale);
	}

	private void stopPulse(){
		if(pulse!=null){ pulse.cancel(); pulse=null; }
	}
}
