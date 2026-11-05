package org.joinmastodon.android.sponsors;

import android.app.Activity;
import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Tests the src/test-only fake-provider entry used by payment-method UI integration tests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class, shadows=AlipaySdkAdapterShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AlipaySdkAdapterShadowTest{
	@Test public void productionStaticContractCanBeFakedWithoutInstallingWalletOrStartingPayTask(){
		try(var controller=Robolectric.buildActivity(Activity.class).setup()){
			AlipaySdkAdapterShadow.configure(true, "8000");
			assertTrue(AlipaySdkAdapter.available(controller.get()));
			List<String> results=new ArrayList<>();
			AlipaySdkAdapter.pay(controller.get(), "test-order-not-a-real-signed-order", results::add);
			ShadowLooper.idleMainLooper();
			assertEquals(1, AlipaySdkAdapterShadow.pending()); assertEquals(0, AlipaySdkAdapterShadow.calls());
			AlipaySdkAdapterShadow.drainSdk(); assertTrue(results.isEmpty());
			ShadowLooper.idleMainLooper(); assertEquals(List.of("8000"), results);
			assertEquals(1, AlipaySdkAdapterShadow.calls());
		}
	}

	@Test public void canceledPageStillRunsQueuedFakePaymentButCannotReceiveCallback(){
		try(var controller=Robolectric.buildActivity(Activity.class).setup()){
			AlipaySdkAdapterShadow.configure(true, "9000");
			List<String> results=new ArrayList<>();
			AlipaySdkAdapter.Cancelable delivery=AlipaySdkAdapter.pay(controller.get(), "test-order", results::add);
			delivery.cancel(); ShadowLooper.idleMainLooper(); AlipaySdkAdapterShadow.drainSdk(); ShadowLooper.idleMainLooper();
			assertEquals(1, AlipaySdkAdapterShadow.calls()); assertTrue(results.isEmpty());
		}
	}
}
