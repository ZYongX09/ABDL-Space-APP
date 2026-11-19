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
import java.io.IOException;
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

/** Only authenticated first-party media, with ephemeral owner-scoped memory keys and no disk cache. */
public final class ContentMediaImageDownloader extends ImageDownloader{
	private static final int MAX_BYTES=20*1024*1024, MAX_PIXELS=32*1024*1024;
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
		return owner!=null && owner.canRead() ? new ScopedRequest(original,url.uri,session,owner) : null;
	}
	public static boolean accepts(ImageLoaderRequest request){ return !(request instanceof ScopedRequest scoped) || scoped.current(); }
	@Override public boolean needsDiskCache(){ return false; }
	@Override public boolean canHandleRequest(ImageLoaderRequest request){ return request instanceof UrlImageLoaderRequest url && isMedia(url.uri); }
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
			if(!decode) return null;
			byte[] bytes=output.toByteArray(); BitmapFactory.Options options=new BitmapFactory.Options(); options.inJustDecodeBounds=true; BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
			if(options.outWidth<=0 || options.outHeight<=0 || (long)options.outWidth*options.outHeight>MAX_PIXELS) throw new IOException("Invalid content media image");
			options.inJustDecodeBounds=false; options.inSampleSize=1;
			int width=request.desiredMaxWidth>0 ? request.desiredMaxWidth : 2048, height=request.desiredMaxHeight>0 ? request.desiredMaxHeight : 2048;
			while(options.outWidth/(options.inSampleSize*2)>=width && options.outHeight/(options.inSampleSize*2)>=height) options.inSampleSize*=2;
			options.inPreferredConfig=request.desiredConfig==Bitmap.Config.HARDWARE ? Bitmap.Config.ARGB_8888 : request.desiredConfig;
			Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options); if(bitmap==null) throw new IOException("Invalid content media image");
			if(!scoped.current()){ bitmap.recycle(); throw new IOException("Content media owner changed"); }
			Drawable drawable=new BitmapDrawable(MastodonApp.context.getResources(),bitmap);
			for(ImageProcessingStep step:request.processingSteps) drawable=step.processDrawable(drawable);
			if(!scoped.current()) throw new IOException("Content media owner changed");
			return drawable;
		}finally{ if(info!=null) info.httpCall=null; }
	}
	private static final class ScopedRequest extends UrlImageLoaderRequest{
		final ContentAccessCoordinator.Owner owner;
		final AccountSession session;
		final org.joinmastodon.android.model.Token token;
		final String tokenValue, scope;
		ScopedRequest(ImageLoaderRequest original,Uri uri,AccountSession session,ContentAccessCoordinator.Owner owner){
			super(original.desiredConfig,original.desiredMaxWidth,original.desiredMaxHeight,original.processingSteps,uri);
			this.session=session; this.owner=owner; token=session.token; tokenValue=token.accessToken;
			// Random in-memory epoch avoids credential material and cross-process/account reuse in cache keys.
			scope=owner.mediaScope();
		}
		boolean current(){ return owner.canRead() && session.token==token && Objects.equals(tokenValue,token.accessToken); }
		@Override public String getMemoryCacheKey(){ return super.getMemoryCacheKey()+"/content-"+scope; }
		@Override public String getDiskCacheKey(){ return okio.ByteString.encodeUtf8(getMemoryCacheKey()).md5().hex(); }
		@Override public boolean equals(Object other){ return other instanceof ScopedRequest value && scope.equals(value.scope) && super.equals(other); }
		@Override public int hashCode(){ return Objects.hash(super.hashCode(),scope); }
		@Override public boolean sourcesEqual(ImageLoaderRequest other){ return other instanceof ScopedRequest value && scope.equals(value.scope) && super.sourcesEqual(other); }
	}
}
