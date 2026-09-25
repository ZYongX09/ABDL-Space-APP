package org.joinmastodon.android.novel.editor

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.joinmastodon.android.api.novels.PrivateNovelApi
import org.joinmastodon.android.novel.download.NovelDownloadWorker
import org.joinmastodon.android.novel.importer.NovelImportCoordinator
import org.joinmastodon.android.novel.sync.NovelSyncWorker
import org.joinmastodon.reader.data.NovelDatabase

/** Thin non-UI bridge: the new Java shelf reuses existing private storage/workers. */
class NovelLibraryBridge(private val application: Application, private val accountId: String) : AutoCloseable {
	private val database = NovelDatabase.open(application, accountId)
	data class Book(val id: String, val remoteId: String?, val title: String, val author: String?, val downloadState: String, val chapterCount: Int)

	fun books(): List<Book> = runBlocking {
		database.novelBookDao().getActive(accountId).filter { it.sourceType == "private" }.map {
			Book(it.id, it.remoteId, it.title, it.author, it.downloadState, database.novelChapterDao().countByBookId(it.id))
		}
	}
	fun refresh() { NovelSyncWorker.enqueue(application, accountId) }
	fun upload(uri: Uri, title: String, author: String, format: String, mime: String, flags: Int) = runBlocking {
		NovelImportCoordinator(application).uploadContentUri(accountId, uri, PrivateNovelApi.UploadMetadata(title, author, format, mime), flags) {}
	}
	fun download(book: Book) { book.remoteId?.let { NovelDownloadWorker.enqueue(application, accountId, it) } }
	override fun close() { database.close() }
}
