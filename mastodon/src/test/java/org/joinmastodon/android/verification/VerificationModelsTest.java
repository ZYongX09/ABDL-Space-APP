package org.joinmastodon.android.verification;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.joinmastodon.android.model.verification.VerificationModels;
import org.joinmastodon.android.model.verification.VerificationModels.Status;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class VerificationModelsTest{
	@Test
	public void uploadAuthorizationAcceptsAndNormalizesBackendSignedHeaders() throws Exception{
		VerificationModels.UploadAuthorization value=new VerificationModels.UploadAuthorization();
		value.evidenceId="123e4567-e89b-42d3-a456-426614174000";
		value.status="pending";
		value.uploadUrl="https://abdl-1339643562.cos.ap-shanghai.myqcloud.com/baby-verification/private/1/a/b.jpg";
		value.expiresAt=System.currentTimeMillis()/1000+300;
		value.requiredHeaders=new LinkedHashMap<>(Map.of(
				"content-length", "1234",
				"CONTENT-MD5", "abcd==",
				"Content-Type", "image/jpeg",
				"authorization", "signed",
				"X-COS-ACL", "private",
				"x-cos-forbid-overwrite", "true",
				"x-cos-meta-sha256", "a".repeat(64)
		));
		value.postprocess();
		assertTrue(value.requiredHeaders.containsKey("Content-Length"));
		assertTrue(value.requiredHeaders.containsKey("Content-MD5"));
		assertTrue(value.requiredHeaders.containsKey("Authorization"));
	}

	@Test(expected=org.joinmastodon.android.api.ObjectValidationException.class)
	public void uploadAuthorizationRejectsDuplicateCanonicalHeaders() throws Exception{
		VerificationModels.UploadAuthorization value=new VerificationModels.UploadAuthorization();
		value.evidenceId="123e4567-e89b-42d3-a456-426614174000";
		value.status="pending";
		value.uploadUrl="https://abdl-1339643562.cos.ap-shanghai.myqcloud.com/baby-verification/private/1/a/b.jpg";
		value.expiresAt=System.currentTimeMillis()/1000+300;
		value.requiredHeaders=new LinkedHashMap<>();
		value.requiredHeaders.put("Content-Length", "1234");
		value.requiredHeaders.put("content-length", "1234");
		value.requiredHeaders.put("Content-MD5", "abcd==");
		value.requiredHeaders.put("Content-Type", "image/jpeg");
		value.requiredHeaders.put("Authorization", "signed");
		value.requiredHeaders.put("x-cos-acl", "private");
		value.requiredHeaders.put("x-cos-forbid-overwrite", "true");
		value.requiredHeaders.put("x-cos-meta-sha256", "a".repeat(64));
		value.postprocess();
	}

	@Test
	public void canStartOnlyFromNewCancelledOrRejectedStates(){
		for(Status status:Status.values()){
			VerificationModels.State state=state(status);
			boolean expected=status==Status.NOT_STARTED || status==Status.CANCELLED || status==Status.REJECTED;
			if(expected) assertTrue(status.name(), state.canStart());
			else assertFalse(status.name(), state.canStart());
		}
	}

	private static VerificationModels.State state(Status status){
		VerificationModels.State state=new VerificationModels.State();
		state.config=new VerificationModels.Config();
		state.config.enabled=true;
		state.quota=new VerificationModels.Quota();
		state.quota.limit=3;
		state.quota.remaining=3;
		if(status!=Status.NOT_STARTED){
			state.application=new VerificationModels.Application();
			state.application.parsedStatus=status;
		}
		return state;
	}
}
