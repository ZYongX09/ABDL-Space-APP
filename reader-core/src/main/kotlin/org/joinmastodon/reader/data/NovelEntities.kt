package org.joinmastodon.reader.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "novel_books")
data class NovelBookEntity(
	@PrimaryKey val id: String,
	val title: String,
	val author: String? = null,
	val sourceUri: String? = null,
	val coverUri: String? = null,
	val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
	tableName = "novel_chapters",
	foreignKeys = [
		ForeignKey(
			entity = NovelBookEntity::class,
			parentColumns = ["id"],
			childColumns = ["bookId"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [Index("bookId"), Index(value = ["bookId", "chapterIndex"], unique = true)],
)
data class NovelChapterEntity(
	@PrimaryKey val id: String,
	val bookId: String,
	val title: String,
	val content: String,
	val chapterIndex: Int,
)

@Entity(
	tableName = "bookmarks",
	foreignKeys = [
		ForeignKey(
			entity = NovelBookEntity::class,
			parentColumns = ["id"],
			childColumns = ["bookId"],
			onDelete = ForeignKey.CASCADE,
		),
		ForeignKey(
			entity = NovelChapterEntity::class,
			parentColumns = ["id"],
			childColumns = ["chapterId"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [Index("bookId"), Index("chapterId")],
)
data class BookmarkEntity(
	@PrimaryKey val id: String,
	val bookId: String,
	val chapterId: String,
	val position: Int,
	val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
	tableName = "annotations",
	foreignKeys = [
		ForeignKey(
			entity = NovelBookEntity::class,
			parentColumns = ["id"],
			childColumns = ["bookId"],
			onDelete = ForeignKey.CASCADE,
		),
		ForeignKey(
			entity = NovelChapterEntity::class,
			parentColumns = ["id"],
			childColumns = ["chapterId"],
			onDelete = ForeignKey.CASCADE,
		),
	],
	indices = [Index("bookId"), Index("chapterId")],
)
data class AnnotationEntity(
	@PrimaryKey val id: String,
	val bookId: String,
	val chapterId: String,
	val startOffset: Int,
	val endOffset: Int,
	val selectedText: String,
	val note: String? = null,
	val createdAt: Long = System.currentTimeMillis(),
	val updatedAt: Long = createdAt,
)
