package org.joinmastodon.android.albums;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.squareup.otto.Subscribe;

import org.joinmastodon.android.E;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.events.AccountLoggedOutEvent;
import org.joinmastodon.android.model.Token;

import java.lang.ref.WeakReference;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Short-lived, process-only pixels for public, non-sensitive, non-protected timeline covers.
 * Callers MUST check those facts on the current model before EVERY get/put, require its fresh
 * public projection URL, and invalidate when that projection/policy is withdrawn. This is not
 * an ACL cache: a signed path alone cannot establish that an album is public. Never use this
 * helper for private album/viewer media, or reuse an expired model to authorize a lookup.
 *
 * Only the seven known COS signing fields may differ for an alias hit. No request URL is
 * returned, rewritten, fetched or persisted; the caller keeps its complete signed network URL.
 */
public final class AlbumPublicCoverMemory{
	private static final String HOST="abdl-1339643562.cos.ap-shanghai.myqcloud.com";
	private static final String UUID="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
	// The middle UUID is an upload batch, NOT the model's album ID.
	private static final Pattern PATH=Pattern.compile("/albums/([1-9][0-9]{0,18})/"+UUID+"/"+UUID+"/preview\\.(?:jpg|webp)");
	private static final Pattern TIME=Pattern.compile("(0|[1-9][0-9]{0,12});(0|[1-9][0-9]{0,12})");
	private static final Pattern ACCESS_KEY=Pattern.compile("[A-Za-z0-9_-]{1,128}");
	private static final Pattern SHA1=Pattern.compile("[0-9a-f]{40}");
	private static final long TTL_MS=60_000L;
	private static final int MAX_ENTRIES=16;
	private static final long MAX_BYTES=8L*1024*1024, MAX_BITMAP_BYTES=2L*1024*1024;
	private static final AlbumPublicCoverMemory INSTANCE=new AlbumPublicCoverMemory();

	private final LinkedHashMap<Key, Entry> entries=new LinkedHashMap<>(16, .75f, true);
	private final Handler handler=new Handler(Looper.getMainLooper());
	private final Runnable expiryTask=()->{
		synchronized(this){ purge(SystemClock.elapsedRealtime(), System.currentTimeMillis()); }
	};
	private Identity identity;
	private long retainedBytes;

	private AlbumPublicCoverMemory(){ E.register(this); }

	/** Dimensions are the exact decode-request pixel bounds, not the source photo's dimensions. */
	public static Drawable get(Context context, String accountID, String albumID, String signedUrl, int width, int height){
		synchronized(INSTANCE){
			long elapsed=SystemClock.elapsedRealtime(), wall=System.currentTimeMillis();
			INSTANCE.purge(elapsed, wall);
			if(context==null || context.getApplicationContext()==null || !validNamespace(albumID) || width<=0 || height<=0) return null;
			SignedCover cover=parse(signedUrl, wall);
			Identity scope=INSTANCE.scope(accountID);
			if(cover==null || scope==null) return null;
			Entry entry=INSTANCE.entries.get(new Key(scope, albumID, cover.path, width, height));
			if(entry==null || !scope.current()) return null;
			// A new wrapper/state uses application resources; no source Drawable or Activity is retained.
			return new BitmapDrawable(context.getApplicationContext().getResources(), entry.bitmap);
		}
	}

	public static void put(Context context, String accountID, String albumID, String signedUrl, int width, int height, Drawable drawable){
		synchronized(INSTANCE){
			long elapsed=SystemClock.elapsedRealtime(), wall=System.currentTimeMillis();
			INSTANCE.purge(elapsed, wall);
			if(context==null || context.getApplicationContext()==null || !validNamespace(albumID) || width<=0 || height<=0
					|| !(drawable instanceof BitmapDrawable bitmapDrawable)) return;
			SignedCover cover=parse(signedUrl, wall);
			Identity scope=INSTANCE.scope(accountID);
			if(cover==null || scope==null) return;
			Bitmap bitmap=bitmapDrawable.getBitmap();
			long bytes=bitmapBytes(bitmap);
			if(bytes<=0 || bytes>MAX_BITMAP_BYTES || !scope.current()) return;
			Key key=new Key(scope, albumID, cover.path, width, height);
			// Rebinding a cached Drawable (or another signature) must not slide the original lifetime.
			if(INSTANCE.entries.get(key)!=null) return;
			long lifetime=Math.min(TTL_MS, cover.expiresWall-wall);
			INSTANCE.entries.put(key, new Entry(bitmap, bytes, elapsed, elapsed+lifetime, cover.expiresWall));
			INSTANCE.retainedBytes+=bytes;
			Iterator<Entry> iterator=INSTANCE.entries.values().iterator();
			while(INSTANCE.entries.size()>MAX_ENTRIES || INSTANCE.retainedBytes>MAX_BYTES){
				INSTANCE.retainedBytes-=iterator.next().bytes;
				iterator.remove();
			}
			INSTANCE.schedulePurge();
		}
	}

	/** Drops only the supplied account/album namespace, including old incarnations of that session. */
	public static void invalidate(String accountID, String albumID){
		synchronized(INSTANCE){
			Iterator<Map.Entry<Key, Entry>> iterator=INSTANCE.entries.entrySet().iterator();
			while(iterator.hasNext()){
				Map.Entry<Key, Entry> entry=iterator.next();
				if(Objects.equals(accountID, entry.getKey().identity.accountID) && Objects.equals(albumID, entry.getKey().albumID)){
					INSTANCE.retainedBytes-=entry.getValue().bytes;
					iterator.remove();
				}
			}
			INSTANCE.schedulePurge();
		}
	}

	public static void clearAll(){
		synchronized(INSTANCE){ INSTANCE.clear(); }
	}

	@Subscribe public void onAccountLoggedOut(AccountLoggedOutEvent event){
		synchronized(this){
			if(event!=null && identity!=null && identity.accountID.equals(event.id)) clear();
		}
	}

	private void clear(){
		// Bitmaps may still belong to a view/image loader. Drop references; never recycle their pixels.
		entries.clear();
		retainedBytes=0;
		identity=null;
		handler.removeCallbacks(expiryTask);
	}

	private Identity scope(String accountID){
		AccountSession session=activeSession(accountID);
		if(session==null) return null;
		if(identity!=null && (!identity.accountID.equals(accountID) || !identity.matches(session))) clear();
		if(identity==null) identity=new Identity(accountID, session);
		return identity;
	}

	private void purge(long elapsed, long wall){
		if(identity!=null && !identity.current()) clear();
		Iterator<Entry> iterator=entries.values().iterator();
		while(iterator.hasNext()){
			Entry entry=iterator.next();
			if(elapsed<entry.createdElapsed || elapsed>=entry.expiresElapsed || wall>=entry.expiresWall || bitmapBytes(entry.bitmap)<=0){
				retainedBytes-=entry.bytes;
				iterator.remove();
			}
		}
		schedulePurge();
	}

	private void schedulePurge(){
		handler.removeCallbacks(expiryTask);
		if(entries.isEmpty()) return;
		long next=Long.MAX_VALUE;
		for(Entry entry:entries.values()) next=Math.min(next, entry.expiresElapsed);
		handler.postDelayed(expiryTask, Math.max(1L, next-SystemClock.elapsedRealtime()));
	}

	private static AccountSession activeSession(String accountID){
		if(accountID==null || accountID.isBlank()) return null;
		AccountSessionManager manager=AccountSessionManager.getInstance();
		if(!accountID.equals(manager.getLastActiveAccountID())) return null;
		AccountSession session=manager.tryGetAccount(accountID);
		return session!=null && session.activated && session.self!=null && session.domain!=null
				&& accountID.equals(session.getID()) && session.token!=null && session.token.accessToken!=null
				&& !session.token.accessToken.isBlank() ? session : null;
	}

	private static boolean validNamespace(String albumID){ return albumID!=null && !albumID.isBlank() && albumID.length()<=256; }

	private static long bitmapBytes(Bitmap bitmap){
		if(bitmap==null || bitmap.isRecycled()) return 0;
		try{ return Math.max(bitmap.getAllocationByteCount(), bitmap.getByteCount()); }
		catch(IllegalStateException ignored){ return 0; }
	}

	private static SignedCover parse(String signedUrl, long wall){
		if(signedUrl==null || signedUrl.length()>4096) return null;
		try{
			URI uri=new URI(signedUrl);
			if(!"https".equals(uri.getScheme()) || !HOST.equals(uri.getRawAuthority()) || uri.getRawUserInfo()!=null
					|| uri.getPort()!=-1 || uri.getRawFragment()!=null || uri.isOpaque()) return null;
			String path=uri.getRawPath();
			if(path==null || !path.equals(uri.getPath())) return null; // No percent/double encoding or encoded separators.
			Matcher managed=PATH.matcher(path);
			if(!managed.matches() || Long.parseLong(managed.group(1))<=0) return null;
			String query=uri.getRawQuery();
			if(query==null || !query.equals(uri.getQuery())) return null;
			String[] pairs=query.split("&", -1);
			if(pairs.length!=7) return null; // Unknown transforms/parameters are never stripped for reuse.
			Map<String, String> fields=new HashMap<>();
			for(String pair:pairs){
				int equals=pair.indexOf('=');
				if(equals<=0) return null;
				String name=pair.substring(0, equals), value=pair.substring(equals+1);
				switch(name){
					case "q-sign-algorithm", "q-ak", "q-sign-time", "q-key-time", "q-header-list", "q-url-param-list", "q-signature" -> {
						if(fields.put(name, value)!=null) return null;
					}
					default -> { return null; }
				}
			}
			if(!"sha1".equals(fields.get("q-sign-algorithm")) || !ACCESS_KEY.matcher(fields.get("q-ak")).matches()
					|| !"host".equals(fields.get("q-header-list")) || !"".equals(fields.get("q-url-param-list"))
					|| !SHA1.matcher(fields.get("q-signature")).matches()
					|| !Objects.equals(fields.get("q-sign-time"), fields.get("q-key-time"))) return null;
			Matcher time=TIME.matcher(fields.get("q-sign-time"));
			if(!time.matches()) return null;
			long start=Long.parseLong(time.group(1)), end=Long.parseLong(time.group(2));
			if(end<=start || end-start>TTL_MS/1000 || start*1000>wall || end*1000<=wall) return null;
			return new SignedCover(path, end*1000);
		}catch(URISyntaxException | NumberFormatException ignored){ return null; }
	}

	private static final class SignedCover{
		final String path;
		final long expiresWall;
		SignedCover(String path, long expiresWall){ this.path=path; this.expiresWall=expiresWall; }
	}

	private static final class Identity{
		final String accountID;
		final WeakReference<AccountSession> session;
		final WeakReference<Token> token;
		final WeakReference<String> accessToken;
		Identity(String accountID, AccountSession session){
			this.accountID=accountID;
			this.session=new WeakReference<>(session);
			token=new WeakReference<>(session.token);
			accessToken=new WeakReference<>(session.token.accessToken);
		}
		boolean matches(AccountSession current){
			return current!=null && session.get()==current && token.get()==current.token
					&& current.token!=null && accessToken.get()!=null && accessToken.get()==current.token.accessToken;
		}
		boolean current(){ return matches(activeSession(accountID)); }
	}

	private static final class Key{
		final Identity identity;
		final String albumID, path;
		final int width, height;
		Key(Identity identity, String albumID, String path, int width, int height){
			this.identity=identity; this.albumID=albumID; this.path=path; this.width=width; this.height=height;
		}
		@Override public boolean equals(Object other){
			return other instanceof Key key && identity==key.identity && width==key.width && height==key.height
					&& albumID.equals(key.albumID) && path.equals(key.path);
		}
		@Override public int hashCode(){ return Objects.hash(System.identityHashCode(identity), albumID, path, width, height); }
	}

	private static final class Entry{
		final Bitmap bitmap;
		final long bytes, createdElapsed, expiresElapsed, expiresWall;
		Entry(Bitmap bitmap, long bytes, long createdElapsed, long expiresElapsed, long expiresWall){
			this.bitmap=bitmap; this.bytes=bytes; this.createdElapsed=createdElapsed; this.expiresElapsed=expiresElapsed; this.expiresWall=expiresWall;
		}
	}
}
