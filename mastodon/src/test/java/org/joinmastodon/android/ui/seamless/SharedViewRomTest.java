package org.joinmastodon.android.ui.seamless;

import android.app.Activity;
import android.app.ActivityOptions;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.joinmastodon.android.ui.seamless.SharedViewRom.Availability.*;
import static org.junit.Assert.*;

/** Hand-written signature fixtures, checked against the demo's 1.0.0.4/1.0.0.5 common V1 ABI.
 * No KIT implementation, vendor-package placeholder or Android runtime is needed by these tests. */
public class SharedViewRomTest{
	@Before
	public void resetRom(){
		FullRom.active=true;
		FullRom.failure=null;
		FullRom.queries=0;
		Calls.lastCall=null;
		Calls.lastArguments=null;
		Calls.metadata=new HashMap<>();
		Calls.cancelled=true;
	}

	@After
	public void resetTransport(){ SharedViewRom.setTransportForTests(null); }

	@Test
	public void completePublicStaticAbiInvokesBothSourcesOverrideAndCancel() throws Exception{
		var bridge=SharedViewRom.ReflectionTransport.inspect(FullRom.class, Listener.class);
		assertTrue(bridge.available());
		assertTrue(bridge.enabled());
		assertEquals(1, FullRom.queries);
		assertSame(Calls.metadata, bridge.extras());
		assertNull(bridge.fromView(null, null));
		assertEquals("view", Calls.lastCall);
		assertArrayEquals(new Object[]{null, null, null}, Calls.lastArguments);
		assertNull(bridge.fromBitmap(null, null, 8.5f, null));
		assertEquals("bitmap", Calls.lastCall);
		assertArrayEquals(new Object[]{null, null, 8.5f, null, null}, Calls.lastArguments);
		bridge.override(null, null);
		assertEquals("override", Calls.lastCall);
		assertArrayEquals(new Object[]{null, null}, Calls.lastArguments);
		assertTrue(bridge.cancel());
		Calls.cancelled=false;
		assertFalse(bridge.cancel());
	}

	@Test
	public void getMethodAcceptsInheritedPublicMethodsCallbacksAndCovariantMap() throws Exception{
		var bridge=SharedViewRom.ReflectionTransport.inspect(InheritedRom.class, Listener.class);
		assertTrue(bridge.enabled());
		assertSame(Calls.metadata, bridge.extras());
		var covariant=SharedViewRom.ReflectionTransport.inspect(CovariantExtrasRom.class, Listener.class);
		assertEquals(Map.of("live", "metadata"), covariant.extras());
	}

	@Test
	public void instancePrivateBoxedIncompleteAndWrongParameterAbisAreRejected(){
		for(Class<?> invalid:new Class<?>[]{InstanceEnabledRom.class, PrivateEnabledRom.class,
				BoxedEnabledRom.class, MissingBitmapRom.class, WrongBitmapResultRom.class, WrongRadiusRom.class, HiddenRom.class}){
			assertThrows(invalid.getSimpleName(), NoSuchMethodException.class,
					()->SharedViewRom.ReflectionTransport.inspect(invalid, Listener.class));
		}
	}

	@Test
	public void listenerMustHavePublicInstanceVoidCallbacksAndTheExactParameterIdentity(){
		for(Class<?> invalid:new Class<?>[]{Runnable.class, StaticListener.class, WrongResultListener.class,
				NotAnInterface.class, HiddenListener.class, DifferentListener.class}){
			assertThrows(invalid.getSimpleName(), NoSuchMethodException.class,
					()->SharedViewRom.ReflectionTransport.inspect(FullRom.class, invalid));
		}
	}

	@Test
	public void bootCachesReflectionTransportButQueriesEnabledAndMetadataLive() throws Exception{
		AtomicInteger lookups=new AtomicInteger();
		var boot=new SharedViewRom.BootTransport(name->{ lookups.incrementAndGet(); return fixture(name); });
		SharedViewRom.setTransportForTests(boot);
		assertTrue(boot.available());
		assertEquals(0, FullRom.queries); // ABI inspection must not freeze the live feature flag.
		Object cached=delegate(boot);
		assertNotNull(cached);
		assertEquals(100, SharedViewRom.supportedProtocol());
		FullRom.active=false;
		assertEquals(DISABLED, SharedViewRom.availability());
		assertEquals(0, SharedViewRom.supportedProtocol());
		assertTrue(boot.available()); // Interface exists even when the system disables the feature.
		FullRom.failure=new IllegalStateException("temporary query failure");
		assertEquals(QUERY_FAILED, SharedViewRom.availability());
		FullRom.failure=null;
		FullRom.active=true;
		assertEquals(SUPPORTED, SharedViewRom.availability());
		Calls.metadata=Map.of(SharedViewRom.BOUNDS, "rect|WINDOW|bounds");
		assertSame(Calls.metadata, boot.extras());
		Calls.metadata=null;
		assertEquals(Map.of(), SharedViewRom.extras());
		assertSame("Live failures must not discard or reinspect a verified ABI", cached, delegate(boot));
		assertEquals(2, lookups.get());
		assertEquals(5, FullRom.queries);
	}

	@Test
	public void missingBootManagerIsUnsupportedAndNegativeAbiIsCached(){
		AtomicInteger lookups=new AtomicInteger();
		var boot=new SharedViewRom.BootTransport(name->{ lookups.incrementAndGet(); throw new ClassNotFoundException(name); });
		SharedViewRom.setTransportForTests(boot);
		assertEquals(UNSUPPORTED, SharedViewRom.availability());
		assertEquals(0, SharedViewRom.supportedProtocol());
		assertFalse(boot.available());
		assertEquals(1, lookups.get());
		assertEquals(0, FullRom.queries);
	}

	@Test
	public void missingListenerOrInvalidManagerIsIncompatibleRatherThanSystemDisabled(){
		AtomicInteger lookups=new AtomicInteger();
		var missingListener=new SharedViewRom.BootTransport(name->{
			lookups.incrementAndGet();
			if(name.endsWith("Manager")) return FullRom.class;
			throw new ClassNotFoundException(name);
		});
		SharedViewRom.setTransportForTests(missingListener);
		assertEquals(INTERFACE_INCOMPATIBLE, SharedViewRom.availability());
		assertEquals(INTERFACE_INCOMPATIBLE, SharedViewRom.availability());
		assertEquals(2, lookups.get());
		SharedViewRom.setTransportForTests(new SharedViewRom.BootTransport(name->name.endsWith("Manager") ? InstanceEnabledRom.class : Listener.class));
		assertEquals(INTERFACE_INCOMPATIBLE, SharedViewRom.availability());
		assertEquals(0, FullRom.queries);
	}

	@Test
	public void failedClassQueryIsNotCachedAsAnAbsentOrIncompatibleRom(){
		AtomicInteger lookups=new AtomicInteger();
		SharedViewRom.setTransportForTests(new SharedViewRom.BootTransport(name->{
			if(lookups.incrementAndGet()==1) throw new SecurityException("query temporarily blocked");
			return fixture(name);
		}));
		assertEquals(QUERY_FAILED, SharedViewRom.availability());
		assertEquals(SUPPORTED, SharedViewRom.availability());
		assertEquals(3, lookups.get());
	}

	@Test
	public void linkedClassQueryFailureFailsClosedAndCanBeCheckedAgain(){
		AtomicInteger lookups=new AtomicInteger();
		SharedViewRom.setTransportForTests(new SharedViewRom.BootTransport(name->{
			if(lookups.incrementAndGet()==1) throw new NoClassDefFoundError("missing dependency");
			return fixture(name);
		}));
		assertEquals(QUERY_FAILED, SharedViewRom.availability());
		assertEquals(100, SharedViewRom.supportedProtocol());
	}

	@Test
	public void concurrentProbeNeverObservesAPartiallyPublishedReflectionTransport() throws Exception{
		var entered=new CountDownLatch(1);
		var release=new CountDownLatch(1);
		var secondEntered=new CountDownLatch(1);
		var executor=Executors.newFixedThreadPool(2);
		var boot=new SharedViewRom.BootTransport(name->{
			if(name.endsWith("Manager")){
				entered.countDown();
				try{ if(!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("probe was not released"); }
				catch(InterruptedException interrupted){ Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
			}
			return fixture(name);
		});
		try{
			var first=executor.submit(boot::available);
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			var second=executor.submit(()->{ secondEntered.countDown(); return boot.available(); });
			assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
			assertThrows(TimeoutException.class, ()->second.get(100, TimeUnit.MILLISECONDS));
			release.countDown();
			assertTrue(first.get(5, TimeUnit.SECONDS));
			assertTrue(second.get(5, TimeUnit.SECONDS));
		}finally{
			release.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	public void productionLookupDoesNotMistakeAppClassPathForBootRom(){
		assertNotNull(FullRom.class.getClassLoader());
		SharedViewRom.setTransportForTests(null);
		assertEquals(UNSUPPORTED, SharedViewRom.availability());
		assertEquals(0, SharedViewRom.supportedProtocol());
	}

	private static Class<?> fixture(String name) throws ClassNotFoundException{
		return switch(name){
			case "com.androidex.animation.sharedview.ViewAnimationManager" -> FullRom.class;
			case "com.androidex.animation.sharedview.ViewAnimationListener" -> Listener.class;
			default -> throw new ClassNotFoundException(name);
		};
	}
	private static Object delegate(SharedViewRom.BootTransport boot) throws Exception{
		Field field=SharedViewRom.BootTransport.class.getDeclaredField("delegate");
		field.setAccessible(true);
		return field.get(boot);
	}

	public interface Callbacks{
		void onAnimationStart(int mode);
		void onAnimationCancel(int mode);
		void onAnimationEnd(int mode);
	}
	public interface Listener extends Callbacks{}
	public interface DifferentListener extends Callbacks{}
	interface HiddenListener extends Callbacks{}
	public interface StaticListener{
		static void onAnimationStart(int mode){}
		void onAnimationCancel(int mode);
		void onAnimationEnd(int mode);
	}
	public interface WrongResultListener{
		int onAnimationStart(int mode);
		void onAnimationCancel(int mode);
		void onAnimationEnd(int mode);
	}
	public static class NotAnInterface implements Listener{
		@Override public void onAnimationStart(int mode){}
		@Override public void onAnimationCancel(int mode){}
		@Override public void onAnimationEnd(int mode){}
	}

	public static class Calls{
		static String lastCall;
		static Object[] lastArguments;
		static Map<String, String> metadata;
		static boolean cancelled;
		public Calls(){ throw new AssertionError("Static ROM ABI must not instantiate a manager"); }
		public static Map<String, String> getViewAnimationExtras(){ return metadata; }
		public static ActivityOptions makeViewAnimationOptions(View view, Bundle config, Listener listener){
			lastCall="view"; lastArguments=new Object[]{view, config, listener}; return null;
		}
		public static ActivityOptions makeViewAnimationOptions(Bitmap bitmap, Rect bounds, float radius, Bundle config, Listener listener){
			lastCall="bitmap"; lastArguments=new Object[]{bitmap, bounds, radius, config, listener}; return null;
		}
		public static void overrideViewAnimationTransition(Activity activity, Bundle config){ lastCall="override"; lastArguments=new Object[]{activity, config}; }
		public static boolean cancelCurrentViewAnimation(){ return cancelled; }
	}
	public static class FullRom extends Calls{
		static boolean active;
		static RuntimeException failure;
		static int queries;
		public static boolean isFeatureEnabled(){ queries++; if(failure!=null) throw failure; return active; }
	}
	public static class InheritedRom extends FullRom{}
	static class HiddenRom extends FullRom{}
	public static class CovariantExtrasRom extends FullRom{
		public static LinkedHashMap<String, String> getViewAnimationExtras(){ return new LinkedHashMap<>(Map.of("live", "metadata")); }
	}
	public static class InstanceEnabledRom extends Calls{ public boolean isFeatureEnabled(){ return true; } }
	public static class PrivateEnabledRom extends Calls{ private static boolean isFeatureEnabled(){ return true; } }
	public static class BoxedEnabledRom extends Calls{ public static Boolean isFeatureEnabled(){ return true; } }
	public static class MissingBitmapRom{
		public static boolean isFeatureEnabled(){ return true; }
		public static Map<String, String> getViewAnimationExtras(){ return Map.of(); }
		public static ActivityOptions makeViewAnimationOptions(View view, Bundle config, Listener listener){ return null; }
		public static void overrideViewAnimationTransition(Activity activity, Bundle config){}
		public static boolean cancelCurrentViewAnimation(){ return true; }
	}
	public static class WrongBitmapResultRom extends MissingBitmapRom{
		public static Object makeViewAnimationOptions(Bitmap bitmap, Rect bounds, float radius, Bundle config, Listener listener){ return null; }
	}
	public static class WrongRadiusRom extends MissingBitmapRom{
		public static ActivityOptions makeViewAnimationOptions(Bitmap bitmap, Rect bounds, double radius, Bundle config, Listener listener){ return null; }
	}
}
