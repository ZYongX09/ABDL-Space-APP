package org.joinmastodon.reader.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface NovelAuthorDraftDao {
	@Query("SELECT * FROM novel_author_revision_drafts WHERE accountId = :accountId AND chapterId = :chapterId")
	suspend fun getDraft(accountId: String, chapterId: String): NovelAuthorRevisionDraftEntity?

	@Query("SELECT * FROM novel_author_revision_drafts WHERE accountId = :accountId AND chapterId = :chapterId")
	fun observeDraft(accountId: String, chapterId: String): Flow<NovelAuthorRevisionDraftEntity?>

	@Query("SELECT * FROM novel_author_revision_outbox WHERE accountId = :accountId AND state = 'pending' ORDER BY updatedAt")
	suspend fun pending(accountId: String): List<NovelAuthorRevisionOutboxEntity>

	@Query("SELECT * FROM novel_author_revision_conflicts WHERE accountId = :accountId AND chapterId = :chapterId AND resolvedAt IS NULL LIMIT 1")
	suspend fun conflict(accountId: String, chapterId: String): NovelAuthorRevisionConflictEntity?

	@Upsert suspend fun upsertDraft(draft: NovelAuthorRevisionDraftEntity)
	@Upsert suspend fun upsertOutbox(outbox: NovelAuthorRevisionOutboxEntity)
	@Upsert suspend fun upsertConflict(conflict: NovelAuthorRevisionConflictEntity)

	@Query("DELETE FROM novel_author_revision_outbox WHERE accountId = :accountId AND localDraftId = :localDraftId")
	suspend fun deleteOutbox(accountId: String, localDraftId: String)

	@Query("DELETE FROM novel_author_revision_conflicts WHERE accountId = :accountId AND conflictId = :conflictId")
	suspend fun deleteConflict(accountId: String, conflictId: String)

	@Query("UPDATE novel_author_revision_drafts SET baseVersion = :serverVersion, lastSyncedAt = :syncedAt, dirty = CASE WHEN localVersion = :sentLocalVersion THEN 0 ELSE 1 END, syncState = CASE WHEN localVersion = :sentLocalVersion THEN 'clean' ELSE 'pending' END WHERE accountId = :accountId AND localId = :localDraftId")
	suspend fun acknowledgeDraft(accountId: String, localDraftId: String, sentLocalVersion: Long, serverVersion: Long, syncedAt: Long): Int

	@Query("UPDATE novel_author_revision_outbox SET baseVersion = :serverVersion WHERE accountId = :accountId AND localDraftId = :localDraftId AND localVersion > :sentLocalVersion")
	suspend fun advanceNewerOutbox(accountId: String, localDraftId: String, sentLocalVersion: Long, serverVersion: Long)

	@Query("DELETE FROM novel_author_revision_outbox WHERE accountId = :accountId AND localDraftId = :localDraftId AND localVersion = :sentLocalVersion")
	suspend fun deleteAcknowledgedOutbox(accountId: String, localDraftId: String, sentLocalVersion: Long)

	@Query("UPDATE novel_author_revision_drafts SET syncState = 'conflict', dirty = 1 WHERE accountId = :accountId AND localId = :localDraftId AND localVersion = :expectedLocalVersion")
	suspend fun markConflict(accountId: String, localDraftId: String, expectedLocalVersion: Long): Int

	@Query("UPDATE novel_author_revision_outbox SET state = 'blocked_conflict' WHERE accountId = :accountId AND identity = :identity AND localVersion = :expectedLocalVersion")
	suspend fun blockOutbox(accountId: String, identity: String, expectedLocalVersion: Long): Int

	@Query("UPDATE novel_author_revision_drafts SET content = :serverContent, baseVersion = :serverVersion, localVersion = localVersion + 1, dirty = 0, syncState = 'clean', updatedAt = :resolvedAt, lastSyncedAt = :resolvedAt WHERE accountId = :accountId AND localId = :localDraftId")
	suspend fun useServer(accountId: String, localDraftId: String, serverContent: String, serverVersion: Long, resolvedAt: Long): Int

	@Transaction
	suspend fun saveLocalDraft(draft: NovelAuthorRevisionDraftEntity, outbox: NovelAuthorRevisionOutboxEntity) {
		require(draft.accountId == outbox.accountId && draft.localId == outbox.localDraftId)
		require(draft.workId == outbox.workId && draft.chapterId == outbox.chapterId)
		require(draft.baseVersion == outbox.baseVersion && draft.localVersion == outbox.localVersion && draft.content == outbox.content)
		upsertDraft(draft)
		upsertOutbox(outbox)
	}

	@Transaction
	suspend fun acknowledgePush(accountId: String, localDraftId: String, sentLocalVersion: Long, serverVersion: Long, syncedAt: Long) {
		check(acknowledgeDraft(accountId, localDraftId, sentLocalVersion, serverVersion, syncedAt) == 1)
		deleteAcknowledgedOutbox(accountId, localDraftId, sentLocalVersion)
		advanceNewerOutbox(accountId, localDraftId, sentLocalVersion, serverVersion)
	}

	@Transaction
	suspend fun recordConflict(conflict: NovelAuthorRevisionConflictEntity, outboxIdentity: String, expectedLocalVersion: Long) {
		check(markConflict(conflict.accountId, conflict.localDraftId, expectedLocalVersion) == 1)
		check(blockOutbox(conflict.accountId, outboxIdentity, expectedLocalVersion) == 1)
		upsertConflict(conflict)
	}

	@Transaction
	suspend fun resolveUsingServer(accountId: String, localDraftId: String, conflictId: String, serverContent: String, serverVersion: Long, resolvedAt: Long) {
		check(useServer(accountId, localDraftId, serverContent, serverVersion, resolvedAt) == 1)
		deleteOutbox(accountId, localDraftId)
		deleteConflict(accountId, conflictId)
	}
}
