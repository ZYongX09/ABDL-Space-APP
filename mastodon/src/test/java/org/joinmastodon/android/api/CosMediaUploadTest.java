package org.joinmastodon.android.api;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CosMediaUploadTest{
	@Test
	public void parsesSnakeCaseAuthorizationAndCopiesOnlyRequiredHeaders(){
		CosUploadAuthorization authorization=new CosUploadAuthorization();
		authorization.uploadId="upload-id";
		authorization.uploadUrl="https://bucket.cos.ap-shanghai.myqcloud.com/media/a.jpg";
		authorization.publicUrl="https://media.example.test/media/a.jpg";
		authorization.expiresAt=1234;
		authorization.requiredHeaders=new LinkedHashMap<>();
		authorization.requiredHeaders.put("Authorization", "signed");
		authorization.requiredHeaders.put("Content-Type", "image/jpeg");
		authorization.requiredHeaders.put("x-cos-forbid-overwrite", "true");

		Map<String, String> headers=CosMediaUpload.requiredHeaders(authorization);
		assertEquals(3, headers.size());
		assertEquals("signed", headers.get("Authorization"));
		assertFalse(headers.containsKey("Host"));
	}

	@Test
	public void aggregatesOriginalAndPreviewProgressMonotonically(){
		CosMediaUpload.Progress progress=new CosMediaUpload.Progress(900, 100);
		assertEquals(0, progress.updateOriginal(0));
		assertEquals(45, progress.updateOriginal(450));
		assertEquals(90, progress.updateOriginal(900));
		assertEquals(95, progress.updatePreview(50));
		assertEquals(100, progress.updatePreview(100));
		assertEquals(100, progress.updateOriginal(900));
	}

	@Test
	public void validatesAuthorizationContract(){
		CosUploadAuthorization authorization=new CosUploadAuthorization();
		authorization.uploadId="upload-id";
		authorization.uploadUrl="https://bucket.cos.ap-shanghai.myqcloud.com/media/a.jpg";
		authorization.publicUrl="https://media.example.test/media/a.jpg";
		authorization.requiredHeaders=Map.of("Authorization", "signed", "Content-Type", "image/jpeg", "x-cos-forbid-overwrite", "true");
		assertTrue(CosMediaUpload.isValidAuthorization(authorization));
		authorization.requiredHeaders=Map.of("Authorization", "signed");
		assertFalse(CosMediaUpload.isValidAuthorization(authorization));
	}
}
