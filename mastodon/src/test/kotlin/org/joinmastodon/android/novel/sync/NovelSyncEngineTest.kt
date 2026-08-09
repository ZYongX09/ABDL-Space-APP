package org.joinmastodon.android.novel.sync

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelSyncEngineTest {
	@Test
	fun pullPersistsLastPageCheckpointAndIncludesEventsInsertedBetweenPages() = runBlocking {
		val calls = mutableListOf<String>()
		val remote = FakeRemote(
			pages = mutableMapOf(
				null to SyncPage(listOf(item(1, "bookmark", "first")), "page-2", "1"),
				"page-2" to SyncPage(listOf(item(2, "note", "between-pages")), null, "2"),
			),
			calls = calls,
		)
		val store = FakeStore(calls = calls)
		val engine = engine(remote, store)

		engine.sync()

		assertEquals(listOf(1L, 2L), store.applied.map { it.seq })
		assertEquals("2", store.checkpoint)
		assertEquals(listOf("pull:null", "apply:1", "pull:page-2", "apply:2"), calls)
	}

	@Test
	fun pullsBeforePushingLocalChanges() = runBlocking {
		val calls = mutableListOf<String>()
		val remote = FakeRemote(mutableMapOf(null to SyncPage(emptyList(), null, "9")), calls)
		val store = FakeStore(calls = calls).apply { outbox += change("bookmark", "local") }

		engine(remote, store).sync()

		assertTrue(calls.indexOf("apply:9") < calls.indexOf("push:local"))
		assertTrue(store.outbox.isEmpty())
	}

	@Test
	fun progressWaitsTwoSecondsAndOnlyQueuesLastValue() = runBlocking {
		val scheduler = ManualScheduler()
		val store = FakeStore()
		val engine = engine(FakeRemote(), store, scheduler = scheduler)

		engine.updateProgress("book-local", "remote-book", "progress-1", "{\"position\":1}")
		scheduler.advanceBy(1_000)
		engine.updateProgress("book-local", "remote-book", "progress-1", "{\"position\":2}")
		scheduler.advanceBy(1_999)
		assertTrue(store.outbox.isEmpty())
		scheduler.advanceBy(1)

		assertEquals(listOf("{\"position\":2}"), store.outbox.map { it.payload })
	}

	@Test
	fun bookmarkAndNoteQueueImmediatelyAndRequestSync() = runBlocking {
		val store = FakeStore()
		var syncRequests = 0
		val engine = engine(FakeRemote(), store, requestSync = { syncRequests++ })

		engine.enqueueBookmark("book-local", "remote-book", "bookmark-1", "{}", null)
		engine.enqueueNote("book-local", "remote-book", "note-1", "{}", null)

		assertEquals(listOf("bookmark", "note"), store.outbox.map { it.itemType })
		assertEquals(2, syncRequests)
	}

	@Test
	fun remoteTombstoneDoesNotResurrectNewerLocalTombstone() = runBlocking {
		val remote = FakeRemote(mutableMapOf(null to SyncPage(listOf(item(3, "bookmark", "dead", deletedAt = 100)), null, "3")))
		val store = FakeStore().apply { localTombstones["dead"] = 200 }

		engine(remote, store).sync()

		assertEquals(200L, store.localTombstones["dead"])
		assertFalse(store.liveItems.contains("dead"))
	}

	@Test
	fun clientClockIsClampedToNow() = runBlocking {
		val store = FakeStore()
		val engine = engine(FakeRemote(), store, now = { 1_000 })

		engine.enqueueBookmark("book-local", "remote-book", "bookmark-1", "{}", null, clientUpdatedAt = 9_999)

		assertEquals(1_000L, store.outbox.single().clientUpdatedAt)
	}

	@Test
	fun closingAccountCancelsPendingProgress() = runBlocking {
		val scheduler = ManualScheduler()
		val store = FakeStore()
		val engine = engine(FakeRemote(), store, scheduler = scheduler)

		engine.updateProgress("book-local", "remote-book", "progress-1", "{}")
		engine.close()
		scheduler.advanceBy(2_000)

		assertTrue(store.outbox.isEmpty())
	}

	@Test
	fun unknownRemoteBookAdvancesCheckpointWithoutCrossAccountWrite() = runBlocking {
		val remote = FakeRemote(mutableMapOf(null to SyncPage(listOf(item(4, "note", "unknown", bookId = "other-book")), null, "4")))
		val store = FakeStore(knownRemoteBooks = mutableSetOf("known-book"))

		engine(remote, store).sync()

		assertTrue(store.applied.isEmpty())
		assertEquals("4", store.checkpoint)
	}

	@Test
	fun temporaryPushFailureKeepsOutboxForRetry() = runBlocking {
		val store = FakeStore().apply { outbox += change("note", "retry") }
		val remote = FakeRemote(putFailure = IOException("offline"))

		engine(remote, store).sync()

		assertEquals(listOf("retry"), store.outbox.map { it.itemId })
		assertEquals(1, store.outbox.single().attempts)
	}

	private fun engine(
		remote: SyncRemote,
		store: SyncStore,
		now: () -> Long = { 1_000 },
		scheduler: SyncScheduler = ManualScheduler(),
		requestSync: () -> Unit = {},
	) = NovelSyncEngine("account-a", remote, store, now, scheduler, requestSync)

	private fun item(seq: Long, type: String, id: String, bookId: String = "known-book", deletedAt: Long? = null) =
		RemoteSyncItem(seq, bookId, type, id, "{}", 100, 100, deletedAt)

	private fun change(type: String, id: String) = LocalSyncChange(id, type, "local-book", "known-book", "{}", 100, null, 0)

	private class FakeRemote(
		private val pages: MutableMap<String?, SyncPage> = mutableMapOf(null to SyncPage(emptyList(), null, "0")),
		private val calls: MutableList<String> = mutableListOf(),
		private val putFailure: IOException? = null,
	) : SyncRemote {
		override suspend fun getSync(cursor: String?, limit: Int): SyncPage {
			calls += "pull:$cursor"
			return requireNotNull(pages[cursor])
		}

		override suspend fun put(change: LocalSyncChange): RemoteSyncItem {
			calls += "push:${change.itemId}"
			putFailure?.let { throw it }
			return RemoteSyncItem(99, change.remoteBookId, change.itemType, change.itemId, "{}", 100, 100, change.deletedAt)
		}
	}

	private class FakeStore(
		val knownRemoteBooks: MutableSet<String> = mutableSetOf("known-book"),
		private val calls: MutableList<String> = mutableListOf(),
	) : SyncStore {
		var checkpoint: String? = null
		val applied = mutableListOf<RemoteSyncItem>()
		val outbox = mutableListOf<LocalSyncChange>()
		val localTombstones = mutableMapOf<String, Long>()
		val liveItems = mutableSetOf<String>()

		override suspend fun checkpoint(): String? = checkpoint

		override suspend fun applyPage(items: List<RemoteSyncItem>, checkpointCursor: String) {
			items.filter { it.bookId in knownRemoteBooks }.forEach { remote ->
				applied += remote
				if (remote.deletedAt != null) {
					val current = localTombstones[remote.itemId]
					if (current == null || remote.deletedAt > current) localTombstones[remote.itemId] = remote.deletedAt
					liveItems -= remote.itemId
				} else if (localTombstones[remote.itemId] == null) {
					liveItems += remote.itemId
				}
			}
			checkpoint = checkpointCursor
			calls += "apply:$checkpointCursor"
		}

		override suspend fun pendingChanges(): List<LocalSyncChange> = outbox.toList()

		override suspend fun enqueue(change: LocalSyncChange) {
			outbox.removeAll { it.itemType == change.itemType && it.itemId == change.itemId }
			outbox += change
		}

		override suspend fun markPushed(change: LocalSyncChange, remote: RemoteSyncItem) {
			outbox.removeAll { it.itemType == change.itemType && it.itemId == change.itemId }
		}

		override suspend fun markFailed(change: LocalSyncChange) {
			val index = outbox.indexOfFirst { it.itemType == change.itemType && it.itemId == change.itemId }
			if (index >= 0) outbox[index] = outbox[index].copy(attempts = outbox[index].attempts + 1)
		}
	}

	private class ManualScheduler : SyncScheduler {
		private data class Entry(val at: Long, val task: suspend () -> Unit, var canceled: Boolean = false)
		private var now = 0L
		private val entries = mutableListOf<Entry>()

		override fun schedule(delayMillis: Long, task: suspend () -> Unit): SyncCancellation {
			val entry = Entry(now + delayMillis, task)
			entries += entry
			return SyncCancellation { entry.canceled = true }
		}

		fun advanceBy(millis: Long) = runBlocking {
			now += millis
			entries.filter { !it.canceled && it.at <= now }.toList().forEach {
				it.canceled = true
				it.task()
			}
		}
	}
}
