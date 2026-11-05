package org.joinmastodon.android.sponsors;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import com.alipay.sdk.app.PayTask;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Native wallet transport only. Every SDK status still requires authoritative server reconciliation. */
public final class AlipaySdkAdapter{
	private static final String WALLET_PACKAGE="com.eg.android.AlipayGphone";
	private static final Handler MAIN=new Handler(Looper.getMainLooper());
	private static final Engine NATIVE=new Engine(new NativeProvider(), Executors.newSingleThreadExecutor(task->{
		Thread thread=new Thread(task, "sponsor-alipay-sdk");
		thread.setDaemon(true);
		return thread;
	}), task->{
		if(!MAIN.post(task)) throw new RejectedExecutionException();
	});

	private AlipaySdkAdapter(){}

	public interface Callback{ void complete(String resultStatus); }
	public interface Cancelable{ void cancel(); }

	/** SDK + installed/enabled production wallet only; NOT merchant readiness, policy or verified limits. */
	public static boolean available(Context context){ return NATIVE.available(context); }

	/**
	 * Consumes only a server-signed order string, calls payV2 once off-main and delivers only a
	 * whitelisted resultStatus on main. Missing capability, invalid input or SDK failure is UNKNOWN.
	 * A null callback never starts payment. Null/finishing/destroyed owners receive no callback and
	 * do not start a new SDK call. An owner destroyed during payment suppresses its late callback.
	 * cancel() releases/suppresses the page callback ONLY: an already scheduled payment can still run;
	 * it neither interrupts the SDK nor closes, cancels or proves nonpayment of the backend order.
	 */
	public static Cancelable pay(Activity activity, String orderStr, Callback callback){
		return NATIVE.pay(activity, orderStr, callback);
	}

	/* Per-instance, package-private seams for same-package tests. No mutable global SDK override,
	 * public test mode, Intent/BuildConfig switch or runtime provider registration exists. */
	interface Provider{
		boolean available(Context context);
		Map<String, String> pay(Activity activity, String orderStr);
	}

	static final class Engine{
		private final Provider provider;
		private final Executor worker, main;
		private final AtomicBoolean paying=new AtomicBoolean();

		Engine(Provider provider, Executor worker, Executor main){
			this.provider=provider; this.worker=worker; this.main=main;
		}

		boolean available(Context context){
			if(context==null) return false;
			try{ return provider.available(context); }
			catch(RuntimeException | LinkageError unavailable){ return false; }
		}

		Cancelable pay(Activity activity, String orderStr, Callback callback){
			Delivery delivery=new Delivery(activity, callback);
			if(callback!=null) post(delivery, ()->start(delivery, orderStr), false);
			return delivery;
		}

		private void start(Delivery delivery, String orderStr){
			Activity owner=delivery.owner.get();
			if(!usable(owner)){
				delivery.cancel();
				return;
			}
			if(orderStr==null || orderStr.isBlank() || !available(owner) || !paying.compareAndSet(false, true)){
				post(delivery, ()->delivery.complete("UNKNOWN"), false);
				return;
			}
			try{
				worker.execute(()->{
					String status="UNKNOWN";
					try{
						// Recheck lifecycle and capability after queueing; never launch a browser fallback.
						if(usable(owner) && available(owner)){
							Map<String, String> result=provider.pay(owner, orderStr);
							status=normalizeStatus(result==null ? null : result.get("resultStatus"));
						}
					}catch(RuntimeException | LinkageError failure){
						// A transport failure is not proof that the signed order was unpaid. No retry.
						status="UNKNOWN";
					}
					String resultStatus=status;
					post(delivery, ()->{
						paying.set(false);
						delivery.complete(resultStatus);
					}, true);
				});
			}catch(RuntimeException | LinkageError unavailable){
				paying.set(false);
				post(delivery, ()->delivery.complete("UNKNOWN"), false);
			}
		}

		private void post(Delivery delivery, Runnable task, boolean releasesPayment){
			try{ main.execute(task); }
			catch(RejectedExecutionException unavailable){
				if(releasesPayment) paying.set(false);
				delivery.cancel(); // Never deliver on the worker if main is unavailable.
			}
		}
	}

	static String normalizeStatus(String status){
		if(status==null) return "UNKNOWN";
		return switch(status){
			case "9000", "8000", "6001", "4000", "6002", "6004", "5000" -> status;
			default -> "UNKNOWN";
		};
	}

	private static boolean usable(Activity activity){
		return activity!=null && !activity.isFinishing() && !activity.isDestroyed();
	}

	private static final class Delivery implements Cancelable{
		private final WeakReference<Activity> owner;
		private final AtomicReference<Callback> callback;

		Delivery(Activity activity, Callback callback){
			owner=new WeakReference<>(activity);
			this.callback=new AtomicReference<>(callback);
		}
		@Override public void cancel(){ callback.set(null); }
		void complete(String status){
			Callback target=callback.getAndSet(null);
			if(target!=null && usable(owner.get())) target.complete(status);
		}
	}

	private static final class NativeProvider implements Provider{
		@Override public boolean available(Context context){
			try{
				// Reflection without initialization checks linkage without starting SDK telemetry/UI.
				Class<?> sdk=Class.forName("com.alipay.sdk.app.PayTask", false, AlipaySdkAdapter.class.getClassLoader());
				sdk.getConstructor(Activity.class);
				if(!Map.class.isAssignableFrom(sdk.getMethod("payV2", String.class, boolean.class).getReturnType())) return false;
				ApplicationInfo wallet=context.getPackageManager().getApplicationInfo(WALLET_PACKAGE, 0);
				return wallet.enabled && (wallet.flags & ApplicationInfo.FLAG_INSTALLED)!=0
						&& (wallet.flags & ApplicationInfo.FLAG_SUSPENDED)==0;
			}catch(ReflectiveOperationException | PackageManager.NameNotFoundException unavailable){
				return false;
			}
		}
		@Override public Map<String, String> pay(Activity activity, String orderStr){
			return new PayTask(activity).payV2(orderStr, true);
		}
	}
}
