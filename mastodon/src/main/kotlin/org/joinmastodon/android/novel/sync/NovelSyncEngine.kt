package org.joinmastodon.android.novel.sync

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

data class RemoteSyncItem(
	val seq: Long,
	val bookId: String,
	val itemType: String,
	val itemId: String,
	val payload: String,
	val clientUpdatedAt: Long,
	val serverUpdatedAt: Long,
	val deletedAt: Long?,
)

data class RemoteBook(
	val id: String,
	val title: String,
	val author: String?,
	val contentHash: String?,
	val updatedAt: Long,
)

data class RemoteBooksPage(val items: List<RemoteBook>, val nextCursor: String?)
data class SyncResult(val retryNeeded: Boolean = false)

data class SyncPage(
	val items: List<RemoteSyncItem>,
	val nextCursor: String?,
	val checkpointCursor: String,
)

data class LocalSyncChange(
	val identity: String,
	val itemType: String,
	val localBookId: String,
	val remoteBookId: String,
	val payload: String,
	val clientUpdatedAt: Long,
	val deletedAt: Long?,
	val attempts: Int,
) {
	val itemId: String get() = identity
}

interface SyncRemote {
	suspend fun getBooks(cursor: String?, limit: Int): RemoteBooksPage
	suspend fun getSync(cursor: String?, limit: Int): SyncPage
	suspend fun put(change: LocalSyncChange): RemoteSyncItem
}

class RemoteBookDeletedException : IOException()

interface SyncStore {
	suspend fun replaceRemoteBooks(books: List<RemoteBook>)
	suspend fun checkpoint(): String?
	suspend fun applyPage(items: List<RemoteSyncItem>, checkpointCursor: String)
	suspend fun pendingChanges(): List<LocalSyncChange>
	suspend fun enqueue(change: LocalSyncChange)
	suspend fun markPushed(change: LocalSyncChange, remote: RemoteSyncItem)
	suspend fun markFailed(change: LocalSyncChange)
}

fun interface SyncCancellation {
	fun cancel()
}

interface SyncScheduler {
	fun schedule(delayMillis: Long, task: suspend () -> Unit): SyncCancellation
}

class ExecutorSyncScheduler : SyncScheduler, Closeable {
	private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
		Thread(runnable, "novel-sync-progress").apply { isDaemon = true }
	}

	override fun schedule(delayMillis: Long, task: suspend () -> Unit): SyncCancellation {
		val future = executor.schedule({ runBlocking { task() } }, delayMillis, TimeUnit.MILLISECONDS)
		return SyncCancellation { future.cancel(false) }
	}

	override fun close() {
		executor.shutdownNow()
	}
}

class NovelSyncEngine(
	private val accountId: String,
	private val remote: SyncRemote,
	private val store: SyncStore,
	private val now: () -> Long = System::currentTimeMillis,
	private val scheduler: SyncScheduler = ExecutorSyncScheduler(),
	private val requestSync: () -> Unit = {},
	private val guard: suspend () -> Unit = {},
) : Closeable {
	private val lock = Any()
	private val pendingProgress = mutableMapOf<String, SyncCancellation>()
	@Volatile private var closed = false

	suspend fun sync(limit: Int = 100): SyncResult {
		if (closed) return SyncResult()
		val books = mutableListOf<RemoteBook>()
		var booksCursor: String? = null
		do {
			if (closed) return SyncResult()
			guard()
			val page = remote.getBooks(booksCursor, limit)
			guard()
			books += page.items
			booksCursor = page.nextCursor
		} while (booksCursor != null)
		guard()
		store.replaceRemoteBooks(books)
		var cursor = store.checkpoint()
		do {
			if (closed) return SyncResult()
			guard()
			val page = remote.getSync(cursor, limit)
			guard()
			if (closed) return SyncResult()
			guard()
			store.applyPage(page.items, page.checkpointCursor)
			cursor = page.nextCursor
		} while (cursor != null)

		var retryNeeded = false
		store.pendingChanges().forEach { change ->
			if (closed) return SyncResult(retryNeeded)
			try {
				guard()
				val pushed = remote.put(change)
				guard()
				store.markPushed(change, pushed)
			} catch (error: RemoteBookDeletedException) {
				store.markPushed(change, RemoteSyncItem(0, change.remoteBookId, change.itemType, change.itemId, change.payload, change.clientUpdatedAt, now(), change.deletedAt))
			} catch (error: IOException) {
				store.markFailed(change)
				retryNeeded = true
			}
		}
		return SyncResult(retryNeeded)
	}

	fun updateProgress(localBookId: String, remoteBookId: String, itemId: String, payload: String, clientUpdatedAt: Long = now()) {
		val key = "$localBookId:$itemId"
		synchronized(lock) {
			if (closed) return
			pendingProgress.remove(key)?.cancel()
			pendingProgress[key] = scheduler.schedule(PROGRESS_DELAY_MILLIS) {
				synchronized(lock) {
					if (closed) return@schedule
					pendingProgress.remove(key)
				}
				store.enqueue(change("progress", localBookId, remoteBookId, itemId, payload, null, clientUpdatedAt))
				requestSync()
			}
		}
	}

	suspend fun enqueueBookmark(localBookId: String, remoteBookId: String, itemId: String, payload: String, deletedAt: Long?, clientUpdatedAt: Long = now()) =
		enqueueImmediate("bookmark", localBookId, remoteBookId, itemId, payload, deletedAt, clientUpdatedAt)

	suspend fun enqueueNote(localBookId: String, remoteBookId: String, itemId: String, payload: String, deletedAt: Long?, clientUpdatedAt: Long = now()) =
		enqueueImmediate("note", localBookId, remoteBookId, itemId, payload, deletedAt, clientUpdatedAt)

	private suspend fun enqueueImmediate(itemType: String, localBookId: String, remoteBookId: String, itemId: String, payload: String, deletedAt: Long?, clientUpdatedAt: Long) {
		if (closed) return
		store.enqueue(change(itemType, localBookId, remoteBookId, itemId, payload, deletedAt, clientUpdatedAt))
		requestSync()
	}

	private fun change(itemType: String, localBookId: String, remoteBookId: String, itemId: String, payload: String, deletedAt: Long?, clientUpdatedAt: Long) =
		LocalSyncChange(itemId, itemType, localBookId, remoteBookId, payload, clientUpdatedAt.coerceAtMost(now()), deletedAt?.coerceAtMost(now()), 0)

	override fun close() {
		synchronized(lock) {
			if (closed) return
			closed = true
			pendingProgress.values.forEach(SyncCancellation::cancel)
			pendingProgress.clear()
		}
		if (scheduler is Closeable) scheduler.close()
	}

	companion object {
		const val PROGRESS_DELAY_MILLIS = 2_000L
	}
}
