package org.joinmastodon.android.sponsors;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;

import java.util.ArrayDeque;
import java.util.Map;

/**
 * Test-source-only UI transport seam. Opt in with @Config(shadows=AlipaySdkAdapterShadow.class),
 * call configure(true, "9000"), idle main to queue the payment, then drainSdk() and idle main
 * again to deliver its result. Production static calls otherwise remain the real native provider.
 */
@Implements(AlipaySdkAdapter.class)
public final class AlipaySdkAdapterShadow{
	private static final ArrayDeque<Runnable> WORK=new ArrayDeque<>();
	private static boolean walletAvailable;
	private static String resultStatus="UNKNOWN";
	private static int calls;
	private static AlipaySdkAdapter.Engine engine=createEngine();

	private static AlipaySdkAdapter.Engine createEngine(){
		return new AlipaySdkAdapter.Engine(new AlipaySdkAdapter.Provider(){
			@Override public boolean available(Context context){ return walletAvailable; }
			@Override public Map<String, String> pay(Activity activity, String orderStr){
				calls++;
				return Map.of("resultStatus", resultStatus);
			}
		}, WORK::addLast, task->new Handler(Looper.getMainLooper()).post(task));
	}

	public static void configure(boolean available, String status){
		reset(); walletAvailable=available; resultStatus=AlipaySdkAdapter.normalizeStatus(status);
	}
	public static int calls(){ return calls; }
	public static int pending(){ return WORK.size(); }
	public static void drainSdk(){ while(!WORK.isEmpty()) WORK.removeFirst().run(); }

	@Implementation public static boolean available(Context context){ return engine.available(context); }
	@Implementation public static AlipaySdkAdapter.Cancelable pay(Activity activity, String orderStr, AlipaySdkAdapter.Callback callback){
		return engine.pay(activity, orderStr, callback);
	}

	@Resetter public static void reset(){
		WORK.clear(); walletAvailable=false; resultStatus="UNKNOWN"; calls=0; engine=createEngine();
	}
}
