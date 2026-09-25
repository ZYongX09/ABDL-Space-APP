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
	public static final String EXTRA_CERTIFICATION_MODE="media_camera_certification";
	public static final String EXTRA_CERTIFICATION_SESSION="media_camera_certification_session";
	public static final String EXTRA_CERTIFICATION_SLOT="media_camera_certification_slot";
	public static final String EXTRA_CERTIFICATION_REQUIREMENT="media_camera_certification_requirement";
	public static final String EXTRA_CERTIFICATION_DURATION="media_camera_certification_duration";
	public static final String EXTRA_CONTROLLED_PATH="media_camera_controlled_path";

	private MediaCameraContract(){ }

	public static Intent createIntent(Context context, boolean allowVideo){
		return new Intent().setClassName(context, "org.joinmastodon.android.ui.MediaCameraActivity").putExtra(EXTRA_ALLOW_VIDEO, allowVideo);
	}

	public static Intent createResult(Uri uri, boolean video, String mimeType){
		return new Intent().putExtra(EXTRA_MEDIA_URI, uri).putExtra(EXTRA_MEDIA_IS_VIDEO, video).putExtra(EXTRA_MEDIA_MIME_TYPE, mimeType);
	}

	public static Intent createCertificationIntent(Context context, String sessionId, int slot, String requirement, long durationMs){
		return createIntent(context, false).putExtra(EXTRA_CERTIFICATION_MODE, true).putExtra(EXTRA_CERTIFICATION_SESSION, sessionId)
				.putExtra(EXTRA_CERTIFICATION_SLOT, slot).putExtra(EXTRA_CERTIFICATION_REQUIREMENT, requirement).putExtra(EXTRA_CERTIFICATION_DURATION, durationMs);
	}

	public static Intent createCertificationResult(String controlledPath, String sessionId, int slot){
		return new Intent().putExtra(EXTRA_CONTROLLED_PATH, controlledPath).putExtra(EXTRA_CERTIFICATION_SESSION, sessionId).putExtra(EXTRA_CERTIFICATION_SLOT, slot);
	}
	public static String getControlledPath(Intent intent){ return intent==null ? null : intent.getStringExtra(EXTRA_CONTROLLED_PATH); }
	public static String getCertificationSession(Intent intent){ return intent==null ? null : intent.getStringExtra(EXTRA_CERTIFICATION_SESSION); }
	public static int getCertificationSlot(Intent intent){ return intent==null ? -1 : intent.getIntExtra(EXTRA_CERTIFICATION_SLOT, -1); }
	public static boolean isCertification(Intent intent){ return intent!=null && intent.getBooleanExtra(EXTRA_CERTIFICATION_MODE, false); }

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
