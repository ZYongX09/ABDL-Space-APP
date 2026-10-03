package org.joinmastodon.android.novel.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.joinmastodon.reader.data.BookmarkEntity
import org.joinmastodon.reader.data.NovelBookEntity
import org.joinmastodon.reader.data.NovelChapterEntity
import org.joinmastodon.reader.data.NovelDatabase
import org.joinmastodon.reader.data.NovelProgressEntity
import org.joinmastodon.reader.data.NovelSyncOutboxEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomNovelSyncStoreTest {
	private val context = ApplicationProvider.getApplicationContext<Context>()
	private val accountId = "room-sync.example_1"
	private val database = NovelDatabase.open(context, accountId)
	private val store = RoomNovelSyncStore(accountId, database)

	@After fun tearDown() { database.close(); context.deleteDatabase(NovelDatabase.databaseName(accountId)) }

	@Test fun lwwUsesClientTimeEqualTombstoneWinsAndTombstoneCannotResurrect() = runBlocking {
		val book = book("book-1", "remote-1")
		val chapter = NovelChapterEntity("chapter-1", book.id, "Chapter", "Body", 0)
		database.novelBookDao().upsert(book)
		database.novelChapterDao().upsert(listOf(chapter))
		database.syncDao().upsertProgress(NovelProgressEntity("progress-1", accountId, book.id, "{\"position\":9}", 200, null))
		database.bookmarkDao().applyRemote(BookmarkEntity("bookmark-1", accountId, book.id, chapter.id, 9, 1, 200, null))

		store.applyPage(listOf(
			remote("progress", "progress-1", "{\"position\":1}", 100, null),
			remote("bookmark", "bookmark-1", "{\"chapterId\":\"chapter-1\",\"position\":1}", 200, 200),
		), "1")
		store.applyPage(listOf(remote("bookmark", "bookmark-1", "{\"chapterId\":\"chapter-1\",\"position\":7}", 300, null)), "2")

		assertEquals("{\"position\":9}", database.syncDao().progress(accountId, "progress-1")?.payload)
		assertEquals(200L, database.bookmarkDao().get(accountId, "bookmark-1")?.deletedAt)
	}

	@Test fun markPushedAppliesAuthoritativeRemoteBeforeDeletingOutbox() = runBlocking {
		val book = book("book-1", "remote-1")
		database.novelBookDao().upsert(book)
		val change = LocalSyncChange("progress-1", "progress", book.id, "remote-1", "{\"position\":1}", 100, null, 0)
		database.syncDao().enqueue(NovelSyncOutboxEntity("progress:progress-1", accountId, "progress", "progress-1", book.id, "remote-1", change.payload, 100, null))

		store.markPushed(change, remote("progress", "progress-1", "{\"position\":8}", 200, 200))

		assertEquals("{\"position\":8}", database.syncDao().progress(accountId, "progress-1")?.payload)
		assertEquals(200L, database.syncDao().progress(accountId, "progress-1")?.deletedAt)
		assertTrue(database.syncDao().pending(accountId).isEmpty())
	}

	@Test fun markPushedNormalizesEqualTimestampPayloadFromServer() = runBlocking {
		val book = book("book-1", "remote-1")
		database.novelBookDao().upsert(book)
		database.syncDao().upsertProgress(NovelProgressEntity("progress-1", accountId, book.id, "{\"position\":1}", 200, null))
		val change = LocalSyncChange("progress-1", "progress", book.id, "remote-1", "{\"position\":1}", 200, null, 0)
		database.syncDao().enqueue(NovelSyncOutboxEntity("progress:progress-1", accountId, "progress", "progress-1", book.id, "remote-1", change.payload, 200, null))

		store.markPushed(change, remote("progress", "progress-1", "{\"pageIndex\":1,\"chapterIndex\":0}", 200, null))

		assertEquals("{\"pageIndex\":1,\"chapterIndex\":0}", database.syncDao().progress(accountId, "progress-1")?.payload)
		assertTrue(database.syncDao().pending(accountId).isEmpty())
	}

	@Test fun markPushedAppliesEqualTimestampAuthoritativeTombstone() = runBlocking {
		val book = book("book-1", "remote-1")
		val chapter = NovelChapterEntity("chapter-1", book.id, "Chapter", "Body", 0)
		database.novelBookDao().upsert(book)
		database.novelChapterDao().upsert(listOf(chapter))
		database.bookmarkDao().applyRemote(BookmarkEntity("bookmark-1", accountId, book.id, chapter.id, 3, 100, 200, null))
		val payload = "{\"chapterId\":\"chapter-1\",\"position\":3}"
		val change = LocalSyncChange("bookmark-1", "bookmark", book.id, "remote-1", payload, 200, null, 0)
		database.syncDao().enqueue(NovelSyncOutboxEntity("bookmark:bookmark-1", accountId, "bookmark", "bookmark-1", book.id, "remote-1", payload, 200, null))

		store.markPushed(change, remote("bookmark", "bookmark-1", payload, 200, 200))

		assertEquals(200L, database.bookmarkDao().get(accountId, "bookmark-1")?.deletedAt)
		assertTrue(database.syncDao().pending(accountId).isEmpty())
	}

	@Test fun completeMetadataMarksMissingPrivateRemoteDeletedButKeepsLocalBooks() = runBlocking {
		database.novelBookDao().upsert(book("keep", "remote-keep"))
		database.novelBookDao().upsert(book("missing", "remote-missing"))
		database.novelBookDao().upsert(NovelBookEntity("local", accountId, "Local"))

		store.replaceRemoteBooks(listOf(RemoteBook("remote-keep", "Updated", "Author", "hash", 50)))

		assertNull(database.novelBookDao().getById(accountId, "keep")?.deletedAt)
		assertEquals("Updated", database.novelBookDao().getById(accountId, "keep")?.title)
		assertTrue(database.novelBookDao().getById(accountId, "missing")?.deletedAt != null)
		assertNull(database.novelBookDao().getById(accountId, "local")?.deletedAt)
	}

	private fun book(id: String, remoteId: String) = NovelBookEntity(id, accountId, id, remoteId = remoteId, sourceType = "private")
	private fun remote(type: String, id: String, payload: String, updatedAt: Long, deletedAt: Long?) = RemoteSyncItem(1, "remote-1", type, id, payload, updatedAt, updatedAt, deletedAt)
}
