package org.joinmastodon.android.ui.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;

import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.api.MastodonAPIController;
import org.joinmastodon.android.api.session.AccountSession;
import org.joinmastodon.android.api.session.AccountSessionManager;
import org.joinmastodon.android.api.session.ContentAccessCoordinator;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

import me.grishka.appkit.imageloader.ImageCache;
import me.grishka.appkit.imageloader.downloaders.ImageDownloader;
import me.grishka.appkit.imageloader.processing.ImageProcessingStep;
import me.grishka.appkit.imageloader.requests.ImageLoaderRequest;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Only authenticated first-party media, with owner-scoped memory keys and a private disk cache. */
public final class ContentMediaImageDownloader extends ImageDownloader{
	private static final int MAX_BYTES=20*1024*1024, MAX_PIXELS=32*1024*1024;
	private static final long DISK_MAX_BYTES=256L*1024*1024, DISK_MAX_ENTRY=4L*1024*1024;
	private static final Object DISK_LOCK=new Object();
	private static final java.util.concurrent.ExecutorService PREFETCH=java.util.concurrent.Executors.newFixedThreadPool(2,r->{
		Thread t=new Thread(r,"ContentMediaPrefetch");
		t.setDaemon(true);
		return t;
	});
	private static final java.util.Set<String> PREFETCHING=java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static final class PrefetchHolder{
		static final ContentMediaImageDownloader INSTANCE=new ContentMediaImageDownloader();
	}
	/** Avatars/emoji are small but get evicted from the shared LRU by large post media; keep a
	 * bounded side cache so scrolling an avatar back into view never refetches it. */
	private static final android.util.LruCache<String,Drawable> smallImages=new android.util.LruCache<>(1024);
	private final OkHttpClient client;
	public ContentMediaImageDownloader(){ this(MastodonAPIController.getHttpClient()); }
	public ContentMediaImageDownloader(OkHttpClient client){ this.client=client.newBuilder().cache(null).followRedirects(false).followSslRedirects(false).build(); }
	public static boolean isMedia(Uri uri){
		return ContentAccessCoordinator.isFirstParty(uri) && uri.getFragment()==null && uri.getEncodedPath()!=null && uri.getEncodedPath().startsWith("/api/v1/media/")
				&& !uri.getEncodedPath().contains("%") && !uri.getEncodedPath().contains("/../");
	}
	public static ImageLoaderRequest scoped(ImageLoaderRequest original){
		if(!(original instanceof UrlImageLoaderRequest url) || !isMedia(url.uri)) return original;
		if(original instanceof ScopedRequest scoped) return scoped.current() ? scoped : null;
		AccountSession session=AccountSessionManager.getInstance().getLastActiveAccount();
		if(!ContentAccessCoordinator.applies(session)) return null;
		ContentAccessCoordinator.Owner owner=session.getContentAccessCoordinator().capture();
		return owner!=null && owner.canFetchMedia() ? new ScopedRequest(original,url.uri,session,owner) : null;
	}
	public static boolean accepts(ImageLoaderRequest request){ return !(request instanceof ScopedRequest scoped) || scoped.current(); }
	public static Drawable peekSmall(android.graphics.drawable.Drawable ignored, ImageLoaderRequest original){
		ImageLoaderRequest request=scoped(original);
		if(request instanceof ScopedRequest scoped && scoped.isSmall()) return smallImages.get(scoped.getMemoryCacheKey());
		return null;
	}
	private static boolean isSmall(int width, int height){ return width>0 && height>0 && width<=256 && height<=256; }
	@Override public boolean needsDiskCache(){ return false; }
	@Override public boolean canHandleRequest(ImageLoaderRequest request){ return request instanceof UrlImageLoaderRequest url && isMedia(url.uri); }

	/** Warm disk/network for offscreen list images without touching the UI thread. */
	public static void prefetch(ImageLoaderRequest request){
		if(!(request instanceof ScopedRequest scoped) || !scoped.current()) return;
		String key=scoped.getMemoryCacheKey();
		if(!PREFETCHING.add(key)) return;
		PREFETCH.execute(()->{
			try{
				if(scoped.current() && readDisk(scoped)==null) PrefetchHolder.INSTANCE.getDrawable(scoped,false,null);
			}catch(IOException|RuntimeException ignored){
			}finally{
				PREFETCHING.remove(key);
			}
		});
	}

	/** Drop the private media cache when the account identity changes. */
	public static void clearDiskCache(){
		new Thread(()->{
			File dir=diskDir();
			if(dir!=null) deleteTree(dir);
		}, "ContentMediaCacheClear").start();
	}

	@Override public Drawable getDrawable(ImageLoaderRequest request, boolean decode, ImageCache.ImageDownloadInfo info) throws IOException{
		// Callers may hand us an unwrapped URL request (avatars, settings rows). Bind it to the current
		// account/owner here instead of rejecting it, so authenticated bytes never enter the global cache.
		if(!(request instanceof ScopedRequest)){
			ImageLoaderRequest wrapped=scoped(request);
			if(!(wrapped instanceof ScopedRequest scopedRequest) || !scopedRequest.current()) throw new IOException("Content media owner unavailable");
			return getDrawable(scopedRequest,decode,info);
		}
		ScopedRequest scoped=(ScopedRequest)request;
		if(!scoped.current()) throw new IOException("Content media owner unavailable");
		byte[] cached=readDisk(scoped);
		if(cached!=null){
			try{
				return decode(cached,scoped,decode);
			}catch(IOException invalid){
				deleteDisk(scoped);
			}
		}
		Request http=new Request.Builder().url(scoped.uri.toString()).header("Authorization","Bearer "+scoped.tokenValue)
				.header("Cache-Control","no-store").tag(ContentAccessCoordinator.Owner.class,scoped.owner).build();
		Call call=client.newCall(http); if(info!=null) info.httpCall=call;
		try(Response response=call.execute()){
			if(!scoped.current() || !response.isSuccessful() || !isMedia(Uri.parse(response.request().url().toString())) || response.body()==null)
				throw new IOException("Content media unavailable");
			if(response.body().contentLength()>MAX_BYTES) throw new IOException("Content media too large");
			ByteArrayOutputStream output=new ByteArrayOutputStream(); byte[] chunk=new byte[8192]; int count;
			try(java.io.InputStream input=response.body().byteStream()){
				while((count=input.read(chunk))!=-1){ if(output.size()+count>MAX_BYTES || !scoped.current()) throw new IOException("Content media unavailable"); output.write(chunk,0,count); }
			}
			byte[] bytes=output.toByteArray();
			if(!scoped.current()) throw new IOException("Content media owner changed");
			writeDisk(scoped,bytes);
			return decode(bytes,scoped,decode);
		}finally{ if(info!=null) info.httpCall=null; }
	}

	private Drawable decode(byte[] bytes, ScopedRequest scoped, boolean decode) throws IOException{
		if(!decode) return null;
		BitmapFactory.Options options=new BitmapFactory.Options(); options.inJustDecodeBounds=true; BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
		if(options.outWidth<=0 || options.outHeight<=0 || (long)options.outWidth*options.outHeight>MAX_PIXELS) throw new IOException("Invalid content media image");
		options.inJustDecodeBounds=false; options.inSampleSize=1;
		int width=scoped.desiredMaxWidth>0 ? scoped.desiredMaxWidth : 2048, height=scoped.desiredMaxHeight>0 ? scoped.desiredMaxHeight : 2048;
		while(options.outWidth/(options.inSampleSize*2)>=width && options.outHeight/(options.inSampleSize*2)>=height) options.inSampleSize*=2;
		options.inPreferredConfig=scoped.desiredConfig==Bitmap.Config.HARDWARE ? Bitmap.Config.ARGB_8888 : scoped.desiredConfig;
		Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options); if(bitmap==null) throw new IOException("Invalid content media image");
		if(!scoped.current()){ bitmap.recycle(); throw new IOException("Content media owner changed"); }
		Drawable drawable=new BitmapDrawable(MastodonApp.context.getResources(),bitmap);
		for(ImageProcessingStep step:scoped.processingSteps) drawable=step.processDrawable(drawable);
		if(scoped.isSmall()) smallImages.put(scoped.getMemoryCacheKey(),drawable);
		if(!scoped.current()) throw new IOException("Content media owner changed");
		return drawable;
	}

	private static File diskDir(){
		File dir=new File(MastodonApp.context.getCacheDir(),"content-media");
		return dir.isDirectory() || dir.mkdirs() ? dir : null;
	}

	private static byte[] readDisk(ScopedRequest request){
		File dir=diskDir();
		if(dir==null) return null;
		File file=new File(dir,request.getDiskCacheKey());
		if(!file.isFile() || file.length()<=0 || file.length()>DISK_MAX_ENTRY) return null;
		try(FileInputStream input=new FileInputStream(file)){
			byte[] data=new byte[(int)file.length()];
			int read=0;
			while(read<data.length){
				int count=input.read(data,read,data.length-read);
				if(count<0) break;
				read+=count;
			}
			return read==data.length ? data : null;
		}catch(IOException unavailable){
			return null;
		}
	}

	private static void writeDisk(ScopedRequest request, byte[] bytes){
		if(bytes==null || bytes.length<=0 || bytes.length>DISK_MAX_ENTRY) return;
		File dir=diskDir();
		if(dir==null) return;
		String key=request.getDiskCacheKey();
		File target=new File(dir,key);
		File temp=new File(dir,key+".tmp-"+Thread.currentThread().getId());
		try(FileOutputStream output=new FileOutputStream(temp)){
			output.write(bytes);
		}catch(IOException unavailable){
			temp.delete();
			return;
		}
		synchronized(DISK_LOCK){
			if(target.exists() && !target.delete()){ temp.delete(); return; }
			if(!temp.renameTo(target)){ temp.delete(); return; }
			trimLocked(dir);
		}
	}

	private static void deleteDisk(ScopedRequest request){
		File dir=diskDir();
		if(dir!=null) new File(dir,request.getDiskCacheKey()).delete();
	}

	private static void trimLocked(File dir){
		File[] files=dir.listFiles((d,name)->!name.contains(".tmp-"));
		if(files==null || files.length==0) return;
		long total=0;
		for(File file:files) total+=file.length();
		if(total<=DISK_MAX_BYTES) return;
		Arrays.sort(files, Comparator.comparingLong(File::lastModified));
		for(File file:files){
			if(total<=DISK_MAX_BYTES) break;
			long size=file.length();
			if(file.delete()) total-=size;
		}
	}

	private static void deleteTree(File file){
		File[] children=file.listFiles();
		if(children!=null) for(File child:children) deleteTree(child);
		file.delete();
	}

	private static final class ScopedRequest extends UrlImageLoaderRequest{
		final ContentAccessCoordinator.Owner owner;
		final AccountSession session;
		final org.joinmastodon.android.model.Token token;
		final String tokenValue, scope;
		ScopedRequest(ImageLoaderRequest original,Uri uri,AccountSession session,ContentAccessCoordinator.Owner owner){
			super(original.desiredConfig,original.desiredMaxWidth,original.desiredMaxHeight,original.processingSteps,uri);
			this.session=session; this.owner=owner; token=session.token; tokenValue=token.accessToken;
			// Stable per account credential so the disk cache survives process restarts without ever
			// mixing accounts or exposing the token itself.
			scope=owner.mediaScope();
		}
		boolean current(){ return owner.canFetchMedia() && session.token==token && Objects.equals(tokenValue,token.accessToken); }
		boolean isSmall(){ return ContentMediaImageDownloader.isSmall(desiredMaxWidth,desiredMaxHeight); }
		@Override public String getMemoryCacheKey(){ return super.getMemoryCacheKey()+"/content-"+scope; }
		@Override public String getDiskCacheKey(){ return okio.ByteString.encodeUtf8(getMemoryCacheKey()).md5().hex(); }
		@Override public boolean equals(Object other){ return other instanceof ScopedRequest value && scope.equals(value.scope) && super.equals(other); }
		@Override public int hashCode(){ return Objects.hash(super.hashCode(),scope); }
		@Override public boolean sourcesEqual(ImageLoaderRequest other){ return other instanceof ScopedRequest value && scope.equals(value.scope) && super.sourcesEqual(other); }
	}
}
