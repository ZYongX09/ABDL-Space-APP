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
	private static Transport transport=new BootTransport();

	private SharedViewRom(){}

	interface Transport{
		boolean available();
		boolean enabled() throws Exception;
		Map<String, String> extras() throws Exception;
		ActivityOptions fromView(View view, Bundle config) throws Exception;
		ActivityOptions fromBitmap(Bitmap bitmap, Rect bounds, float radius, Bundle config) throws Exception;
		void override(Activity activity, Bundle config) throws Exception;
		boolean cancel() throws Exception;
	}

	/** 100 means this bridge verified the complete V1 ABI plus the ROM's boolean feature flag. */
	public static int supportedProtocol(){
		try{ return transport.available() && transport.enabled() ? 100 : 0; }
		catch(Exception | LinkageError unavailable){ return 0; }
	}

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

	private static final class BootTransport implements Transport{
		private boolean inspected;
		private ReflectionTransport delegate;
		@Override public boolean available(){
			if(!inspected){
				inspected=true;
				try{
					// null is the boot loader: never mistake an APK's placeholder for a ROM implementation.
					delegate=ReflectionTransport.inspect(
							Class.forName("com.androidex.animation.sharedview.ViewAnimationManager", false, null),
							Class.forName("com.androidex.animation.sharedview.ViewAnimationListener", false, null));
				}catch(ReflectiveOperationException | RuntimeException | LinkageError ignored){}
			}
			return delegate!=null;
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
