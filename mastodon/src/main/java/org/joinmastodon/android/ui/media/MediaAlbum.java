package org.joinmastodon.android.ui.media;

import java.util.ArrayList;

public class MediaAlbum {
	public final long id;
	public final String name;
	public final ArrayList<MediaItem> items=new ArrayList<>();
	public MediaItem cover;

	public MediaAlbum(long id, String name){
		this.id=id;
		this.name=name;
	}

	public void add(MediaItem item){
		if(cover==null)
			cover=item;
		items.add(item);
	}
}
