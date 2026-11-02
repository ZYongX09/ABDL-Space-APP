package org.joinmastodon.android.ui.views;

import static org.junit.Assert.*;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.GlobalUserPreferences;
import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Field;

/** Real native Canvas pixels, bounded animation ownership and native View lifecycle. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class, qualifiers="en-rUS-w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ThreadSkeletonNativeTest{
	private ActivityController<Activity> lifecycle;
	private FrameLayout host;
	private ThreadSkeletonView skeleton;
	private boolean previousReduceMotion;
	private float previousScale, previousFont;

	@Before public void setup(){
		ShadowChoreographer.setPaused(true);
		previousReduceMotion=GlobalUserPreferences.reduceMotion; GlobalUserPreferences.reduceMotion=false;
		previousScale=ReflectionHelpers.getStaticField(ValueAnimator.class, "sDurationScale");
		previousFont=RuntimeEnvironment.getApplication().getResources().getConfiguration().fontScale;
		setAnimatorScale(1);
	}

	@After public void cleanup(){
		if(lifecycle!=null) lifecycle.close();
		GlobalUserPreferences.reduceMotion=previousReduceMotion;
		RuntimeEnvironment.setFontScale(previousFont); setAnimatorScale(previousScale);
		ShadowChoreographer.setPaused(false);
	}

	@Test public void nativeLightGradientActuallyMovesAcrossGrayReplyRowsAndNeverOutsideShapes() throws Exception{
		mount(false); assertNotNull(diagnostic(), animator());
		ValueAnimator animation=animator(); animation.setCurrentPlayTime(0); Bitmap first=draw();
		animation.setCurrentPlayTime(700); Bitmap middle=draw();
		assertFalse("Not an alpha pulse: horizontal highlight must change native pixels", first.sameAs(middle));
		assertTrue("Both reply rows must be drawn", Color.alpha(middle.getPixel(36,36))>0 && Color.alpha(middle.getPixel(36,160))>0);
		assertEquals("No filled full-screen layer", Color.TRANSPARENT, middle.getPixel(2,2));
		assertEquals(Color.TRANSPARENT, middle.getPixel(318,260));
		assertTrue("Moving band has two different gray levels within the same line", middle.getPixel(95,72)!=middle.getPixel(280,72));
		first.recycle(); middle.recycle();
	}

	@Test public void nativeDarkUsesThemeGrayAndBrighterHighlightInsteadOfWhiteFlash() throws Exception{
		mount(true); ValueAnimator animation=animator(); assertNotNull(diagnostic(), animation);
		animation.setCurrentPlayTime(0); Bitmap first=draw(); animation.setCurrentPlayTime(700); Bitmap middle=draw();
		assertFalse(first.sameAs(middle));
		int base=((Paint)field("basePaint").get(skeleton)).getColor();
		int surface=UiUtils.getThemeColor(skeleton.getContext(), R.attr.colorM3Surface);
		assertTrue(Color.red(base)>Color.red(surface)); assertTrue(Color.red(base)<128);
		assertTrue("Highlight remains a muted dark-theme gray", Color.red(middle.getPixel(95,72))<160);
		assertTrue(Color.red(middle.getPixel(95,72))>Color.red(base));
		first.recycle(); middle.recycle();
	}

	@Test public void reducedMotionAndSystemDisabledAnimationsStayStaticButKeepLoadingAccessible() throws Exception{
		GlobalUserPreferences.reduceMotion=true; mount(false); assertNull(animator());
		Bitmap reduced=draw(); skeleton.setHostActive(true); Bitmap again=draw(); assertTrue(reduced.sameAs(again));
		GlobalUserPreferences.reduceMotion=false; setAnimatorScale(0); skeleton.setHostActive(true);
		assertFalse(ValueAnimator.areAnimatorsEnabled()); assertNull(animator());
		assertEquals("Loading replies", skeleton.getContentDescription().toString());
		assertTrue(Color.alpha(again.getPixel(36,36))>0);
		setAnimatorScale(1); skeleton.setHostActive(true); assertNotNull(animator());
		GlobalUserPreferences.reduceMotion=true;
		animator().setCurrentPlayTime(100); assertNull("Preference changes stop on next frame", animator());
		reduced.recycle(); again.recycle();
	}

	@Test public void detachHiddenParentHostPauseAndCompletionReleaseAnimatorWithoutDelayedRestart() throws Exception{
		mount(false); assertNotNull(diagnostic(), animator());
		for(int hidden:new int[]{View.INVISIBLE, View.GONE}){
			ValueAnimator previous=animator();
			host.setVisibility(hidden); assertNull(animator()); assertFalse(previous.isStarted());
			advanceFrames(3); assertNull("A hidden parent must not restart animation", animator());
			host.setVisibility(View.VISIBLE); layout(); assertNotNull(diagnostic(), animator());
		}
		skeleton.setHostActive(false); assertNull(animator());
		skeleton.setHostActive(true); assertNotNull(animator());
		ValueAnimator detached=animator(); host.removeView(skeleton);
		assertFalse(skeleton.isAttachedToWindow()); assertNull(animator()); assertFalse(detached.isStarted());
		skeleton.setLoading(true); skeleton.setHostActive(true);
		skeleton.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(264,View.MeasureSpec.EXACTLY));
		skeleton.layout(0,0,320,264); advanceFrames(3);
		assertNull("Setters and layout cannot animate a detached footer", animator());
		host.addView(skeleton, new FrameLayout.LayoutParams(-1,-2)); layout(); assertNotNull(diagnostic(), animator());
		skeleton.setLoading(false); assertNull(animator()); assertNull(skeleton.getContentDescription());
		skeleton.setHostActive(true); assertNull(animator());
		layout(); assertNull("A later layout must not restart a completed skeleton", animator());
	}

	@Test public void invisibleWindowCannotRestartAnimationUntilRealViewRootMakesItVisible() throws Exception{
		mount(false);
		View decor=lifecycle.get().getWindow().getDecorView();
		decor.setVisibility(View.INVISIBLE); advanceFrames(3);
		assertTrue(skeleton.isAttachedToWindow()); assertEquals(View.INVISIBLE, skeleton.getWindowVisibility());
		skeleton.setLoading(true); skeleton.setHostActive(true); advanceFrames(3); assertNull(animator());
		decor.setVisibility(View.VISIBLE); layout(); assertNotNull(diagnostic(), animator());
	}

	@Test public void native320LargeFontHasSingleAccessibleNodeAndFixedBoundedWorkAcross120Frames() throws Exception{
		RuntimeEnvironment.setFontScale(1.6f); mount(false);
		assertEquals(320, skeleton.getWidth()); assertEquals(264, skeleton.getHeight());
		assertEquals(1.6f, skeleton.getResources().getConfiguration().fontScale, .001f);
		AccessibilityNodeInfo node=skeleton.createAccessibilityNodeInfo();
		assertEquals(0, node.getChildCount()); assertFalse(node.isClickable());
		assertEquals("Loading replies", node.getContentDescription());
		assertEquals("Loading replies", skeleton.getContentDescription().toString());
		assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, skeleton.getImportantForAccessibility());
		Object shader=field("shimmer").get(skeleton), base=field("basePaint").get(skeleton), highlight=field("shimmerPaint").get(skeleton);
		ValueAnimator one=animator(); Bitmap image=Bitmap.createBitmap(320,264,Bitmap.Config.ARGB_8888); Canvas canvas=new Canvas(image);
		try{
			for(int frame=0;frame<120;frame++){
				one.setCurrentPlayTime(frame*11L); skeleton.draw(canvas);
				assertSame(one, animator()); assertSame(shader, field("shimmer").get(skeleton));
				assertSame(base, field("basePaint").get(skeleton)); assertSame(highlight, field("shimmerPaint").get(skeleton));
			}
			assertFalse(skeleton.isLayoutRequested()); assertEquals(View.LAYER_TYPE_NONE, skeleton.getLayerType());
			assertEquals(1400, one.getDuration()); assertEquals(ValueAnimator.INFINITE,
					((org.robolectric.shadows.ShadowValueAnimator)org.robolectric.shadow.api.Shadow.extract(one)).getActualRepeatCount());
		}finally{ image.recycle(); }
		skeleton.setLoading(false); assertNull(animator());
		assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, skeleton.getImportantForAccessibility());
	}

	private void mount(boolean dark) throws Exception{
		lifecycle=Robolectric.buildActivity(Activity.class);
		Activity activity=lifecycle.get(); activity.setTheme(dark ? R.style.Theme_Mastodon_Dark : R.style.Theme_Mastodon_Light);
		lifecycle.create();
		host=new FrameLayout(activity); activity.setContentView(host);
		skeleton=new ThreadSkeletonView(activity); host.addView(skeleton, new FrameLayout.LayoutParams(-1,-2));
		// Give the unmounted hierarchy real geometry first: neither layout nor state setters may animate it.
		host.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY));
		host.layout(0,0,320,640);
		skeleton.setLoading(true); skeleton.setHostActive(true);
		assertFalse(skeleton.isAttachedToWindow()); assertNull("No animator before the first ViewRoot attach", animator());
		lifecycle.start().resume().visible(); layout();
	}
	private void layout(){
		View decor=lifecycle.get().getWindow().getDecorView();
		decor.requestLayout(); advanceFrames(3);
		assertTrue("The real ViewRoot must attach the decor", decor.isAttachedToWindow());
		assertTrue(diagnostic(), host.isAttachedToWindow()); assertTrue(diagnostic(), skeleton.isAttachedToWindow());
		assertEquals(diagnostic(), View.VISIBLE, skeleton.getWindowVisibility());
		Rect visible=new Rect(); assertTrue("Skeleton must intersect the actual window viewport", skeleton.getGlobalVisibleRect(visible));
		assertFalse(visible.isEmpty());
	}
	private static void advanceFrames(int count){
		for(int frame=0;frame<count;frame++){
			// idle() alone leaves the initial traversal/vsync and its synchronization barrier pending.
			ShadowLooper.getShadowMainLooper().idleFor(ShadowChoreographer.getFrameDelay());
			ShadowLooper.idleMainLooper();
		}
	}
	private Bitmap draw(){
		Bitmap image=Bitmap.createBitmap(skeleton.getWidth(),skeleton.getHeight(),Bitmap.Config.ARGB_8888);
		skeleton.draw(new Canvas(image)); return image;
	}
	private String diagnostic(){
		return "attached="+skeleton.isAttachedToWindow()+", shown="+skeleton.isShown()+", visibility="+skeleton.getVisibility()
				+", windowVisibility="+skeleton.getWindowVisibility()+", size="+skeleton.getWidth()+"x"+skeleton.getHeight()
				+", hostAttached="+host.isAttachedToWindow()+", reduceMotion="+GlobalUserPreferences.reduceMotion
				+", animators="+ValueAnimator.areAnimatorsEnabled()+", loading="+skeleton.getContentDescription();
	}
	private ValueAnimator animator() throws Exception{ return (ValueAnimator)field("animator").get(skeleton); }
	private static Field field(String name) throws Exception{ Field result=ThreadSkeletonView.class.getDeclaredField(name); result.setAccessible(true); return result; }
	private static void setAnimatorScale(float scale){
		ReflectionHelpers.callStaticMethod(ValueAnimator.class,"setDurationScale", ReflectionHelpers.ClassParameter.from(float.class,scale));
	}
}
