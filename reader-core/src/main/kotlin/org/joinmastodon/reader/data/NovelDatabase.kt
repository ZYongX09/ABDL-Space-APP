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
	],
	version = 6,
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

	companion object {
		private val openDatabases = mutableMapOf<String, MutableSet<NovelDatabase>>()

		fun open(context: Context, accountId: String): NovelDatabase =
			Room.databaseBuilder(
				context.applicationContext,
				NovelDatabase::class.java,
				databaseName(accountId),
			).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build().also { database ->
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
