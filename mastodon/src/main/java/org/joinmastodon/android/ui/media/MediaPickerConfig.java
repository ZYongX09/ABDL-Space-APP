package org.joinmastodon.android.ui.media;

import android.os.Bundle;

public class MediaPickerConfig {
	public static final String KEY_MAX_COUNT="media_picker_max_count";
	public static final String KEY_ALLOW_IMAGES="media_picker_allow_images";
	public static final String KEY_ALLOW_VIDEOS="media_picker_allow_videos";

	public boolean allowImages=true;
	public boolean allowVideos=true;
	public int maxCount=4;

	public Bundle toBundle(){
		Bundle bundle=new Bundle();
		bundle.putInt(KEY_MAX_COUNT, maxCount);
		bundle.putBoolean(KEY_ALLOW_IMAGES, allowImages);
		bundle.putBoolean(KEY_ALLOW_VIDEOS, allowVideos);
		return bundle;
	}

	public static MediaPickerConfig fromBundle(Bundle bundle){
		MediaPickerConfig config=new MediaPickerConfig();
		if(bundle!=null){
			config.maxCount=bundle.getInt(KEY_MAX_COUNT, config.maxCount);
			config.allowImages=bundle.getBoolean(KEY_ALLOW_IMAGES, config.allowImages);
			config.allowVideos=bundle.getBoolean(KEY_ALLOW_VIDEOS, config.allowVideos);
		}
		return config;
	}
}
