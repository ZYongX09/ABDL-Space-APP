package org.joinmastodon.android.novel.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.joinmastodon.reader.data.NovelBookEntity
import org.joinmastodon.reader.data.NovelChapterEntity
import org.joinmastodon.reader.data.NovelDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.joinmastodon.reader.domain.ReaderPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.yield
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NovelSyncWriteFacadeTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()
	private val accountId = "facade.example_1"
	private val database = NovelDatabase.open(context, accountId)

	@After
	fun tearDown() {
		database.close()
		context.deleteDatabase(NovelDatabase.databaseName(accountId))
	}

	@Test
	fun hostFacadePersistsMutationsAndQueuesSync() = runBlocking {
		val book = NovelBookEntity("book-1", accountId, "Book", remoteId = "remote-1", sourceType = "private")
		val chapter = NovelChapterEntity("chapter-1", book.id, "Chapter", "Content", 0)
		database.novelBookDao().upsert(book)
		database.novelChapterDao().upsert(listOf(chapter))
		var syncRequests = 0
		val facade = NovelSyncWriteFacade(accountId, database, { syncRequests++ }, now = { 500 })

		facade.saveBookmark("bookmark-1", book.id, chapter.id, 12)
		facade.saveAnnotation("note-1", book.id, chapter.id, 1, 4, "ont", "note")

		assertNotNull(database.bookmarkDao().get(accountId, "bookmark-1"))
		assertNotNull(database.annotationDao().get(accountId, "note-1"))
		assertEquals(listOf("bookmark", "note"), database.syncDao().pending(accountId).map { it.itemType })
		assertEquals(2, syncRequests)
	}

	@Test
	fun hostFacadeDeletesAsTombstonesInsteadOfDroppingRows() = runBlocking {
		val book = NovelBookEntity("book-1", accountId, "Book", remoteId = "remote-1", sourceType = "private")
		val chapter = NovelChapterEntity("chapter-1", book.id, "Chapter", "Content", 0)
		database.novelBookDao().upsert(book)
		database.novelChapterDao().upsert(listOf(chapter))
		var timestamp = 100L
		val facade = NovelSyncWriteFacade(accountId, database, {}, now = { timestamp })
		facade.saveBookmark("bookmark-1", book.id, chapter.id, 12)
		facade.saveAnnotation("note-1", book.id, chapter.id, 1, 4, "ont", null)

		timestamp = 200L
		facade.deleteBookmark("bookmark-1")
		facade.deleteAnnotation("note-1")

		assertEquals(200L, database.bookmarkDao().get(accountId, "bookmark-1")?.deletedAt)
		assertEquals(200L, database.annotationDao().get(accountId, "note-1")?.deletedAt)
		assertEquals(listOf(200L, 200L), database.syncDao().pending(accountId).map { it.deletedAt })
	}

	@Test
	fun productionReaderControllerPersistsOnlyLastProgressAndRequestsWorker() = runBlocking {
		val book = NovelBookEntity("book-1", accountId, "Book", remoteId = "remote-1", sourceType = "private")
		database.novelBookDao().upsert(book)
		var syncRequests = 0
		val waits = ArrayDeque<CompletableDeferred<Unit>>()
		val parent = Job()
		val controller = NovelReaderSyncController(CoroutineScope(Dispatchers.Unconfined + parent), NovelSyncWriteFacade(accountId, database, { syncRequests++ }, now = { 500 }), wait = { waits.addLast(CompletableDeferred()); waits.last().await() })

		controller.onPositionChanged(book.id, ReaderPosition(1, 2))
		controller.onPositionChanged(book.id, ReaderPosition(3, 4))
		waits.last().complete(Unit)
		parent.children.toList().joinAll()

		assertEquals("{\"chapterIndex\":3,\"pageIndex\":4}", database.syncDao().progress(accountId, "progress:${book.id}")?.payload)
		assertEquals(listOf("progress"), database.syncDao().pending(accountId).map { it.itemType })
		assertEquals(1, syncRequests)
	}
}
