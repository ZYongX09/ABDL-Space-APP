package org.joinmastodon.android.ui.map;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import me.grishka.appkit.imageloader.ImageCache;
import me.grishka.appkit.imageloader.ImageLoaderCallback;
import me.grishka.appkit.imageloader.requests.ImageLoaderRequest;
import me.grishka.appkit.imageloader.requests.UrlImageLoaderRequest;

import org.joinmastodon.android.model.map.MapModels;

final class MapAvatarRenderer{
	private final Context context;
	private final Handler main=new Handler(Looper.getMainLooper());
	private final Map<String, Object> pending=new HashMap<>();
	private final Map<String, ImageCache.PendingImageRequest> requests=new HashMap<>();
	private int generation;

	MapAvatarRenderer(Context context){ this.context=context.getApplicationContext(); }

	void render(MapModels.Point point, Consumer<Bitmap> callback){
		String name=point.anonymous ? "?" : point.account==null ? "?" : point.account.displayName;
		if(name==null || name.isBlank()) name=point.account==null ? "?" : point.account.username;
		String initial=name==null || name.isBlank() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1));
		callback.accept(draw(null, initial));
		if(point.anonymous || point.account==null || point.account.avatar==null || !point.account.avatar.startsWith("https://")) return;
		String url=point.account.avatar;
		ImageCache.PendingImageRequest previous=requests.remove(point.id);
		if(previous!=null) previous.cancel();
		Object ticket=new Object(); pending.put(point.id, ticket);
		int currentGeneration=generation;
		ImageCache.PendingImageRequest imageRequest=ImageCache.getInstance(context).get(new UrlImageLoaderRequest(url, 96, 96), null, new ImageLoaderCallback(){
			@Override public void onImageLoaded(ImageLoaderRequest request, Drawable drawable){
				main.post(()->{
					if(currentGeneration!=generation || pending.get(point.id)!=ticket) return;
					pending.remove(point.id); requests.remove(point.id); callback.accept(draw(drawable, initial));
				});
			}
			@Override public void onImageLoadingFailed(ImageLoaderRequest request, Throwable error){
				main.post(()->{ if(pending.get(point.id)==ticket){ pending.remove(point.id); requests.remove(point.id); } });
			}
		}, true);
		if(pending.get(point.id)==ticket) requests.put(point.id,imageRequest);
	}

	void clear(){
		generation++; pending.clear();
		for(ImageCache.PendingImageRequest request:requests.values()) if(request!=null) request.cancel();
		requests.clear();
	}

	private Bitmap draw(Drawable drawable, String initial){
		int size=Math.max(48, Math.round(48*context.getResources().getDisplayMetrics().density));
		Bitmap bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);
		Canvas canvas=new Canvas(bitmap); Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
		float center=size/2f, radius=center-3;
		paint.setColor(Color.WHITE); canvas.drawCircle(center,center,center-1,paint);
		paint.setColor(0xff6657cd); canvas.drawCircle(center,center,radius,paint);
		if(drawable!=null){
			int save=canvas.save(); Path clip=new Path(); clip.addCircle(center,center,radius,Path.Direction.CW); canvas.clipPath(clip);
			Rect original=new Rect(drawable.getBounds()); drawable.setBounds(3,3,size-3,size-3); drawable.draw(canvas); drawable.setBounds(original); canvas.restoreToCount(save);
		}else{
			paint.setColor(Color.WHITE); paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(size*0.42f);
			canvas.drawText(initial,center,center-(paint.ascent()+paint.descent())/2,paint);
		}
		return bitmap;
	}
}
