package org.joinmastodon.android.sponsors;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import com.alipay.sdk.app.PayTask;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Variant-only RC provider, using the unchanged Engine's lifecycle/status/delivery rules. */
final class SandboxAlipayLauncher implements SponsorPaymentCoordinator.PaymentLauncher{
	private static final Handler MAIN=new Handler(Looper.getMainLooper());
	private static final AlipaySdkAdapter.Engine ENGINE=new AlipaySdkAdapter.Engine(new RcProvider(), Executors.newSingleThreadExecutor(task->{
		Thread thread=new Thread(task, "sandbox-alipay-sdk"); thread.setDaemon(true); return thread;
	}), task->{ if(!MAIN.post(task)) throw new RejectedExecutionException(); });
	private final Activity activity;
	SandboxAlipayLauncher(Activity activity){ this.activity=activity; }
	@Override public boolean available(){ return ENGINE.available(activity); }
	@Override public SponsorPaymentCoordinator.Cancelable pay(String orderString, Consumer<String> completed){
		AlipaySdkAdapter.Cancelable call=ENGINE.pay(activity, orderString, completed::accept);
		return call::cancel;
	}
	static final class RcProvider implements AlipaySdkAdapter.Provider{
		@Override public boolean available(Context context){
			try{
				Class<?> sdk=Class.forName("com.alipay.sdk.app.PayTask", false, SandboxAlipayLauncher.class.getClassLoader());
				sdk.getConstructor(Activity.class);
				if(!Map.class.isAssignableFrom(sdk.getMethod("payV2", String.class, boolean.class).getReturnType())) return false;
				ApplicationInfo wallet=context.getPackageManager().getApplicationInfo("com.eg.android.AlipayGphoneRC", 0);
				return wallet.enabled && (wallet.flags & ApplicationInfo.FLAG_INSTALLED)!=0 && (wallet.flags & ApplicationInfo.FLAG_SUSPENDED)==0;
			}catch(ReflectiveOperationException | PackageManager.NameNotFoundException unavailable){ return false; }
		}
		@Override public Map<String, String> pay(Activity activity, String orderString){ return new PayTask(activity).payV2(orderString, true); }
	}
}
