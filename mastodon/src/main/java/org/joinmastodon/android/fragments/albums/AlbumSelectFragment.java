package org.joinmastodon.android.fragments.albums;

import android.os.Bundle;

import org.joinmastodon.android.model.albums.AlbumModels.Album;

/** Standard AppKit fragment result, used by both album uploads and the composer. */
public class AlbumSelectFragment extends AlbumListFragment{
	public static final String RESULT_ALBUM_ID="albumId", RESULT_ALBUM_NAME="albumName", RESULT_VISIBILITY="visibility";
	@Override protected boolean isSelection(){ return true; }
	@Override protected boolean includeAlbum(Album album){ return album.isOwner && album.canUpload; }
	@Override protected void onAlbumClicked(Album album){
		if(!album.isOwner || !album.canUpload || getActivity()==null) return;
		Bundle result=new Bundle(); result.putString(RESULT_ALBUM_ID, album.id); result.putString(RESULT_ALBUM_NAME, album.name); result.putString(RESULT_VISIBILITY, album.visibility);
		setResult(true, result); getActivity().onBackPressed();
	}
}
