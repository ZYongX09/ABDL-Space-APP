package org.joinmastodon.android.ui.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.content.Intent;
import android.net.Uri;
import android.view.Surface;

import org.junit.Test;

public class MediaCameraContractTest{
	@Test
	public void backCameraOrientationUsesSensorMinusDisplay(){
		assertEquals(90, MediaCameraContract.jpegOrientation(90, Surface.ROTATION_0, false));
		assertEquals(0, MediaCameraContract.jpegOrientation(90, Surface.ROTATION_90, false));
	}

	@Test
	public void frontCameraOrientationUsesSensorPlusDisplay(){
		assertEquals(270, MediaCameraContract.jpegOrientation(270, Surface.ROTATION_0, true));
		assertEquals(0, MediaCameraContract.jpegOrientation(270, Surface.ROTATION_90, true));
	}

	@Test
	public void resultRoundTripsMediaMetadata(){
		Uri uri=Uri.parse("content://camera/test.jpg");
		Intent result=MediaCameraContract.createResult(uri, false, "image/jpeg");
		assertEquals(uri, MediaCameraContract.getUri(result));
		assertFalse(MediaCameraContract.isVideo(result));
		assertEquals("image/jpeg", MediaCameraContract.getMimeType(result));
	}
}
