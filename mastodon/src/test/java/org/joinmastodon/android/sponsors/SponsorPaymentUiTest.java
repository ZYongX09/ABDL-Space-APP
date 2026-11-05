package org.joinmastodon.android.sponsors;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.joinmastodon.android.CompatibilityTestApplication;
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
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;

import me.grishka.appkit.utils.V;

import static org.junit.Assert.*;

/** Attached native controls at narrow width / large fonts, never a paid demo or real SDK. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=31, application=CompatibilityTestApplication.class, qualifiers="en-w320dp-h720dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class SponsorPaymentUiTest{
	@Before public void setup(){ V.setApplicationContext(RuntimeEnvironment.getApplication()); }
	@Test public void englishLightAt320LargeText() throws Exception{ render(false, false); }
	@Test public void englishDarkAt320LargeText() throws Exception{ render(true, false); }
	@Test @Config(qualifiers="zh-rCN-w320dp-h720dp-mdpi") public void chineseLightAt320LargeText() throws Exception{ render(false, true); }
	@Test @Config(qualifiers="zh-rCN-w320dp-h720dp-mdpi") public void chineseDarkAt320LargeText() throws Exception{ render(true, true); }

	@SuppressWarnings("deprecation") private void render(boolean dark, boolean chinese) throws Exception{
		try(var host=Robolectric.buildActivity(Activity.class)){
			Activity activity=host.get(); activity.setTheme(dark ? R.style.Theme_Mastodon_Dark : R.style.Theme_Mastodon_Light);
			Configuration config=new Configuration(activity.getResources().getConfiguration()); config.fontScale=1.6f;
			activity.getResources().updateConfiguration(config, activity.getResources().getDisplayMetrics()); host.setup();
			SponsorPaymentCoordinatorTest.Harness h=new SponsorPaymentCoordinatorTest.Harness();
			h.source.catalog.plans.get(0).name=chinese ? "月度赞助者方案" : "Monthly sponsor plan";
			h.source.options.plan.name=h.source.catalog.plans.get(0).name;
			SponsorPaymentUi ui=new SponsorPaymentUi(activity, new SponsorPaymentUi.Actions(){
				@Override public void select(String channel){ h.coordinator.select(channel); }
				@Override public void pay(){ h.coordinator.pay(); }
				@Override public void retry(){ h.coordinator.retry(); }
				@Override public void switchChannel(){ h.coordinator.switchChannel(); }			});
			// Bind native clicks without replacing production views or faking the controller state.
			ui.alipay.view.setOnClickListener(v->{ h.coordinator.select("alipay"); ui.render(h.coordinator); });
			ui.afdian.view.setOnClickListener(v->{ h.coordinator.select("afdian"); ui.render(h.coordinator); });
			activity.setContentView(ui.root); ui.insets(0, 0, V.dp(24)); h.start(); ui.render(h.coordinator); layout(ui.root);
			assertTrue(ui.root.isAttachedToWindow()); assertTrue(ui.pay.isEnabled()); assertEquals("alipay", h.coordinator.selected());
			assertTrue(ui.alipay.view.getBackground() instanceof RippleDrawable); assertNull(ui.alipay.logo.getImageTintList()); assertNull(ui.afdian.logo.getImageTintList());
			assertTrue(ui.alipay.name.getText().toString().contains(activity.getString(R.string.sponsor_payment_recommended)));
			assertTrue(ui.afdian.name.getText().toString().contains(activity.getString(R.string.sponsor_payment_wechat)));
			assertEquals(chinese ? "支付宝" : "Alipay", activity.getString(R.string.sponsor_payment_alipay));
			assertNotEquals("A channel card must not become a solid red recommendation badge", Color.rgb(185, 38, 50), SponsorUi.cardColor(activity));
			AccessibilityNodeInfo info=ui.alipay.view.createAccessibilityNodeInfo(); assertTrue(info.isCheckable()); assertTrue(info.isChecked());
			assertTrue(info.getContentDescription().toString().contains(activity.getString(R.string.sponsor_payment_recommended))); info.recycle();
			assertTrue(ui.afdian.view.performClick()); assertEquals("afdian", h.coordinator.selected()); assertTrue(ui.afdian.radio.isChecked()); assertFalse(ui.alipay.radio.isChecked());
			assertTrue(ui.alipay.view.performClick()); assertEquals("alipay", h.coordinator.selected());
			assertEquals(V.dp(36), ui.footer.getPaddingBottom()); assertTrue(ui.pay.getBottom()<=ui.footer.getHeight()-ui.footer.getPaddingBottom());
			assertTrue(ui.scroll.getBottom()<=ui.footer.getTop()); assertNoTextClipping(ui.root);
			String suffix=(chinese ? "zh" : "en")+"-"+(dark ? "dark" : "light")+"-320-font160";
			capture(ui.root, "sponsor-payment-"+suffix);
			// Real default-policy style fixture: disabled Alipay is explicit, Afdian remains selectable.
			h.source.options=SponsorPaymentFixtures.options("limits_unverified"); h.source.options.plan.name=h.source.catalog.plans.get(0).name;
			h.coordinator.retry(); ui.render(h.coordinator); layout(ui.root);
			assertFalse(ui.alipay.view.isEnabled()); assertEquals(View.VISIBLE, ui.alipay.reason.getVisibility());
			assertEquals(activity.getString(R.string.sponsor_payment_limits_unverified), ui.alipay.reason.getText().toString()); assertEquals("afdian", h.coordinator.selected());
			capture(ui.root, "sponsor-payment-disabled-"+suffix);
			h.source.options=SponsorPaymentFixtures.options("amount_unsupported"); h.source.options.plan.name=h.source.catalog.plans.get(0).name;
			h.coordinator.retry(); ui.render(h.coordinator); layout(ui.root); assertEquals(View.GONE, ui.alipay.view.getVisibility()); assertTrue(ui.afdian.view.isEnabled());
			h.coordinator.destroy();
		}
	}
	private static void layout(View root){
		for(int i=0;i<3;i++){
			root.measure(View.MeasureSpec.makeMeasureSpec(V.dp(320), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(V.dp(720), View.MeasureSpec.EXACTLY));
			root.layout(0, 0, V.dp(320), V.dp(720)); root.getViewTreeObserver().dispatchOnPreDraw(); ShadowLooper.idleMainLooper();
		}
	}
	private static void assertNoTextClipping(View view){
		if(view instanceof TextView label && label.getVisibility()==View.VISIBLE && label.getLayout()!=null){
			assertTrue("Text needs wrap-content height: "+label.getText(), label.getLayout().getHeight()<=label.getHeight()-label.getCompoundPaddingTop()-label.getCompoundPaddingBottom());
			for(int i=0;i<label.getLayout().getLineCount();i++) assertEquals("No ellipsis: "+label.getText(), 0, label.getLayout().getEllipsisCount(i));
		}
		if(view instanceof ViewGroup group) for(int i=0;i<group.getChildCount();i++) assertNoTextClipping(group.getChildAt(i));
	}
	private static void capture(View root, String name) throws Exception{
		File directory=new File(System.getProperty("sponsorPaymentRenderDir", "/tmp/abdl-sponsor-payment-renders")); assertTrue(directory.isDirectory() || directory.mkdirs());
		Bitmap bitmap=Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888); root.draw(new Canvas(bitmap));
		try(FileOutputStream output=new FileOutputStream(new File(directory, name+".png"))){ assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); }
		bitmap.recycle();
	}
}
