package org.joinmastodon.reader.data

import androidx.room.Dao
import androidx.room.Query
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

	@Query("SELECT * FROM novel_chapters WHERE bookId = :bookId ORDER BY chapterIndex")
	suspend fun getByBookId(bookId: String): List<NovelChapterEntity>

	@Query("DELETE FROM novel_chapters WHERE bookId = :bookId")
	suspend fun deleteByBookId(bookId: String)
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
