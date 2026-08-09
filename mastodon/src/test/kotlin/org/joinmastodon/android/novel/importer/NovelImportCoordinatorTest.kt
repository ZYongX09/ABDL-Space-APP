package org.joinmastodon.android.novel.importer

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.joinmastodon.android.api.novels.PrivateBookUpload
import org.joinmastodon.android.api.novels.PrivateNovelApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelImportCoordinatorTest {
	@Test
	fun preparedJournalWithoutFileIsRemovedDuringRecovery() = runBlocking {
		val directory = Files.createTempDirectory("novel-upload-journal-only").toFile()
		val target = File(directory, "source.txt")
		var journalDeleted = false

		val outcome = NovelImportCoordinator.recoverPreparedFile(target, 0, "", "") {
			journalDeleted = true
		}

		assertEquals(NovelImportCoordinator.PreparedRecovery.MISSING, outcome)
		assertTrue(journalDeleted)
		directory.deleteRecursively()
		Unit
	}

	@Test
	fun movedFileBeforeSummaryIsRecoveredWithoutBecomingOrphan() = runBlocking {
		val directory = Files.createTempDirectory("novel-upload-file-window").toFile()
		val target = File(directory, "source.txt").apply { writeText("book") }
		var journalDeleted = false

		val outcome = NovelImportCoordinator.recoverPreparedFile(target, 0, "", "") {
			journalDeleted = true
		}

		assertEquals(NovelImportCoordinator.PreparedRecovery.REBUILD_SUMMARY, outcome)
		assertFalse(journalDeleted)
		assertTrue(target.exists())
		directory.deleteRecursively()
		Unit
	}

	@Test
	fun coordinatorQueuesPreparedUploadWithoutRunningNetwork() {
		val source = File("src/main/kotlin/org/joinmastodon/android/novel/importer/NovelImportCoordinator.kt").readText()
		val uploadEntry = source.substringAfter("suspend fun uploadContentUri(").substringBefore("suspend fun resumeUpload(")

		assertTrue(uploadEntry.contains("NovelUploadWorker.enqueue("))
		assertTrue(uploadEntry.contains("parseStatus = \"queued\""))
		assertTrue(uploadEntry.contains("enterOperation("))
		assertFalse(uploadEntry.contains("uploadPrepared("))
		assertFalse(uploadEntry.contains("PrivateBookUpload("))
	}

	@Test
	fun cancelCoordinatorUploadCancelsBlockingCallAndNeverCompletes() = runBlocking {
		val file = Files.createTempFile("novel-upload-cancel", ".txt").toFile().apply { writeText("book") }
		val entered = CountDownLatch(1)
		val canceled = CountDownLatch(1)
		val completed = AtomicBoolean(false)
		val uploader = object : PrivateBookUpload(
			PrivateNovelApi("http://127.0.0.1/", "token", OkHttpClient(), true),
			{},
		) {
			override fun upload(file: File, metadata: PrivateNovelApi.UploadMetadata): PrivateNovelApi.BookDto {
				entered.countDown()
				if (!canceled.await(5, TimeUnit.SECONDS)) throw IOException("cancel was not delivered")
				throw IOException("Canceled")
			}

			override fun cancel() {
				canceled.countDown()
				super.cancel()
			}
		}
		val job = launch(Dispatchers.IO) {
			try {
				NovelImportCoordinator.uploadPrepared(uploader, file, PrivateNovelApi.UploadMetadata("Title", "Author", "txt", "text/plain"))
				completed.set(true)
			} catch (_: CancellationException) {
			}
		}

		assertTrue(entered.await(2, TimeUnit.SECONDS))
		job.cancelAndJoin()

		assertTrue(canceled.await(2, TimeUnit.SECONDS))
		assertFalse(completed.get())
		file.delete()
		Unit
	}
}
