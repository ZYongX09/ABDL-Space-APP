package org.joinmastodon.reader.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
	tableName = "novel_author_revision_drafts",
	indices = [
		Index(value = ["accountId", "chapterId"], unique = true),
		Index(value = ["accountId", "localId"], unique = true),
		Index(value = ["accountId", "syncState"]),
	],
)
data class NovelAuthorRevisionDraftEntity(
	@PrimaryKey val localId: String,
	val accountId: String,
	val workId: String,
	val volumeId: String,
	val chapterId: String,
	val remoteRevisionId: String?,
	val baseVersion: Long,
	val localVersion: Long,
	val content: String,
	val revisionState: String,
	val syncState: String,
	val dirty: Boolean,
	val createdAt: Long,
	val updatedAt: Long,
	val lastSyncedAt: Long?,
)

@Entity(
	tableName = "novel_author_revision_outbox",
	foreignKeys = [ForeignKey(
		entity = NovelAuthorRevisionDraftEntity::class,
		parentColumns = ["accountId", "localId"],
		childColumns = ["accountId", "localDraftId"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [Index(value = ["accountId", "localDraftId", "operation"], unique = true), Index(value = ["accountId", "state"])],
)
data class NovelAuthorRevisionOutboxEntity(
	@PrimaryKey val identity: String,
	val accountId: String,
	val localDraftId: String,
	val workId: String,
	val chapterId: String,
	val remoteRevisionId: String?,
	val operation: String,
	val idempotencyKey: String,
	val baseVersion: Long,
	val localVersion: Long,
	val content: String,
	val state: String,
	val attempts: Int,
	val createdAt: Long,
	val updatedAt: Long,
)

@Entity(
	tableName = "novel_author_revision_conflicts",
	foreignKeys = [ForeignKey(
		entity = NovelAuthorRevisionDraftEntity::class,
		parentColumns = ["accountId", "localId"],
		childColumns = ["accountId", "localDraftId"],
		onDelete = ForeignKey.CASCADE,
	)],
	indices = [Index(value = ["accountId", "chapterId", "resolvedAt"]), Index(value = ["accountId", "localDraftId"], unique = true)],
)
data class NovelAuthorRevisionConflictEntity(
	@PrimaryKey val conflictId: String,
	val accountId: String,
	val localDraftId: String,
	val chapterId: String,
	val localBaseVersion: Long,
	val localVersion: Long,
	val localContent: String,
	val serverVersion: Long,
	val serverContent: String,
	val detectedAt: Long,
	val resolvedAt: Long?,
	val resolution: String?,
)
