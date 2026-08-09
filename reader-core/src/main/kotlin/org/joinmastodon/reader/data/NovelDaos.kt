package org.joinmastodon.reader.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NovelBookDao {
	@Upsert
	suspend fun upsert(book: NovelBookEntity)

	@Query("SELECT * FROM novel_books WHERE accountId = :accountId AND id = :id")
	suspend fun getById(accountId: String, id: String): NovelBookEntity?

	@Query("SELECT * FROM novel_books WHERE accountId = :accountId AND sourceType = :sourceType AND remoteId = :remoteId")
	suspend fun getByRemoteId(accountId: String, sourceType: String, remoteId: String): NovelBookEntity?

	@Query("SELECT * FROM novel_books WHERE accountId = :accountId AND deletedAt IS NULL ORDER BY updatedAt DESC")
	suspend fun getActive(accountId: String): List<NovelBookEntity>

	@Query("SELECT * FROM novel_books WHERE accountId = :accountId AND deletedAt IS NULL ORDER BY updatedAt DESC")
	fun observeActive(accountId: String): Flow<List<NovelBookEntity>>

	@Query("DELETE FROM novel_books WHERE accountId = :accountId AND id = :id")
	suspend fun deleteById(accountId: String, id: String)

	@Query("SELECT COUNT(*) FROM novel_books WHERE accountId = :accountId")
	suspend fun count(accountId: String): Int

	@Query("UPDATE novel_books SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE accountId = :accountId AND sourceType = 'private' AND remoteId IS NOT NULL AND remoteId NOT IN (:remoteIds) AND deletedAt IS NULL")
	suspend fun markMissingPrivateRemoteDeleted(accountId: String, remoteIds: List<String>, deletedAt: Long)

	@Query("UPDATE novel_books SET deletedAt = :deletedAt, updatedAt = :deletedAt WHERE accountId = :accountId AND sourceType = 'private' AND remoteId IS NOT NULL AND deletedAt IS NULL")
	suspend fun markAllPrivateRemoteDeleted(accountId: String, deletedAt: Long)
}

@Dao
interface NovelChapterDao {
	@Upsert
	suspend fun upsert(chapters: List<NovelChapterEntity>)

	@Query("SELECT * FROM novel_chapters WHERE bookId = :bookId AND deletedAt IS NULL ORDER BY chapterIndex")
	suspend fun getByBookId(bookId: String): List<NovelChapterEntity>

	@Query("SELECT * FROM novel_chapters WHERE id = :id")
	suspend fun getById(id: String): NovelChapterEntity?

	@Query("DELETE FROM novel_chapters WHERE bookId = :bookId")
	suspend fun deleteByBookId(bookId: String)
}

@Dao
interface NovelTransferDao {
	@Upsert
	suspend fun upsert(transfer: NovelTransferEntity)

	@Query("SELECT * FROM novel_transfers WHERE transferId = :transferId")
	suspend fun get(transferId: String): NovelTransferEntity?

	@Query("SELECT * FROM novel_transfers WHERE direction = :direction AND remoteBookId = :remoteBookId LIMIT 1")
	suspend fun getByRemoteBook(direction: String, remoteBookId: String): NovelTransferEntity?

	@Query("SELECT * FROM novel_transfers ORDER BY updatedAt")
	suspend fun list(): List<NovelTransferEntity>

	@Query("DELETE FROM novel_transfers WHERE transferId = :transferId")
	suspend fun delete(transferId: String)

	@Query("UPDATE novel_transfers SET claimOwner = :owner, claimExpiresAt = :expiresAt WHERE transferId = :transferId AND (claimOwner IS NULL OR claimOwner = :owner OR claimExpiresAt <= :now)")
	suspend fun claim(transferId: String, owner: String, now: Long, expiresAt: Long): Int

	@Query("UPDATE novel_transfers SET claimExpiresAt = :expiresAt WHERE transferId = :transferId AND claimOwner = :owner")
	suspend fun renewClaim(transferId: String, owner: String, expiresAt: Long): Int

	@Query("UPDATE novel_transfers SET uploadId = :uploadId, remoteBookId = :uploadId, phase = :phase, updatedAt = :updatedAt WHERE transferId = :transferId")
	suspend fun updateUploadProgress(transferId: String, uploadId: String, phase: String, updatedAt: Long): Int

	@Query("UPDATE novel_transfers SET phase = :phase, updatedAt = :updatedAt WHERE transferId = :transferId")
	suspend fun updatePhase(transferId: String, phase: String, updatedAt: Long): Int

	@Query("UPDATE novel_transfers SET claimOwner = NULL, claimExpiresAt = NULL WHERE transferId = :transferId AND claimOwner = :owner")
	suspend fun release(transferId: String, owner: String): Int
}

@Dao
interface NovelImportDao {
	@Query("SELECT * FROM novel_books WHERE accountId = :accountId AND sourceType = :sourceType AND remoteId = :remoteId")
	suspend fun findRemoteBook(accountId: String, sourceType: String, remoteId: String): NovelBookEntity?

	@Query("SELECT * FROM novel_chapters WHERE bookId = :bookId ORDER BY chapterIndex")
	suspend fun findChapters(bookId: String): List<NovelChapterEntity>

	@Upsert
	suspend fun upsertBook(book: NovelBookEntity)

	@Query("SELECT COUNT(*) FROM bookmarks WHERE chapterId = :chapterId")
	suspend fun bookmarkCount(chapterId: String): Int

	@Query("SELECT COUNT(*) FROM annotations WHERE chapterId = :chapterId")
	suspend fun annotationCount(chapterId: String): Int

	@Query("DELETE FROM novel_chapters WHERE id = :chapterId")
	suspend fun deleteChapter(chapterId: String)

	@Query("UPDATE novel_chapters SET deletedAt = :deletedAt WHERE id = :chapterId")
	suspend fun hideChapter(chapterId: String, deletedAt: Long)

	@Upsert
	suspend fun upsertChapters(chapters: List<NovelChapterEntity>)

	@Transaction
	suspend fun importBook(book: NovelBookEntity, chapters: List<NovelChapterEntity>): String {
		val existingBook = book.remoteId?.let { findRemoteBook(book.accountId, book.sourceType, it) }
		val stableBook = book.copy(id = existingBook?.id ?: book.id)
		val existingChapters = findChapters(stableBook.id)
		val existingByContent = existingChapters
			.filter { it.deletedAt == null }
			.groupBy { it.title to it.content }
		val incomingCounts = chapters.groupingBy { it.title to it.content }.eachCount()
		val stableChapters = chapters.map { chapter ->
			val key = chapter.title to chapter.content
			val existing = existingByContent[key]?.singleOrNull()?.takeIf { incomingCounts[key] == 1 }
			chapter.copy(
				id = existing?.id ?: chapter.id,
				bookId = stableBook.id,
				deletedAt = null,
			)
		}
		upsertBook(stableBook)
		upsertChapters(stableChapters)
		val retainedIds = stableChapters.mapTo(HashSet()) { it.id }
		val deletedAt = System.currentTimeMillis()
		existingChapters.filter { it.deletedAt == null && it.id !in retainedIds }.forEach { chapter ->
			if (bookmarkCount(chapter.id) == 0 && annotationCount(chapter.id) == 0) deleteChapter(chapter.id)
			else hideChapter(chapter.id, deletedAt)
		}
		return stableBook.id
	}
}

@Dao
interface BookmarkDao {
	@Upsert
	suspend fun applyRemote(bookmark: BookmarkEntity)

	@Upsert
	suspend fun enqueueOutbox(change: NovelSyncOutboxEntity)

	@Transaction
	suspend fun saveWithOutbox(bookmark: BookmarkEntity, change: NovelSyncOutboxEntity) {
		require(change.accountId == bookmark.accountId)
		require(change.itemType == "bookmark" && change.itemId == bookmark.id && change.bookId == bookmark.bookId)
		applyRemote(bookmark)
		enqueueOutbox(change)
	}

	@Query("SELECT * FROM bookmarks WHERE accountId = :accountId AND bookId = :bookId AND deletedAt IS NULL ORDER BY createdAt")
	suspend fun getByBookId(accountId: String, bookId: String): List<BookmarkEntity>

	@Query("SELECT * FROM bookmarks WHERE accountId = :accountId AND id = :id")
	suspend fun get(accountId: String, id: String): BookmarkEntity?

}

@Dao
interface AnnotationDao {
	@Upsert
	suspend fun applyRemote(annotation: AnnotationEntity)

	@Upsert
	suspend fun enqueueOutbox(change: NovelSyncOutboxEntity)

	@Transaction
	suspend fun saveWithOutbox(annotation: AnnotationEntity, change: NovelSyncOutboxEntity) {
		require(change.accountId == annotation.accountId)
		require(change.itemType == "note" && change.itemId == annotation.id && change.bookId == annotation.bookId)
		applyRemote(annotation)
		enqueueOutbox(change)
	}

	@Query("SELECT * FROM annotations WHERE accountId = :accountId AND bookId = :bookId AND deletedAt IS NULL ORDER BY createdAt")
	suspend fun getByBookId(accountId: String, bookId: String): List<AnnotationEntity>

	@Query("SELECT * FROM annotations WHERE accountId = :accountId AND id = :id")
	suspend fun get(accountId: String, id: String): AnnotationEntity?

}

@Dao
interface NovelSyncDao {
	@Query("SELECT cursor FROM novel_sync_checkpoint WHERE accountId = :accountId")
	suspend fun checkpoint(accountId: String): String?

	@Upsert suspend fun setCheckpoint(checkpoint: NovelSyncCheckpointEntity)

	@Query("SELECT * FROM novel_sync_outbox WHERE accountId = :accountId AND state = 'pending' ORDER BY clientUpdatedAt")
	suspend fun pending(accountId: String): List<NovelSyncOutboxEntity>

	@Upsert suspend fun enqueue(change: NovelSyncOutboxEntity)

	@Query("DELETE FROM novel_sync_outbox WHERE accountId = :accountId AND identity = :identity")
	suspend fun delete(accountId: String, identity: String)

	@Query("UPDATE novel_sync_outbox SET attempts = attempts + 1 WHERE accountId = :accountId AND identity = :identity")
	suspend fun incrementAttempts(accountId: String, identity: String)

	@Upsert suspend fun upsertProgress(progress: NovelProgressEntity)

	@Query("SELECT * FROM novel_progress WHERE accountId = :accountId AND itemId = :itemId")
	suspend fun progress(accountId: String, itemId: String): NovelProgressEntity?
}
