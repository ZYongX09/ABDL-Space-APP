package org.joinmastodon.android.novel.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.joinmastodon.android.api.novels.PrivateBookUpload
import org.joinmastodon.android.api.novels.PrivateNovelApi
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.novel.importer.NovelImportCoordinator
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
		val api = PrivateNovelApi(session)
		try {
			if (!SAFE_BOOK_ID.matches(bookId)) return@withContext Result.failure()
			val book = execute(api, api.newBookCall(bookId), PrivateNovelApi.BookDto::class.java)
			if (book.id != bookId || book.format !in SUPPORTED_FORMATS || book.verifiedSize <= 0 || book.verifiedSize > PrivateBookUpload.MAX_SIZE || !SHA_256.matches(book.contentHash.orEmpty())) return@withContext Result.failure()
			val authorization = execute(api, api.newDownloadAuthorizeCall(bookId), PrivateNovelApi.DownloadAuthorization::class.java)
			val directory = File(applicationContext.filesDir, "novels/${NovelImportCoordinator.accountHash(accountId)}").apply { mkdirs() }
			val destination = File(directory, "$bookId.${book.format.lowercase(Locale.ROOT)}")
			downloadVerified(api.callFactory, authorization.downloadUrl, destination, book.verifiedSize, book.contentHash, false) { call -> currentCall.set(call) }
			if (AccountSessionManager.getInstance().tryGetAccount(accountId) !== session) {
				destination.delete()
				return@withContext Result.failure()
			}
			try {
				NovelImportCoordinator(applicationContext).importPrivateBook(accountId, destination, book)
			} catch (error: Exception) {
				destination.delete()
				throw error
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

	private fun <T> execute(api: PrivateNovelApi, call: Call, type: Class<T>): T {
		currentCall.set(call)
		return try {
			api.executeJson(call, type)
		} finally {
			currentCall.compareAndSet(call, null)
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
		fun enqueue(context: Context, accountId: String, bookId: String) {
			val request = OneTimeWorkRequestBuilder<NovelDownloadWorker>()
				.setInputData(Data.Builder().putString(KEY_ACCOUNT_ID, accountId).putString(KEY_BOOK_ID, bookId).build())
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
	}
}
