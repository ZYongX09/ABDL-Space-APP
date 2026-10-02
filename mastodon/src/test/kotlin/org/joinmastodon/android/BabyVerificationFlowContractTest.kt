package org.joinmastodon.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BabyVerificationFlowContractTest {
	private val projectDir = File(requireNotNull(System.getProperty("user.dir")))

	@Test
	fun draftIsRecoveredInsteadOfStartingAnotherApplication() {
		val fragment = source("src/main/java/org/joinmastodon/android/fragments/settings/BabyVerificationFragment.java")
		val models = source("src/main/java/org/joinmastodon/android/model/verification/VerificationModels.java")
		assertTrue(fragment.contains("case DRAFT -> loadDraftDetail"))
		assertTrue(fragment.contains("VerificationRequest.detail(id)"))
		assertTrue(fragment.contains("VerificationRequest.cancelApplication(id)"))
		assertTrue(fragment.contains("VerificationUploader.Recovery.fromDetail"))
		assertTrue(models.contains("Set.of(Status.NOT_STARTED, Status.CANCELLED, Status.REJECTED)"))
	}

	@Test
	fun controlledCameraResultIsBoundToTheExpectedSession() {
		val fragment = source("src/main/java/org/joinmastodon/android/fragments/settings/BabyVerificationFragment.java")
		val contract = source("src/main/java/org/joinmastodon/android/ui/media/MediaCameraContract.java")
		assertTrue(contract.contains("createCertificationResult(String controlledPath, String sessionId, int slot)"))
		assertTrue(fragment.contains("getCertificationSession(data)"))
		assertTrue(fragment.contains("getCertificationSlot(data)"))
		assertTrue(fragment.contains("VerificationPendingCapture.load(getActivity(), resultSession, accountID, ownerUserId())"))
		assertTrue(fragment.contains("slot!=0"))
	}

	@Test
	fun imageWorkAndUnknownSubmitAreRecoverable() {
		val fragment = source("src/main/java/org/joinmastodon/android/fragments/settings/BabyVerificationFragment.java")
		assertTrue(fragment.contains("IMAGE_EXECUTOR.execute"))
		assertTrue(fragment.contains("VerificationImageProcessor.inspectProcessed"))
		assertTrue(fragment.contains("checkSubmitOutcome"))
		assertTrue(fragment.contains("value.outcomeUnknown"))
		assertFalse(fragment.contains("content.removeAllViews"))
	}

	@Test
	fun applicationAndCertificateAreFetchedTogetherAndRenderedAsCombinedState() {
		val fragment = source("src/main/java/org/joinmastodon/android/fragments/settings/BabyVerificationFragment.java")
		assertTrue(fragment.contains("VerificationRequest.state()"))
		assertTrue(fragment.contains("VerificationRequest.certificate()"))
		assertTrue(fragment.contains("renderState(currentState, currentCertificate, generation)"))
		assertTrue(fragment.contains("certificate.isRevoked()"))
		assertTrue(fragment.contains("certificate.isActive()"))
		assertTrue(fragment.contains("verification_certificate_pending_sync"))
	}

	@Test
	fun certificatePageRefreshesBeforeExportAndDropsOldBitmap() {
		val fragment = source("src/main/java/org/joinmastodon/android/fragments/settings/BabyVerificationCertificateFragment.java")
		assertTrue(fragment.contains("refresh(PendingAction.SAVE)"))
		assertTrue(fragment.contains("refresh(PendingAction.SHARE)"))
		assertTrue(fragment.contains("clearRendered()"))
		assertTrue(fragment.contains("certificate.isRevoked()"))
		assertTrue(fragment.contains("certificate.isActive()"))
		assertTrue(fragment.contains("token==generation"))
	}

	@Test
	fun settingsAndPublicVerifierUseCertificateLifecycleStates() {
		val settings = source("src/main/java/org/joinmastodon/android/fragments/settings/SettingsAccountFragment.java")
		val result = source("src/main/java/org/joinmastodon/android/fragments/settings/BabyVerificationResultFragment.java")
		assertTrue(settings.contains("refreshVerificationStatus()"))
		assertTrue(settings.contains("VerificationRequest.certificate()"))
		assertFalse(settings.contains("babyVerification.verified"))
		assertTrue(result.contains("case \"superseded\""))
		assertTrue(result.contains("case \"revoked\""))
		assertTrue(result.contains("case \"unknown\""))
		assertTrue(result.contains("verification_verify_network_error"))
	}

	@Test
	fun signedUploadContractIncludesContentLengthAndPrivateIntegrityHeaders() {
		val models = source("src/main/java/org/joinmastodon/android/model/verification/VerificationModels.java")
		val uploader = source("src/main/java/org/joinmastodon/android/verification/VerificationUploader.java")
		for (header in listOf("Content-Length", "Content-MD5", "Content-Type", "Authorization", "x-cos-acl", "x-cos-forbid-overwrite", "x-cos-meta-sha256")) {
			assertTrue("missing $header", models.contains("\"$header\""))
		}
		assertTrue(uploader.contains("isUncertainPut"))
		assertTrue(uploader.contains("upload_expired"))
		assertTrue(uploader.contains("evidence_verifying"))
	}

	private fun source(path: String) = File(projectDir, path).readText()
}
