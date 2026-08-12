package org.joinmastodon.android.novel.download

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.joinmastodon.android.api.novels.PrivateBookUpload
import org.joinmastodon.android.api.novels.PrivateNovelApi
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.novel.importer.NovelImportCoordinator
import org.joinmastodon.android.novel.NovelAccountDataCleaner
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
	class DatabaseCommittedException(val original: Throwable) : Exception(original)

	private val currentCall = AtomicReference<Call?>()

	override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
		coroutineContext.job.invokeOnCompletion { currentCall.getAndSet(null)?.cancel() }
		val accountId = inputData.getString(KEY_ACCOUNT_ID) ?: return@withContext Result.failure()
		val bookId = inputData.getString(KEY_BOOK_ID) ?: return@withContext Result.failure()
		val session = AccountSessionManager.getInstance().tryGetAccount(accountId) ?: return@withContext Result.failure()
		val generation = NovelAccountDataCleaner.captureGeneration(accountId)
		val lease = NovelAccountDataCleaner.enterOperation(accountId, generation) ?: return@withContext Result.failure()
		val accessToken = session.token.accessToken
		val sessionGuard = AccountSessionGuard {
			val current = AccountSessionManager.getInstance().tryGetAccount(accountId)
			current?.token?.accessToken == accessToken && NovelAccountDataCleaner.isGenerationValid(accountId, generation)
		}
		val api = PrivateNovelApi(session)
		var stage = "validate"
		try {
			if (!SAFE_BOOK_ID.matches(bookId)) return@withContext Result.failure()
			updateDownloadState(accountId, bookId, "downloading")
			stage = "recover"
			val recoveryDatabase = NovelDatabase.open(applicationContext, accountId)
			try {
				val existing = recoveryDatabase.transferDao().getByRemoteBook(NovelTransferEntity.DOWNLOAD, bookId)
				if (existing?.phase == NovelTransferEntity.DATABASE_COMMITTED) {
					val destination = File(existing.localTempPath)
					if (recoverCommitted(destination, File(destination.parentFile, destination.name + ".candidate"), existing.size, existing.contentHash, sessionGuard::isValid)) {
						sessionGuard.requireValid()
						recoveryDatabase.transferDao().delete(existing.transferId)
						return@withContext Result.success()
					} else {
						recoveryDatabase.transferDao().upsert(existing.copy(phase = NovelTransferEntity.CANDIDATE_READY, updatedAt = System.currentTimeMillis()))
					}
				}
			} finally {
				recoveryDatabase.close()
			}
			sessionGuard.requireValid()
			stage = "book_call"
			val bookCall = api.newBookCall(bookId)
			stage = "book_json"
			val book = execute(api, bookCall, PrivateNovelApi.BookDto::class.java)
			stage = "book_metadata"
			if (book.id != bookId || book.format !in SUPPORTED_FORMATS || book.verifiedSize <= 0 || book.verifiedSize > PrivateBookUpload.MAX_SIZE || !SHA_256.matches(book.contentHash.orEmpty())) throw IOException("Invalid book metadata")
			stage = "book_session"
			sessionGuard.requireValid()
			stage = "authorize"
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
					stage = "download"
					downloadVerified(api.callFactory, authorization.downloadUrl, candidate, book.verifiedSize, book.contentHash, false) { call -> registerCall(call) }
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
			stage = "commit"
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
			val retry = !isStopped && isRetryable(error)
			Log.w(LOG_TAG, "Download $stage ${if (retry) "retry" else "failed"}: ${error.javaClass.simpleName}: ${safeFailureMessage(error)}")
			updateDownloadState(accountId, bookId, if (retry) "remote" else "failed")
			if (retry) Result.retry() else Result.failure()
		} catch (error: Exception) {
			Log.w(LOG_TAG, "Download $stage failed: ${error.javaClass.simpleName}: ${safeFailureMessage(error)}")
			updateDownloadState(accountId, bookId, "failed")
			Result.failure()
		} finally {
			currentCall.set(null)
			lease.close()
		}
	}

	private suspend fun updateDownloadState(accountId: String, bookId: String, state: String) {
		val database = NovelDatabase.open(applicationContext, accountId)
		try {
			database.novelBookDao().updatePrivateDownloadState(accountId, bookId, state, System.currentTimeMillis())
		} finally {
			database.close()
		}
	}

	private suspend fun <T> execute(api: PrivateNovelApi, call: Call, type: Class<T>): T {
		registerCall(call)
		val cancellation = coroutineContext.job.invokeOnCompletion { cause ->
			if (cause is kotlinx.coroutines.CancellationException) call.cancel()
		}
		return try {
			api.executeJson(call, type)
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
		private const val LOG_TAG = "NovelDownloadWorker"
		const val KEY_ACCOUNT_ID = "account_id"
		const val KEY_BOOK_ID = "book_id"
		private val SAFE_BOOK_ID = Regex("[A-Za-z0-9_-]{1,128}")
		private val SHA_256 = Regex("[a-fA-F0-9]{64}")
		private val SUPPORTED_FORMATS = setOf("txt", "epub")

		private fun safeFailureMessage(error: Throwable): String {
			val message = error.message.orEmpty()
			return when {
				message.matches(Regex("HTTP \\d{3}( \\([a-z_]+\\))?")) -> message
				message.matches(Regex("Download failed: HTTP \\d{3}")) -> message
				message in setOf(
					"Invalid book metadata",
					"Invalid download contract",
					"Empty download body",
					"Downloaded book failed integrity verification",
					"Download exceeds expected size",
					"Redirects are not allowed",
				) -> message
				else -> "download_failed"
			}
		}

		internal fun isRetryable(error: IOException): Boolean {
			if (error is PrivateNovelApi.ApiException) return error.status >= 500 || error.status == 408 || error.status == 429
			return error.message !in setOf(
				"Invalid book metadata",
				"Invalid download contract",
				"Empty download body",
				"Downloaded book failed integrity verification",
				"Download exceeds expected size",
				"Redirects are not allowed",
				"Atomic file replacement is unavailable",
			)
				&& !error.message.orEmpty().matches(Regex("Download failed: HTTP (4\\d\\d|3\\d\\d)"))
		}

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
			var committed = false
			try {
				requireSession(sessionValid)
				if (destination.exists()) moveAtomic(destination, backup)
				requireSession(sessionValid)
				moveAtomic(candidate, destination)
				switched = true
				requireSession(sessionValid)
				try {
					withContext(NonCancellable) {
						commitDatabase()
						committed = true
					}
				} catch (error: DatabaseCommittedException) {
					committed = true
					throw error.original
				}
				backup.delete()
			} catch (error: Throwable) {
				if (!committed) {
					if (switched) destination.delete()
					if (backup.exists()) moveAtomic(backup, destination)
				}
				throw error
			} finally {
				candidate.delete()
				if (!committed && backup.exists() && !destination.exists()) moveAtomic(backup, destination)
				if (committed && destination.exists()) backup.delete()
			}
		}

		@JvmStatic
		fun recoverCommitted(destination: File, candidate: File, expectedSize: Long, expectedHash: String, sessionValid: () -> Boolean): Boolean {
			requireSession(sessionValid)
			val backup = File(destination.parentFile, destination.name + ".backup")
			val valid = destination.isFile && destination.length() == expectedSize && sha256(destination).equals(expectedHash, ignoreCase = true)
			requireSession(sessionValid)
			if (valid) {
				backup.delete()
				candidate.delete()
				return true
			}
			destination.delete()
			if (backup.exists()) moveAtomic(backup, destination)
			candidate.delete()
			return false
		}

		private fun sha256(file: File): String {
			val digest = MessageDigest.getInstance("SHA-256")
			file.inputStream().use { input ->
				val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
				while (true) {
					val read = input.read(buffer)
					if (read < 0) break
					digest.update(buffer, 0, read)
				}
			}
			return digest.digest().joinToString("") { "%02x".format(it) }
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
