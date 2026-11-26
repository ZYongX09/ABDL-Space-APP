package org.joinmastodon.android.ui.views;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.TextView;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.R;
import org.joinmastodon.android.ui.utils.UiUtils;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import me.grishka.appkit.utils.V;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=CompatibilityTestApplication.class, qualifiers="zh-rCN-w360dp-h740dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class HomeTimelineTabStripTest{
	private ActivityController<Activity> activity;
	private FrameLayout host;
	private HomeTimelineTabStrip strip;
	private HorizontalScrollView scroller;
	private ViewGroup row;
	private ImageButton edit;
	private final List<Integer> clicked=new ArrayList<>();
	private int editorOpens;

	@After public void cleanup(){
		if(activity!=null) activity.close();
	}

	@Test public void dragOnlyScrollsLabelsAndPlusStaysFixedWhileClickRoutesByDefinitionIndex() throws Exception{
		mount(R.style.Theme_Mastodon_Dark, View.LAYOUT_DIRECTION_LTR, 360);
		strip.setTabs(sampleTabs(), 0);
		layout(360);
		assertEquals(V.dp(48), strip.getHeight());
		assertEquals(V.dp(48), edit.getWidth());
		assertEquals(V.dp(28), strip.getWidth()-(edit.getLeft()+edit.getRight())/2);
		assertEquals(scroller.getRight(), edit.getLeft());
		assertEquals(V.dp(8), row.getChildAt(0).getLeft());
		assertTrue(scroller.canScrollHorizontally(1));
		assertFalse(scroller.canScrollHorizontally(-1));
		int fixedPlus=edit.getLeft();
		swipe();
		assertTrue("A touch drag must pan the strip independently", scroller.getScrollX()>0);
		assertTrue(scroller.canScrollHorizontally(-1));
		assertEquals(fixedPlus, edit.getLeft());
		assertTrue(clicked.isEmpty());
		assertTrue(row.getChildAt(0).isSelected());
		assertEquals(0, editorOpens);
		ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);
		scroller.computeScroll();

		strip.setSelectedPage(2, false);
		strip.setScrollPosition(2);
		layout(360);
		View technology=row.getChildAt(2);
		assertEquals((technology.getRight()+technology.getLeft()-scroller.getWidth())/2, scroller.getScrollX());
		tap(centerX(technology)-scroller.getScrollX());
		assertEquals(List.of(5), clicked);
		tap(centerX(edit));
		assertEquals(1, editorOpens);
		assertEquals(List.of(5), clicked);
		assertTrue(technology.createAccessibilityNodeInfo().isSelected());
		assertEquals("Technology", technology.getContentDescription());
		assertEquals(strip.getContext().getString(R.string.sk_edit_timelines), edit.getContentDescription());
		Bitmap image=draw();
		try{ capture(image, "home-tabs-dark-overflow"); }
		finally{ image.recycle(); }
	}

	@Test public void fittingLabelsShareTheViewportAndOnlyTheThinLineMovesOnSwipe() throws Exception{
		mount(R.style.Theme_Mastodon_Light, View.LAYOUT_DIRECTION_LTR, 360);
		strip.setTabs(List.of(tab(1,"For you"), tab(4,"Following")), 0);
		layout(360);
		View first=row.getChildAt(0), second=row.getChildAt(1);
		assertEquals(scroller.getWidth(), row.getWidth());
		assertEquals(row.getWidth()/2, first.getWidth());
		assertEquals(first.getWidth(), second.getWidth());
		assertFalse(scroller.canScrollHorizontally(1));
		int selectedColor=UiUtils.getThemeColor(strip.getContext(), R.attr.colorM3OnSurface);
		int normalColor=UiUtils.getThemeColor(strip.getContext(), R.attr.colorM3OnSurfaceVariant);
		assertEquals(selectedColor, label(0).getCurrentTextColor());
		assertEquals(normalColor, label(1).getCurrentTextColor());
		assertTrue(label(0).getTypeface().isBold());
		assertTrue(label(1).getTypeface().isBold());
		Bitmap firstFrame=draw();
		assertEquals(selectedColor, firstFrame.getPixel(centerX(first), strip.getHeight()-V.dp(1)));
		assertNotEquals(selectedColor, firstFrame.getPixel(centerX(first), strip.getHeight()-V.dp(2)-1));
		assertNotEquals(selectedColor, firstFrame.getPixel(centerX(second), strip.getHeight()-V.dp(1)));

		strip.setScrollPosition(.5f);
		assertFalse("Indicator drawing must not request layout", strip.isLayoutRequested());
		assertEquals(0, scroller.getScrollX());
		assertEquals(first.getRight(), second.getLeft());
		Bitmap middle=draw();
		assertEquals(selectedColor, middle.getPixel(second.getLeft(), strip.getHeight()-V.dp(1)));
		assertNotEquals(selectedColor, middle.getPixel(first.getLeft()+V.dp(10), strip.getHeight()-V.dp(1)));
		assertNotEquals(selectedColor, middle.getPixel(second.getRight()-V.dp(10), strip.getHeight()-V.dp(1)));
		assertTrue(first.isSelected());
		strip.setSelectedPage(1, false);
		strip.setScrollPosition(1);
		Bitmap settled=draw();
		assertEquals(selectedColor, settled.getPixel(centerX(second), strip.getHeight()-V.dp(1)));
		assertFalse(first.isSelected());
		assertTrue(second.isSelected());
		try{ capture(settled, "home-tabs-light-fitting"); }
		finally{ firstFrame.recycle(); middle.recycle(); settled.recycle(); }
	}

	@Test public void rtlKeepsTheFirstTabOnTheRightAndTheEditorAtTheTrailingEdge(){
		mount(R.style.Theme_Mastodon_Dark, View.LAYOUT_DIRECTION_RTL, 360);
		strip.setTabs(sampleTabs(), 0);
		layout(360);
		assertEquals(V.dp(4), edit.getLeft());
		assertEquals(edit.getRight(), scroller.getLeft());
		assertEquals(V.dp(8), row.getWidth()-row.getChildAt(0).getRight());
		assertTrue(scroller.canScrollHorizontally(-1));
		assertFalse(scroller.canScrollHorizontally(1));
		strip.setSelectedPage(5, false);
		strip.setScrollPosition(5);
		layout(360);
		assertEquals(0, scroller.getScrollX());
		View last=row.getChildAt(5);
		assertTrue(last.isSelected());
		tap(scroller.getLeft()+centerX(last));
		assertEquals(List.of(13), clicked);
		tap(centerX(edit));
		assertEquals(1, editorOpens);
	}

	@Test public void selectedAndPersistentIconsRespectLabelWidthAndLongTitleStaysReachable(){
		mount(R.style.Theme_Mastodon_Light, View.LAYOUT_DIRECTION_LTR, 300);
		String title="很长的自定义时间线标题，窄屏也不会挡住编辑入口".repeat(3);
		int icon=R.drawable.ic_fluent_lightbulb_24_regular;
		strip.setTabs(List.of(tab(1,"主页"), new HomeTimelineTabStrip.Tab(5,title,icon,true,false),
				new HomeTimelineTabStrip.Tab(8,"列表",icon,true,true)), 0);
		layout(300);
		assertNull(label(1).getCompoundDrawablesRelative()[0]);
		assertNotNull(label(2).getCompoundDrawablesRelative()[0]);
		strip.setSelectedPage(1, false);
		strip.setScrollPosition(1);
		layout(300);
		View longTab=row.getChildAt(1);
		assertNotNull(label(1).getCompoundDrawablesRelative()[0]);
		assertEquals(V.dp(20), label(1).getCompoundDrawablesRelative()[0].getBounds().width());
		assertEquals(V.dp(6), label(1).getCompoundDrawablePadding());
		assertTrue(label(1).getLayout().getEllipsisCount(0)>0);
		assertEquals(title, longTab.getContentDescription());
		assertTrue(longTab.getLeft()>=scroller.getScrollX());
		assertTrue(longTab.getRight()<=scroller.getScrollX()+scroller.getWidth());
		strip.setSelectedPage(2, false);
		layout(300);
		assertNull(label(1).getCompoundDrawablesRelative()[0]);
		assertNotNull(label(2).getCompoundDrawablesRelative()[0]);
		assertEquals(strip.getWidth()-V.dp(4), edit.getRight());
	}

	private void mount(int theme, int direction, int widthDp){
		V.setApplicationContext(RuntimeEnvironment.getApplication());
		activity=Robolectric.buildActivity(Activity.class);
		activity.get().setTheme(theme);
		activity.setup().visible();
		host=new FrameLayout(activity.get());
		host.setLayoutDirection(direction);
		strip=new HomeTimelineTabStrip(activity.get(), clicked::add, ()->editorOpens++);
		host.addView(strip, new FrameLayout.LayoutParams(-1, V.dp(HomeTimelineTabStrip.HEIGHT_DP)));
		activity.get().setContentView(host);
		scroller=(HorizontalScrollView)strip.getChildAt(0);
		row=(ViewGroup)scroller.getChildAt(0);
		edit=(ImageButton)strip.getChildAt(1);
		layout(widthDp);
	}

	private void layout(int widthDp){
		int width=V.dp(widthDp), height=V.dp(HomeTimelineTabStrip.HEIGHT_DP);
		host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
		host.layout(0, 0, width, height);
	}

	private void swipe(){
		long time=SystemClock.uptimeMillis();
		float start=scroller.getWidth()-V.dp(20);
		float end=start-V.dp(140);
		touch(time, 0, MotionEvent.ACTION_DOWN, start);
		touch(time, 40, MotionEvent.ACTION_MOVE, start-V.dp(35));
		touch(time, 180, MotionEvent.ACTION_MOVE, end);
		touch(time, 300, MotionEvent.ACTION_MOVE, end);
		touch(time, 400, MotionEvent.ACTION_UP, end);
	}

	private void tap(float x){
		long time=SystemClock.uptimeMillis();
		touch(time, 0, MotionEvent.ACTION_DOWN, x);
		touch(time, 70, MotionEvent.ACTION_UP, x);
		org.robolectric.shadows.ShadowLooper.idleMainLooper();
	}

	private void touch(long time, long elapsed, int action, float x){
		MotionEvent event=MotionEvent.obtain(time, time+elapsed, action, x, strip.getHeight()/2f, 0);
		try{ strip.dispatchTouchEvent(event); }
		finally{ event.recycle(); }
	}

	private TextView label(int i){ return (TextView)((ViewGroup)row.getChildAt(i)).getChildAt(0); }
	private static int centerX(View view){ return (view.getLeft()+view.getRight())/2; }
	private static HomeTimelineTabStrip.Tab tab(int index, String title){ return new HomeTimelineTabStrip.Tab(index,title,0,true,false); }
	private static List<HomeTimelineTabStrip.Tab> sampleTabs(){
		return List.of(tab(1,"For you"), tab(3,"Following"),
				new HomeTimelineTabStrip.Tab(5,"Technology",R.drawable.ic_fluent_lightbulb_24_regular,true,false),
				tab(7,"Politics"),tab(10,"Local"),tab(13,"Bookmarks"));
	}

	private Bitmap draw(){
		Bitmap image=Bitmap.createBitmap(strip.getWidth(),strip.getHeight(),Bitmap.Config.ARGB_8888);
		strip.draw(new Canvas(image));
		return image;
	}

	private static void capture(Bitmap image, String name) throws IOException{
		String dir=System.getenv("HOME_TAB_RENDER_DIR");
		if(dir==null) return;
		File output=new File(dir,name+".png");
		try(FileOutputStream stream=new FileOutputStream(output)){ assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
	}
}
