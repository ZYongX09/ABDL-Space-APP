package org.joinmastodon.android.novel

import java.io.File
import java.nio.file.Files
import org.joinmastodon.android.api.novels.PrivateBookUpload
import org.joinmastodon.android.novel.importer.NovelImportCoordinator
import org.joinmastodon.reader.data.NovelDatabase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAccountDataCleanerTest {
	@Test
	fun revokeCancelsRegisteredUploadAndInvalidatesCapturedGeneration() {
		val accountId = "logout.example_1"
		val generation = NovelAccountDataCleaner.captureGeneration(accountId)
		var canceled = false
		val upload = object : PrivateBookUpload(null, {}) {
			override fun cancel() {
				canceled = true
			}
		}
		assertTrue(NovelAccountDataCleaner.registerUpload(accountId, generation, upload) { true })

		NovelAccountDataCleaner.invalidate(accountId)

		assertTrue(canceled)
		assertFalse(NovelAccountDataCleaner.isGenerationValid(accountId, generation))
	}

	@Test
	fun uploadRegistrationAfterInvalidationIsRejectedAndCanceled() {
		val accountId = "logout.example_race"
		val generation = NovelAccountDataCleaner.captureGeneration(accountId)
		NovelAccountDataCleaner.invalidate(accountId)
		var canceled = false
		val upload = object : PrivateBookUpload(null, {}) {
			override fun cancel() { canceled = true }
		}

		assertFalse(NovelAccountDataCleaner.registerUpload(accountId, generation, upload) { true })
		assertTrue(canceled)
	}

	@Test
	fun revokeAndRegistrationAreAtomicallyFenced() {
		val accountId = "logout.example_atomic"
		val generation = NovelAccountDataCleaner.captureGeneration(accountId)
		var sessionPresent = true
		NovelAccountDataCleaner.revoke(accountId) { sessionPresent = false }
		var canceled = false
		val upload = object : PrivateBookUpload(null, {}) {
			override fun cancel() { canceled = true }
		}

		assertFalse(NovelAccountDataCleaner.registerUpload(accountId, generation, upload) { sessionPresent })
		assertTrue(canceled)
	}

	@Test
	fun cleanupDeletesDatabaseAndEveryAccountScopedNovelRoot() {
		val root = Files.createTempDirectory("novel-account-cleaner").toFile()
		val files = File(root, "files")
		val cache = File(root, "cache")
		val databases = File(root, "databases")
		val accountId = "logout.example_2"
		val hash = NovelImportCoordinator.accountHash(accountId)
		val paths = listOf(
			File(files, "novels/$hash/book.txt"),
			File(files, "novels/transfers/$hash/transfer/source.txt"),
			File(cache, "novels/import/$hash/import.part"),
			File(databases, NovelDatabase.databaseName(accountId)),
			File(databases, NovelDatabase.databaseName(accountId) + "-wal"),
			File(databases, NovelDatabase.databaseName(accountId) + "-shm"),
		)
		paths.forEach { it.parentFile?.mkdirs(); it.writeText("data") }

		NovelAccountDataCleaner.deleteAccountData(files, cache, databases, accountId)

		paths.forEach { assertFalse(it.exists()) }
		root.deleteRecursively()
	}

	@Test
	fun cleanupRetriesWhileOperationLeaseExistsThenDeletesWhenReleased() {
		val root = Files.createTempDirectory("novel-account-cleaner-lease").toFile()
		val files = File(root, "files")
		val cache = File(root, "cache")
		val databases = File(root, "databases")
		val accountId = "logout.example_lease"
		val target = File(files, "novels/${NovelImportCoordinator.accountHash(accountId)}/book.txt")
		target.parentFile?.mkdirs()
		target.writeText("data")
		val lease = requireNotNull(NovelAccountDataCleaner.enterOperation(accountId, NovelAccountDataCleaner.captureGeneration(accountId)))

		assertFalse(NovelAccountDataCleaner.cleanIfIdle(files, cache, databases, accountId))
		assertTrue(target.exists())
		lease.close()
		assertTrue(NovelAccountDataCleaner.cleanIfIdle(files, cache, databases, accountId))
		assertFalse(target.exists())
		root.deleteRecursively()
	}

	@Test
	fun logoutCleanupUsesPersistentMarkerAndIndependentWorkIdentity() {
		val source = File("src/main/kotlin/org/joinmastodon/android/novel/NovelAccountDataCleaner.kt").readText()
		val worker = File("src/main/kotlin/org/joinmastodon/android/novel/NovelAccountCleanupWorker.kt").readText()

		assertTrue(source.contains("markCleanupPending"))
		assertTrue(source.contains("NovelAccountCleanupWorker.enqueue"))
		assertTrue(worker.contains("Result.retry()"))
		assertTrue(worker.contains("clearCleanupPending"))
		assertTrue(worker.contains("enqueuePending"))
		assertTrue(worker.contains("novel-cleanup-account-"))
		assertFalse(worker.contains("NovelUploadWorker.accountWorkTag"))
	}
}
