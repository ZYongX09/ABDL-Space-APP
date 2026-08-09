package org.joinmastodon.android.novel

import java.io.File
import java.nio.file.Files
import org.joinmastodon.android.api.novels.PrivateBookUpload
import org.joinmastodon.android.novel.importer.NovelImportCoordinator
import org.joinmastodon.reader.data.NovelDatabase
import org.junit.Assert.assertFalse
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
}
