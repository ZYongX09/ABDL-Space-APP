package org.joinmastodon.android.novel.upload

import java.io.File
import org.joinmastodon.android.novel.importer.NovelImportCoordinator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelUploadWorkerTest {
	@Test
	fun workIdentityAndTagAreAccountIsolated() {
		val accountA = "example.social_1"
		val accountB = "example.social_2"

		assertTrue(NovelUploadWorker.uniqueWorkName(accountA, "transfer") != NovelUploadWorker.uniqueWorkName(accountB, "transfer"))
		assertTrue(NovelUploadWorker.accountWorkTag(accountA) != NovelUploadWorker.accountWorkTag(accountB))
		assertTrue(NovelUploadWorker.accountWorkTag(accountA).startsWith("novel-transfer-account-"))
		assertTrue(NovelUploadWorker.uniqueWorkName(accountA, "transfer").contains(NovelImportCoordinator.accountHash(accountA)))
	}

	@Test
	fun productionWorkerListsJournalResumesTransferAndEnqueuesUniqueWork() {
		val source = File("src/main/kotlin/org/joinmastodon/android/novel/upload/NovelUploadWorker.kt").readText()

		assertTrue(source.contains("transferDao().list()"))
		assertTrue(source.contains("resumeUpload("))
		assertTrue(source.contains("enqueueUniqueWork"))
		assertTrue(source.contains("ExistingWorkPolicy.KEEP"))
		assertFalse(source.contains("token"))
		assertFalse(source.contains(":pending"))
		assertTrue(source.contains("transferDao().claim("))
	}
}
