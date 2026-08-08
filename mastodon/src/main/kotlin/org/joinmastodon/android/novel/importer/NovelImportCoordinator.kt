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
import org.joinmastodon.reader.parser.BookParser
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class NovelImportCoordinator(
	private val context: Context,
	private val parser: BookParser = BookParser(),
) {
	data class PreparedImport(
		val file: File,
		val contentHash: String,
		val contentMd5: String,
	)

	suspend fun prepareContentUri(accountId: String, uri: Uri, format: String, takeFlags: Int): PreparedImport = withContext(Dispatchers.IO) {
		if (takeFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
			runCatching {
				context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
			}
		}
		val directory = File(context.cacheDir, "novels/import/${accountHash(accountId)}").apply { mkdirs() }
		val target = File.createTempFile("import_", ".${format.lowercase()}", directory)
		try {
			currentCoroutineContext().ensureActive()
			context.contentResolver.openInputStream(uri)?.use { input ->
				FileOutputStream(target).use { output ->
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
			if (target.length() == 0L) error("小说文件不能为空")
			currentCoroutineContext().ensureActive()
			PreparedImport(target, PrivateBookUpload.sha256(target), PrivateBookUpload.md5Base64(target))
		} catch (error: Throwable) {
			target.delete()
			throw error
		}
	}

	suspend fun uploadContentUri(
		accountId: String,
		uri: Uri,
		metadata: PrivateNovelApi.UploadMetadata,
		takeFlags: Int,
		progress: (Int) -> Unit,
	): PrivateNovelApi.BookDto = withContext(Dispatchers.IO) {
		val prepared = prepareContentUri(accountId, uri, metadata.format, takeFlags)
		try {
			val session = AccountSessionManager.getInstance().tryGetAccount(accountId) ?: error("账号已退出")
			val uploader = PrivateBookUpload(PrivateNovelApi(session), progress)
			uploadPrepared(uploader, prepared.file, metadata)
		} finally {
			prepared.file.delete()
		}
	}

	suspend fun importPrivateBook(accountId: String, file: File, remote: PrivateNovelApi.BookDto, officialPath: String = file.absolutePath, sessionGuard: () -> Boolean = { true }): Unit = withContext(Dispatchers.IO) {
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
				if (!sessionGuard()) error("账号已退出")
			}
		} finally {
			database.close()
		}
	}

	companion object {
		const val SOURCE_TYPE_PRIVATE = "private"
		const val DOWNLOAD_STATE_READY = "ready"

		internal suspend fun uploadPrepared(uploader: PrivateBookUpload, file: File, metadata: PrivateNovelApi.UploadMetadata): PrivateNovelApi.BookDto =
			suspendCancellableCoroutine { continuation ->
				continuation.invokeOnCancellation { uploader.cancel() }
				val thread = Thread {
					try {
						val result = uploader.upload(file, metadata)
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
