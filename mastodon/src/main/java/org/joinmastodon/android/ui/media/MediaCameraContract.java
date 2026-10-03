package org.joinmastodon.android.ui.media;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.Surface;

import java.io.File;
import java.io.IOException;

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

	public static final String EXTRA_CERTIFICATION_DEADLINE="media_camera_certification_deadline";

	private MediaCameraContract(){ }

	public static File certificationPhotoFile(Context context, String sessionId, int slot){
		if(sessionId==null || !sessionId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}") || slot!=0)
			throw new IllegalArgumentException("Invalid certification session or slot");
		return new File(context.getNoBackupFilesDir(), "verification/"+sessionId+"/raw-"+slot+".jpg");
	}

	/** Trust the platform-provided private root alias, never links introduced below it. */
	public static boolean isControlledFile(Context context, File file) throws IOException{
		File root=context.getNoBackupFilesDir().getAbsoluteFile();
		java.nio.file.Path relative;
		try{ relative=root.toPath().relativize(file.getAbsoluteFile().toPath()); }
		catch(IllegalArgumentException ignored){ return false; }
		if(relative.getNameCount()==0 || !file.getAbsoluteFile().equals(new File(root, relative.toString()))) return false;
		File current=root;
		for(java.nio.file.Path part:relative){
			String name=part.toString();
			if(name.isEmpty() || name.equals(".") || name.equals("..")) return false;
			current=new File(current, name);
			if(java.nio.file.Files.isSymbolicLink(current.toPath())) return false;
		}
		return file.getCanonicalFile().equals(new File(root.getCanonicalFile(), relative.toString()).getAbsoluteFile());
	}

	public record CertificationRequest(String sessionId, int slot, String requirement, long deadline, File output){ }

	/** Reject malformed requests before touching any caller-supplied file. */
	public static CertificationRequest readCertificationRequest(Context context, Intent intent) throws IOException{
		if(!isCertification(intent)) throw new IllegalArgumentException("Not a certification request");
		String session=getCertificationSession(intent);
		int slot=getCertificationSlot(intent);
		File expected=certificationPhotoFile(context, session, slot);
		String path=getControlledPath(intent);
		String requirement=intent.getStringExtra(EXTRA_CERTIFICATION_REQUIREMENT);
		long duration=intent.getLongExtra(EXTRA_CERTIFICATION_DURATION, 0);
		long deadline=intent.getLongExtra(EXTRA_CERTIFICATION_DEADLINE, 0);
		if(path==null || !expected.getAbsolutePath().equals(path)
				|| !isControlledFile(context, expected)
				|| requirement==null || requirement.isBlank() || duration<=0 || deadline<=0)
			throw new IllegalArgumentException("Invalid certification camera parameters");
		return new CertificationRequest(session, slot, requirement, deadline, expected);
	}

	public static Intent createIntent(Context context, boolean allowVideo){
		return new Intent().setClassName(context, "org.joinmastodon.android.ui.MediaCameraActivity").putExtra(EXTRA_ALLOW_VIDEO, allowVideo);
	}

	public static Intent createResult(Uri uri, boolean video, String mimeType){
		return new Intent().putExtra(EXTRA_MEDIA_URI, uri).putExtra(EXTRA_MEDIA_IS_VIDEO, video).putExtra(EXTRA_MEDIA_MIME_TYPE, mimeType);
	}

	public static Intent createCertificationIntent(Context context, String sessionId, int slot, String requirement, long durationMs){
		long now=System.currentTimeMillis();
		if(durationMs<=0 || durationMs>Long.MAX_VALUE-now) throw new IllegalArgumentException("Invalid certification duration");
		return createIntent(context, false).putExtra(EXTRA_CERTIFICATION_MODE, true).putExtra(EXTRA_CERTIFICATION_SESSION, sessionId)
				.putExtra(EXTRA_CERTIFICATION_SLOT, slot).putExtra(EXTRA_CERTIFICATION_REQUIREMENT, requirement).putExtra(EXTRA_CERTIFICATION_DURATION, durationMs)
				.putExtra(EXTRA_CERTIFICATION_DEADLINE, now+durationMs)
				.putExtra(EXTRA_CONTROLLED_PATH, certificationPhotoFile(context, sessionId, slot).getAbsolutePath());
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
