package org.joinmastodon.android.fragments.albums;

import android.os.Bundle;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;

/** The editor's state, independent of requests, views, and background upload lifetime. */
final class AlbumUploadDraft{
	String albumId, albumName, visibility, description="", quality="hd", customTime="";
	boolean customCapturedAt;
	ArrayList<String> photos=new ArrayList<>();
	static final DateTimeFormatter CAPTURED_TIME=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
	boolean descriptionValid(){ return description!=null && description.codePointCount(0, description.length())<=3000; }
	static boolean originalAllowed(org.joinmastodon.android.model.albums.AlbumModels.StorageQuota quota, java.util.List<SourceInfo> sources){
		if(quota==null || !quota.sponsorActive || !quota.originalUploadAllowed || sources==null || sources.isEmpty()) return false;
		for(SourceInfo source:sources) if(source==null || !org.joinmastodon.android.albums.AlbumUploader.sourceSizeAllowed(source.size)
				|| source.mime==null || !java.util.Set.of("image/jpeg", "image/png", "image/webp", "image/gif", "image/heif", "image/heic").contains(source.mime)) return false;
		return true;
	}
	static final class SourceInfo{
		final long size;
		final String mime;
		SourceInfo(long size, String mime){ this.size=size; this.mime=mime; }
	}
	Long capturedAt(ZoneId zone){
		if(!customCapturedAt) return null;
		LocalDateTime local=LocalDateTime.parse(customTime, CAPTURED_TIME);
		// Do not silently shift a nonexistent local time during a DST transition.
		if(zone.getRules().getValidOffsets(local).size()!=1) throw new IllegalArgumentException("Ambiguous or missing local time");
		long seconds=local.atZone(zone).toEpochSecond(); if(seconds<0) throw new IllegalArgumentException("Time before epoch"); return seconds;
	}
	boolean select(String id, String name, String privacy){
		if(id==null || !id.matches("[A-Za-z0-9_-]{1,128}") || name==null || !AlbumUi.VISIBILITIES.contains(privacy)) return false;
		albumId=id; albumName=name; visibility=privacy; return true;
	}
	boolean addPhoto(String value){
		if(value==null || !(value.startsWith("content://") || value.startsWith("file://")) || photos.contains(value) || photos.size()>=20) return false;
		photos.add(value); return true;
	}
	Bundle save(){
		Bundle state=new Bundle(); state.putString("albumId", albumId); state.putString("albumName", albumName); state.putString("visibility", visibility);
		state.putString("description", description); state.putString("quality", quality); state.putString("customTime", customTime);
		state.putBoolean("customCapturedAt", customCapturedAt); state.putStringArrayList("photos", new ArrayList<>(photos)); return state;
	}
	static AlbumUploadDraft restore(Bundle state){
		AlbumUploadDraft draft=new AlbumUploadDraft(); if(state==null) return draft;
		draft.albumId=state.getString("albumId"); draft.albumName=state.getString("albumName"); draft.visibility=state.getString("visibility");
		draft.description=state.getString("description", ""); draft.quality="original".equals(state.getString("quality")) ? "original" : "hd";
		draft.customTime=state.getString("customTime", ""); draft.customCapturedAt=state.getBoolean("customCapturedAt");
		ArrayList<String> photos=state.getStringArrayList("photos"); if(photos!=null) for(String photo:photos) draft.addPhoto(photo);
		return draft;
	}
}
