package org.joinmastodon.reader.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.security.MessageDigest

@Database(
	entities = [
		NovelBookEntity::class,
		NovelChapterEntity::class,
		BookmarkEntity::class,
		AnnotationEntity::class,
	],
	version = 2,
	exportSchema = true,
)
abstract class NovelDatabase : RoomDatabase() {
	abstract fun novelBookDao(): NovelBookDao
	abstract fun novelChapterDao(): NovelChapterDao
	abstract fun novelImportDao(): NovelImportDao
	abstract fun bookmarkDao(): BookmarkDao
	abstract fun annotationDao(): AnnotationDao

	companion object {
		fun open(context: Context, accountId: String): NovelDatabase =
			Room.databaseBuilder(
				context.applicationContext,
				NovelDatabase::class.java,
				databaseName(accountId),
			).addMigrations(MIGRATION_1_2).build()

		val MIGRATION_1_2 = object : Migration(1, 2) {
			override fun migrate(database: SupportSQLiteDatabase) {
				database.execSQL("ALTER TABLE novel_chapters ADD COLUMN deletedAt INTEGER")
				database.execSQL("DROP INDEX index_novel_chapters_bookId_chapterIndex")
				database.execSQL("CREATE INDEX IF NOT EXISTS index_novel_chapters_bookId_chapterIndex ON novel_chapters(bookId, chapterIndex)")
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
