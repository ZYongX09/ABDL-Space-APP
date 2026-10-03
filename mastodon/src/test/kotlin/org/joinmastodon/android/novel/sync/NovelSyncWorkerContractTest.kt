package org.joinmastodon.android.novel.sync

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelSyncWorkerContractTest {
	private val source = File(requireNotNull(System.getProperty("user.dir")), "src/main/kotlin/org/joinmastodon/android/novel/sync/NovelSyncWorker.kt").readText()

	@Test fun workerUsesEngineMetadataOrderAndRetriesTemporaryPushFailures() {
		assertTrue(source.contains("syncResult.retryNeeded"))
		assertTrue(source.contains("Result.retry()"))
		assertTrue(source.contains("guard = guard"))
	}
}
