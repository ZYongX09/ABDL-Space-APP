package org.joinmastodon.reader.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap

@Database(
	entities = [
		NovelBookEntity::class,
		NovelChapterEntity::class,
		BookmarkEntity::class,
		AnnotationEntity::class,
		NovelTransferEntity::class,
		NovelSyncCheckpointEntity::class,
		NovelSyncOutboxEntity::class,
		NovelProgressEntity::class,
		NovelAuthorRevisionDraftEntity::class,
		NovelAuthorRevisionOutboxEntity::class,
		NovelAuthorRevisionConflictEntity::class,
	],
	version = 7,
	exportSchema = true,
)
abstract class NovelDatabase : RoomDatabase() {
	abstract fun novelBookDao(): NovelBookDao
	abstract fun novelChapterDao(): NovelChapterDao
	abstract fun novelImportDao(): NovelImportDao
	abstract fun bookmarkDao(): BookmarkDao
	abstract fun annotationDao(): AnnotationDao
	abstract fun transferDao(): NovelTransferDao
	abstract fun syncDao(): NovelSyncDao
	abstract fun authorDraftDao(): NovelAuthorDraftDao

	companion object {
		private val openDatabases = mutableMapOf<String, MutableSet<NovelDatabase>>()

		fun open(context: Context, accountId: String): NovelDatabase =
			Room.databaseBuilder(
				context.applicationContext,
				NovelDatabase::class.java,
				databaseName(accountId),
			).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build().also { database ->
				synchronized(openDatabases) {
					openDatabases.getOrPut(accountId) { Collections.newSetFromMap(WeakHashMap()) }.add(database)
				}
			}

		@JvmStatic
		fun closeAccount(accountId: String) {
			val databases = synchronized(openDatabases) { openDatabases.remove(accountId)?.toList().orEmpty() }
			databases.forEach { runCatching { it.close() } }
		}

		val MIGRATION_1_2 = object : Migration(1, 2) {
			override fun migrate(database: SupportSQLiteDatabase) {
				database.execSQL("ALTER TABLE novel_chapters ADD COLUMN deletedAt INTEGER")
				database.execSQL("DROP INDEX index_novel_chapters_bookId_chapterIndex")
				database.execSQL("CREATE INDEX IF NOT EXISTS index_novel_chapters_bookId_chapterIndex ON novel_chapters(bookId, chapterIndex)")
			}
		}

		val MIGRATION_2_3 = object : Migration(2, 3) {
			override fun migrate(db: SupportSQLiteDatabase) {
				db.execSQL("CREATE TABLE IF NOT EXISTS novel_transfers (transferId TEXT NOT NULL, accountId TEXT NOT NULL, direction TEXT NOT NULL, remoteBookId TEXT, uploadId TEXT, localTempPath TEXT NOT NULL, title TEXT, author TEXT, format TEXT NOT NULL, mimeType TEXT NOT NULL, phase TEXT NOT NULL, contentHash TEXT NOT NULL, contentMd5 TEXT, size INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(transferId))")
			}
		}

		val MIGRATION_3_4 = object : Migration(3, 4) {
			override fun migrate(db: SupportSQLiteDatabase) {
				db.execSQL("ALTER TABLE novel_transfers ADD COLUMN claimOwner TEXT")
			}
		}

		val MIGRATION_4_5 = object : Migration(4, 5) {
			override fun migrate(db: SupportSQLiteDatabase) {
				db.execSQL("ALTER TABLE novel_transfers ADD COLUMN claimExpiresAt INTEGER")
				db.execSQL("UPDATE novel_transfers SET claimExpiresAt = 0 WHERE claimOwner IS NOT NULL")
			}
		}

		val MIGRATION_5_6 = object : Migration(5, 6) {
			override fun migrate(db: SupportSQLiteDatabase) {
				db.execSQL("CREATE TABLE IF NOT EXISTS novel_sync_checkpoint (accountId TEXT NOT NULL, cursor TEXT, updatedAt INTEGER NOT NULL, PRIMARY KEY(accountId))")
				db.execSQL("CREATE TABLE IF NOT EXISTS novel_sync_outbox (identity TEXT NOT NULL, accountId TEXT NOT NULL, itemType TEXT NOT NULL, itemId TEXT NOT NULL, bookId TEXT NOT NULL, remoteBookId TEXT NOT NULL, payload TEXT NOT NULL, clientUpdatedAt INTEGER NOT NULL, deletedAt INTEGER, state TEXT NOT NULL, attempts INTEGER NOT NULL, PRIMARY KEY(identity))")
				db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_novel_sync_outbox_accountId_itemType_itemId ON novel_sync_outbox(accountId, itemType, itemId)")
				db.execSQL("CREATE INDEX IF NOT EXISTS index_novel_sync_outbox_accountId_state ON novel_sync_outbox(accountId, state)")
				db.execSQL("CREATE TABLE IF NOT EXISTS novel_progress (itemId TEXT NOT NULL, accountId TEXT NOT NULL, bookId TEXT NOT NULL, payload TEXT NOT NULL, updatedAt INTEGER NOT NULL, deletedAt INTEGER, PRIMARY KEY(itemId))")
				db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_novel_progress_accountId_bookId ON novel_progress(accountId, bookId)")
			}
		}

		val MIGRATION_6_7 = object : Migration(6, 7) {
			override fun migrate(db: SupportSQLiteDatabase) {
				db.execSQL("CREATE TABLE IF NOT EXISTS novel_author_revision_drafts (localId TEXT NOT NULL, accountId TEXT NOT NULL, workId TEXT NOT NULL, volumeId TEXT NOT NULL, chapterId TEXT NOT NULL, remoteRevisionId TEXT, baseVersion INTEGER NOT NULL, localVersion INTEGER NOT NULL, content TEXT NOT NULL, revisionState TEXT NOT NULL, syncState TEXT NOT NULL, dirty INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, lastSyncedAt INTEGER, PRIMARY KEY(localId))")
				db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_novel_author_revision_drafts_accountId_chapterId ON novel_author_revision_drafts(accountId, chapterId)")
				db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_novel_author_revision_drafts_accountId_localId ON novel_author_revision_drafts(accountId, localId)")
				db.execSQL("CREATE INDEX IF NOT EXISTS index_novel_author_revision_drafts_accountId_syncState ON novel_author_revision_drafts(accountId, syncState)")
				db.execSQL("CREATE TABLE IF NOT EXISTS novel_author_revision_outbox (identity TEXT NOT NULL, accountId TEXT NOT NULL, localDraftId TEXT NOT NULL, workId TEXT NOT NULL, chapterId TEXT NOT NULL, remoteRevisionId TEXT, operation TEXT NOT NULL, idempotencyKey TEXT NOT NULL, baseVersion INTEGER NOT NULL, localVersion INTEGER NOT NULL, content TEXT NOT NULL, state TEXT NOT NULL, attempts INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(identity), FOREIGN KEY(accountId, localDraftId) REFERENCES novel_author_revision_drafts(accountId, localId) ON UPDATE NO ACTION ON DELETE CASCADE)")
				db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_novel_author_revision_outbox_accountId_localDraftId_operation ON novel_author_revision_outbox(accountId, localDraftId, operation)")
				db.execSQL("CREATE INDEX IF NOT EXISTS index_novel_author_revision_outbox_accountId_state ON novel_author_revision_outbox(accountId, state)")
				db.execSQL("CREATE TABLE IF NOT EXISTS novel_author_revision_conflicts (conflictId TEXT NOT NULL, accountId TEXT NOT NULL, localDraftId TEXT NOT NULL, chapterId TEXT NOT NULL, localBaseVersion INTEGER NOT NULL, localVersion INTEGER NOT NULL, localContent TEXT NOT NULL, serverVersion INTEGER NOT NULL, serverContent TEXT NOT NULL, detectedAt INTEGER NOT NULL, resolvedAt INTEGER, resolution TEXT, PRIMARY KEY(conflictId), FOREIGN KEY(accountId, localDraftId) REFERENCES novel_author_revision_drafts(accountId, localId) ON UPDATE NO ACTION ON DELETE CASCADE)")
				db.execSQL("CREATE INDEX IF NOT EXISTS index_novel_author_revision_conflicts_accountId_chapterId_resolvedAt ON novel_author_revision_conflicts(accountId, chapterId, resolvedAt)")
				db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_novel_author_revision_conflicts_accountId_localDraftId ON novel_author_revision_conflicts(accountId, localDraftId)")
			}
		}

		fun databaseName(accountId: String): String {
			val digest = MessageDigest.getInstance("SHA-256")
				.digest(accountId.toByteArray(Charsets.UTF_8))
			val hash = buildString(digest.size * 2) {
				digest.forEach { byte -> append("%02x".format(byte.toInt() and 0xff)) }
			}
			return "novels_$hash.db"
		}
	}
}
