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
	private static final java.util.concurrent.atomic.AtomicLong diskGeneration=new java.util.concurrent.atomic.AtomicLong();
	private static volatile long clearedDiskGeneration;
	private static final long DISK_MAX_BYTES=256L*1024*1024, DISK_MAX_ENTRY=4L*1024*1024;
	private static final Object DISK_LOCK=new Object();
	// Small images get their own byte budget so a scroll through large media does not evict every avatar.
	private static final android.util.LruCache<String,Drawable> smallImages=new android.util.LruCache<>(8*1024*1024){
		@Override protected int sizeOf(String key, Drawable image){
			return image instanceof BitmapDrawable bitmap ? bitmap.getBitmap().getAllocationByteCount() : 4096;
		}
	};
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
	public static boolean accepts(ImageLoaderRequest request){
		if(request instanceof CachedImageRequest cached) return cached.current();
		return !(request instanceof ScopedRequest scoped) || scoped.current();
	}
	public static Drawable peekSmall(android.graphics.drawable.Drawable ignored, ImageLoaderRequest original){
		ImageLoaderRequest request=scoped(original);
		if(request instanceof ScopedRequest scoped && scoped.isSmall()) return smallImages.get(scoped.getMemoryCacheKey());
		return null;
	}
	private static boolean isSmall(int width, int height){ return width>0 && height>0 && width<=256 && height<=256; }
	@Override public boolean needsDiskCache(){ return false; }
	@Override public boolean canHandleRequest(ImageLoaderRequest request){
		return request instanceof CachedImageRequest || request instanceof UrlImageLoaderRequest url && isMedia(url.uri);
	}

	/** Cached timeline rows do not authorize a fetch of their stored media URLs. */
	public static ImageLoaderRequest cacheOnly(ImageLoaderRequest request, AccountSession session){
		if(request==null || session==null) return null;
		ImageLoaderRequest source=scoped(request);
		if(source==null) return null;
		CachedImageRequest cached=new CachedImageRequest(source, session);
		return cached.current() ? cached : null;
	}

	/** Drop the private media cache when the account identity changes. */
	public static void clearDiskCache(){
		long retired=diskGeneration.incrementAndGet();
		smallImages.evictAll();
		new Thread(()->{
			synchronized(DISK_LOCK){
				if(clearedDiskGeneration>=retired) return;
				File dir=new File(MastodonApp.context.getCacheDir(), "content-media");
				if(dir.exists()) deleteTree(dir);
				clearedDiskGeneration=diskGeneration.get();
			}
		}, "ContentMediaCacheClear").start();
	}

	@Override public Drawable getDrawable(ImageLoaderRequest request, boolean decode, ImageCache.ImageDownloadInfo info) throws IOException{
		if(request instanceof CachedImageRequest cached) return readCacheOnly(cached, decode);
		// Legacy image widgets still use raw URLs; bind their wire read to the current credential.
		if(!(request instanceof ScopedRequest scoped)){
			ImageLoaderRequest bound=scoped(request);
			if(!(bound instanceof ScopedRequest)) throw new IOException("Content media owner unavailable");
			return getDrawable(bound,decode,info);
		}
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

	private Drawable readCacheOnly(CachedImageRequest cached, boolean decode) throws IOException{
		if(!cached.current()) throw new IOException("Cached media owner unavailable");
		ImageCache cache=ImageCache.getInstance(MastodonApp.context);
		Drawable drawable=cache.getFromTop(cached.original);
		if(drawable==null){
			if(cached.original instanceof ScopedRequest scoped){
				if(scoped.isSmall()) drawable=smallImages.get(scoped.getMemoryCacheKey());
				if(drawable==null){
					byte[] bytes=readDisk(scoped);
					if(bytes!=null) drawable=decode(bytes, scoped, decode);
				}
			}else{
				File file=cache.getFile(cached.original);
				if(file!=null && decode) drawable=cache.decodeImage(file, null, cached.original);
			}
		}
		if(!cached.current()) throw new IOException("Cached media owner changed");
		return drawable;
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
		if(clearedDiskGeneration!=diskGeneration.get() || !request.current()) return null;
		File dir=diskDir();
		if(dir==null) return null;
		File file=new File(dir,request.diskKey);
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
		String key=request.diskKey;
		File target=new File(dir,key);
		File temp=new File(dir,key+".tmp-"+Thread.currentThread().getId());
		try(FileOutputStream output=new FileOutputStream(temp)){
			output.write(bytes);
		}catch(IOException unavailable){
			temp.delete();
			return;
		}
		synchronized(DISK_LOCK){
			if(clearedDiskGeneration!=diskGeneration.get() || !request.current()){ temp.delete(); return; }
			if(target.exists() && !target.delete()){ temp.delete(); return; }
			if(!temp.renameTo(target)){ temp.delete(); return; }
			trimLocked(dir);
		}
	}

	private static void deleteDisk(ScopedRequest request){
		File dir=diskDir();
		if(dir!=null) new File(dir,request.diskKey).delete();
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

	private static final class CachedImageRequest extends ImageLoaderRequest{
		final ImageLoaderRequest original;
		final AccountSession session;
		final org.joinmastodon.android.model.Token token;
		final ContentAccessCoordinator.Owner owner;
		final String tokenValue, key, dispatchKey;
		CachedImageRequest(ImageLoaderRequest original, AccountSession session){
			super(original.desiredConfig, original.desiredMaxWidth, original.desiredMaxHeight, original.processingSteps);
			this.original=original;
			this.session=session;
			token=session.token;
			tokenValue=token==null ? null : token.accessToken;
			owner=ContentAccessCoordinator.applies(session) ? session.getContentAccessCoordinator().capture() : null;
			String scope=owner==null ? org.joinmastodon.android.api.cache.HomeTimelineSnapshot.identity(session.getID(), tokenValue) : owner.mediaMemoryScope();
			key=original.getMemoryCacheKey()+"/offline-"+scope;
			dispatchKey=okio.ByteString.encodeUtf8(key).sha256().hex();
		}
		boolean current(){
			return token!=null && session.token==token && Objects.equals(tokenValue, token.accessToken)
					&& AccountSessionManager.getInstance().getLastActiveAccount()==session
					&& (!ContentAccessCoordinator.applies(session) || owner!=null && owner.canFetchMedia()) && NBWImageRequests.accepts(original);
		}
		@Override public String getMemoryCacheKey(){ return key; }
		@Override public String getDiskCacheKey(){ return dispatchKey; }
		@Override public boolean sourcesEqual(ImageLoaderRequest other){ return other instanceof CachedImageRequest cached && key.equals(cached.key) && original.sourcesEqual(cached.original); }
		@Override public boolean equals(Object other){ return other instanceof CachedImageRequest cached && key.equals(cached.key); }
		@Override public int hashCode(){ return key.hashCode(); }
	}

	private static final class ScopedRequest extends UrlImageLoaderRequest{
		final ContentAccessCoordinator.Owner owner;
		final AccountSession session;
		final org.joinmastodon.android.model.Token token;
		final String tokenValue, scope, diskKey, dispatchKey;
		ScopedRequest(ImageLoaderRequest original,Uri uri,AccountSession session,ContentAccessCoordinator.Owner owner){
			super(original.desiredConfig,original.desiredMaxWidth,original.desiredMaxHeight,original.processingSteps,uri);
			this.session=session; this.owner=owner; token=session.token; tokenValue=token.accessToken;
			// Stable disk bytes, but a separate in-process epoch prevents reusing retired callbacks or drawables.
			scope=owner.mediaMemoryScope();
			diskKey=okio.ByteString.encodeUtf8(super.getMemoryCacheKey()+"/content-"+owner.mediaDiskScope()).sha256().hex();
			dispatchKey=okio.ByteString.encodeUtf8(getMemoryCacheKey()).sha256().hex();
		}
		boolean current(){ return owner.canFetchMedia() && session.token==token && Objects.equals(tokenValue,token.accessToken); }
		boolean isSmall(){ return ContentMediaImageDownloader.isSmall(desiredMaxWidth,desiredMaxHeight); }
		@Override public String getMemoryCacheKey(){ return super.getMemoryCacheKey()+"/content-"+scope; }
		@Override public String getDiskCacheKey(){ return dispatchKey; }
		@Override public boolean equals(Object other){ return other instanceof ScopedRequest value && scope.equals(value.scope) && super.equals(other); }
		@Override public int hashCode(){ return Objects.hash(super.hashCode(),scope); }
		@Override public boolean sourcesEqual(ImageLoaderRequest other){ return other instanceof ScopedRequest value && scope.equals(value.scope) && super.sourcesEqual(other); }
	}
}
