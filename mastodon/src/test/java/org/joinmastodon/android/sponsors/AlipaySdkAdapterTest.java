package org.joinmastodon.android.sponsors;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import androidx.test.core.app.ApplicationProvider;

import static org.junit.Assert.*;

/** Fakes are per Engine instance; no real PayTask, gateway, order signing or global override is used. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={26, 35}, application=Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class AlipaySdkAdapterTest{
	private static final String ORDER="test-only-server-signed-order";
	private static final String WALLET="com.eg.android.AlipayGphone";

	private static final class QueueExecutor implements Executor{
		final ArrayDeque<Runnable> tasks=new ArrayDeque<>();
		@Override public void execute(Runnable task){ tasks.addLast(task); }
		void drain(){ while(!tasks.isEmpty()) tasks.removeFirst().run(); }
	}

	private static final class FakeProvider implements AlipaySdkAdapter.Provider{
		boolean ready=true;
		int payments;
		Map<String, String> result=Map.of("resultStatus", "9000", "result", "never-expose-raw-payment-data", "memo", "never-expose-memo");
		RuntimeException availabilityFailure, paymentFailure;
		LinkageError unavailableSdk;
		Activity paidActivity;
		String paidOrder;
		Thread paymentThread;

		@Override public boolean available(Context context){
			if(availabilityFailure!=null) throw availabilityFailure;
			if(unavailableSdk!=null) throw unavailableSdk;
			return ready;
		}
		@Override public Map<String, String> pay(Activity activity, String orderStr){
			payments++; paidActivity=activity; paidOrder=orderStr; paymentThread=Thread.currentThread();
			if(paymentFailure!=null) throw paymentFailure;
			if(unavailableSdk!=null) throw unavailableSdk;
			return result;
		}
	}

	private static final class Harness{
		final FakeProvider provider=new FakeProvider();
		final QueueExecutor worker=new QueueExecutor(), main=new QueueExecutor();
		final AlipaySdkAdapter.Engine engine=new AlipaySdkAdapter.Engine(provider, worker, main);
		final List<String> results=new ArrayList<>();
		AlipaySdkAdapter.Cancelable pay(Activity activity){ return engine.pay(activity, ORDER, results::add); }
		void finish(){ main.drain(); worker.drain(); main.drain(); }
	}

	@Test public void normalizesOnlyExactDocumentedStatuses(){
		for(String status:new String[]{"9000", "8000", "6001", "4000", "6002", "6004", "5000"}){
			assertEquals(status, AlipaySdkAdapter.normalizeStatus(status));
		}
		for(String malformed:new String[]{null, "", "UNKNOWN", "9000 ", " 9000", "9000\n", "9000;result={paid}", "success", "9001", "6003"}){
			assertEquals("UNKNOWN", AlipaySdkAdapter.normalizeStatus(malformed));
		}
	}

	@Test public void invokesExactlyOnceAndExposesOnlyStatusAfterMainDelivery(){
		try(var controller=Robolectric.buildActivity(Activity.class).setup()){
			Harness h=new Harness(); Activity owner=controller.get();
			h.pay(owner);
			assertEquals(0, h.provider.payments); assertTrue(h.results.isEmpty());
			h.main.drain();
			assertEquals(1, h.worker.tasks.size()); assertEquals(0, h.provider.payments);
			h.worker.drain();
			assertTrue(h.results.isEmpty()); assertEquals(1, h.provider.payments);
			assertSame(owner, h.provider.paidActivity); assertEquals(ORDER, h.provider.paidOrder);
			h.main.drain(); h.finish();
			assertEquals(List.of("9000"), h.results); assertEquals(1, h.provider.payments);
		}
	}

	@Test public void everyResultStatusIncludingCancelAndProcessingIsDeliveredWithoutRetry(){
		try(var controller=Robolectric.buildActivity(Activity.class).setup()){
			for(String status:new String[]{"9000", "8000", "6001", "4000", "6002", "6004", "5000", "untrusted-raw-status"}){
				Harness h=new Harness(); h.provider.result=Map.of("resultStatus", status);
				h.pay(controller.get()); h.finish(); h.finish();
				assertEquals(List.of(AlipaySdkAdapter.normalizeStatus(status)), h.results);
				assertEquals(1, h.provider.payments);
			}
		}
	}

	@Test public void nullAndMissingResultsAreUnknownNotSuccessful(){
		try(var controller=Robolectric.buildActivity(Activity.class).setup()){
			for(Map<String, String> result:java.util.Arrays.<Map<String, String>>asList(null, Map.of(), Map.of("result", "9000"))){
				Harness h=new Harness(); h.provider.result=result;
				h.pay(controller.get()); h.finish();
				assertEquals(List.of("UNKNOWN"), h.results); assertEquals(1, h.provider.payments);
			}
		}
	}

	@Test public void invalidInputAndAbsentCapabilityDoNotInvokePayment(){
		try(var controller=Robolectric.buildActivity(Activity.class).setup()){
			for(String order:new String[]{null, "", " \t\n"}){
				Harness h=new Harness(); h.engine.pay(controller.get(), order, h.results::add); h.finish();
				assertEquals(List.of("UNKNOWN"), h.results); assertEquals(0, h.provider.payments);
			}
			Harness missingWallet=new Harness(); missingWallet.provider.ready=false;
			missingWallet.pay(controller.get()); missingWallet.finish();
			assertEquals(List.of("UNKNOWN"), missingWallet.results); assertEquals(0, missingWallet.provider.payments);
			Harness noCallback=new Harness(); noCallback.engine.pay(controller.get(), ORDER, null); noCallback.finish();
			assertEquals(0, noCallback.provider.payments);
		}
	}

	@Test public void capabilityErrorsAreFailClosedAndDoNotExposeExceptionMessages(){
		Context context=ApplicationProvider.getApplicationContext();
		Harness h=new Harness(); assertFalse(h.engine.available(null));
		h.provider.availabilityFailure=new SecurityException("sensitive-error-detail");
		assertFalse(h.engine.available(context));
		h.provider.availabilityFailure=null; h.provider.unavailableSdk=new NoClassDefFoundError("missing-sdk");
		assertFalse(h.engine.available(context));
		try(var controller=Robolectric.buildActivity(Activity.class).setup()){
			h.pay(controller.get()); h.finish();
			assertEquals(List.of("UNKNOWN"), h.results); assertEquals(0, h.provider.payments);
		}
	}

	@Test public void providerExceptionsBecomeUnknownAndNeverAutomaticallyRetry(){
		try(var controller=Robolectric.buildActivity(Activity.class).setup()){
			Harness h=new Harness(); h.provider.paymentFailure=new IllegalStateException("signed-order-and-raw-result-must-not-leak");
			h.pay(controller.get()); h.finish(); h.finish();
			assertEquals(List.of("UNKNOWN"), h.results); assertEquals(1, h.provider.payments);
		}
	}

	@Test public void disappearingWalletBetweenMainAndWorkerDoesNotLaunchSdk(){
		try(var controller=Robolectric.buildActivity(Activity.class).setup()){
			Harness h=new Harness(); h.pay(controller.get()); h.main.drain(); h.provider.ready=false;
			h.worker.drain(); h.main.drain();
			assertEquals(List.of("UNKNOWN"), h.results); assertEquals(0, h.provider.payments);
		}
	}

	@Test public void cancelSuppressesCallbackButDoesNotCancelOrInterruptPayment(){
		try(var controller=Robolectric.buildActivity(Activity.class).setup()){
			Harness h=new Harness(); AlipaySdkAdapter.Cancelable delivery=h.pay(controller.get());
			delivery.cancel(); delivery.cancel(); h.finish();
			assertEquals(1, h.provider.payments); assertTrue(h.results.isEmpty());
		}
	}

	@Test public void cancelAfterSdkReturnSuppressesAlreadyQueuedMainResult(){
		try(var controller=Robolectric.buildActivity(Activity.class).setup()){
			Harness h=new Harness(); AlipaySdkAdapter.Cancelable delivery=h.pay(controller.get());
			h.main.drain(); h.worker.drain(); delivery.cancel(); h.main.drain();
			assertEquals(1, h.provider.payments); assertTrue(h.results.isEmpty());
		}
	}

	@Test public void cancelDoesNotUnlockAnInflightNativePaymentForAnotherCall(){
		try(var controller=Robolectric.buildActivity(Activity.class).setup()){
			Harness h=new Harness(); AlipaySdkAdapter.Cancelable first=h.pay(controller.get());
			h.main.drain(); first.cancel(); h.pay(controller.get()); h.main.drain();
			assertEquals(List.of("UNKNOWN"), h.results); assertEquals(1, h.worker.tasks.size());
			h.worker.drain(); h.main.drain(); assertEquals(1, h.provider.payments);
			assertEquals(List.of("UNKNOWN"), h.results);
			// A later explicit call is allowed only after the original transport actually returns.
			h.pay(controller.get()); h.finish();
			assertEquals(2, h.provider.payments); assertEquals(List.of("UNKNOWN", "9000"), h.results);
		}
	}

	@Test public void nullFinishingAndDestroyedOwnersCannotStartPaymentOrReceiveCallbacks(){
		Harness missing=new Harness(); missing.pay(null); missing.finish();
		assertEquals(0, missing.provider.payments); assertTrue(missing.results.isEmpty());
		try(var controller=Robolectric.buildActivity(Activity.class).setup()){
			Harness h=new Harness(); controller.get().finish(); h.pay(controller.get()); h.finish();
			assertEquals(0, h.provider.payments); assertTrue(h.results.isEmpty());
		}
		try(ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup()){
			Harness h=new Harness(); Activity owner=controller.get(); controller.pause().stop().destroy();
			h.pay(owner); h.finish();
			assertEquals(0, h.provider.payments); assertTrue(h.results.isEmpty());
		}
	}

	@Test public void destructionBeforeWorkerSkipsSdkAndDestructionBeforeDeliveryDropsResult(){
		for(boolean beforeWorker:new boolean[]{true, false}){
			try(var controller=Robolectric.buildActivity(Activity.class).setup()){
				Harness h=new Harness(); h.pay(controller.get()); h.main.drain();
				if(!beforeWorker) h.worker.drain();
				controller.pause().stop().destroy(); h.worker.drain(); h.main.drain();
				assertEquals(beforeWorker ? 0 : 1, h.provider.payments); assertTrue(h.results.isEmpty());
			}
		}
	}

	@Test public void independentEnginesDoNotMutateProductionProviderOrEachOther(){
		try(var controller=Robolectric.buildActivity(Activity.class).setup()){
			Harness first=new Harness(), second=new Harness(); second.provider.result=Map.of("resultStatus", "8000");
			first.pay(controller.get()); second.pay(controller.get()); first.finish(); second.finish();
			assertEquals(List.of("9000"), first.results); assertEquals(List.of("8000"), second.results);
			assertEquals(1, first.provider.payments); assertEquals(1, second.provider.payments);
		}
	}

	@Test public void rejectedWorkerReturnsUnknownWithoutFallbackOrRepeatedSdkCall(){
		try(var controller=Robolectric.buildActivity(Activity.class).setup()){
			Harness h=new Harness();
			AlipaySdkAdapter.Engine engine=new AlipaySdkAdapter.Engine(h.provider, task->{ throw new java.util.concurrent.RejectedExecutionException(); }, h.main);
			engine.pay(controller.get(), ORDER, h.results::add); h.main.drain();
			assertEquals(List.of("UNKNOWN"), h.results); assertEquals(0, h.provider.payments);
		}
	}

	@Test public void fakeProviderRunsOffMainAndCallbackAlwaysReturnsToMain() throws Exception{
		try(var controller=Robolectric.buildActivity(Activity.class).setup()){
			ExecutorService worker=Executors.newSingleThreadExecutor();
			try{
				FakeProvider provider=new FakeProvider(); Handler main=new Handler(Looper.getMainLooper());
				AtomicReference<Thread> callbackThread=new AtomicReference<>(); AtomicReference<String> status=new AtomicReference<>();
				CountDownLatch releaseWorker=new CountDownLatch(1);
				AlipaySdkAdapter.Engine engine=new AlipaySdkAdapter.Engine(provider, task->worker.execute(()->{
					try{
						if(!releaseWorker.await(5, TimeUnit.SECONDS)) throw new AssertionError("Worker was not released");
					}catch(InterruptedException interrupted){ Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
					task.run();
				}), task->main.post(task));
				engine.pay(controller.get(), ORDER, result->{ callbackThread.set(Thread.currentThread()); status.set(result); });
				ShadowLooper.idleMainLooper();
				assertEquals(0, provider.payments); assertNull(status.get()); releaseWorker.countDown();
				worker.submit(()->{}).get(5, TimeUnit.SECONDS);
				assertEquals(1, provider.payments); assertNotSame(Thread.currentThread(), provider.paymentThread);
				assertNull(status.get()); ShadowLooper.idleMainLooper();
				assertEquals("9000", status.get()); assertSame(Looper.getMainLooper().getThread(), callbackThread.get());
			}finally{ worker.shutdownNow(); }
		}
	}

	@Test public void nativeCapabilityRequiresEnabledInstalledProductionWalletNotMerchantSettings(){
		Context context=ApplicationProvider.getApplicationContext();
		assertFalse(AlipaySdkAdapter.available(null)); assertFalse(AlipaySdkAdapter.available(context));
		installWallet(context, "com.eg.android.AlipayGphoneRC", true, ApplicationInfo.FLAG_INSTALLED);
		installWallet(context, "hk.alipay.wallet", true, ApplicationInfo.FLAG_INSTALLED);
		assertFalse(AlipaySdkAdapter.available(context));
		installWallet(context, WALLET, true, ApplicationInfo.FLAG_INSTALLED);
		assertTrue(AlipaySdkAdapter.available(context));
		installWallet(context, WALLET, false, ApplicationInfo.FLAG_INSTALLED);
		assertFalse(AlipaySdkAdapter.available(context));
		installWallet(context, WALLET, true, ApplicationInfo.FLAG_INSTALLED | ApplicationInfo.FLAG_SUSPENDED);
		assertFalse(AlipaySdkAdapter.available(context));
	}

	private static void installWallet(Context context, String packageName, boolean enabled, int flags){
		PackageInfo info=new PackageInfo(); info.packageName=packageName;
		info.applicationInfo=new ApplicationInfo(); info.applicationInfo.packageName=packageName;
		info.applicationInfo.enabled=enabled; info.applicationInfo.flags=flags;
		Shadow.<ShadowPackageManager>extract(context.getPackageManager()).installPackage(info);
	}
}
