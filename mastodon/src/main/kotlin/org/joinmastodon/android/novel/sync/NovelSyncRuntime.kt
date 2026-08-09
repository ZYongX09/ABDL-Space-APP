package org.joinmastodon.android.novel.sync

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.joinmastodon.android.api.novels.PrivateNovelApi
import org.joinmastodon.reader.data.AnnotationEntity
import org.joinmastodon.reader.data.BookmarkEntity
import org.joinmastodon.reader.data.NovelBookEntity
import org.joinmastodon.reader.data.NovelDatabase
import org.joinmastodon.reader.data.NovelProgressEntity
import org.joinmastodon.reader.data.NovelSyncCheckpointEntity
import org.joinmastodon.reader.data.NovelSyncOutboxEntity

class PrivateNovelSyncRemote(private val api: PrivateNovelApi) : SyncRemote {
	override suspend fun getBooks(cursor: String?, limit: Int): RemoteBooksPage = withContext(Dispatchers.IO) {
		val page = runInterruptible { api.executeJson(api.newBooksCall(cursor, limit), PrivateNovelApi.BooksPage::class.java) }
		RemoteBooksPage(page.items.orEmpty().map { RemoteBook(it.id, it.title.orEmpty(), it.author, it.contentHash, it.updatedAt) }, page.nextCursor)
	}

	override suspend fun getSync(cursor: String?, limit: Int): SyncPage = withContext(Dispatchers.IO) {
		val page = runInterruptible { api.executeJson(api.newSyncCall(cursor, limit), PrivateNovelApi.SyncPageDto::class.java) }
		SyncPage(page.items.orEmpty().map(::map), page.nextCursor, page.checkpointCursor ?: cursor.orEmpty())
	}

	override suspend fun put(change: LocalSyncChange): RemoteSyncItem = withContext(Dispatchers.IO) {
		try {
			val request = PrivateNovelApi.SyncPutRequest(change.remoteBookId, change.itemType, change.itemId, JsonParser.parseString(change.payload).asJsonObject, change.clientUpdatedAt, change.deletedAt)
			map(runInterruptible { api.executeJson(api.newPutSyncItemCall(change.itemId, request), PrivateNovelApi.SyncItemDto::class.java) })
		} catch (error: PrivateNovelApi.ApiException) {
			if (error.status == 404) throw RemoteBookDeletedException()
			throw error
		}
	}

	private fun map(item: PrivateNovelApi.SyncItemDto) = RemoteSyncItem(
		item.seq, item.bookId, item.itemType, item.itemId, item.payload?.toString() ?: "{}", item.clientUpdatedAt, item.serverUpdatedAt, item.deletedAt,
	)
}

class RoomNovelSyncStore(
	private val accountId: String,
	private val database: NovelDatabase,
) : SyncStore {
	private val gson = Gson()

	override suspend fun replaceRemoteBooks(books: List<RemoteBook>) = database.withTransaction {
		books.forEach { remote ->
			val existing = database.novelBookDao().getByRemoteId(accountId, "private", remote.id)
			database.novelBookDao().upsert(NovelBookEntity(
				id = existing?.id ?: UUID.randomUUID().toString(), accountId = accountId, title = remote.title, author = remote.author,
				remoteId = remote.id, sourceType = "private", contentHash = remote.contentHash, localFilePath = existing?.localFilePath,
				downloadState = existing?.downloadState ?: "remote", remoteUpdatedAt = remote.updatedAt, updatedAt = remote.updatedAt, deletedAt = null,
			))
		}
		val remoteIds = books.map { it.id }
		val deletedAt = System.currentTimeMillis()
		if (remoteIds.isEmpty()) database.novelBookDao().markAllPrivateRemoteDeleted(accountId, deletedAt)
		else database.novelBookDao().markMissingPrivateRemoteDeleted(accountId, remoteIds, deletedAt)
	}

	override suspend fun checkpoint(): String? = database.syncDao().checkpoint(accountId)

	override suspend fun applyPage(items: List<RemoteSyncItem>, checkpointCursor: String) = database.withTransaction {
		items.forEach { item ->
			val book = database.novelBookDao().getByRemoteId(accountId, "private", item.bookId) ?: return@forEach
			when (item.itemType) {
				"progress" -> {
					val current = database.syncDao().progress(accountId, item.itemId)
					if (shouldApply(current?.updatedAt, current?.deletedAt, item.clientUpdatedAt, item.deletedAt)) {
						database.syncDao().upsertProgress(NovelProgressEntity(item.itemId, accountId, book.id, item.payload, item.clientUpdatedAt, item.deletedAt))
					}
				}
				"bookmark" -> applyBookmark(book.id, item)
				"note" -> applyNote(book.id, item)
			}
		}
		database.syncDao().setCheckpoint(NovelSyncCheckpointEntity(accountId, checkpointCursor))
	}

	private suspend fun applyBookmark(bookId: String, item: RemoteSyncItem) {
		val payload = runCatching { gson.fromJson(item.payload, BookmarkPayload::class.java) }.getOrNull() ?: return
		val chapter = database.novelChapterDao().getById(payload.chapterId) ?: return
		if (chapter.bookId != bookId) return
		val current = database.bookmarkDao().get(accountId, item.itemId)
		if (!shouldApply(current?.updatedAt, current?.deletedAt, item.clientUpdatedAt, item.deletedAt)) return
		database.bookmarkDao().applyRemote(BookmarkEntity(item.itemId, accountId, bookId, chapter.id, payload.position, item.clientUpdatedAt, item.clientUpdatedAt, item.deletedAt))
	}

	private suspend fun applyNote(bookId: String, item: RemoteSyncItem) {
		val payload = runCatching { gson.fromJson(item.payload, NotePayload::class.java) }.getOrNull() ?: return
		val chapter = database.novelChapterDao().getById(payload.chapterId) ?: return
		if (chapter.bookId != bookId) return
		val current = database.annotationDao().get(accountId, item.itemId)
		if (!shouldApply(current?.updatedAt, current?.deletedAt, item.clientUpdatedAt, item.deletedAt)) return
		database.annotationDao().applyRemote(AnnotationEntity(item.itemId, accountId, bookId, chapter.id, payload.startOffset, payload.endOffset, payload.selectedText.orEmpty(), payload.note, item.clientUpdatedAt, item.clientUpdatedAt, item.deletedAt))
	}

	override suspend fun pendingChanges(): List<LocalSyncChange> = database.syncDao().pending(accountId).map {
		LocalSyncChange(it.itemId, it.itemType, it.bookId, it.remoteBookId, it.payload, it.clientUpdatedAt, it.deletedAt, it.attempts)
	}

	override suspend fun enqueue(change: LocalSyncChange) {
		database.syncDao().enqueue(NovelSyncOutboxEntity("${change.itemType}:${change.itemId}", accountId, change.itemType, change.itemId, change.localBookId, change.remoteBookId, change.payload, change.clientUpdatedAt, change.deletedAt, attempts = change.attempts))
	}

	override suspend fun markPushed(change: LocalSyncChange, remote: RemoteSyncItem) = database.withTransaction {
		applyAuthoritative(remote)
		database.syncDao().delete(accountId, "${change.itemType}:${change.itemId}")
	}
	override suspend fun markFailed(change: LocalSyncChange) = database.syncDao().incrementAttempts(accountId, "${change.itemType}:${change.itemId}")

	private data class BookmarkPayload(val chapterId: String = "", val position: Int = 0)
	private data class NotePayload(val chapterId: String = "", val startOffset: Int = 0, val endOffset: Int = 0, val selectedText: String? = null, val note: String? = null)

	private suspend fun applyAuthoritative(item: RemoteSyncItem) {
		val book = requireNotNull(database.novelBookDao().getByRemoteId(accountId, "private", item.bookId)) { "Authoritative sync book does not belong to the active account" }
		when (item.itemType) {
			"progress" -> database.syncDao().upsertProgress(NovelProgressEntity(item.itemId, accountId, book.id, item.payload, item.clientUpdatedAt, item.deletedAt))
			"bookmark" -> applyAuthoritativeBookmark(book.id, item)
			"note" -> applyAuthoritativeNote(book.id, item)
		}
	}

	private suspend fun applyAuthoritativeBookmark(bookId: String, item: RemoteSyncItem) {
		val payload = requireNotNull(runCatching { gson.fromJson(item.payload, BookmarkPayload::class.java) }.getOrNull()) { "Invalid authoritative bookmark payload" }
		val chapter = requireNotNull(database.novelChapterDao().getById(payload.chapterId)) { "Authoritative bookmark chapter does not exist" }
		require(chapter.bookId == bookId) { "Authoritative bookmark chapter does not belong to the book" }
		val createdAt = database.bookmarkDao().get(accountId, item.itemId)?.createdAt ?: item.clientUpdatedAt
		database.bookmarkDao().applyRemote(BookmarkEntity(item.itemId, accountId, bookId, chapter.id, payload.position, createdAt, item.clientUpdatedAt, item.deletedAt))
	}

	private suspend fun applyAuthoritativeNote(bookId: String, item: RemoteSyncItem) {
		val payload = requireNotNull(runCatching { gson.fromJson(item.payload, NotePayload::class.java) }.getOrNull()) { "Invalid authoritative note payload" }
		val chapter = requireNotNull(database.novelChapterDao().getById(payload.chapterId)) { "Authoritative note chapter does not exist" }
		require(chapter.bookId == bookId) { "Authoritative note chapter does not belong to the book" }
		val createdAt = database.annotationDao().get(accountId, item.itemId)?.createdAt ?: item.clientUpdatedAt
		database.annotationDao().applyRemote(AnnotationEntity(item.itemId, accountId, bookId, chapter.id, payload.startOffset, payload.endOffset, payload.selectedText.orEmpty(), payload.note, createdAt, item.clientUpdatedAt, item.deletedAt))
	}

	private fun shouldApply(localUpdatedAt: Long?, localDeletedAt: Long?, remoteUpdatedAt: Long, remoteDeletedAt: Long?): Boolean {
		if (localUpdatedAt == null) return true
		if (localDeletedAt != null && remoteDeletedAt == null) return false
		return remoteUpdatedAt > localUpdatedAt || (remoteUpdatedAt == localUpdatedAt && remoteDeletedAt != null)
	}
}
