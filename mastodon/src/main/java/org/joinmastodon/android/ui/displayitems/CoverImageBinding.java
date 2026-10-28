package org.joinmastodon.android.ui.displayitems;

import android.content.Context;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import me.grishka.appkit.imageloader.ImageCache;
import me.grishka.appkit.imageloader.requests.ImageLoaderRequest;

/** Holder-local state only; downloads and the public URL cache keys remain owned by AppKit. */
final class CoverImageBinding{
	record Key(String accountID, String parentID, String entityID, Object presentation, String requestKey){}

	private Key key;
	private Drawable image;
	// Keep budgets across rebinds, including A -> B -> A, without growing forever on a long feed.
	private final Map<Key, Integer> retryCounts=new LinkedHashMap<>(16, .75f, true){
		@Override protected boolean removeEldestEntry(Map.Entry<Key, Integer> eldest){ return size()>64; }
	};

	Drawable bind(Context context, Key next, ImageLoaderRequest request){
		if(!Objects.equals(key, next)) image=null;
		key=next;
		if(next==null || request==null){
			image=null;
			return null;
		}
		ImageCache cache=ImageCache.getInstance(context);
		Drawable cached=cache.getFromTop(request); // Memory only: never read disk or start a second loader.
		if(usable(cached)) image=cached;
		else if(usable(image)){
			// The wrapper calls clearImage on a top-cache miss immediately after model.bind().
			// Re-admit only this permitted, exact binding's displayed image, so that path also
			// sees a hit. Explicit clearImage still clears both the view and our reference.
			cache.put(request, image);
		}else image=null;
		return image;
	}

	boolean matches(Key current){ return key!=null && key.equals(current); }

	Drawable loaded(Drawable drawable){
		image=usable(drawable) ? drawable : null;
		return image;
	}

	void clear(){ image=null; }

	boolean shouldRetry(Key current){
		if(!matches(current)) return false;
		int count=retryCounts.getOrDefault(key, 0);
		if(count>=2) return false;
		retryCounts.put(key, count+1);
		return true;
	}

	private static boolean usable(Drawable drawable){
		return drawable!=null && (!(drawable instanceof BitmapDrawable bitmap) || !bitmap.getBitmap().isRecycled());
	}
}
