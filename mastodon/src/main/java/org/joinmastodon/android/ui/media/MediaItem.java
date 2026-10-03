package org.joinmastodon.android.ui.media;

import android.net.Uri;

public class MediaItem {
	public final long id;
	public final long albumId;
	public final String albumName;
	public final Uri uri;
	public final String mimeType;
	public final long modifiedTime;
	public final int width;
	public final int height;
	public final long size;
	public final long duration;
	public final boolean video;

	public MediaItem(long id, long albumId, String albumName, Uri uri, String mimeType,
			long modifiedTime, int width, int height, long size, long duration, boolean video){
		this.id=id;
		this.albumId=albumId;
		this.albumName=albumName;
		this.uri=uri;
		this.mimeType=mimeType;
		this.modifiedTime=modifiedTime;
		this.width=width;
		this.height=height;
		this.size=size;
		this.duration=duration;
		this.video=video;
	}

	public String getKey(){
		return uri.toString();
	}
}
