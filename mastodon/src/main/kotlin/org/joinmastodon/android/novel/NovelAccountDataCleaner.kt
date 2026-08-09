package org.joinmastodon.android.novel

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.joinmastodon.android.api.novels.PrivateBookUpload
import org.joinmastodon.android.api.session.AccountSession
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.novel.download.NovelDownloadWorker
import org.joinmastodon.android.novel.importer.NovelImportCoordinator
import org.joinmastodon.android.novel.upload.NovelUploadWorker
import org.joinmastodon.reader.data.NovelDatabase

object NovelAccountDataCleaner {
	private val generations = ConcurrentHashMap<String, AtomicLong>()
	private val uploads = ConcurrentHashMap<String, MutableSet<PrivateBookUpload>>()
	private val leases = ConcurrentHashMap<String, AtomicLong>()
	private val lock = java.lang.Object()

	@JvmStatic
	fun captureGeneration(accountId: String): Long = generations.computeIfAbsent(accountId) { AtomicLong() }.get()

	@JvmStatic
	fun isGenerationValid(accountId: String, generation: Long): Boolean = captureGeneration(accountId) == generation

	@JvmStatic
	fun isSessionValid(accountId: String, session: AccountSession, generation: Long): Boolean = synchronized(lock) {
		val current = AccountSessionManager.getInstance().tryGetAccount(accountId)
		current === session && current.token.accessToken == session.token.accessToken && isGenerationValid(accountId, generation)
	}

	@JvmStatic
	fun registerUpload(accountId: String, generation: Long, upload: PrivateBookUpload): Boolean =
		registerUpload(accountId, generation, upload) { AccountSessionManager.getInstance().tryGetAccount(accountId) != null }

	internal fun registerUpload(accountId: String, generation: Long, upload: PrivateBookUpload, sessionPresent: () -> Boolean): Boolean = synchronized(lock) {
		if (!isGenerationValid(accountId, generation) || !sessionPresent()) {
			upload.cancel()
			false
		} else {
			uploads.computeIfAbsent(accountId) { ConcurrentHashMap.newKeySet() }.add(upload)
			true
		}
	}

	@JvmStatic
	fun unregisterUpload(accountId: String, upload: PrivateBookUpload) {
		uploads[accountId]?.let { active ->
			active.remove(upload)
			if (active.isEmpty()) uploads.remove(accountId, active)
		}
	}

	@JvmStatic
	fun hasActiveUpload(accountId: String): Boolean = uploads[accountId]?.isNotEmpty() == true

	class OperationLease internal constructor(private val accountId: String) : AutoCloseable {
		override fun close() {
			leases[accountId]?.let { count ->
				if (count.decrementAndGet() == 0L) synchronized(lock) { lock.notifyAll() }
			}
		}
	}

	@JvmStatic
	fun enterOperation(accountId: String, generation: Long): OperationLease? = synchronized(lock) {
		if (!isGenerationValid(accountId, generation)) return@synchronized null
		leases.computeIfAbsent(accountId) { AtomicLong() }.incrementAndGet()
		OperationLease(accountId)
	}

	@JvmStatic
	fun invalidate(accountId: String) = synchronized(lock) {
		generations.computeIfAbsent(accountId) { AtomicLong() }.incrementAndGet()
		uploads.remove(accountId)?.forEach(PrivateBookUpload::cancel)
	}

	@JvmStatic
	fun revoke(accountId: String, removeSession: Runnable) = synchronized(lock) {
		removeSession.run()
		generations.computeIfAbsent(accountId) { AtomicLong() }.incrementAndGet()
		uploads.remove(accountId)?.forEach(PrivateBookUpload::cancel)
	}

	@JvmStatic
	fun clean(context: Context, accountId: String) {
		NovelDownloadWorker.cancelAccount(context, accountId)
		NovelUploadWorker.cancelAccount(context, accountId)
		Thread {
			synchronized(lock) {
				while ((leases[accountId]?.get() ?: 0L) > 0L) lock.wait()
			}
			NovelDatabase.closeAccount(accountId)
			deleteAccountData(context.filesDir, context.cacheDir, requireNotNull(context.getDatabasePath("placeholder").parentFile), accountId)
		}.apply { name = "novel-account-cleanup" }.start()
	}

	@JvmStatic
	fun deleteAccountData(filesDir: File, cacheDir: File, databasesDir: File, accountId: String) {
		val hash = NovelImportCoordinator.accountHash(accountId)
		File(filesDir, "novels/$hash").deleteRecursively()
		File(filesDir, "novels/transfers/$hash").deleteRecursively()
		File(cacheDir, "novels/import/$hash").deleteRecursively()
		val database = NovelDatabase.databaseName(accountId)
		listOf(database, "$database-wal", "$database-shm", "$database-journal").forEach { File(databasesDir, it).delete() }
	}
}
