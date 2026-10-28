package org.joinmastodon.android.albums;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import com.squareup.otto.Subscribe;

import org.joinmastodon.android.E;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.events.AccountLoggedOutEvent;
import org.joinmastodon.android.model.Attachment;
import org.joinmastodon.android.model.Token;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import okhttp3.HttpUrl;

/**
 * Viewer-only preview RAM, NOT the public image loader or a source/download cache.
 * Callers must renew ACL and metadata before every lookup, and validate their request generation
 * again before putting decoded bytes. A hit only avoids bytes/decode, never the fresh ACL GET.
 * URLs are exact except the seven COS signing fields on the application's non-overwritable
 * owner/batch/photo preview path. All other query parameters remain part of content identity.
 */
public final class AlbumPreviewMemoryCache implements Application.ActivityLifecycleCallbacks{
	static final int MAX_ENTRIES=8, MAX_BYTES=24*1024*1024, MAX_ENTRY_BYTES=8*1024*1024;
	static final long TTL_MILLIS=120_000;
	private static AlbumPreviewMemoryCache instance;
	private final Application application;
	private final SharedPreferences accountPrefs;
	private final Handler main=new Handler(Looper.getMainLooper());
	private final LinkedHashMap<Key,Entry> entries=new LinkedHashMap<>(8, .75f, true);
	private final Runnable sweep=this::sweep;
	private final SharedPreferences.OnSharedPreferenceChangeListener accountListener=(prefs,key)->{
		if(key==null || "lastActiveAccount".equals(key)) sweep();
	};
	private int bytes;

	public static synchronized AlbumPreviewMemoryCache getInstance(Context context){
		Application app=(Application)context.getApplicationContext();
		if(instance==null || instance.application!=app){
			if(instance!=null) instance.dispose();
			instance=new AlbumPreviewMemoryCache(app);
		}
		return instance;
	}

	private AlbumPreviewMemoryCache(Application application){
		this.application=application;
		accountPrefs=application.getSharedPreferences("account_manager", Context.MODE_PRIVATE);
		accountPrefs.registerOnSharedPreferenceChangeListener(accountListener);
		application.registerActivityLifecycleCallbacks(this);
		E.register(this);
	}

	public synchronized Bitmap get(String accountID, AccountSession session, Attachment preview, int decodeSize, boolean downloadProtected){
		prune();
		if(!eligible(accountID, session, preview, downloadProtected)) return null;
		Entry entry=entries.get(new Key(accountID, preview, decodeSize));
		return entry!=null && entry.session.get()==session ? entry.bitmap : null;
	}

	public synchronized void put(String accountID, AccountSession session, Attachment preview, int decodeSize, boolean downloadProtected, Bitmap bitmap){
		prune();
		if(!eligible(accountID, session, preview, downloadProtected) || !usable(bitmap) || decodeSize<=0) return;
		long lifetime=lifetime(preview.previewUrl);
		if(lifetime<=0) return;
		Key key=new Key(accountID, preview, decodeSize);
		remove(key);
		Entry entry=new Entry(session, bitmap, SystemClock.elapsedRealtime(), lifetime);
		entries.put(key, entry); bytes+=entry.bytes;
		Iterator<Map.Entry<Key,Entry>> iterator=entries.entrySet().iterator();
		while((bytes>MAX_BYTES || entries.size()>MAX_ENTRIES) && iterator.hasNext()){
			bytes-=iterator.next().getValue().bytes; iterator.remove();
		}
		scheduleSweep();
	}

	private boolean eligible(String accountID, AccountSession session, Attachment preview, boolean downloadProtected){
		if(preview==null) return false;
		if(downloadProtected || preview.adminBlocked){ invalidatePhoto(accountID, session, preview.id); return false; }
		return selected(accountID, session) && preview.type==Attachment.Type.IMAGE && !TextUtils.isEmpty(preview.id)
				&& !TextUtils.isEmpty(preview.previewUrl) && preview.previewUrl.startsWith("https://")
				&& contentKey(preview.id, preview.previewUrl)!=null && lifetime(preview.previewUrl)>0
				&& preview.getWidth()>0 && preview.getHeight()>0;
	}

	public synchronized void invalidatePhoto(String accountID, AccountSession session, String photoID){
		Iterator<Map.Entry<Key,Entry>> iterator=entries.entrySet().iterator();
		while(iterator.hasNext()){
			Map.Entry<Key,Entry> entry=iterator.next();
			if(Objects.equals(accountID, entry.getKey().accountID) && entry.getValue().session.get()==session && Objects.equals(photoID, entry.getKey().photoID)){
				bytes-=entry.getValue().bytes; iterator.remove();
			}
		}
	}

	public synchronized void invalidateSession(String accountID, AccountSession session){
		Iterator<Map.Entry<Key,Entry>> iterator=entries.entrySet().iterator();
		while(iterator.hasNext()){
			Map.Entry<Key,Entry> entry=iterator.next();
			if(Objects.equals(accountID, entry.getKey().accountID) && entry.getValue().session.get()==session){
				bytes-=entry.getValue().bytes; iterator.remove();
			}
		}
	}

	@Subscribe public synchronized void onAccountLoggedOut(AccountLoggedOutEvent event){
		Iterator<Map.Entry<Key,Entry>> iterator=entries.entrySet().iterator();
		while(iterator.hasNext()){
			Map.Entry<Key,Entry> entry=iterator.next();
			if(Objects.equals(event.id, entry.getKey().accountID)){ bytes-=entry.getValue().bytes; iterator.remove(); }
		}
	}

	private static boolean selected(String accountID, AccountSession session){
		return session!=null && session.activated && session.token!=null && !TextUtils.isEmpty(session.token.accessToken)
				&& accountID!=null && AccountSessionManager.getInstance().tryGetAccount(accountID)==session
				&& accountID.equals(AccountSessionManager.getInstance().getLastActiveAccountID());
	}

	private static boolean usable(Bitmap bitmap){
		return bitmap!=null && !bitmap.isRecycled() && !bitmap.isMutable() && bitmap.getWidth()>0 && bitmap.getHeight()>0
				&& bitmap.getWidth()<=4096 && bitmap.getHeight()<=4096
				&& bitmap.getAllocationByteCount()>0 && bitmap.getAllocationByteCount()<=MAX_ENTRY_BYTES;
	}

	private void remove(Key key){ Entry old=entries.remove(key); if(old!=null) bytes-=old.bytes; }

	private void prune(){
		long now=SystemClock.elapsedRealtime();
		Iterator<Map.Entry<Key,Entry>> iterator=entries.entrySet().iterator();
		while(iterator.hasNext()){
			Map.Entry<Key,Entry> item=iterator.next(); Entry entry=item.getValue();
			AccountSession session=entry.session.get();
			if(!selected(item.getKey().accountID, session) || session.token!=entry.token.get() || session.token.accessToken!=entry.accessToken.get()
					|| now<entry.created || now-entry.created>=entry.lifetime || !usable(entry.bitmap)){
				bytes-=entry.bytes; iterator.remove();
			}
		}
	}

	private synchronized void sweep(){ prune(); scheduleSweep(); }
	private void scheduleSweep(){
		main.removeCallbacks(sweep);
		// Token/session replacement has no dedicated event. Keep a bounded watcher only while RAM exists.
		if(!entries.isEmpty()) main.postDelayed(sweep, 250);
	}

	private static final Set<String> COS_AUTH=Set.of("q-sign-algorithm", "q-ak", "q-sign-time", "q-key-time", "q-header-list", "q-url-param-list", "q-signature");

	/** Null rejects malformed keys; unrecognized hosts/paths keep their COMPLETE URL identity. */
	public static String contentKey(String photoID, String url){
		HttpUrl parsed=url==null ? null : HttpUrl.parse(url);
		if(parsed==null || !"https".equals(parsed.scheme()) || !parsed.username().isEmpty() || !parsed.password().isEmpty() || parsed.fragment()!=null) return null;
		Set<String> names=new HashSet<>();
		for(int i=0; i<parsed.querySize(); i++) if(!names.add(parsed.queryParameterName(i))) return null;
		String uuid="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
		String path=parsed.encodedPath();
		if(!"abdl-1339643562.cos.ap-shanghai.myqcloud.com".equals(parsed.host()) || parsed.port()!=443
				|| !path.matches("/albums/[1-9][0-9]*/"+uuid+"/"+uuid+"/preview\\.(jpg|webp)")
				|| !Objects.equals(photoID, parsed.pathSegments().get(3))) return "exact:"+url;
		if(!names.containsAll(COS_AUTH) || !"sha1".equals(parsed.queryParameter("q-sign-algorithm"))
				|| !Objects.toString(parsed.queryParameter("q-sign-time"), "").matches("[0-9]{1,12};[0-9]{1,12}")
				|| !Objects.toString(parsed.queryParameter("q-ak"), "").matches("[A-Za-z0-9_-]{1,256}")
				|| !"host".equals(parsed.queryParameter("q-header-list"))
				|| !"".equals(parsed.queryParameter("q-url-param-list"))
				|| !Objects.equals(parsed.queryParameter("q-sign-time"), parsed.queryParameter("q-key-time"))
				|| !Objects.toString(parsed.queryParameter("q-signature"), "").matches("[0-9a-f]{40}")) return null;
		List<String> contentParameters=new ArrayList<>();
		for(String parameter:parsed.encodedQuery().split("&", -1)){
			int equal=parameter.indexOf('=');
			if(equal<=0) return null;
			String name=parameter.substring(0,equal);
			// Encoded/unknown signing names are not whitelisted: they stay in content identity.
			if(!COS_AUTH.contains(name)) contentParameters.add(parameter);
		}
		Collections.sort(contentParameters);
		return "cos-preview:"+parsed.newBuilder().query(null).build()+"?"+String.join("&", contentParameters);
	}

	private static long lifetime(String url){
		long lifetime=TTL_MILLIS;
		HttpUrl parsed=HttpUrl.parse(url);
		if(parsed==null) return 0;
		if(parsed.queryParameterNames().contains("q-sign-time")){
			if(parsed.queryParameterValues("q-sign-time").size()!=1) return 0;
			String value=parsed.queryParameter("q-sign-time");
			if(value==null || !value.matches("[0-9]{1,12};[0-9]{1,12}")) return 0;
			try{
				long end=Long.parseLong(value.substring(value.indexOf(';')+1));
				lifetime=Math.min(lifetime, end*1000-System.currentTimeMillis());
			}catch(NumberFormatException error){ return 0; }
		}
		return lifetime;
	}

	private synchronized void dispose(){
		entries.clear(); bytes=0; main.removeCallbacks(sweep);
		accountPrefs.unregisterOnSharedPreferenceChangeListener(accountListener);
		application.unregisterActivityLifecycleCallbacks(this); E.unregister(this);
	}

	@Override public synchronized void onActivityStopped(Activity activity){
		// Also runs after a normally dismissed viewer: backgrounding must not leave private RAM behind.
		AccountSessionManager manager=AccountSessionManager.getInstance();
		String accountID=manager.getLastActiveAccountID();
		invalidateSession(accountID, manager.tryGetAccount(accountID)); prune();
	}
	@Override public void onActivityCreated(Activity activity, Bundle state){}
	@Override public void onActivityStarted(Activity activity){}
	@Override public void onActivityResumed(Activity activity){}
	@Override public void onActivityPaused(Activity activity){}
	@Override public void onActivitySaveInstanceState(Activity activity, Bundle state){}
	@Override public void onActivityDestroyed(Activity activity){}

	private static final class Entry{
		final WeakReference<AccountSession> session;
		final WeakReference<Token> token;
		final WeakReference<String> accessToken;
		final Bitmap bitmap;
		final int bytes;
		final long created, lifetime;
		Entry(AccountSession session, Bitmap bitmap, long created, long lifetime){
			this.session=new WeakReference<>(session); token=new WeakReference<>(session.token); accessToken=new WeakReference<>(session.token.accessToken);
			this.bitmap=bitmap; bytes=bitmap.getAllocationByteCount(); this.created=created; this.lifetime=lifetime;
		}
	}

	private static final class Key{
		final String accountID, photoID, url;
		final int width, height, decodeSize;
		Key(String accountID, Attachment preview, int decodeSize){
			this.accountID=accountID; photoID=preview.id; url=contentKey(photoID, preview.previewUrl);
			width=preview.getWidth(); height=preview.getHeight(); this.decodeSize=decodeSize;
		}
		@Override public boolean equals(Object other){
			return other instanceof Key key && width==key.width && height==key.height && decodeSize==key.decodeSize
					&& Objects.equals(accountID,key.accountID) && Objects.equals(photoID,key.photoID) && Objects.equals(url,key.url);
		}
		@Override public int hashCode(){ return Objects.hash(accountID,photoID,url,width,height,decodeSize); }
	}
}
