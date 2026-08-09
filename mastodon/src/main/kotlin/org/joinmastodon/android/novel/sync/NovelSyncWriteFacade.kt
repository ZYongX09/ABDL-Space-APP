package org.joinmastodon.android.novel.sync

import com.google.gson.Gson
import androidx.room.withTransaction
import org.joinmastodon.reader.data.AnnotationEntity
import org.joinmastodon.reader.data.BookmarkEntity
import org.joinmastodon.reader.data.NovelDatabase
import org.joinmastodon.reader.data.NovelSyncOutboxEntity
import org.joinmastodon.reader.data.NovelProgressEntity

/** Host-facing entry point for local reading mutations that must be cloud-synced. */
class NovelSyncWriteFacade(
	private val accountId: String,
	private val database: NovelDatabase,
	private val requestSync: () -> Unit,
	private val now: () -> Long = System::currentTimeMillis,
) {
	private val gson = Gson()

	suspend fun saveBookmark(id: String, bookId: String, chapterId: String, position: Int): BookmarkEntity {
		val book = requireSyncableBook(bookId)
		requireChapter(bookId, chapterId)
		val timestamp = now()
		val existing = database.bookmarkDao().get(accountId, id)
		val bookmark = BookmarkEntity(id, accountId, bookId, chapterId, position, existing?.createdAt ?: timestamp, timestamp)
		database.bookmarkDao().saveWithOutbox(bookmark, outbox("bookmark", id, bookId, requireNotNull(book.remoteId), gson.toJson(BookmarkPayload(chapterId, position)), timestamp, null))
		requestSync()
		return bookmark
	}

	suspend fun saveProgress(bookId: String, chapterIndex: Int, pageIndex: Int) {
		val book = requireSyncableBook(bookId)
		val timestamp = now()
		val itemId = "progress:$bookId"
		val payload = gson.toJson(ProgressPayload(chapterIndex, pageIndex))
		database.withTransaction {
			database.syncDao().upsertProgress(NovelProgressEntity(itemId, accountId, bookId, payload, timestamp, null))
			database.syncDao().enqueue(outbox("progress", itemId, bookId, requireNotNull(book.remoteId), payload, timestamp, null))
		}
		requestSync()
	}

	suspend fun deleteBookmark(id: String): BookmarkEntity? {
		val existing = database.bookmarkDao().get(accountId, id) ?: return null
		val book = requireSyncableBook(existing.bookId)
		val timestamp = now()
		val deleted = existing.copy(updatedAt = timestamp, deletedAt = timestamp)
		database.bookmarkDao().saveWithOutbox(deleted, outbox("bookmark", id, existing.bookId, requireNotNull(book.remoteId), gson.toJson(BookmarkPayload(existing.chapterId, existing.position)), timestamp, timestamp))
		requestSync()
		return deleted
	}

	suspend fun saveAnnotation(id: String, bookId: String, chapterId: String, startOffset: Int, endOffset: Int, selectedText: String, note: String?): AnnotationEntity {
		val book = requireSyncableBook(bookId)
		requireChapter(bookId, chapterId)
		require(startOffset >= 0 && endOffset >= startOffset)
		val timestamp = now()
		val existing = database.annotationDao().get(accountId, id)
		val annotation = AnnotationEntity(id, accountId, bookId, chapterId, startOffset, endOffset, selectedText, note, existing?.createdAt ?: timestamp, timestamp)
		val payload = gson.toJson(NotePayload(chapterId, startOffset, endOffset, selectedText, note))
		database.annotationDao().saveWithOutbox(annotation, outbox("note", id, bookId, requireNotNull(book.remoteId), payload, timestamp, null))
		requestSync()
		return annotation
	}

	suspend fun deleteAnnotation(id: String): AnnotationEntity? {
		val existing = database.annotationDao().get(accountId, id) ?: return null
		val book = requireSyncableBook(existing.bookId)
		val timestamp = now()
		val deleted = existing.copy(updatedAt = timestamp, deletedAt = timestamp)
		val payload = gson.toJson(NotePayload(existing.chapterId, existing.startOffset, existing.endOffset, existing.selectedText, existing.note))
		database.annotationDao().saveWithOutbox(deleted, outbox("note", id, existing.bookId, requireNotNull(book.remoteId), payload, timestamp, timestamp))
		requestSync()
		return deleted
	}

	private suspend fun requireSyncableBook(bookId: String) = requireNotNull(database.novelBookDao().getById(accountId, bookId)) {
		"Book does not belong to the active account"
	}.also { require(it.remoteId != null) { "Local-only books cannot be cloud-synced" } }

	private suspend fun requireChapter(bookId: String, chapterId: String) {
		require(database.novelChapterDao().getById(chapterId)?.bookId == bookId) { "Chapter does not belong to the book" }
	}

	private fun outbox(itemType: String, itemId: String, bookId: String, remoteBookId: String, payload: String, updatedAt: Long, deletedAt: Long?) =
		NovelSyncOutboxEntity("$itemType:$itemId", accountId, itemType, itemId, bookId, remoteBookId, payload, updatedAt, deletedAt)

	private data class BookmarkPayload(val chapterId: String, val position: Int)
	private data class NotePayload(val chapterId: String, val startOffset: Int, val endOffset: Int, val selectedText: String, val note: String?)
	private data class ProgressPayload(val chapterIndex: Int, val pageIndex: Int)
}
