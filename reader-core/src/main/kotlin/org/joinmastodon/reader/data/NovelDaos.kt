package org.joinmastodon.reader.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface NovelBookDao {
	@Upsert
	suspend fun upsert(book: NovelBookEntity)

	@Query("SELECT * FROM novel_books WHERE id = :id")
	suspend fun getById(id: String): NovelBookEntity?

	@Query("DELETE FROM novel_books WHERE id = :id")
	suspend fun deleteById(id: String)

	@Query("SELECT COUNT(*) FROM novel_books")
	suspend fun count(): Int
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

	@Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt")
	suspend fun getByBookId(bookId: String): List<BookmarkEntity>

	@Query("DELETE FROM bookmarks WHERE id = :id")
	suspend fun deleteById(id: String)
}

@Dao
interface AnnotationDao {
	@Upsert
	suspend fun upsert(annotation: AnnotationEntity)

	@Query("SELECT * FROM annotations WHERE bookId = :bookId ORDER BY createdAt")
	suspend fun getByBookId(bookId: String): List<AnnotationEntity>

	@Query("DELETE FROM annotations WHERE id = :id")
	suspend fun deleteById(id: String)
}
