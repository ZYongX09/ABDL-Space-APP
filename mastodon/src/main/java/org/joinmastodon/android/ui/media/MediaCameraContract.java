package org.joinmastodon.android.ui.media;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.Surface;

public final class MediaCameraContract{
	public static final String EXTRA_ALLOW_VIDEO="media_camera_allow_video";
	public static final String EXTRA_MEDIA_URI="media_uri";
	public static final String EXTRA_MEDIA_IS_VIDEO="media_is_video";
	public static final String EXTRA_MEDIA_MIME_TYPE="media_mime_type";

	private MediaCameraContract(){ }

	public static Intent createIntent(Context context, boolean allowVideo){
		return new Intent().setClassName(context, "org.joinmastodon.android.ui.MediaCameraActivity").putExtra(EXTRA_ALLOW_VIDEO, allowVideo);
	}

	public static Intent createResult(Uri uri, boolean video, String mimeType){
		return new Intent().putExtra(EXTRA_MEDIA_URI, uri).putExtra(EXTRA_MEDIA_IS_VIDEO, video).putExtra(EXTRA_MEDIA_MIME_TYPE, mimeType);
	}

	public static Uri getUri(Intent intent){
		return intent==null ? null : intent.getParcelableExtra(EXTRA_MEDIA_URI);
	}

	public static boolean isVideo(Intent intent){
		return intent!=null && intent.getBooleanExtra(EXTRA_MEDIA_IS_VIDEO, false);
	}

	public static String getMimeType(Intent intent){
		return intent==null ? null : intent.getStringExtra(EXTRA_MEDIA_MIME_TYPE);
	}

	public static int jpegOrientation(int sensorOrientation, int displayRotation, boolean frontFacing){
		int displayDegrees=switch(displayRotation){
			case Surface.ROTATION_90 -> 90;
			case Surface.ROTATION_180 -> 180;
			case Surface.ROTATION_270 -> 270;
			default -> 0;
		};
		return (sensorOrientation+(frontFacing ? displayDegrees : -displayDegrees)+360)%360;
	}
}
