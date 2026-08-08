package org.joinmastodon.android.novel.download

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelDownloadTransactionTest {
	@Test
	fun failedImportKeepsOldOfficialFileAndCleansCandidate() {
		runBlocking {
			val directory = Files.createTempDirectory("novel-download-transaction").toFile()
			val official = File(directory, "book.txt").apply { writeText("old") }
			val candidate = File(directory, "book.txt.candidate").apply { writeText("new") }

			var failure: IOException? = null
			try {
				NovelDownloadWorker.commitCandidate(official, candidate) { throw IOException("database failed") }
			} catch (error: IOException) {
				failure = error
			}

			assertEquals("database failed", failure?.message)
			assertEquals("old", official.readText())
			assertFalse(candidate.exists())
			assertFalse(File(directory, "book.txt.backup").exists())
			directory.deleteRecursively()
		}
	}

	@Test
	fun successfulImportSwitchesOfficialFileAfterDatabaseCommit() {
		runBlocking {
			val directory = Files.createTempDirectory("novel-download-transaction").toFile()
			val official = File(directory, "book.txt").apply { writeText("old") }
			val candidate = File(directory, "book.txt.candidate").apply { writeText("new") }
			var sawNewFile = false

			NovelDownloadWorker.commitCandidate(official, candidate) { sawNewFile = official.readText() == "new" }

			assertEquals(true, sawNewFile)
			assertEquals("new", official.readText())
			assertFalse(candidate.exists())
			directory.deleteRecursively()
		}
	}

	@Test
	fun staleBackupIsRestoredBeforeFailedReplacement() = runBlocking {
		val directory = Files.createTempDirectory("novel-download-recovery").toFile()
		val official = File(directory, "book.txt")
		val backup = File(directory, "book.txt.backup").apply { writeText("old") }
		val candidate = File(directory, "book.txt.candidate").apply { writeText("new") }

		try {
			NovelDownloadWorker.commitCandidate(official, candidate) { throw IOException("database failed") }
		} catch (_: IOException) {
		}

		assertEquals("old", official.readText())
		assertFalse(candidate.exists())
		assertFalse(backup.exists())
		directory.deleteRecursively()
		Unit
	}

	@Test
	fun invalidSessionBeforeCommitKeepsOldFileAndSkipsDatabase() = runBlocking {
		val directory = Files.createTempDirectory("novel-download-session").toFile()
		val official = File(directory, "book.txt").apply { writeText("old") }
		val candidate = File(directory, "book.txt.candidate").apply { writeText("new") }
		var valid = false
		var databaseCalled = false

		val failure = runCatching {
			NovelDownloadWorker.commitCandidate(official, candidate, { valid }) {
				databaseCalled = true
			}
		}.exceptionOrNull()

		assertTrue(failure is IOException)
		assertFalse(databaseCalled)
		assertEquals("old", official.readText())
		directory.deleteRecursively()
		Unit
	}

	@Test
	fun logoutAfterFileSwitchRollsBackFileAndDatabaseGate() = runBlocking {
		val directory = Files.createTempDirectory("novel-download-session-gate").toFile()
		val official = File(directory, "book.txt").apply { writeText("old") }
		val candidate = File(directory, "book.txt.candidate").apply { writeText("new") }
		var checks = 0
		var databaseCalled = false

		val failure = runCatching {
			NovelDownloadWorker.commitCandidate(official, candidate, { ++checks < 2 }) {
				databaseCalled = true
			}
		}.exceptionOrNull()

		assertTrue(failure is IOException)
		assertFalse(databaseCalled)
		assertEquals("old", official.readText())
		directory.deleteRecursively()
		Unit
	}
}
