package org.joinmastodon.reader.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovelDatabaseTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()
	private val openedDatabases = mutableListOf<NovelDatabase>()
	private val accountIds = mutableSetOf<String>()

	@After
	fun tearDown() {
		openedDatabases.forEach(NovelDatabase::close)
		accountIds.forEach { context.deleteDatabase(NovelDatabase.databaseName(it)) }
	}

	@Test
	fun sameBookIdIsIsolatedByAccountAndDeletingADoesNotAffectB() = runBlocking {
		val accountA = "mastodon.example_100"
		val accountB = "mastodon.example_200"
		val databaseA = open(accountA)
		val databaseB = open(accountB)
		val sharedBookId = "book:stable-id"

		databaseA.novelBookDao().upsert(NovelBookEntity(sharedBookId, accountA, "Account A book"))
		databaseB.novelBookDao().upsert(NovelBookEntity(sharedBookId, accountB, "Account B book"))

		assertEquals("Account A book", databaseA.novelBookDao().getById(accountA, sharedBookId)?.title)
		assertEquals("Account B book", databaseB.novelBookDao().getById(accountB, sharedBookId)?.title)

		databaseA.novelBookDao().deleteById(accountA, sharedBookId)

		assertNull(databaseA.novelBookDao().getById(accountA, sharedBookId))
		assertEquals("Account B book", databaseB.novelBookDao().getById(accountB, sharedBookId)?.title)
	}

	@Test
	fun bookUsesStableStringPrimaryKey() = runBlocking {
		val database = open("mastodon.example_300")
		val stableId = "https://books.example/novel/42"

		val accountId = "mastodon.example_300"
		database.novelBookDao().upsert(NovelBookEntity(stableId, accountId, "First title"))
		database.novelBookDao().upsert(NovelBookEntity(stableId, accountId, "Updated title"))

		assertEquals(1, database.novelBookDao().count(accountId))
		assertEquals(stableId, database.novelBookDao().getById(accountId, stableId)?.id)
		assertEquals("Updated title", database.novelBookDao().getById(accountId, stableId)?.title)
	}

	@Test
	fun databaseNameUsesAccountHashWithoutLeakingAccountId() {
		val accountId = "social.example_123456789"
		val expectedHash = MessageDigest.getInstance("SHA-256")
			.digest(accountId.toByteArray(Charsets.UTF_8))
			.joinToString("") { "%02x".format(it) }

		val databaseName = NovelDatabase.databaseName(accountId)

		assertEquals("novels_$expectedHash.db", databaseName)
		assertFalse(databaseName.contains(accountId))
	}

	@Test
	fun syncEntitiesExposeRequiredAccountAndLifecycleFields() {
		assertFields(
			NovelBookEntity::class.java,
			"accountId",
			"remoteId",
			"sourceType",
			"contentHash",
			"localFilePath",
			"downloadState",
			"remoteUpdatedAt",
			"deletedAt",
		)
		assertFields(BookmarkEntity::class.java, "accountId", "updatedAt", "deletedAt")
		assertFields(AnnotationEntity::class.java, "accountId", "updatedAt", "deletedAt")
		assertFields(NovelChapterEntity::class.java, "deletedAt")
		assertFields(
			NovelTransferEntity::class.java,
			"transferId",
			"accountId",
			"direction",
			"remoteBookId",
			"uploadId",
			"localTempPath",
			"title",
			"author",
			"format",
			"mimeType",
			"phase",
			"contentHash",
			"contentMd5",
			"size",
			"claimOwner",
			"claimExpiresAt",
			"updatedAt",
		)
	}

	@Test
	fun versionSixExportsSchemaAndMigratesRealVersionOneData() = runBlocking {
		val schema = File("schemas/org.joinmastodon.reader.data.NovelDatabase/6.json")
		assertTrue(schema.isFile)
		assertTrue(schema.readText().contains("\"version\": 6"))
		val accountId = "migration.example_1"
		createVersionOneDatabase(accountId)

		val database = open(accountId)

		assertEquals("Migrated book", database.novelBookDao().getById(accountId, "book-1")?.title)
		assertEquals(null, database.novelChapterDao().getById("chapter-1")?.deletedAt)
		assertEquals("chapter-1", database.bookmarkDao().getByBookId(accountId, "book-1").single().chapterId)
		assertEquals("chapter-1", database.annotationDao().getByBookId(accountId, "book-1").single().chapterId)
		assertTrue(database.transferDao().list().isEmpty())
		assertNull(database.syncDao().checkpoint(accountId))
		val sqlite = database.openHelper.writableDatabase
		assertEquals(setOf("bookId", "bookId,chapterIndex"), sqlite.query("PRAGMA index_list('novel_chapters')").use { cursor ->
			buildSet {
				while (cursor.moveToNext()) {
					val indexName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
					if (indexName.startsWith("sqlite_autoindex_")) continue
					sqlite.query("PRAGMA index_info('$indexName')").use { columns ->
						val names = mutableListOf<String>()
						while (columns.moveToNext()) names += columns.getString(columns.getColumnIndexOrThrow("name"))
						add(names.joinToString(","))
					}
				}
			}
		})
		assertEquals(listOf("novel_books"), sqlite.query("PRAGMA foreign_key_list('novel_chapters')").use { cursor ->
			buildList {
				while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("table")))
			}
		})
	}

	@Test
	fun syncCheckpointAndOutboxAreAccountScoped() = runBlocking {
		val accountId = "sync.example_1"
		val database = open(accountId)
		database.syncDao().setCheckpoint(NovelSyncCheckpointEntity(accountId, "42", 1))
		database.syncDao().enqueue(NovelSyncOutboxEntity("bookmark-1", accountId, "bookmark", "bookmark-1", "local", "remote", "{}", 2, null))

		assertEquals("42", database.syncDao().checkpoint(accountId))
		assertEquals("bookmark-1", database.syncDao().pending(accountId).single().itemId)
		assertTrue(database.syncDao().pending("other").isEmpty())
	}

	@Test
	fun transferDaoPersistsAndMutatesPendingTransfer() = runBlocking {
		val database = open("transfer.example_1")
		val pending = NovelTransferEntity(
			transferId = "transfer-1",
			accountId = "transfer.example_1",
			direction = NovelTransferEntity.UPLOAD,
			remoteBookId = null,
			uploadId = "upload-1",
			localTempPath = "/tmp/book.txt",
			title = "Book",
			author = "Author",
			format = "txt",
			mimeType = "text/plain",
			phase = NovelTransferEntity.PUT_PENDING,
			contentHash = "sha256",
			contentMd5 = "md5",
			size = 42,
			updatedAt = 100,
		)

		database.transferDao().upsert(pending)
		assertEquals(pending, database.transferDao().get("transfer-1"))
		assertEquals(listOf(pending), database.transferDao().list())

		val completePending = pending.copy(phase = NovelTransferEntity.COMPLETE_PENDING, updatedAt = 200)
		database.transferDao().upsert(completePending)
		assertEquals(completePending, database.transferDao().get("transfer-1"))

		database.transferDao().delete("transfer-1")
		assertNull(database.transferDao().get("transfer-1"))
	}

	@Test
	fun transferClaimLeaseRejectsSecondOwnerAllowsReentryAndExpires() = runBlocking {
		val database = open("claim.example_1")
		val pending = NovelTransferEntity(
			transferId = "transfer-claim", accountId = "claim.example_1", direction = NovelTransferEntity.UPLOAD,
			remoteBookId = null, uploadId = null, localTempPath = "/tmp/book.txt", title = "Book", author = "Author",
			format = "txt", mimeType = "text/plain", phase = NovelTransferEntity.PREPARED, contentHash = "hash",
			contentMd5 = "md5", size = 4,
		)
		database.transferDao().upsert(pending)

		assertEquals(1, database.transferDao().claim("transfer-claim", "worker-a", 1_000, 11_000))
		assertEquals(0, database.transferDao().claim("transfer-claim", "worker-b", 2_000, 12_000))
		assertEquals(1, database.transferDao().claim("transfer-claim", "worker-a", 3_000, 13_000))
		assertEquals("worker-a", database.transferDao().get("transfer-claim")?.claimOwner)
		assertEquals(13_000L, database.transferDao().get("transfer-claim")?.claimExpiresAt)
		assertEquals(1, database.transferDao().claim("transfer-claim", "worker-b", 13_000, 23_000))
		assertEquals("worker-b", database.transferDao().get("transfer-claim")?.claimOwner)
	}

	@Test
	fun versionFourClaimWithoutExpiryBecomesImmediatelyClaimable() = runBlocking {
		val accountId = "claim.migration.example"
		createVersionFourDatabaseWithClaim(accountId)
		val database = open(accountId)

		assertEquals(1, database.transferDao().claim("transfer-v4", "worker-new", 1, 10_001))
		assertEquals("worker-new", database.transferDao().get("transfer-v4")?.claimOwner)
	}

	@Test
	fun changedChapterGetsNewIdentityAndReferencesKeepOldHiddenChapter() = runBlocking {
		val accountId = "mastodon.example_400"
		val database = open(accountId)
		val originalBook = NovelBookEntity(
			id = "parsed-book-v1",
			accountId = accountId,
			title = "Original",
			remoteId = "remote-1",
			sourceType = "private",
		)
		val originalChapter = NovelChapterEntity("parsed-chapter-v1", originalBook.id, "Chapter 1", "old", 0)
		database.novelImportDao().importBook(originalBook, listOf(originalChapter))
		database.bookmarkDao().upsert(BookmarkEntity("bookmark-1", accountId, originalBook.id, originalChapter.id, 1))
		database.annotationDao().upsert(AnnotationEntity("annotation-1", accountId, originalBook.id, originalChapter.id, 0, 3, "old"))

		val updatedBook = originalBook.copy(id = "parsed-book-v2", title = "Updated")
		val updatedChapter = NovelChapterEntity("parsed-chapter-v2", updatedBook.id, "Chapter 1", "new content", 0)
		val stableBookId = database.novelImportDao().importBook(updatedBook, listOf(updatedChapter))

		assertEquals(originalBook.id, stableBookId)
		assertEquals(1, database.novelBookDao().count(accountId))
		assertEquals("Updated", database.novelBookDao().getByRemoteId(accountId, "private", "remote-1")?.title)
		assertEquals(listOf(updatedChapter.id), database.novelChapterDao().getByBookId(originalBook.id).map { it.id })
		assertEquals("new content", database.novelChapterDao().getByBookId(originalBook.id).single().content)
		assertEquals(originalChapter.id, database.bookmarkDao().getByBookId(accountId, originalBook.id).single().chapterId)
		assertEquals(originalChapter.id, database.annotationDao().getByBookId(accountId, originalBook.id).single().chapterId)
		assertEquals("old", database.novelChapterDao().getById(originalChapter.id)?.content)
		assertTrue(database.novelChapterDao().getById(originalChapter.id)?.deletedAt != null)
	}

	@Test
	fun removedReferencedChapterIsNotPhysicallyDeleted() = runBlocking {
		val accountId = "mastodon.example_500"
		val database = open(accountId)
		val book = NovelBookEntity("book", accountId, "Book", remoteId = "remote-2", sourceType = "private")
		val kept = NovelChapterEntity("chapter-1", book.id, "One", "one", 0)
		val removedButReferenced = NovelChapterEntity("chapter-2", book.id, "Two", "two", 1)
		database.novelImportDao().importBook(book, listOf(kept, removedButReferenced))
		database.bookmarkDao().upsert(BookmarkEntity("bookmark-2", accountId, book.id, removedButReferenced.id, 0))

		database.novelImportDao().importBook(book.copy(title = "Updated"), listOf(kept.copy(content = "updated")))

		assertEquals(setOf("chapter-1"), database.novelChapterDao().getByBookId(book.id).mapTo(mutableSetOf()) { it.id })
		assertEquals("chapter-2", database.bookmarkDao().getByBookId(accountId, book.id).single().chapterId)
		assertTrue(database.novelChapterDao().getById("chapter-2")?.deletedAt != null)
	}

	@Test
	fun insertingReorderingAndDeletingNeverReassignsChapterIdentity() = runBlocking {
		val accountId = "mastodon.example_600"
		val database = open(accountId)
		val book = NovelBookEntity("book-sequence", accountId, "Book", remoteId = "remote-3", sourceType = "private")
		val a = NovelChapterEntity("a", book.id, "Same", "alpha", 0)
		val b = NovelChapterEntity("b", book.id, "Same", "beta", 1)
		val duplicateA = NovelChapterEntity("a-duplicate", book.id, "Same", "alpha", 2)
		database.novelImportDao().importBook(book, listOf(a, b, duplicateA))
		database.bookmarkDao().upsert(BookmarkEntity("bookmark-a", accountId, book.id, a.id, 0))
		database.bookmarkDao().upsert(BookmarkEntity("bookmark-a-duplicate", accountId, book.id, duplicateA.id, 0))
		database.annotationDao().upsert(AnnotationEntity("annotation-b", accountId, book.id, b.id, 0, 2, "be"))

		val inserted = NovelChapterEntity("new", book.id, "Intro", "intro", 0)
		val reorderedDuplicate = NovelChapterEntity("parsed-duplicate", book.id, "Same", "alpha", 1)
		val reorderedA = NovelChapterEntity("parsed-a", book.id, "Same", "alpha", 2)
		database.novelImportDao().importBook(book, listOf(inserted, reorderedDuplicate, reorderedA))

		val active = database.novelChapterDao().getByBookId(book.id)
		assertEquals(listOf("new", "parsed-duplicate", "parsed-a"), active.map { it.id })
		assertEquals(setOf("a", "a-duplicate"), database.bookmarkDao().getByBookId(accountId, book.id).mapTo(mutableSetOf()) { it.chapterId })
		assertEquals("beta", database.novelChapterDao().getById(database.annotationDao().getByBookId(accountId, book.id).single().chapterId)?.content)
		assertTrue(database.novelChapterDao().getById("a")?.deletedAt != null)
		assertTrue(database.novelChapterDao().getById("a-duplicate")?.deletedAt != null)
		assertTrue(database.novelChapterDao().getById("b")?.deletedAt != null)
	}

	private fun createVersionOneDatabase(accountId: String) {
		accountIds += accountId
		context.deleteDatabase(NovelDatabase.databaseName(accountId))
		val database = context.openOrCreateDatabase(NovelDatabase.databaseName(accountId), Context.MODE_PRIVATE, null)
		database.execSQL("PRAGMA foreign_keys = ON")
		database.execSQL("CREATE TABLE novel_books (id TEXT NOT NULL PRIMARY KEY, accountId TEXT NOT NULL, title TEXT NOT NULL, author TEXT, sourceUri TEXT, coverUri TEXT, remoteId TEXT, sourceType TEXT NOT NULL, contentHash TEXT, localFilePath TEXT, downloadState TEXT NOT NULL, remoteUpdatedAt INTEGER, updatedAt INTEGER NOT NULL, deletedAt INTEGER)")
		database.execSQL("CREATE INDEX index_novel_books_accountId ON novel_books(accountId)")
		database.execSQL("CREATE UNIQUE INDEX index_novel_books_accountId_sourceType_remoteId ON novel_books(accountId, sourceType, remoteId)")
		database.execSQL("CREATE INDEX index_novel_books_accountId_deletedAt ON novel_books(accountId, deletedAt)")
		database.execSQL("CREATE TABLE novel_chapters (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL, chapterIndex INTEGER NOT NULL, FOREIGN KEY(bookId) REFERENCES novel_books(id) ON DELETE CASCADE)")
		database.execSQL("CREATE INDEX index_novel_chapters_bookId ON novel_chapters(bookId)")
		database.execSQL("CREATE UNIQUE INDEX index_novel_chapters_bookId_chapterIndex ON novel_chapters(bookId, chapterIndex)")
		database.execSQL("CREATE TABLE bookmarks (id TEXT NOT NULL PRIMARY KEY, accountId TEXT NOT NULL, bookId TEXT NOT NULL, chapterId TEXT NOT NULL, position INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, deletedAt INTEGER, FOREIGN KEY(bookId) REFERENCES novel_books(id) ON DELETE CASCADE, FOREIGN KEY(chapterId) REFERENCES novel_chapters(id) ON DELETE CASCADE)")
		database.execSQL("CREATE INDEX index_bookmarks_bookId ON bookmarks(bookId)")
		database.execSQL("CREATE INDEX index_bookmarks_chapterId ON bookmarks(chapterId)")
		database.execSQL("CREATE INDEX index_bookmarks_accountId_bookId_deletedAt ON bookmarks(accountId, bookId, deletedAt)")
		database.execSQL("CREATE TABLE annotations (id TEXT NOT NULL PRIMARY KEY, accountId TEXT NOT NULL, bookId TEXT NOT NULL, chapterId TEXT NOT NULL, startOffset INTEGER NOT NULL, endOffset INTEGER NOT NULL, selectedText TEXT NOT NULL, note TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, deletedAt INTEGER, FOREIGN KEY(bookId) REFERENCES novel_books(id) ON DELETE CASCADE, FOREIGN KEY(chapterId) REFERENCES novel_chapters(id) ON DELETE CASCADE)")
		database.execSQL("CREATE INDEX index_annotations_bookId ON annotations(bookId)")
		database.execSQL("CREATE INDEX index_annotations_chapterId ON annotations(chapterId)")
		database.execSQL("CREATE INDEX index_annotations_accountId_bookId_deletedAt ON annotations(accountId, bookId, deletedAt)")
		database.execSQL("INSERT INTO novel_books VALUES ('book-1', ?, 'Migrated book', NULL, NULL, NULL, 'remote-1', 'private', NULL, NULL, 'pending', NULL, 1, NULL)", arrayOf(accountId))
		database.execSQL("INSERT INTO novel_chapters VALUES ('chapter-1', 'book-1', 'Chapter', 'Content', 0)")
		database.execSQL("INSERT INTO bookmarks VALUES ('bookmark-1', ?, 'book-1', 'chapter-1', 3, 1, 1, NULL)", arrayOf(accountId))
		database.execSQL("INSERT INTO annotations VALUES ('annotation-1', ?, 'book-1', 'chapter-1', 0, 3, 'Con', NULL, 1, 1, NULL)", arrayOf(accountId))
		database.version = 1
		database.close()
	}

	private fun createVersionFourDatabaseWithClaim(accountId: String) {
		createVersionOneDatabase(accountId)
		val database = context.openOrCreateDatabase(NovelDatabase.databaseName(accountId), Context.MODE_PRIVATE, null)
		database.execSQL("ALTER TABLE novel_chapters ADD COLUMN deletedAt INTEGER")
		database.execSQL("DROP INDEX index_novel_chapters_bookId_chapterIndex")
		database.execSQL("CREATE INDEX index_novel_chapters_bookId_chapterIndex ON novel_chapters(bookId, chapterIndex)")
		database.execSQL("CREATE TABLE novel_transfers (transferId TEXT NOT NULL, accountId TEXT NOT NULL, direction TEXT NOT NULL, remoteBookId TEXT, uploadId TEXT, localTempPath TEXT NOT NULL, title TEXT, author TEXT, format TEXT NOT NULL, mimeType TEXT NOT NULL, phase TEXT NOT NULL, contentHash TEXT NOT NULL, contentMd5 TEXT, size INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(transferId))")
		database.execSQL("ALTER TABLE novel_transfers ADD COLUMN claimOwner TEXT")
		database.execSQL("INSERT INTO novel_transfers VALUES ('transfer-v4', ?, 'UPLOAD', NULL, NULL, '/tmp/book.txt', 'Book', NULL, 'txt', 'text/plain', 'PREPARED', 'hash', 'md5', 4, 1, 'worker-old')", arrayOf(accountId))
		database.version = 4
		database.close()
	}

	private fun open(accountId: String): NovelDatabase {
		accountIds += accountId
		return NovelDatabase.open(context, accountId).also(openedDatabases::add)
	}

	private fun assertFields(entityClass: Class<*>, vararg expectedFields: String) {
		val actualFields = entityClass.declaredFields.mapTo(mutableSetOf()) { it.name }
		expectedFields.forEach { field ->
			assertTrue("${entityClass.simpleName} must define $field", field in actualFields)
		}
	}
}
