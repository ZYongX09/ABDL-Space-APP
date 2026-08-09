package org.joinmastodon.reader.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

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

	@Query("DELETE FROM novel_books WHERE accountId = :accountId AND id = :id")
	suspend fun deleteById(accountId: String, id: String)

	@Query("SELECT COUNT(*) FROM novel_books WHERE accountId = :accountId")
	suspend fun count(accountId: String): Int
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
			.mapValues { (_, matches) -> ArrayDeque(matches.sortedBy { it.chapterIndex }) }
		val stableChapters = chapters.map { chapter ->
			val existing = existingByContent[chapter.title to chapter.content]?.removeFirstOrNull()
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
	suspend fun upsert(bookmark: BookmarkEntity)

	@Query("SELECT * FROM bookmarks WHERE accountId = :accountId AND bookId = :bookId AND deletedAt IS NULL ORDER BY createdAt")
	suspend fun getByBookId(accountId: String, bookId: String): List<BookmarkEntity>

	@Query("DELETE FROM bookmarks WHERE accountId = :accountId AND id = :id")
	suspend fun deleteById(accountId: String, id: String)
}

@Dao
interface AnnotationDao {
	@Upsert
	suspend fun upsert(annotation: AnnotationEntity)

	@Query("SELECT * FROM annotations WHERE accountId = :accountId AND bookId = :bookId AND deletedAt IS NULL ORDER BY createdAt")
	suspend fun getByBookId(accountId: String, bookId: String): List<AnnotationEntity>

	@Query("DELETE FROM annotations WHERE accountId = :accountId AND id = :id")
	suspend fun deleteById(accountId: String, id: String)
}
