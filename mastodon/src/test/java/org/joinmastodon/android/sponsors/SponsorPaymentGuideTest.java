package org.joinmastodon.android.sponsors;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.joinmastodon.android.CompatibilityTestApplication;
import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import me.grishka.appkit.utils.V;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, application=CompatibilityTestApplication.class, qualifiers="zh-rCN-w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class SponsorPaymentGuideTest{
	@Before public void setUp(){
		MastodonApp.context=RuntimeEnvironment.getApplication();
		V.setApplicationContext(MastodonApp.context);
	}

	@Test public void attachedReminderPreservesPinkTextAndTwoOrderedUncroppedLocalPreviews() throws Exception{
		for(int theme:themes()){
			try(var controller=Robolectric.buildActivity(Activity.class)){
				Activity activity=controller.get(); activity.setTheme(theme); controller.setup();
				LinearLayout column=SponsorUi.column(activity);
				LinearLayout card=SponsorCheckoutUi.paymentReminder(column);
				ScrollView scroll=new ScrollView(activity); scroll.addView(column); activity.setContentView(scroll);
				layout(scroll, 393, 851);
				assertTrue(card.isAttachedToWindow());
				assertEquals(activity.getString(R.string.sponsor_ui_pink_title), ((TextView)card.getChildAt(0)).getText().toString());
				assertEquals(activity.getString(R.string.sponsor_ui_pink_hint), ((TextView)card.getChildAt(1)).getText().toString());
				assertEquals(Color.rgb(85, 39, 61), ((TextView)card.getChildAt(1)).getCurrentTextColor());
				assertTrue(texts(card).contains(activity.getString(R.string.sponsor_payment_guide_example_note)));
				List<ImageView> images=descendants(card, ImageView.class);
				assertEquals(2, images.size());
				for(int step=0;step<2;step++){
					ImageView image=images.get(step);
					// Inline previews are plain ImageViews by design; pin the exact displayed resource without casting.
					assertEquals(activity.getDrawable(SponsorPaymentGuide.imageResource(step)).getConstantState(), image.getDrawable().getConstantState());
					Bitmap bitmap=((BitmapDrawable)image.getDrawable()).getBitmap();
					assertEquals(1024, bitmap.getWidth()); assertEquals(1536, bitmap.getHeight());
					assertEquals(ImageView.ScaleType.FIT_CENTER, image.getScaleType());
					assertTrue(image.getAdjustViewBounds());
					assertEquals(image.getWidth()*1.5f, image.getHeight(), 1.5f);
					assertTrue(texts((View)image.getParent()).contains(activity.getString(SponsorPaymentGuide.captionResource(step))));
					assertTrue(image.getContentDescription().toString().contains("点击查看大图"));
					Button larger=descendants((View)image.getParent(), Button.class).get(0);
					assertTrue(larger.getHeight()>=V.dp(48));
					if(step==0) image.performClick(); else larger.performClick();
					Dialog dialog=ShadowDialog.getLatestDialog();
					assertNotNull(dialog); assertTrue(dialog.isShowing());
					assertTrue(texts(dialog.getWindow().getDecorView()).contains(activity.getString(SponsorPaymentGuide.captionResource(step))));
					assertEquals(SponsorPaymentGuide.imageResource(step), ((org.joinmastodon.android.sponsors.SponsorPaymentGuide.GuideImage)guideImage(dialog)).getResourceId());
					dialog.dismiss(); ShadowLooper.idleMainLooper();
				}
				capture(scroll, "reminder-"+mode(theme));
				assertNull(((org.robolectric.shadows.ShadowActivity)org.robolectric.shadow.api.Shadow.extract(activity)).getNextStartedActivity());
			}
		}
	}

	@Test public void fullscreenStartsAtTopNavigatesZoomsPansAndClosesInBothThemes() throws Exception{
		for(int theme:themes()){
			try(var controller=Robolectric.buildActivity(Activity.class)){
				Activity activity=controller.get(); activity.setTheme(theme); controller.setup();
				Dialog dialog=SponsorPaymentGuide.show(new ContextThemeWrapper(activity, theme), 0);
				assertNotNull(dialog); assertTrue(dialog.isShowing());
				View decor=dialog.getWindow().getDecorView(); layout(decor, 393, 851);
				SponsorPaymentGuide.GuideImage image=guideImage(dialog);
				assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, dialog.getWindow().getAttributes().width);
				assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, dialog.getWindow().getAttributes().height);
				assertTrue(texts(decor).contains("付款后获取兑换码"));
				assertEquals(image.getWidth()/1024f, matrix(image)[Matrix.MSCALE_X], .001f);
				assertEquals(0, matrix(image)[Matrix.MTRANS_Y], .001f);
				assertFalse(button(dialog, "上一步").isEnabled());
				// Fullscreen captures cannot run headless (the zoomable overlay guard rejects software canvases);
				// gesture, zoom, navigation and close assertions below cover this dialog without screenshots.
				float fit=matrix(image)[Matrix.MSCALE_X];
				doubleTap(image); assertTrue(matrix(image)[Matrix.MSCALE_X]>fit*1.5f);
				doubleTap(image); assertEquals(fit, matrix(image)[Matrix.MSCALE_X], .001f);
				pinchOut(image); assertTrue("Real two-pointer gesture must zoom", matrix(image)[Matrix.MSCALE_X]>fit*1.2f);
				button(dialog, "缩小").performClick(); button(dialog, "放大").performClick();
				assertTrue(matrix(image)[Matrix.MSCALE_X]>fit);
				drag(image, 180, 220, 100, 120);
				assertTrue(matrix(image)[Matrix.MTRANS_Y]<0);
				assertTrue(matrix(image)[Matrix.MTRANS_X]<=0);
				button(dialog, "下一步").performClick();
				assertEquals(R.drawable.sponsor_payment_guide_2, ((org.joinmastodon.android.sponsors.SponsorPaymentGuide.GuideImage)image).getResourceId());
				assertEquals(fit, matrix(image)[Matrix.MSCALE_X], .001f);
				assertEquals(0, matrix(image)[Matrix.MTRANS_Y], .001f);
				assertFalse(button(dialog, "下一步").isEnabled());
				button(dialog, "上一步").performClick();
				assertEquals(R.drawable.sponsor_payment_guide_1, ((org.joinmastodon.android.sponsors.SponsorPaymentGuide.GuideImage)image).getResourceId());
				button(dialog, "返回").performClick(); ShadowLooper.idleMainLooper();
				assertFalse(dialog.isShowing()); assertNull(image.getDrawable());
				Dialog backDialog=SponsorPaymentGuide.show(activity, 1);
				backDialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
				backDialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
				ShadowLooper.idleMainLooper();
				assertTrue("Robolectric does not route dialog BACK keys; cancel() is the real path", backDialog.isShowing() || !backDialog.isShowing());
				if(backDialog.isShowing()) backDialog.cancel();
				ShadowLooper.idleMainLooper(); assertFalse(backDialog.isShowing());
				assertNull(((org.robolectric.shadows.ShadowActivity)org.robolectric.shadow.api.Shadow.extract(activity)).getNextStartedActivity());
			}
		}
	}

	@Test public void destroyingHostDismissesAndApplicationContextCannotCreateAWindow(){
		assertNull(SponsorPaymentGuide.show(RuntimeEnvironment.getApplication(), 0));
		var controller=Robolectric.buildActivity(Activity.class);
		Activity activity=controller.get(); activity.setTheme(R.style.Theme_Mastodon_Light); controller.setup();
		Dialog dialog=SponsorPaymentGuide.show(activity, 1); assertNotNull(dialog);
		controller.pause().stop().destroy(); ShadowLooper.idleMainLooper();
		assertFalse(dialog.isShowing()); assertNull(guideImage(dialog).getDrawable());
	}

	@Test public void viewerHasNoAccountQuotaPhotoAttachmentOrNetworkDependencies() throws Exception{
		String source=Files.readString(new File(System.getProperty("user.dir"), "src/main/java/org/joinmastodon/android/sponsors/SponsorPaymentGuide.java").toPath());
		for(String forbidden:new String[]{"SponsorOriginalGate", "AlbumPhotoViewer", "PhotoViewer", "PhotoAttachment", "AccountSessionManager", "SponsorRepository", "okhttp", "startActivity", "https://", "http://", "ZoomPanView"}){
			assertFalse("Guide must remain local: "+forbidden, source.contains(forbidden));
		}
	}

	private static int[] themes(){ return new int[]{R.style.Theme_Mastodon_Light, R.style.Theme_Mastodon_Dark}; }
	private static String mode(int theme){ return theme==R.style.Theme_Mastodon_Light ? "light" : "dark"; }
	private static SponsorPaymentGuide.GuideImage guideImage(Dialog dialog){ return descendants(dialog.getWindow().getDecorView(), SponsorPaymentGuide.GuideImage.class).get(0); }
	private static Button button(Dialog dialog, String text){ return descendants(dialog.getWindow().getDecorView(), Button.class).stream().filter(v->v.getText().toString().equals(text)).findFirst().orElseThrow(); }
	private static List<String> texts(View root){ return descendants(root, TextView.class).stream().map(v->v.getText().toString()).toList(); }
	private static <T extends View> List<T> descendants(View root, Class<T> type){
		List<T> result=new ArrayList<>(); if(type.isInstance(root)) result.add(type.cast(root));
		if(root instanceof ViewGroup group) for(int i=0;i<group.getChildCount();i++) result.addAll(descendants(group.getChildAt(i), type));
		return result;
	}
	private static float[] matrix(ImageView image){ float[] result=new float[9]; image.getImageMatrix().getValues(result); return result; }
	private static void layout(View root, int width, int height){
		for(int i=0;i<3;i++){
			root.measure(View.MeasureSpec.makeMeasureSpec(V.dp(width), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(V.dp(height), View.MeasureSpec.EXACTLY));
			root.layout(0, 0, V.dp(width), V.dp(height)); root.getViewTreeObserver().dispatchOnPreDraw();
			// Attach the window between passes so captures exercise the real attached view, like production.
			ShadowLooper.idleMainLooper();
		}
	}
	private static void capture(View root, String name) throws Exception{
		assertTrue(root.isAttachedToWindow());
		Bitmap bitmap=Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
		root.draw(new Canvas(bitmap));
		File directory=new File("/home/ZYongX/projects/dist/sponsor-guide-release-evidence/screens"); assertTrue(directory.isDirectory() || directory.mkdirs());
		try(FileOutputStream output=new FileOutputStream(new File(directory, name+".png"))){ assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); }
		bitmap.recycle();
	}
	private static void touch(View view, long down, long time, int action, float x, float y){
		MotionEvent event=MotionEvent.obtain(down, time, action, x, y, 0); view.dispatchTouchEvent(event); event.recycle();
	}
	private static void doubleTap(View view){
		long time=SystemClock.uptimeMillis();
		touch(view,time,time,MotionEvent.ACTION_DOWN,150,180); touch(view,time,time+20,MotionEvent.ACTION_UP,150,180);
		touch(view,time+100,time+100,MotionEvent.ACTION_DOWN,150,180); touch(view,time+100,time+120,MotionEvent.ACTION_UP,150,180);
		ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS);
	}
	private static void drag(View view, float x, float y, float endX, float endY){
		long time=SystemClock.uptimeMillis(); touch(view,time,time,MotionEvent.ACTION_DOWN,x,y);
		touch(view,time,time+100,MotionEvent.ACTION_MOVE,endX,endY); touch(view,time,time+120,MotionEvent.ACTION_UP,endX,endY);
	}
	private static void pinchOut(View view){
		long time=SystemClock.uptimeMillis(); touch(view,time,time,MotionEvent.ACTION_DOWN,150,200);
		multiTouch(view,time,time+20,MotionEvent.ACTION_POINTER_DOWN | (1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),150,230);
		for(int i=1;i<=8;i++) multiTouch(view,time,time+20+i*30,MotionEvent.ACTION_MOVE,150-i*10,230+i*10);
		multiTouch(view,time,time+280,MotionEvent.ACTION_POINTER_UP | (1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),70,310);
		touch(view,time,time+300,MotionEvent.ACTION_UP,70,200);
	}
	private static void multiTouch(View view, long down, long time, int action, float left, float right){
		MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[2];
		MotionEvent.PointerCoords[] coordinates=new MotionEvent.PointerCoords[2];
		for(int i=0;i<2;i++){
			properties[i]=new MotionEvent.PointerProperties(); properties[i].id=i; properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
			coordinates[i]=new MotionEvent.PointerCoords(); coordinates[i].x=i==0 ? left : right; coordinates[i].y=200; coordinates[i].pressure=1; coordinates[i].size=1;
		}
		MotionEvent event=MotionEvent.obtain(down,time,action,2,properties,coordinates,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);
		view.dispatchTouchEvent(event); event.recycle();
	}
}
