package org.joinmastodon.android.ui.seamless;

import android.app.Activity;
import android.app.ActivityOptions;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/** Optional ROM protocol, not an application-side animation engine. No vendor binary is bundled. */
public final class SharedViewRom{
	public static final String RADIUS="sharedView.radius", COLOR="sharedView.color", BOUNDS="sharedView.bounds";
	public static final String BACK="sharedView.backAnimType", FALLBACK="sharedView.fallbackAnimType";
	public static final int NONE=1, SYSTEM=2, FADE=3, EXPLODE=4;
	private static volatile Transport transport=new BootTransport();

	private SharedViewRom(){}

	/** ABI availability, the ROM's live flag and the user's preference are separate states. */
	public enum Availability{ SUPPORTED, UNSUPPORTED, INTERFACE_INCOMPATIBLE, DISABLED, QUERY_FAILED }

	interface Transport{
		boolean available();
		default Availability abiAvailability(){ return available() ? Availability.SUPPORTED : Availability.UNSUPPORTED; }
		boolean enabled() throws Exception;
		Map<String, String> extras() throws Exception;
		ActivityOptions fromView(View view, Bundle config) throws Exception;
		ActivityOptions fromBitmap(Bitmap bitmap, Rect bounds, float radius, Bundle config) throws Exception;
		void override(Activity activity, Bundle config) throws Exception;
		boolean cancel() throws Exception;
	}

	public static Availability availability(){
		Transport current=transport;
		try{
			Availability abi=current.abiAvailability();
			if(abi!=Availability.SUPPORTED) return abi;
			// Only the immutable ABI is cached. Disabled/failed queries must not poison later checks.
			return current.enabled() ? Availability.SUPPORTED : Availability.DISABLED;
		}catch(Exception | LinkageError unavailable){ return Availability.QUERY_FAILED; }
	}

	/** 100 is verified common V1, not the SDK/KIT version or a guess based on the brand. */
	public static int supportedProtocol(){ return availability()==Availability.SUPPORTED ? 100 : 0; }

	static Map<String, String> extras() throws Exception{
		Map<String, String> result=transport.extras();
		return result==null ? Collections.emptyMap() : result;
	}
	static ActivityOptions make(View view, Bundle config) throws Exception{ return transport.fromView(view, config); }
	static ActivityOptions make(Bitmap bitmap, Rect bounds, float radius, Bundle config) throws Exception{ return transport.fromBitmap(bitmap, bounds, radius, config); }
	static void override(Activity activity, Bundle config) throws Exception{ transport.override(activity, config); }
	static void cancel(){ try{ transport.cancel(); }catch(Exception | LinkageError ignored){} }

	static boolean windowCoordinates(Map<String, String> extras){
		String value=extras.get(BOUNDS);
		if(value==null) return false;
		String[] parts=value.split("\\|", -1);
		if(parts.length>1){
			String token=parts[1].trim().toUpperCase(Locale.ROOT);
			if("WINDOW".equals(token)) return true;
			if("SCREEN".equals(token)) return false;
		}
		return value.toUpperCase(Locale.ROOT).contains("WINDOW");
	}

	static boolean permits(Map<String, String> extras, String key, int type){
		String value=extras.get(key);
		if(value==null) return false;
		String[] parts=value.split("\\|", -1);
		if(!"int".equals(parts[0].trim())) return false;
		if(parts.length<2 || parts[1].isBlank()) return true; // Writable, precise set unspecified.
		String name=switch(type){ case NONE -> "VIEW_ANIM_NONE"; case SYSTEM -> "VIEW_ANIM_FOLLOW_SYSTEM"; case FADE -> "VIEW_ANIM_FADE_OUT"; case EXPLODE -> "VIEW_ANIM_EXPLODE_OUT"; default -> "VIEW_ANIM_DEFAULT"; };
		for(String token:parts[1].split(",")) if(name.equals(token.trim())) return true;
		return false;
	}

	// Package-private injection is solely a transport seam; production security gates are not injectable.
	static void setTransportForTests(Transport value){ transport=value==null ? new BootTransport() : value; }

	interface RomClassLookup{
		Class<?> load(String name) throws ClassNotFoundException;
	}

	static final class BootTransport implements Transport{
		private final RomClassLookup classes;
		private Availability abi;
		private ReflectionTransport delegate;
		BootTransport(){
			// Same ROM path as SharedViewDemo's KIT delegate; never use an APK-side placeholder.
			this(name->Class.forName(name, false, null));
		}
		BootTransport(RomClassLookup classes){ this.classes=classes; }
		@Override public boolean available(){ return abiAvailability()==Availability.SUPPORTED; }
		@Override public synchronized Availability abiAvailability(){
			if(abi==null){
				Class<?> manager;
				try{
					manager=classes.load("com.androidex.animation.sharedview.ViewAnimationManager");
				}catch(ClassNotFoundException missing){ return abi=Availability.UNSUPPORTED; }
				catch(RuntimeException | LinkageError failure){ return Availability.QUERY_FAILED; }
				try{
					Class<?> listener=classes.load("com.androidex.animation.sharedview.ViewAnimationListener");
					delegate=ReflectionTransport.inspect(manager, listener);
					abi=Availability.SUPPORTED;
				}catch(ClassNotFoundException | NoSuchMethodException incompatible){ abi=Availability.INTERFACE_INCOMPATIBLE; }
				catch(RuntimeException | LinkageError failure){ return Availability.QUERY_FAILED; }
			}
			// Publish the complete result under the same monitor, never a partial "inspected" flag.
			return abi;
		}
		@Override public boolean enabled() throws Exception{ return available() && delegate.enabled(); }
		@Override public Map<String, String> extras() throws Exception{ return available() ? delegate.extras() : Collections.emptyMap(); }
		@Override public ActivityOptions fromView(View view, Bundle config) throws Exception{ return available() ? delegate.fromView(view, config) : null; }
		@Override public ActivityOptions fromBitmap(Bitmap bitmap, Rect bounds, float radius, Bundle config) throws Exception{ return available() ? delegate.fromBitmap(bitmap, bounds, radius, config) : null; }
		@Override public void override(Activity activity, Bundle config) throws Exception{ if(available()) delegate.override(activity, config); }
		@Override public boolean cancel() throws Exception{ return available() && delegate.cancel(); }
	}

	static final class ReflectionTransport implements Transport{
		private final Method enabled, extras, view, bitmap, override, cancel;
		private ReflectionTransport(Method enabled, Method extras, Method view, Method bitmap, Method override, Method cancel){
			this.enabled=enabled; this.extras=extras; this.view=view; this.bitmap=bitmap; this.override=override; this.cancel=cancel;
		}
		static ReflectionTransport inspect(Class<?> manager, Class<?> listener) throws NoSuchMethodException{
			// The demo KIT checks public (including inherited) methods, static manager calls and
			// instance listener callbacks. Do not guess getInstance()/getDeclaredMethod() variants.
			if(!Modifier.isPublic(manager.getModifiers()) || !listener.isInterface() || !Modifier.isPublic(listener.getModifiers())) throw new NoSuchMethodException("Not a public ROM contract");
			for(String name:new String[]{"onAnimationStart", "onAnimationCancel", "onAnimationEnd"}){
				Method callback=listener.getMethod(name, int.class);
				if(Modifier.isStatic(callback.getModifiers()) || callback.getReturnType()!=void.class) throw new NoSuchMethodException("Invalid callback ABI");
			}
			return new ReflectionTransport(
					method(manager, "isFeatureEnabled", boolean.class), method(manager, "getViewAnimationExtras", Map.class),
					method(manager, "makeViewAnimationOptions", ActivityOptions.class, View.class, Bundle.class, listener),
					method(manager, "makeViewAnimationOptions", ActivityOptions.class, Bitmap.class, Rect.class, float.class, Bundle.class, listener),
					method(manager, "overrideViewAnimationTransition", void.class, Activity.class, Bundle.class),
					method(manager, "cancelCurrentViewAnimation", boolean.class));
		}
		private static Method method(Class<?> owner, String name, Class<?> result, Class<?>... args) throws NoSuchMethodException{
			Method method=owner.getMethod(name, args);
			if(!Modifier.isStatic(method.getModifiers()) || !result.isAssignableFrom(method.getReturnType())) throw new NoSuchMethodException("Invalid ROM ABI");
			return method;
		}
		@Override public boolean available(){ return true; }
		@Override public boolean enabled() throws Exception{ return Boolean.TRUE.equals(enabled.invoke(null)); }
		@Override @SuppressWarnings("unchecked") public Map<String, String> extras() throws Exception{ return (Map<String, String>)extras.invoke(null); }
		// A null listener is accepted by the verified V1 ABI. No synthetic vendor class or callback is shipped.
		@Override public ActivityOptions fromView(View source, Bundle config) throws Exception{ return (ActivityOptions)view.invoke(null, source, config, null); }
		@Override public ActivityOptions fromBitmap(Bitmap source, Rect bounds, float radius, Bundle config) throws Exception{ return (ActivityOptions)bitmap.invoke(null, source, bounds, radius, config, null); }
		@Override public void override(Activity activity, Bundle config) throws Exception{ override.invoke(null, activity, config); }
		@Override public boolean cancel() throws Exception{ return Boolean.TRUE.equals(cancel.invoke(null)); }
	}
}
