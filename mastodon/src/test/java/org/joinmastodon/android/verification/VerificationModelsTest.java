package org.joinmastodon.android.verification;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
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
	public void certificateAcceptsActiveAndRevokedContracts() throws Exception{
		VerificationModels.Certificate active=certificate("active");
		active.verificationToken="0123456789abcdefghjkmnpqrstvwxyzABCD";
		active.verifyPath="/api/v1/baby-verification/verify/"+active.verificationToken;
		active.postprocess();
		assertTrue(active.isActive());

		VerificationModels.Certificate revoked=certificate("revoked");
		revoked.revokedAt=1_700_000_100L;
		revoked.revokeReason="管理员复核吊销";
		revoked.postprocess();
		assertTrue(revoked.isRevoked());
		assertThrows(IllegalStateException.class, revoked::publicUrl);
	}

	@Test
	public void certificateRequiresCredentialsOnlyWhileActive(){
		VerificationModels.Certificate active=certificate("active");
		assertThrows(org.joinmastodon.android.api.ObjectValidationException.class, active::postprocess);
		VerificationModels.Certificate revoked=certificate("revoked");
		revoked.revokedAt=1_700_000_100L;
		revoked.revokeReason="";
		assertThrows(org.joinmastodon.android.api.ObjectValidationException.class, revoked::postprocess);
	}

	@Test
	public void verifyResultValidatesEveryPublicStatus() throws Exception{
		VerificationModels.VerifyResult active=verify("active");
		active.valid=true;
		active.username="tester";
		active.issuedAt=1_700_000_000L;
		active.generation=2;
		active.postprocess();

		VerificationModels.VerifyResult superseded=verify("superseded");
		superseded.superseded=true;
		superseded.supersededAt=1_700_000_100L;
		superseded.postprocess();

		VerificationModels.VerifyResult revoked=verify("revoked");
		revoked.revokedAt=1_700_000_200L;
		revoked.revokeReason="安全复核";
		revoked.postprocess();

		verify("unknown").postprocess();
	}

	@Test
	public void verifyResultRejectsContradictoryFlagsAndMissingEventDetails(){
		VerificationModels.VerifyResult invalidActive=verify("active");
		invalidActive.valid=false;
		assertThrows(org.joinmastodon.android.api.ObjectValidationException.class, invalidActive::postprocess);
		VerificationModels.VerifyResult invalidSuperseded=verify("superseded");
		invalidSuperseded.superseded=true;
		assertThrows(org.joinmastodon.android.api.ObjectValidationException.class, invalidSuperseded::postprocess);
		VerificationModels.VerifyResult invalidRevoked=verify("revoked");
		invalidRevoked.revokedAt=1_700_000_200L;
		assertThrows(org.joinmastodon.android.api.ObjectValidationException.class, invalidRevoked::postprocess);
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

	private static VerificationModels.Certificate certificate(String status){
		VerificationModels.Certificate value=new VerificationModels.Certificate();
		value.id="123e4567-e89b-42d3-a456-426614174000";
		value.status=status;
		value.issuedAt=1_700_000_000L;
		value.generation=1;
		return value;
	}

	private static VerificationModels.VerifyResult verify(String status){
		VerificationModels.VerifyResult value=new VerificationModels.VerifyResult();
		value.status=status;
		return value;
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
