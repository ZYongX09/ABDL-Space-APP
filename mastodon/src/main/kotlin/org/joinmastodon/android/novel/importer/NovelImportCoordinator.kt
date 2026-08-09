package org.joinmastodon.android.novel.importer

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import org.joinmastodon.android.api.novels.PrivateBookUpload
import org.joinmastodon.android.api.novels.PrivateNovelApi
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.reader.data.NovelBookEntity
import org.joinmastodon.reader.data.NovelChapterEntity
import org.joinmastodon.reader.data.NovelDatabase
import org.joinmastodon.reader.data.NovelTransferEntity
import org.joinmastodon.reader.parser.BookParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.joinmastodon.android.novel.NovelAccountDataCleaner
import org.joinmastodon.android.novel.upload.NovelUploadWorker

class NovelImportCoordinator(
	private val context: Context,
	private val parser: BookParser = BookParser(),
) {
	enum class PreparedRecovery { MISSING, REBUILD_SUMMARY, READY }

	data class PreparedImport(
		val transferId: String,
		val file: File,
		val contentHash: String,
		val contentMd5: String,
	)

	private suspend fun copyContentUri(accountId: String, uri: Uri, format: String, takeFlags: Int, transferId: String = UUID.randomUUID().toString()): PreparedImport = withContext(Dispatchers.IO) {
		if (takeFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
			runCatching {
				context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
			}
		}
		val directory = File(context.filesDir, "novels/transfers/${accountHash(accountId)}/$transferId").apply { mkdirs() }
		val part = File(directory, "source.${format.lowercase()}.part")
		val target = File(directory, "source.${format.lowercase()}")
		try {
			currentCoroutineContext().ensureActive()
			context.contentResolver.openInputStream(uri)?.use { input ->
				FileOutputStream(part).use { output ->
					val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
					var total = 0L
					while (true) {
						currentCoroutineContext().ensureActive()
						val read = input.read(buffer)
						if (read < 0) break
						total += read
						if (total > PrivateBookUpload.MAX_SIZE) error("小说文件超过 50 MiB")
						output.write(buffer, 0, read)
					}
				}
			} ?: error("无法读取选择的小说文件")
			if (part.length() == 0L) error("小说文件不能为空")
			try {
				Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
			} catch (error: java.nio.file.AtomicMoveNotSupportedException) {
				throw IOException("无法原子保存小说传输副本", error)
			}
			currentCoroutineContext().ensureActive()
			PreparedImport(transferId, target, PrivateBookUpload.sha256(target), PrivateBookUpload.md5Base64(target))
		} catch (error: Throwable) {
			directory.deleteRecursively()
			throw error
		}
	}

	suspend fun prepareContentUri(accountId: String, uri: Uri, format: String, takeFlags: Int): PreparedImport =
		copyContentUri(accountId, uri, format, takeFlags)

	suspend fun uploadContentUri(
		accountId: String,
		uri: Uri,
		metadata: PrivateNovelApi.UploadMetadata,
		takeFlags: Int,
		progress: (Int) -> Unit,
	): PrivateNovelApi.BookDto = withContext(Dispatchers.IO) {
		val transferId = UUID.randomUUID().toString()
		val directory = File(context.filesDir, "novels/transfers/${accountHash(accountId)}/$transferId").apply { mkdirs() }
		val target = File(directory, "source.${metadata.format.lowercase()}")
		val database = NovelDatabase.open(context, accountId)
		try {
			var transfer = NovelTransferEntity(
				transferId = transferId,
				accountId = accountId,
				direction = NovelTransferEntity.UPLOAD,
				remoteBookId = null,
				uploadId = null,
				localTempPath = target.absolutePath,
				title = metadata.title,
				author = metadata.author,
				format = metadata.format,
				mimeType = metadata.mimeType,
				phase = NovelTransferEntity.PREPARED,
				contentHash = "",
				contentMd5 = "",
				size = 0,
			)
			database.transferDao().upsert(transfer)
			val prepared = copyContentUri(accountId, uri, metadata.format, takeFlags, transferId)
			transfer = transfer.copy(contentHash = prepared.contentHash, contentMd5 = prepared.contentMd5, size = prepared.file.length(), updatedAt = System.currentTimeMillis())
			database.transferDao().upsert(transfer)
			val session = AccountSessionManager.getInstance().tryGetAccount(accountId) ?: error("账号已退出")
			val uploader = PrivateBookUpload(PrivateNovelApi(session), progress)
			val generation = NovelAccountDataCleaner.captureGeneration(accountId)
			if (!NovelAccountDataCleaner.registerUpload(accountId, generation, uploader)) error("账号已退出")
			NovelUploadWorker.enqueue(context, accountId, transferId)
			val result = try {
				uploadPrepared(uploader, prepared.file, metadata, null) { uploadId, phase ->
					if (!NovelAccountDataCleaner.isGenerationValid(accountId, generation)) throw IOException("账号已退出")
					transfer = transfer.copy(uploadId = uploadId, remoteBookId = uploadId, phase = phase, updatedAt = System.currentTimeMillis())
					runBlocking { database.transferDao().upsert(transfer) }
				}
			} finally {
				NovelAccountDataCleaner.unregisterUpload(accountId, uploader)
			}
			if (!NovelAccountDataCleaner.isGenerationValid(accountId, generation)) error("账号已退出")
			transfer = transfer.copy(phase = NovelTransferEntity.COMPLETE, updatedAt = System.currentTimeMillis())
			database.transferDao().upsert(transfer)
			if (prepared.file.parentFile?.deleteRecursively() != false) database.transferDao().delete(prepared.transferId)
			result
		} finally {
			database.close()
		}
	}

	suspend fun resumeUpload(accountId: String, transferId: String, progress: (Int) -> Unit): PrivateNovelApi.BookDto = withContext(Dispatchers.IO) {
		val database = NovelDatabase.open(context, accountId)
		try {
			var transfer = database.transferDao().get(transferId) ?: error("找不到待恢复的小说传输")
			if (transfer.accountId != accountId || transfer.direction != NovelTransferEntity.UPLOAD) error("小说传输账号不匹配")
			val file = File(transfer.localTempPath)
			if (!file.isFile || file.length() != transfer.size || PrivateBookUpload.sha256(file) != transfer.contentHash || PrivateBookUpload.md5Base64(file) != transfer.contentMd5) error("小说传输副本已损坏")
			if (transfer.phase == PrivateBookUpload.Recovery.COMPLETE) {
				val result = PrivateNovelApi.BookDto().apply {
					id = transfer.remoteBookId
					title = transfer.title
					author = transfer.author
					format = transfer.format
					contentHash = transfer.contentHash
					verifiedSize = transfer.size
					parseStatus = "ready"
				}
				database.transferDao().delete(transferId)
				file.parentFile?.deleteRecursively()
				return@withContext result
			}
			val metadata = PrivateNovelApi.UploadMetadata(transfer.title, transfer.author, transfer.format, transfer.mimeType)
			val session = AccountSessionManager.getInstance().tryGetAccount(accountId) ?: error("账号已退出")
			val recovery = transfer.uploadId?.let { PrivateBookUpload.Recovery(it, transfer.phase) }
			val uploader = PrivateBookUpload(PrivateNovelApi(session), progress)
			val generation = NovelAccountDataCleaner.captureGeneration(accountId)
			if (!NovelAccountDataCleaner.registerUpload(accountId, generation, uploader)) error("账号已退出")
			val result = try {
				uploadPrepared(uploader, file, metadata, recovery) { uploadId, phase ->
					if (!NovelAccountDataCleaner.isGenerationValid(accountId, generation)) throw IOException("账号已退出")
					transfer = transfer.copy(uploadId = uploadId, remoteBookId = uploadId, phase = phase, updatedAt = System.currentTimeMillis())
					runBlocking { database.transferDao().upsert(transfer) }
				}
			} finally {
				NovelAccountDataCleaner.unregisterUpload(accountId, uploader)
			}
			if (!NovelAccountDataCleaner.isGenerationValid(accountId, generation)) error("账号已退出")
			transfer = transfer.copy(phase = NovelTransferEntity.COMPLETE, updatedAt = System.currentTimeMillis())
			database.transferDao().upsert(transfer)
			if (file.parentFile?.deleteRecursively() != false) database.transferDao().delete(transferId)
			result
		} finally {
			database.close()
		}
	}

	suspend fun importPrivateBook(accountId: String, file: File, remote: PrivateNovelApi.BookDto, officialPath: String = file.absolutePath, transferId: String? = null, sessionGuard: () -> Boolean = { true }): Unit = withContext(Dispatchers.IO) {
		currentCoroutineContext().ensureActive()
		val parsed = parser.parse(file)
		currentCoroutineContext().ensureActive()
		val book = NovelBookEntity(
			id = parsed.book.id,
			accountId = accountId,
			title = remote.title ?: parsed.book.title,
			author = remote.author ?: parsed.book.author,
			remoteId = remote.id,
			sourceType = SOURCE_TYPE_PRIVATE,
			contentHash = remote.contentHash,
			localFilePath = officialPath,
			downloadState = DOWNLOAD_STATE_READY,
		)
		val chapters = parsed.chapters.map { chapter ->
			NovelChapterEntity(chapter.id, book.id, chapter.title, chapter.content, chapter.index)
		}
		val database = NovelDatabase.open(context, accountId)
		try {
			database.withTransaction {
				if (!sessionGuard()) error("账号已退出")
				database.novelImportDao().importBook(book, chapters)
				if (transferId != null) {
					val transfer = database.transferDao().get(transferId) ?: error("下载恢复记录不存在")
					database.transferDao().upsert(transfer.copy(phase = NovelTransferEntity.DATABASE_COMMITTED, updatedAt = System.currentTimeMillis()))
				}
				if (!sessionGuard()) error("账号已退出")
			}
		} finally {
			database.close()
		}
	}

	companion object {
		const val SOURCE_TYPE_PRIVATE = "private"
		const val DOWNLOAD_STATE_READY = "ready"
		internal suspend fun recoverPreparedFile(file: File, size: Long, contentHash: String, contentMd5: String, deleteJournal: suspend () -> Unit): PreparedRecovery {
			if (!file.isFile) {
				deleteJournal()
				file.parentFile?.deleteRecursively()
				return PreparedRecovery.MISSING
			}
			if (size <= 0 || contentHash.isEmpty() || contentMd5.isEmpty()) return PreparedRecovery.REBUILD_SUMMARY
			if (file.length() != size || PrivateBookUpload.sha256(file) != contentHash || PrivateBookUpload.md5Base64(file) != contentMd5) {
				deleteJournal()
				file.parentFile?.deleteRecursively()
				return PreparedRecovery.MISSING
			}
			return PreparedRecovery.READY
		}

		internal fun isVerifiedTransferFile(file: File, size: Long, contentHash: String, contentMd5: String): Boolean =
			file.isFile && size > 0 && contentHash.isNotEmpty() && contentMd5.isNotEmpty() && file.length() == size &&
				PrivateBookUpload.sha256(file) == contentHash && PrivateBookUpload.md5Base64(file) == contentMd5

		internal suspend fun uploadPrepared(uploader: PrivateBookUpload, file: File, metadata: PrivateNovelApi.UploadMetadata): PrivateNovelApi.BookDto =
			uploadPreparedCall(uploader) { uploader.upload(file, metadata) }

		internal suspend fun uploadPrepared(
			uploader: PrivateBookUpload,
			file: File,
			metadata: PrivateNovelApi.UploadMetadata,
			recovery: PrivateBookUpload.Recovery?,
			recoveryListener: PrivateBookUpload.RecoveryListener,
		): PrivateNovelApi.BookDto = uploadPreparedCall(uploader) { uploader.resume(file, metadata, recovery, recoveryListener) }

		private suspend fun uploadPreparedCall(uploader: PrivateBookUpload, upload: () -> PrivateNovelApi.BookDto): PrivateNovelApi.BookDto =
			suspendCancellableCoroutine { continuation ->
				continuation.invokeOnCancellation { uploader.cancel() }
				val thread = Thread {
					try {
						val result = upload()
						continuation.resume(result) { _, _, _ -> }
					} catch (error: Throwable) {
						if (continuation.isActive) continuation.resumeWith(Result.failure(error))
					}
				}
				thread.name = "novel-private-upload"
				thread.start()
			}

		fun accountHash(accountId: String): String = MessageDigest.getInstance("SHA-256")
			.digest(accountId.toByteArray(Charsets.UTF_8))
			.joinToString("") { "%02x".format(it.toInt() and 0xff) }
	}
}
