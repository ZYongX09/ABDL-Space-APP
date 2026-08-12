package org.joinmastodon.reader.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
	tableName = "novel_books",
	indices = [
		Index("accountId"),
		Index(value = ["accountId", "sourceType", "remoteId"], unique = true),
		Index(value = ["accountId", "deletedAt"]),
	],
)
data class NovelBookEntity(
	@PrimaryKey val id: String,
	val accountId: String,
	val title: String,
	val author: String? = null,
	val sourceUri: String? = null,
	@Deprecated("小说产品不使用封面；仅保留此列用于现有 Room schema 兼容")
	val coverUri: String? = null,
	val remoteId: String? = null,
	val sourceType: String = "local",
	val contentHash: String? = null,
	val localFilePath: String? = null,
	val downloadState: String = "pending",
	val remoteUpdatedAt: Long? = null,
	val updatedAt: Long = System.currentTimeMillis(),
	val deletedAt: Long? = null,
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
	indices = [Index("bookId"), Index(value = ["bookId", "chapterIndex"])],
)
data class NovelChapterEntity(
	@PrimaryKey val id: String,
	val bookId: String,
	val title: String,
	val content: String,
	val chapterIndex: Int,
	val deletedAt: Long? = null,
)

data class NovelChapterHeader(
	val id: String,
	val bookId: String,
	val title: String,
	val chapterIndex: Int,
	val deletedAt: Long?,
	val contentLength: Int,
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
	indices = [Index("bookId"), Index("chapterId"), Index(value = ["accountId", "bookId", "deletedAt"])],
)
data class BookmarkEntity(
	@PrimaryKey val id: String,
	val accountId: String,
	val bookId: String,
	val chapterId: String,
	val position: Int,
	val createdAt: Long = System.currentTimeMillis(),
	val updatedAt: Long = createdAt,
	val deletedAt: Long? = null,
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
	indices = [Index("bookId"), Index("chapterId"), Index(value = ["accountId", "bookId", "deletedAt"])],
)
data class AnnotationEntity(
	@PrimaryKey val id: String,
	val accountId: String,
	val bookId: String,
	val chapterId: String,
	val startOffset: Int,
	val endOffset: Int,
	val selectedText: String,
	val note: String? = null,
	val createdAt: Long = System.currentTimeMillis(),
	val updatedAt: Long = createdAt,
	val deletedAt: Long? = null,
)

@Entity(tableName = "novel_transfers")
data class NovelTransferEntity(
	@PrimaryKey val transferId: String,
	val accountId: String,
	val direction: String,
	val remoteBookId: String?,
	val uploadId: String?,
	val localTempPath: String,
	val title: String?,
	val author: String?,
	val format: String,
	val mimeType: String,
	val phase: String,
	val contentHash: String,
	val contentMd5: String?,
	val size: Long,
	val claimOwner: String? = null,
	val claimExpiresAt: Long? = null,
	val updatedAt: Long = System.currentTimeMillis(),
) {
	companion object {
		const val UPLOAD = "UPLOAD"
		const val DOWNLOAD = "DOWNLOAD"
		const val PREPARED = "PREPARED"
		const val PUT_PENDING = "PUT_PENDING"
		const val COMPLETE_PENDING = "COMPLETE_PENDING"
		const val COMPLETE = "COMPLETE"
		const val FAILED = "FAILED"
		const val CANDIDATE_READY = "CANDIDATE_READY"
		const val FILE_SWITCHING = "FILE_SWITCHING"
		const val DATABASE_COMMITTED = "DATABASE_COMMITTED"
	}
}

@Entity(tableName = "novel_sync_checkpoint")
data class NovelSyncCheckpointEntity(
	@PrimaryKey val accountId: String,
	val cursor: String?,
	val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
	tableName = "novel_sync_outbox",
	indices = [Index(value = ["accountId", "itemType", "itemId"], unique = true), Index(value = ["accountId", "state"])],
)
data class NovelSyncOutboxEntity(
	@PrimaryKey val identity: String,
	val accountId: String,
	val itemType: String,
	val itemId: String,
	val bookId: String,
	val remoteBookId: String,
	val payload: String,
	val clientUpdatedAt: Long,
	val deletedAt: Long?,
	val state: String = "pending",
	val attempts: Int = 0,
)

@Entity(
	tableName = "novel_progress",
	indices = [Index(value = ["accountId", "bookId"], unique = true)],
)
data class NovelProgressEntity(
	@PrimaryKey val itemId: String,
	val accountId: String,
	val bookId: String,
	val payload: String,
	val updatedAt: Long,
	val deletedAt: Long?,
)
