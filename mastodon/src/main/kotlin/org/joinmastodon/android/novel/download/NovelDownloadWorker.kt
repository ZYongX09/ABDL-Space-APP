package org.joinmastodon.android.novel.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.joinmastodon.android.api.novels.PrivateBookUpload
import org.joinmastodon.android.api.novels.PrivateNovelApi
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.novel.importer.NovelImportCoordinator
import org.joinmastodon.reader.data.NovelDatabase
import org.joinmastodon.reader.data.NovelTransferEntity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.Request

class NovelDownloadWorker(
	appContext: Context,
	params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
	private val currentCall = AtomicReference<Call?>()

	override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
		coroutineContext.job.invokeOnCompletion { currentCall.getAndSet(null)?.cancel() }
		val accountId = inputData.getString(KEY_ACCOUNT_ID) ?: return@withContext Result.failure()
		val bookId = inputData.getString(KEY_BOOK_ID) ?: return@withContext Result.failure()
		val session = AccountSessionManager.getInstance().tryGetAccount(accountId) ?: return@withContext Result.failure()
		val accessToken = session.token.accessToken
		val sessionGuard = AccountSessionGuard {
			val current = AccountSessionManager.getInstance().tryGetAccount(accountId)
			current === session && current.token.accessToken == accessToken
		}
		val api = PrivateNovelApi(session)
		try {
			if (!SAFE_BOOK_ID.matches(bookId)) return@withContext Result.failure()
			val recoveryDatabase = NovelDatabase.open(applicationContext, accountId)
			try {
				val existing = recoveryDatabase.transferDao().getByRemoteBook(NovelTransferEntity.DOWNLOAD, bookId)
				if (existing?.phase == NovelTransferEntity.DATABASE_COMMITTED) {
					sessionGuard.requireValid()
					val destination = File(existing.localTempPath)
					commitCandidate(destination, File(destination.parentFile, destination.name + ".candidate"), sessionGuard::isValid, true) {}
					recoveryDatabase.transferDao().delete(existing.transferId)
					return@withContext Result.success()
				}
			} finally {
				recoveryDatabase.close()
			}
			sessionGuard.requireValid()
			val book = execute(api, api.newBookCall(bookId), PrivateNovelApi.BookDto::class.java)
			if (book.id != bookId || book.format !in SUPPORTED_FORMATS || book.verifiedSize <= 0 || book.verifiedSize > PrivateBookUpload.MAX_SIZE || !SHA_256.matches(book.contentHash.orEmpty())) return@withContext Result.failure()
			sessionGuard.requireValid()
			val authorization = execute(api, api.newDownloadAuthorizeCall(bookId), PrivateNovelApi.DownloadAuthorization::class.java)
			sessionGuard.requireValid()
			val directory = File(applicationContext.filesDir, "novels/${NovelImportCoordinator.accountHash(accountId)}").apply { mkdirs() }
			val destination = File(directory, "$bookId.${book.format.lowercase(Locale.ROOT)}")
			val candidate = File(directory, "$bookId.${book.format.lowercase(Locale.ROOT)}.candidate")
			val transferId = "download:$bookId"
			val transferDatabase = NovelDatabase.open(applicationContext, accountId)
			try {
				transferDatabase.transferDao().upsert(NovelTransferEntity(
					transferId = transferId,
					accountId = accountId,
					direction = NovelTransferEntity.DOWNLOAD,
					remoteBookId = bookId,
					uploadId = null,
					localTempPath = destination.absolutePath,
					title = book.title,
					author = book.author,
					format = book.format,
					mimeType = if (book.format == "txt") "text/plain" else "application/epub+zip",
					phase = NovelTransferEntity.CANDIDATE_READY,
					contentHash = book.contentHash.orEmpty(),
					contentMd5 = null,
					size = book.verifiedSize,
				))
				runInterruptible {
					downloadVerified(api.callFactory, authorization.downloadUrl, candidate, book.verifiedSize, book.contentHash, false) { call -> registerCall(call) }
				}
			} catch (error: Throwable) {
				candidate.delete()
				throw error
			} finally {
				transferDatabase.close()
			}
			if (!sessionGuard.isValid()) {
				candidate.delete()
				return@withContext Result.failure()
			}
			commitCandidate(destination, candidate, sessionGuard::isValid) {
				NovelImportCoordinator(applicationContext).importPrivateBook(accountId, destination, book, transferId = transferId, sessionGuard = sessionGuard::isValid)
			}
			val cleanupDatabase = NovelDatabase.open(applicationContext, accountId)
			try {
				cleanupDatabase.transferDao().delete(transferId)
			} finally {
				cleanupDatabase.close()
			}
			Result.success()
		} catch (error: IOException) {
			if (isStopped) Result.failure() else Result.retry()
		} catch (error: Exception) {
			Result.failure()
		} finally {
			currentCall.set(null)
		}
	}

	private suspend fun <T> execute(api: PrivateNovelApi, call: Call, type: Class<T>): T {
		registerCall(call)
		val cancellation = coroutineContext.job.invokeOnCompletion { cause ->
			if (cause is kotlinx.coroutines.CancellationException) call.cancel()
		}
		return try {
			runInterruptible { api.executeJson(call, type) }
		} finally {
			cancellation.dispose()
			currentCall.compareAndSet(call, null)
		}
	}

	private fun registerCall(call: Call) {
		if (isStopped) {
			call.cancel()
			throw IOException("Canceled")
		}
		currentCall.set(call)
		if (isStopped && currentCall.compareAndSet(call, null)) {
			call.cancel()
			throw IOException("Canceled")
		}
	}

	companion object {
		const val KEY_ACCOUNT_ID = "account_id"
		const val KEY_BOOK_ID = "book_id"
		private val SAFE_BOOK_ID = Regex("[A-Za-z0-9_-]{1,128}")
		private val SHA_256 = Regex("[a-fA-F0-9]{64}")
		private val SUPPORTED_FORMATS = setOf("txt", "epub")

		@JvmStatic
		fun uniqueWorkName(accountId: String, bookId: String): String =
			"novel-download:${NovelImportCoordinator.accountHash(accountId)}:$bookId"

		@JvmStatic
		fun accountWorkTag(accountId: String): String = "novel-download-account:${NovelImportCoordinator.accountHash(accountId)}"

		@JvmStatic
		fun cancelAccount(context: Context, accountId: String) {
			WorkManager.getInstance(context).cancelAllWorkByTag(accountWorkTag(accountId))
		}

		@JvmStatic
		fun enqueue(context: Context, accountId: String, bookId: String) {
			val request = OneTimeWorkRequestBuilder<NovelDownloadWorker>()
				.setInputData(Data.Builder().putString(KEY_ACCOUNT_ID, accountId).putString(KEY_BOOK_ID, bookId).build())
				.addTag(accountWorkTag(accountId))
				.build()
			WorkManager.getInstance(context).enqueueUniqueWork(uniqueWorkName(accountId, bookId), ExistingWorkPolicy.KEEP, request)
		}

		@JvmStatic
		@Throws(IOException::class)
		fun downloadVerified(
			callFactory: Call.Factory,
			downloadUrl: String,
			destination: File,
			expectedSize: Long,
			expectedSha256: String,
			allowHttpForTests: Boolean,
			callObserver: ((Call) -> Unit)?,
		) {
			if ((!allowHttpForTests && !downloadUrl.startsWith("https://")) || expectedSize <= 0 || expectedSize > PrivateBookUpload.MAX_SIZE) throw IOException("Invalid download contract")
			val part = File(destination.parentFile, destination.name + ".part")
			part.delete()
			try {
				val call = callFactory.newCall(Request.Builder().url(downloadUrl).get().build())
				callObserver?.invoke(call)
				call.execute().use { response ->
					if (response.priorResponse() != null) throw IOException("Redirects are not allowed")
					if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code()}")
					val body = response.body() ?: throw IOException("Empty download body")
					val digest = MessageDigest.getInstance("SHA-256")
					var total = 0L
					body.byteStream().use { input ->
						FileOutputStream(part).use { output ->
							val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
							while (true) {
								val read = input.read(buffer)
								if (read < 0) break
								total += read
								if (total > expectedSize || total > PrivateBookUpload.MAX_SIZE) throw IOException("Download exceeds expected size")
								digest.update(buffer, 0, read)
								output.write(buffer, 0, read)
							}
						}
					}
					val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
					if (total != expectedSize || !hash.equals(expectedSha256, ignoreCase = true)) throw IOException("Downloaded book failed integrity verification")
				}
				destination.parentFile?.mkdirs()
				try {
					Files.move(part.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
				} catch (error: java.nio.file.AtomicMoveNotSupportedException) {
					throw IOException("Atomic file replacement is unavailable", error)
				}
			} finally {
				part.delete()
			}
		}

		@JvmStatic
		@Throws(IOException::class)
		suspend fun commitCandidate(destination: File, candidate: File, commitDatabase: suspend () -> Unit) =
			commitCandidate(destination, candidate, { true }, false, commitDatabase)

		@JvmStatic
		@Throws(IOException::class)
		suspend fun commitCandidate(destination: File, candidate: File, sessionValid: () -> Boolean, commitDatabase: suspend () -> Unit) {
			commitCandidate(destination, candidate, sessionValid, false, commitDatabase)
		}

		@JvmStatic
		@Throws(IOException::class)
		suspend fun commitCandidate(destination: File, candidate: File, sessionValid: () -> Boolean, databaseCommitted: Boolean, commitDatabase: suspend () -> Unit) {
			val backup = File(destination.parentFile, destination.name + ".backup")
			if (databaseCommitted) {
				requireSession(sessionValid)
				if (!destination.exists()) throw IOException("Committed novel file is missing")
				backup.delete()
				candidate.delete()
				return
			}
			if (backup.exists()) {
				if (destination.exists()) destination.delete()
				moveAtomic(backup, destination)
			}
			var switched = false
			try {
				requireSession(sessionValid)
				if (destination.exists()) moveAtomic(destination, backup)
				requireSession(sessionValid)
				moveAtomic(candidate, destination)
				switched = true
				requireSession(sessionValid)
				commitDatabase()
				backup.delete()
			} catch (error: Throwable) {
				if (switched) destination.delete()
				if (backup.exists()) moveAtomic(backup, destination)
				throw error
			} finally {
				candidate.delete()
				if (backup.exists() && !destination.exists()) moveAtomic(backup, destination)
				if (destination.exists()) backup.delete()
			}
		}

		private fun requireSession(sessionValid: () -> Boolean) {
			if (!sessionValid()) throw IOException("Account session changed")
		}

		private fun moveAtomic(source: File, destination: File) {
			try {
				Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
			} catch (error: java.nio.file.AtomicMoveNotSupportedException) {
				throw IOException("Atomic file replacement is unavailable", error)
			}
		}
	}
}

fun interface AccountSessionGuard {
	fun isValid(): Boolean

	fun requireValid() {
		if (!isValid()) throw IOException("Account session changed")
	}
}
