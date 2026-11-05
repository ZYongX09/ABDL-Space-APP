package org.joinmastodon.android.api;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.text.method.SingleLineTransformationMethod;
import android.view.Choreographer;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.gson.JsonObject;

import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
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
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import me.grishka.appkit.utils.V;

import static org.junit.Assert.*;

/** Real attached Activity/views, virtual vsync and real lifecycle callbacks. Native PNG cases are not
 * device/ART rendering or an old APK run. Fixture clocks do not replace foreground/lifecycle checks.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, qualifiers="en-w320dp-h720dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class ServiceResourcePauseUiTest{
	public static class HostActivity extends Activity{}
	private ActivityController<HostActivity> lifecycle;
	private HostActivity activity;
	private Application application;
	private FrameLayout content;
	private final AtomicLong elapsed=new AtomicLong(1000);
	private static Field field(Class<?> type, String name) throws Exception{ Field field=type.getDeclaredField(name); field.setAccessible(true); return field; }
	private static Class<?> ui() throws Exception{ return Class.forName(ServiceResourcePause.class.getName()+"$Ui"); }
	@SuppressWarnings("unchecked") private static Map<TextView, ?> bindings() throws Exception{ return (Map<TextView, ?>)field(ui(), "bindings").get(null); }
	@SuppressWarnings("unchecked") private static Map<Application, Application.ActivityLifecycleCallbacks> trackers() throws Exception{ return (Map<Application, Application.ActivityLifecycleCallbacks>)field(ui(), "trackers").get(null); }

	@Before public void setup(){
		application=RuntimeEnvironment.getApplication(); MastodonApp.context=application; V.setApplicationContext(application);
		ShadowChoreographer.setPaused(true);
		ServiceResourcePause.install(application); // Before create/resume; no tracker-null foreground exemption.
	}
	@After public void cleanup() throws Exception{
		if(lifecycle!=null) lifecycle.close();
		for(TextView text:new ArrayList<>(bindings().keySet())) ServiceResourcePause.unbind(text);
		Application.ActivityLifecycleCallbacks tracker=trackers().remove(application);
		if(tracker!=null) application.unregisterActivityLifecycleCallbacks(tracker);
		ShadowLooper.idleMainLooper();
	}
	private void open(boolean dark, float fontScale){
		Configuration configuration=new Configuration(application.getResources().getConfiguration()); configuration.fontScale=fontScale;
		application.getResources().updateConfiguration(configuration, application.getResources().getDisplayMetrics());
		lifecycle=Robolectric.buildActivity(HostActivity.class); activity=lifecycle.get(); activity.setTheme(dark ? R.style.Theme_Mastodon_Dark : R.style.Theme_Mastodon_Light);
		lifecycle.setup().visible();
		content=new FrameLayout(activity); activity.setContentView(content); frame();
		assertTrue("Fixture must attach a real decor", content.isAttachedToWindow());
	}
	private void frame(){
		ShadowLooper.idleMainLooper(32, TimeUnit.MILLISECONDS);
		View decor=activity.getWindow().getDecorView();
		decor.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)); decor.layout(0, 0, 320, 720);
		ShadowLooper.idleMainLooper(32, TimeUnit.MILLISECONDS);
	}
	private void virtualVsync(){
		boolean[] vsync={false}; Choreographer.getInstance().postFrameCallback(time->vsync[0]=true);
		ShadowLooper.idleMainLooper(32, TimeUnit.MILLISECONDS);
		assertTrue("Advance a real attached-window vsync", vsync[0]);
		frame(); activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw();
	}
	private void assertBoundaryTickScheduled(boolean expected) throws Exception{
		Handler handler=(Handler)field(ui(), "handler").get(null);
		assertEquals("Only attached foreground bindings may retain a finite boundary tick", expected, handler.hasCallbacks((Runnable)field(ui(), "tick").get(null)));
	}
	private ServiceResourcePause pause(String server, String reset, String source){
		JsonObject body=ServiceResourcePauseTimeTest.metadata(server, reset, source);
		return ServiceResourcePause.fromResponse(ServiceResourcePauseJsonTest.response("https://api.abdl-space.top/test", 503), body,
				Clock.fixed(Instant.parse("2036-01-01T01:00:00Z"), ZoneOffset.UTC), elapsed::get);
	}
	private ServiceResourcePause midnightPause(){ return pause("2026-11-03T15:59:59Z", "2026-11-04T00:00:00Z", "estimated_utc_midnight"); }
	private TextView text(){
		TextView text=new TextView(activity); text.setId(R.id.error_text); text.setTextSize(16);
		content.addView(text, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)); frame(); return text;
	}
	private MastodonErrorResponse error(ServiceResourcePause pause){ MastodonErrorResponse error=new MastodonErrorResponse("server body is not UI", 503, null); error.serviceResourcePause=pause; return error; }
	private void advance(long millis){ elapsed.addAndGet(millis); ShadowLooper.idleMainLooper(millis+2, TimeUnit.MILLISECONDS); frame(); }
	private void assertCompleteLayout(TextView text){
		assertNotNull("Text must have native layout", text.getLayout());
		assertEquals(text.length(), text.getLayout().getLineEnd(text.getLineCount()-1));
		for(int line=0; line<text.getLineCount(); line++) assertEquals("No ellipsis at line "+line, 0, text.getLayout().getEllipsisCount(line));
		assertTrue("Text height must include every line", text.getHeight()-text.getTotalPaddingTop()-text.getTotalPaddingBottom()>=text.getLayout().getHeight());
	}
	private void png(View view, String name) throws Exception{
		String root=System.getProperty("servicePauseRenderDir", new File(System.getProperty("java.io.tmpdir"), "service-pause-renders").getAbsolutePath());
		File file=new File(root, name); assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
		Bitmap bitmap=Bitmap.createBitmap(Math.max(1, view.getWidth()), Math.max(1, view.getHeight()), Bitmap.Config.ARGB_8888); view.draw(new Canvas(bitmap));
		try(FileOutputStream out=new FileOutputStream(file)){ assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)); }finally{ bitmap.recycle(); }
	}
	private ScrollView parentScroll(TextView text){
		for(android.view.ViewParent parent=text.getParent(); parent!=null; parent=parent.getParent()) if(parent instanceof ScrollView scroll) return scroll;
		return null;
	}

	@Test public void attachedBindingUpdatesAtChinaMidnightAndAtResetWithoutWrappingOldError(){
		open(false, 1f); TextView text=text(); ServiceResourcePause pause=midnightPause(); error(pause).bindErrorView(content); frame();
		assertTrue(text.getText().toString().contains("tomorrow")); assertTrue(bindingsPresent(text));
		advance(2000); assertTrue(text.getText().toString().contains("today"));
		advance(8*60*60*1000L); assertEquals(activity.getString(R.string.service_resource_pause_waiting), text.getText().toString());
		advance(24*60*60*1000L); assertEquals(activity.getString(R.string.service_resource_pause_waiting), text.getText().toString());
		assertEquals(pause.message(activity), text.getText().toString());
	}
	private boolean bindingsPresent(TextView text){ try{ return bindings().containsKey(text); }catch(Exception error){ throw new AssertionError(error); } }
	@Test public void existingSingleLineTransformationDoesNotUnbindAtMidnightOrReset() throws Exception{
		open(false, 1f); TextView text=text(); text.setText("previous single-line error"); text.setSingleLine(true); frame();
		assertTrue(text.isAttachedToWindow()); assertTrue(text.getTransformationMethod() instanceof SingleLineTransformationMethod);
		ServiceResourcePause pause=midnightPause(); pause.bind(text); virtualVsync();
		assertNull(text.getTransformationMethod()); assertEquals(Integer.MAX_VALUE, text.getMaxLines());
		assertTrue("Internal setText during configuration must not unbind", bindingsPresent(text)); assertBoundaryTickScheduled(true);
		assertTrue(text.getText().toString().contains("tomorrow"));
		advance(2000); assertTrue(text.getText().toString().contains("today")); assertTrue(bindingsPresent(text));
		advance(8*60*60*1000L); assertEquals(activity.getString(R.string.service_resource_pause_waiting), text.getText().toString()); assertBoundaryTickScheduled(false);
	}
	@Test public void attachedParentGoneAtBindThenVisibleExpiresAtEightWithoutReattachOrResume() throws Exception{
		open(false, 1f); TextView text=text(); content.setVisibility(View.GONE); virtualVsync();
		assertTrue(content.isAttachedToWindow()); assertTrue(text.isAttachedToWindow()); assertFalse(text.isShown());
		ServiceResourcePause pause=pause("2026-11-03T23:59:59Z", "2026-11-04T00:00:00Z", "estimated_utc_midnight"); pause.bind(text);
		assertTrue(bindingsPresent(text)); assertTrue(text.getText().toString().contains("today")); assertBoundaryTickScheduled(true);
		content.setVisibility(View.VISIBLE); virtualVsync(); assertTrue(text.isAttachedToWindow()); assertTrue(text.isShown());
		advance(2000); virtualVsync(); assertEquals(activity.getString(R.string.service_resource_pause_waiting), text.getText().toString()); assertBoundaryTickScheduled(false);
		advance(24*60*60*1000L); assertEquals(pause.message(activity), text.getText().toString()); assertBoundaryTickScheduled(false);
	}
	@Test public void attachedParentGoneAcrossMidnightShowsTodayOnVirtualVsyncWithoutReattachOrResume() throws Exception{
		open(false, 1f); TextView text=text(); content.setVisibility(View.GONE); virtualVsync();
		assertTrue(content.isAttachedToWindow()); assertTrue(text.isAttachedToWindow()); assertFalse(text.isShown());
		midnightPause().bind(text); assertTrue(text.getText().toString().contains("tomorrow")); assertBoundaryTickScheduled(true);
		advance(2000); assertFalse(text.isShown()); assertBoundaryTickScheduled(true);
		content.setVisibility(View.VISIBLE); virtualVsync(); assertTrue(text.isAttachedToWindow()); assertTrue(text.isShown());
		assertTrue(text.getText().toString().contains("today"));
		advance(8*60*60*1000L); assertEquals(activity.getString(R.string.service_resource_pause_waiting), text.getText().toString()); assertBoundaryTickScheduled(false);
	}
	@Test public void hiddenBoundaryTickCancelsOnPauseDetachAndUnbind() throws Exception{
		open(false, 1f); TextView text=text(); content.setVisibility(View.GONE); virtualVsync(); midnightPause().bind(text);
		String old=text.getText().toString(); assertBoundaryTickScheduled(true);
		lifecycle.pause(); assertBoundaryTickScheduled(false); advance(2000); assertEquals(old, text.getText().toString());
		lifecycle.resume(); virtualVsync(); assertFalse(text.isShown()); assertTrue(text.getText().toString().contains("today")); assertBoundaryTickScheduled(true);
		content.removeView(text); assertFalse(text.isAttachedToWindow()); assertBoundaryTickScheduled(false);
		content.addView(text); virtualVsync(); assertTrue(text.isAttachedToWindow()); assertFalse(text.isShown()); assertBoundaryTickScheduled(true);
		ServiceResourcePause.unbind(text); assertFalse(bindingsPresent(text)); assertBoundaryTickScheduled(false);
		old=text.getText().toString(); advance(8*60*60*1000L); assertEquals(old, text.getText().toString());
	}
	@Test public void detachedBindingHasNoTimerMutationThenReattachRefreshes(){
		open(false, 1f); TextView text=text(); midnightPause().bind(text); String old=text.getText().toString();
		content.removeView(text); advance(2000); assertEquals(old, text.getText().toString());
		content.addView(text); frame(); assertTrue(text.getText().toString().contains("today"));
	}
	@Test public void activityPauseStopsUpdatesAndRealResumeRefreshesAcrossMidnight(){
		open(false, 1f); TextView text=text(); midnightPause().bind(text); String old=text.getText().toString();
		lifecycle.pause(); advance(2000); assertEquals(old, text.getText().toString());
		lifecycle.resume(); frame(); assertTrue(text.getText().toString().contains("today"));
	}
	@Test public void ordinaryMastodonErrorOnReusedViewUnbindsOldDescriptorTimer(){
		open(false, 1f); TextView text=text(); error(midnightPause()).bindErrorView(content);
		new MastodonErrorResponse("ordinary rate limit", 429, null).bindErrorView(content); assertFalse(bindingsPresent(text));
		advance(2000); assertEquals("ordinary rate limit", text.getText().toString());
	}
	@Test public void externalSetTextOnReusedViewAlsoCancelsOldBinding(){
		open(false, 1f); TextView text=text(); midnightPause().bind(text); text.setText("different row"); assertFalse(bindingsPresent(text));
		advance(2000); assertEquals("different row", text.getText().toString());
	}
	@Test public void repeatedBindingReplacesListenerAndExplicitUnbindPreventsUpdates() throws Exception{
		open(false, 1f); TextView text=text(); ServiceResourcePause pause=midnightPause(); pause.bind(text); pause.bind(text);
		assertEquals(1, bindings().size()); ServiceResourcePause.unbind(text); assertFalse(bindingsPresent(text));
		String old=text.getText().toString(); advance(2000); assertEquals(old, text.getText().toString());
	}
	@Test public void newBindingAfterMidnightComputesDayAtDisplayTime(){
		open(false, 1f); ServiceResourcePause pause=midnightPause(); elapsed.addAndGet(2000); TextView text=text(); pause.bind(text);
		assertTrue(text.getText().toString().contains("today"));
	}
	@Test public void explicitNonEightResetUsesActualChinaClockInsteadOfEight(){
		open(false, 1f); ServiceResourcePause pause=pause("2026-11-03T15:59:59Z", "2026-11-03T17:45:12Z", "upstream");
		String message=pause.message(activity); assertTrue(message.contains("01:45:12")); assertFalse(message.contains("8:00 AM"));
	}
	@Test public void missingTimeMetadataUsesDeviceInstantNotDeviceZoneAndExpiresOnlyOnce(){
		open(false, 1f);
		JsonObject body=ServiceResourcePauseTimeTest.metadata(null, null, null);
		for(String zone:new String[]{"UTC", "America/Los_Angeles", "Asia/Tokyo"}){
			ServiceResourcePause pause=ServiceResourcePause.fromResponse(ServiceResourcePauseJsonTest.response("https://abdl-space.top/test", 503), body,
					Clock.fixed(Instant.parse("2026-11-03T15:59:59Z"), ZoneId.of(zone)), elapsed::get);
			assertTrue(pause.message(activity, Instant.parse("2026-11-03T15:59:59Z")).contains("tomorrow"));
			assertTrue(pause.message(activity, Instant.parse("2026-11-03T16:00:00Z")).contains("today"));
			assertEquals(activity.getString(R.string.service_resource_pause_waiting), pause.message(activity, Instant.parse("2026-11-05T00:00:00Z")));
		}
	}
	@Test public void legacyFallback429ShowsFullServerTextBut503StillUsesServerErrorWithoutMetadata(){
		open(false, 1f); TextView text=text(); String full="为了避免网络攻击造成服务资源浪费，我们每天限量提供服务资源；今日服务资源已耗尽，服务已暂停，预计明天早上8时重新恢复服务。";
		MastodonErrorResponse legacy429=new MastodonErrorResponse(full, 429, null); legacy429.bindErrorView(content);
		assertEquals(full, text.getText().toString()); assertNull(ServiceResourcePause.fromError(legacy429));
		MastodonErrorResponse legacy503=new MastodonErrorResponse(full, 503, null); legacy503.bindErrorView(content);
		assertEquals(activity.getString(R.string.server_error, full), text.getText().toString()); assertNotEquals(full, text.getText().toString()); assertNull(ServiceResourcePause.fromError(legacy503));
	}
	@Test public void foregroundScrollableDialogDeduplicatesSameActivityAndResetAndCleansOnPause(){
		open(false, 1f); ServiceResourcePause pause=midnightPause(); pause.show(activity); frame();
		AlertDialog first=ShadowAlertDialog.getLatestAlertDialog(); assertNotNull(first); assertTrue(first.isShowing());
		TextView message=first.findViewById(android.R.id.message); assertEquals(pause.message(activity), message.getText().toString()); assertNotNull(parentScroll(message));
		pause.show(activity); assertSame(first, ShadowAlertDialog.getLatestAlertDialog());
		first.dismiss(); pause.show(activity); assertSame("Same segment stays deduplicated even after user dismiss", first, ShadowAlertDialog.getLatestAlertDialog()); assertFalse(first.isShowing());
		ServiceResourcePause next=pause("2026-11-04T15:59:59Z", "2026-11-05T00:00:00Z", "estimated_utc_midnight"); next.show(activity); frame();
		AlertDialog second=ShadowAlertDialog.getLatestAlertDialog(); assertNotSame(first, second); assertTrue(second.isShowing()); lifecycle.pause(); assertFalse(second.isShowing());
		assertFalse(bindingsPresent((TextView)second.findViewById(android.R.id.message)));
	}
	@Test public void absentTrackerNeverCountsAsForegroundAndInstallIsIdempotent() throws Exception{
		Application.ActivityLifecycleCallbacks tracker=trackers().remove(application); application.unregisterActivityLifecycleCallbacks(tracker);
		open(false, 1f); AlertDialog before=ShadowAlertDialog.getLatestAlertDialog(); midnightPause().show(activity); assertSame(before, ShadowAlertDialog.getLatestAlertDialog());
		ServiceResourcePause.install(application); ServiceResourcePause.install(application); assertEquals(1, trackers().size());
		midnightPause().show(activity); assertSame("Installing after resume is not permission to assume foreground", before, ShadowAlertDialog.getLatestAlertDialog());
		lifecycle.pause().resume(); midnightPause().show(activity); assertNotSame(before, ShadowAlertDialog.getLatestAlertDialog());
	}
	@Test public void backgroundAndApplicationContextDoNotToastOrQueueNextResume(){
		open(false, 1f); ServiceResourcePause pause=midnightPause(); AlertDialog before=ShadowAlertDialog.getLatestAlertDialog();
		lifecycle.pause(); pause.show(activity); pause.show(application); ShadowLooper.idleMainLooper();
		assertSame(before, ShadowAlertDialog.getLatestAlertDialog()); assertNull(ShadowToast.getLatestToast());
		lifecycle.resume(); frame(); assertSame(before, ShadowAlertDialog.getLatestAlertDialog());
		pause.show(activity); assertNotSame(before, ShadowAlertDialog.getLatestAlertDialog());
	}
	@Test public void destroyedActivityCannotShowAndSilentNbwMetadataCannotOpenDialog(){
		open(false, 1f); ServiceResourcePause pause=midnightPause(); AlertDialog before=ShadowAlertDialog.getLatestAlertDialog();
		MastodonErrorResponse.SilentNBWError silent=new MastodonErrorResponse.SilentNBWError("NBW unavailable", 503, null); silent.serviceResourcePause=pause;
		assertNull(ServiceResourcePause.fromError(silent)); silent.showToast(activity); assertSame(before, ShadowAlertDialog.getLatestAlertDialog()); assertNull(ShadowToast.getLatestToast());
		lifecycle.pause().stop().destroy(); lifecycle=null; pause.show(activity); assertSame(before, ShadowAlertDialog.getLatestAlertDialog());
	}
	@Test public void workerThreadShowAndBindDispatchThroughMainLooper() throws Exception{
		open(false, 1f); TextView text=text(); ServiceResourcePause pause=midnightPause();
		Thread worker=new Thread(()->{ pause.bind(text); pause.show(activity); }); worker.start(); worker.join(); ShadowLooper.idleMainLooper(); frame();
		assertEquals(pause.message(activity), text.getText().toString()); assertNotNull(ShadowAlertDialog.getLatestAlertDialog()); assertEquals(Looper.getMainLooper(), text.getHandler().getLooper());
	}

	@Test @GraphicsMode(GraphicsMode.Mode.NATIVE) public void actualEnglish320LargeTextLightFooterAndScrollableDialogRender() throws Exception{ render(false); }
	@Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(qualifiers="zh-rCN-w320dp-h720dp-mdpi") public void actualChinese320LargeTextDarkFooterAndScrollableDialogRender() throws Exception{ render(true); }
	private void render(boolean dark) throws Exception{
		open(dark, 1.6f);
		ScrollView page=new ScrollView(activity); content.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		View footer=LayoutInflater.from(activity).inflate(R.layout.load_more_with_end_mark, page, false); page.addView(footer);
		footer.findViewById(R.id.load_more_progress).setVisibility(View.GONE); footer.findViewById(R.id.end_mark).setVisibility(View.GONE);
		ServiceResourcePause pause=midnightPause(); error(pause).bindErrorView(footer); frame();
		TextView text=footer.findViewById(R.id.error_text); assertTrue(text.isAttachedToWindow()); assertEquals(pause.message(activity), text.getText().toString()); assertTrue(text.getLineCount()>1); assertCompleteLayout(text);
		assertTrue("Footer is no longer fixed at 76dp", footer.getHeight()>76); View retry=footer.findViewById(R.id.error_retry); assertTrue(retry.getTop()>=text.getBottom()); assertTrue(retry.getBottom()<=footer.getHeight());
		String label=dark ? "dark-zh" : "light-en";
		png(activity.getWindow().getDecorView(), "service-pause-footer-"+label+"-320-font160.png");
		pause.show(activity); frame(); AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog(); assertNotNull(dialog); assertTrue(dialog.isShowing());
		View decor=dialog.getWindow().getDecorView(); decor.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST)); decor.layout(0, 0, 320, decor.getMeasuredHeight());
		ShadowLooper.idleMainLooper(32, TimeUnit.MILLISECONDS);
		TextView message=dialog.findViewById(android.R.id.message); assertEquals(pause.message(activity), message.getText().toString()); assertCompleteLayout(message); ScrollView scroll=parentScroll(message); assertNotNull(scroll);
		png(decor, "service-pause-dialog-"+label+"-320-font160-top.png");
			// fullScroll otherwise animates for multiple frames; check the final native scroll position.
			scroll.setSmoothScrollingEnabled(false);
			scroll.fullScroll(View.FOCUS_DOWN); ShadowLooper.idleMainLooper(32, TimeUnit.MILLISECONDS);
			assertFalse("Full message must be reachable at end of dialog scroll", scroll.canScrollVertically(1));
			assertTrue("The final message line is inside the viewport", message.getBottom()<=scroll.getScrollY()+scroll.getHeight()-scroll.getPaddingBottom());
		assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isShown());
		png(decor, "service-pause-dialog-"+label+"-320-font160-bottom.png");
	}
}
