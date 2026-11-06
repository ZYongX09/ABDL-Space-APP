package org.joinmastodon.android.sponsors;

import android.app.Activity;
import android.app.Application;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.R;
import org.joinmastodon.android.model.sponsors.SponsorModels.*;
import org.joinmastodon.android.model.sponsors.SponsorPaymentModels.*;
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
import java.util.function.Consumer;

import me.grishka.appkit.utils.V;

import static org.joinmastodon.android.sponsors.SandboxFixtures.*;
import static org.junit.Assert.*;

/** Real attached views/Coordinator, offline-only. Neither screenshot claims a wallet or payment. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=31, application=Application.class, qualifiers="zh-rCN-w320dp-h720dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class SandboxSponsorUiTest{
	@Before public void setup(){
		V.setApplicationContext(RuntimeEnvironment.getApplication()); MastodonApp.context=RuntimeEnvironment.getApplication();
	}
	private static final class Actions implements SandboxSponsorUi.Actions{
		String submitted; SponsorPaymentCoordinator coordinator;
		@Override public void pair(String code){ submitted=code; }
		@Override public void showPair(){}
		@Override public void select(String channel){ if(coordinator!=null && "alipay".equals(channel)) coordinator.select(channel); }
		@Override public void pay(){ fail("Offline UI fixture must never start payment"); }
		@Override public void retry(){ if(coordinator!=null) coordinator.retry(); }
		@Override public void switchChannel(){ fail("Offline UI fixture has no order to close"); }
	}
	@Test @SuppressWarnings("deprecation") public void entryLightAt320LargeTextAndSecretNotSaved() throws Exception{
		try(var host=Robolectric.buildActivity(Activity.class)){
			Activity activity=host.get(); activity.setTheme(R.style.Theme_Mastodon_Light); largeText(activity); host.setup();
			Actions actions=new Actions(); SandboxSponsorUi ui=new SandboxSponsorUi(activity, actions);
			WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false); SandboxSponsorActivity.installSafeInsets(ui.root);
			ui.entry(ORIGIN, activity.getString(R.string.sandbox_unpaired)); activity.setContentView(ui.root); layout(ui.root);
			ViewCompat.dispatchApplyWindowInsets(ui.root, fixtureInsets(24)); layout(ui.root);
			assertFalse(ui.code.isSaveEnabled()); assertFalse(ui.code.isSaveFromParentEnabled()); assertTrue(ui.pair.isEnabled());
			ui.code.setText(CODE); ui.pair.performClick(); assertEquals(CODE, actions.submitted); assertEquals("", ui.code.getText().toString());
			assertNoTextClipping(ui.root); assertTrue(ui.root.isAttachedToWindow()); capture(ui.root, "sandbox-entry-light-320-font160");
		}
	}
	@Test public void pairedPaymentDarkAt320LargeTextHasNoAfdianOrWalletSuccess() throws Exception{
		try(var host=Robolectric.buildActivity(Activity.class)){
			Activity activity=host.get(); activity.setTheme(R.style.Theme_Mastodon_Dark); largeText(activity); host.setup();
			Actions actions=new Actions(); SandboxSponsorUi ui=new SandboxSponsorUi(activity, actions); ui.checkout(ORIGIN);
			SponsorUi.label(ui.payment.content, "离线 UI fixture · 未连接钱包 · 未付款");
			SponsorPaymentCoordinator.Source source=new SponsorPaymentCoordinator.Source(){
				@Override public SponsorPaymentCoordinator.Cancelable catalog(SponsorPaymentCoordinator.Result<Catalog> result){ result.success(SandboxFixtures.catalog()); return ()->{}; }
				@Override public SponsorPaymentCoordinator.Cancelable me(SponsorPaymentCoordinator.Result<Me> result){ result.success(SandboxFixtures.me()); return ()->{}; }
				@Override public SponsorPaymentCoordinator.Cancelable options(String plan, int version, SponsorPaymentCoordinator.Result<Options> result){ result.success(SandboxFixtures.options()); return ()->{}; }
				@Override public SponsorPaymentCoordinator.Cancelable create(String p, int v, String operation, SponsorPaymentCoordinator.Result<Order> result){ throw new AssertionError("No payment in Canvas fixture"); }
				@Override public SponsorPaymentCoordinator.Cancelable current(SponsorPaymentCoordinator.Result<CurrentOrder> result){ throw new AssertionError("No order in Canvas fixture"); }
				@Override public SponsorPaymentCoordinator.Cancelable order(String id, SponsorPaymentCoordinator.Result<Order> result){ throw new AssertionError("No order in Canvas fixture"); }
				@Override public SponsorPaymentCoordinator.Cancelable close(String id, SponsorPaymentCoordinator.Result<Order> result){ throw new AssertionError("No close in Canvas fixture"); }
			};
			SponsorPaymentCoordinator.PaymentLauncher absentWallet=new SponsorPaymentCoordinator.PaymentLauncher(){
				@Override public boolean available(){ return false; }
				@Override public SponsorPaymentCoordinator.Cancelable pay(String signed, Consumer<String> result){ throw new AssertionError("No SDK in Canvas fixture"); }
			};
			SponsorPaymentCoordinator coordinator=new SponsorPaymentCoordinator(SandboxSession.owner(ORIGIN, RUN, "101"), SandboxProtocol.PLAN,
					source, absentWallet, new SponsorPaymentCoordinatorTest.Store(), new SponsorPaymentCoordinatorTest.Clock(), ()->true,
					new SponsorPaymentCoordinator.Listener(){
						@Override public void changed(){ if(actions.coordinator!=null) ui.render(actions.coordinator); }
						@Override public void granted(Me me){ fail("No granted result in Canvas fixture"); }
						@Override public void openAfdian(){ fail("Sandbox must never open Afdian"); }
					});
			WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false); SandboxSponsorActivity.installSafeInsets(ui.root);
			actions.coordinator=coordinator; activity.setContentView(ui.root); coordinator.resume(); ui.render(coordinator); layout(ui.root);
			ViewCompat.dispatchApplyWindowInsets(ui.root, fixtureInsets(24)); layout(ui.root);
			assertFalse(ui.payment.afdian.view.isEnabled()); assertFalse(ui.payment.pay.isEnabled()); assertFalse(coordinator.confirmed());
			assertEquals("wallet_unavailable", coordinator.disabledReason("alipay")); assertEquals("¥0.01", ui.payment.price.getText().toString());
			assertEquals(View.GONE, ui.localEntitlement.getVisibility()); assertNoTextClipping(ui.root);
			assertTrue(ui.payment.scroll.getBottom()<=ui.payment.footer.getTop());
			assertTrue(ui.payment.pay.getBottom()<=ui.payment.footer.getHeight()-ui.payment.footer.getPaddingBottom());
			capture(ui.root, "sandbox-payment-dark-320-font160"); coordinator.destroy();
		}
	}
	@Test public void rootInsetsAreAbsoluteConsumeOnceAndSurviveEntryCheckoutChanges(){
		try(var host=Robolectric.buildActivity(Activity.class)){
			Activity activity=host.get(); activity.setTheme(R.style.Theme_Mastodon_Light); host.setup();
			SandboxSponsorUi ui=new SandboxSponsorUi(activity, new Actions());
			WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false); SandboxSponsorActivity.installSafeInsets(ui.root);
			ui.entry(ORIGIN, activity.getString(R.string.sandbox_unpaired)); activity.setContentView(ui.root); layout(ui.root);
			WindowInsetsCompat bars=fixtureInsets(24);
			assertTrue(ViewCompat.dispatchApplyWindowInsets(ui.root, bars).isConsumed());
			assertPadding(ui.root, 7, 30, 9, 24);
			ViewCompat.dispatchApplyWindowInsets(ui.root, bars); assertPadding(ui.root, 7, 30, 9, 24);
			ui.checkout(ORIGIN); assertPadding(ui.root, 7, 30, 9, 24);
			ViewCompat.dispatchApplyWindowInsets(ui.root, fixtureInsets(180)); assertPadding(ui.root, 7, 30, 9, 180);
			// Shared UI keeps only its original design spacing; no duplicate system-bar padding.
			assertEquals(V.dp(12), ui.payment.footer.getPaddingBottom());
			ui.entry(ORIGIN, activity.getString(R.string.sandbox_unpaired));
			ViewCompat.dispatchApplyWindowInsets(ui.root, bars); assertPadding(ui.root, 7, 30, 9, 24);
		}
	}
	private static WindowInsetsCompat fixtureInsets(int imeBottom){
		return new WindowInsetsCompat.Builder()
				.setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(3, 24, 4, 24))
				.setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(7, 30, 9, 0))
				.setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottom)).build();
	}
	private static void assertPadding(View view, int left, int top, int right, int bottom){
		assertEquals(left, view.getPaddingLeft()); assertEquals(top, view.getPaddingTop());
		assertEquals(right, view.getPaddingRight()); assertEquals(bottom, view.getPaddingBottom());
	}
	@SuppressWarnings("deprecation") private static void largeText(Activity activity){
		Configuration config=new Configuration(activity.getResources().getConfiguration()); config.fontScale=1.6f;
		activity.getResources().updateConfiguration(config, activity.getResources().getDisplayMetrics());
	}
	private static void layout(View root){
		for(int i=0;i<3;i++){
			root.measure(View.MeasureSpec.makeMeasureSpec(V.dp(320), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(V.dp(720), View.MeasureSpec.EXACTLY));
			root.layout(0, 0, V.dp(320), V.dp(720)); root.getViewTreeObserver().dispatchOnPreDraw(); ShadowLooper.idleMainLooper();
		}
	}
	private static void assertNoTextClipping(View view){
		if(view instanceof TextView text && text.getVisibility()==View.VISIBLE && text.getLayout()!=null){
			assertTrue("Clipped text: "+text.getText(), text.getLayout().getHeight()<=text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom());
			for(int i=0;i<text.getLayout().getLineCount();i++) assertEquals("Ellipsized text: "+text.getText(), 0, text.getLayout().getEllipsisCount(i));
		}
		if(view instanceof ViewGroup group) for(int i=0;i<group.getChildCount();i++) assertNoTextClipping(group.getChildAt(i));
	}
	private static void capture(View root, String name) throws Exception{
		File directory=new File(System.getProperty("sandboxRenderDir", "/tmp/abdl-sandbox-renders")); assertTrue(directory.isDirectory() || directory.mkdirs());
		Bitmap bitmap=Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888); root.draw(new Canvas(bitmap));
		try(FileOutputStream stream=new FileOutputStream(new File(directory, name+".png"))){ assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
		bitmap.recycle();
	}
}
