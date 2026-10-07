package org.joinmastodon.android.albums;

import android.view.Window;
import android.view.WindowManager;

import java.util.WeakHashMap;

/** Main-thread, window-local leases. Never remove a flag present before the first album lease. */
public final class AlbumSecureWindow{
	private static final WeakHashMap<Window, State> windows=new WeakHashMap<>();
	private AlbumSecureWindow(){}
	private static class State{
		int leases;
		final boolean originallySecure;
		State(Window window){ originallySecure=(window.getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE)!=0; }
	}
	public static Scope acquire(Window window){
		State state=windows.get(window);
		if(state==null){ state=new State(window); windows.put(window, state); }
		state.leases++;
		window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
		return new Scope(window);
	}
	public static final class Scope implements AutoCloseable{
		private Window window;
		private Scope(Window window){ this.window=window; }
		@Override public void close(){
			Window current=window; window=null;
			if(current==null) return;
			State state=windows.get(current);
			if(state!=null && --state.leases==0){
				windows.remove(current);
				if(!state.originallySecure) current.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
			}
		}
	}
}
