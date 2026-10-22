package org.joinmastodon.android.ui.utils;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import org.joinmastodon.android.R;
import org.joinmastodon.android.model.Account;

import java.io.IOException;

import me.grishka.appkit.imageloader.ImageCache;
import me.grishka.appkit.imageloader.downloaders.ImageDownloader;
import me.grishka.appkit.imageloader.processing.ImageProcessingStep;
import me.grishka.appkit.imageloader.requests.ImageLoaderRequest;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;

/** Decodes only the bundled NBW avatar; no HTTP, provider or resource-URI decoder is involved. */
public final class NBWAvatarImageDownloader extends ImageDownloader{
	private final Resources resources;

	public NBWAvatarImageDownloader(ImageCache cache, Context context){
		resources=context.getApplicationContext().getResources();
	}

	@Override
	public boolean needsDiskCache(){
		return false;
	}

	@Override
	public boolean canHandleRequest(ImageLoaderRequest request){
		return request instanceof UrlImageLoaderRequest url && Account.NBW_DEFAULT_AVATAR.equals(url.uri.toString());
	}

	@Override
	public Drawable getDrawable(ImageLoaderRequest request, boolean decode, ImageCache.ImageDownloadInfo info) throws IOException{
		if(!canHandleRequest(request)) throw new IOException("Not the bundled NBW avatar");
		if(!decode) return null;
		BitmapFactory.Options bounds=new BitmapFactory.Options();
		bounds.inJustDecodeBounds=true;
		bounds.inScaled=false;
		BitmapFactory.decodeResource(resources, R.drawable.nbw_default_avatar, bounds);
		if(bounds.outWidth<=0 || bounds.outHeight<=0) throw new IOException("Invalid bundled NBW avatar");
		float scale=1f;
		if(request.desiredMaxWidth>0) scale=Math.min(scale, request.desiredMaxWidth/(float)bounds.outWidth);
		if(request.desiredMaxHeight>0) scale=Math.min(scale, request.desiredMaxHeight/(float)bounds.outHeight);
		int width=Math.max(1, Math.round(bounds.outWidth*scale));
		int height=Math.max(1, Math.round(bounds.outHeight*scale));
		BitmapFactory.Options options=new BitmapFactory.Options();
		options.inScaled=false;
		options.inSampleSize=1;
		while(bounds.outWidth/(options.inSampleSize*2)>=width && bounds.outHeight/(options.inSampleSize*2)>=height)
			options.inSampleSize*=2;
		// Resize in software, then honour HARDWARE on the final bitmap rather than scaling a hardware source.
		options.inPreferredConfig=request.desiredConfig==Bitmap.Config.HARDWARE ? Bitmap.Config.ARGB_8888 : request.desiredConfig;
		Bitmap bitmap=BitmapFactory.decodeResource(resources, R.drawable.nbw_default_avatar, options);
		if(bitmap==null) throw new IOException("Could not decode bundled NBW avatar");
		if(bitmap.getWidth()!=width || bitmap.getHeight()!=height){
			Bitmap resized=Bitmap.createScaledBitmap(bitmap, width, height, true);
			if(resized!=bitmap) bitmap.recycle();
			bitmap=resized;
		}
		if(request.desiredConfig!=null && bitmap.getConfig()!=request.desiredConfig){
			Bitmap converted=bitmap.copy(request.desiredConfig, false);
			if(converted==null){
				bitmap.recycle();
				throw new IOException("Could not apply NBW avatar bitmap configuration");
			}
			bitmap.recycle();
			bitmap=converted;
		}
		bitmap.setDensity(Bitmap.DENSITY_NONE);
		Drawable drawable=new BitmapDrawable(resources, bitmap);
		for(ImageProcessingStep step:request.processingSteps)
			drawable=step.processDrawable(drawable);
		return drawable;
	}
}
