package org.joinmastodon.android.ui.media;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MediaStoreLoader {
	public interface Callback{
		void onLoaded(ArrayList<MediaAlbum> albums);
	}

	private final Context context;

	public MediaStoreLoader(Context context){
		this.context=context.getApplicationContext();
	}

	public boolean hasPermission(MediaPickerConfig config){
		if(Build.VERSION.SDK_INT<23)
			return true;
		if(Build.VERSION.SDK_INT>=33){
			boolean images=!config.allowImages || context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)==PackageManager.PERMISSION_GRANTED;
			boolean videos=!config.allowVideos || context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)==PackageManager.PERMISSION_GRANTED;
			return images || videos;
		}
		return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED;
	}

	public void load(MediaPickerConfig config, Callback callback){
		new Thread(()->{
			ArrayList<MediaAlbum> albums=hasPermission(config) ? query(config) : new ArrayList<>();
			android.os.Handler handler=new android.os.Handler(context.getMainLooper());
			handler.post(()->callback.onLoaded(albums));
		}, "media-store-loader").start();
	}

	private ArrayList<MediaAlbum> query(MediaPickerConfig config){
		Map<Long, MediaAlbum> byAlbum=new LinkedHashMap<>();
		MediaAlbum all=new MediaAlbum(0, "全部媒体");
		byAlbum.put(0L, all);
		if(config.allowImages)
			queryImages(config, byAlbum, all);
		if(config.allowVideos)
			queryVideos(config, byAlbum, all);
		ArrayList<MediaAlbum> albums=new ArrayList<>(byAlbum.values());
		if(all.items.isEmpty())
			albums.clear();
		else
			all.items.sort(Comparator.comparingLong((MediaItem item)->item.modifiedTime).reversed());
		for(MediaAlbum album:albums){
			if(album!=all)
				album.items.sort(Comparator.comparingLong((MediaItem item)->item.modifiedTime).reversed());
		}
		return albums;
	}

	private void queryImages(MediaPickerConfig config, Map<Long, MediaAlbum> byAlbum, MediaAlbum all){
		if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)!=PackageManager.PERMISSION_GRANTED)
			return;
		String[] projection={
				MediaStore.Images.Media._ID,
				MediaStore.Images.Media.BUCKET_ID,
				MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
				MediaStore.Images.Media.DATE_MODIFIED,
				MediaStore.Images.Media.WIDTH,
				MediaStore.Images.Media.HEIGHT,
				MediaStore.Images.Media.SIZE,
				MediaStore.Images.Media.MIME_TYPE
		};
		try(Cursor cursor=context.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, MediaStore.Images.Media.DATE_MODIFIED+" DESC")){
			if(cursor==null)
				return;
			int id=cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
			int albumId=cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID);
			int albumName=cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
			int modified=cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED);
			int width=cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH);
			int height=cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT);
			int size=cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);
			int mime=cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE);
			while(cursor.moveToNext()){
				MediaItem item=new MediaItem(cursor.getLong(id), cursor.getLong(albumId), cursor.getString(albumName),
						ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(id)),
						cursor.getString(mime), cursor.getLong(modified), cursor.getInt(width), cursor.getInt(height), cursor.getLong(size), 0, false);
				addItem(byAlbum, all, item);
			}
		}catch(Exception ignored){
		}
	}

	private void queryVideos(MediaPickerConfig config, Map<Long, MediaAlbum> byAlbum, MediaAlbum all){
		if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)!=PackageManager.PERMISSION_GRANTED)
			return;
		String[] projection={
				MediaStore.Video.Media._ID,
				MediaStore.Video.Media.BUCKET_ID,
				MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
				MediaStore.Video.Media.DATE_MODIFIED,
				MediaStore.Video.Media.WIDTH,
				MediaStore.Video.Media.HEIGHT,
				MediaStore.Video.Media.SIZE,
				MediaStore.Video.Media.DURATION,
				MediaStore.Video.Media.MIME_TYPE
		};
		try(Cursor cursor=context.getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null, MediaStore.Video.Media.DATE_MODIFIED+" DESC")){
			if(cursor==null)
				return;
			int id=cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
			int albumId=cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID);
			int albumName=cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
			int modified=cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED);
			int width=cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH);
			int height=cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT);
			int size=cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
			int duration=cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
			int mime=cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE);
			while(cursor.moveToNext()){
				MediaItem item=new MediaItem(cursor.getLong(id), cursor.getLong(albumId), cursor.getString(albumName),
						ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(id)),
						cursor.getString(mime), cursor.getLong(modified), cursor.getInt(width), cursor.getInt(height), cursor.getLong(size), cursor.getLong(duration), true);
				addItem(byAlbum, all, item);
			}
		}catch(Exception ignored){
		}
	}

	private void addItem(Map<Long, MediaAlbum> byAlbum, MediaAlbum all, MediaItem item){
		all.add(item);
		MediaAlbum album=byAlbum.get(item.albumId);
		if(album==null){
			album=new MediaAlbum(item.albumId, TextUtils.isEmpty(item.albumName) ? "未命名相册" : item.albumName);
			byAlbum.put(item.albumId, album);
		}
		album.add(item);
	}
}
