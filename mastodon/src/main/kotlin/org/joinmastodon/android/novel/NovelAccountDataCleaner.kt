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
	private const val CLEANUP_PREFS = "novel_account_cleanup"
	private const val CLEANUP_ACCOUNTS = "pending_accounts"
	private val generations = ConcurrentHashMap<String, AtomicLong>()
	private val uploads = ConcurrentHashMap<String, MutableSet<PrivateBookUpload>>()
	private val leases = ConcurrentHashMap<String, AtomicLong>()
	private val cleaningAccounts = ConcurrentHashMap.newKeySet<String>()
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
		if (accountId in cleaningAccounts || !isGenerationValid(accountId, generation)) return@synchronized null
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
		markCleanupPending(context, accountId)
		NovelAccountCleanupWorker.enqueue(context, accountId)
	}

	@JvmStatic
	fun cleanIfIdle(context: Context, accountId: String): Boolean = cleanIfIdle(
		context.filesDir,
		context.cacheDir,
		requireNotNull(context.getDatabasePath("placeholder").parentFile),
		accountId,
	)

	internal fun cleanIfIdle(filesDir: File, cacheDir: File, databasesDir: File, accountId: String): Boolean {
		synchronized(lock) {
			if ((leases[accountId]?.get() ?: 0L) > 0L) return false
		}
		NovelDatabase.closeAccount(accountId)
		return deleteAccountData(filesDir, cacheDir, databasesDir, accountId)
	}

	@JvmStatic
	fun markCleanupPending(context: Context, accountId: String) = synchronized(lock) {
		cleaningAccounts.add(accountId)
		val prefs = context.getSharedPreferences(CLEANUP_PREFS, Context.MODE_PRIVATE)
		prefs.edit().putStringSet(CLEANUP_ACCOUNTS, prefs.getStringSet(CLEANUP_ACCOUNTS, emptySet()).orEmpty() + accountId).commit()
	}

	@JvmStatic
	fun clearCleanupPending(context: Context, accountId: String) = synchronized(lock) {
		val prefs = context.getSharedPreferences(CLEANUP_PREFS, Context.MODE_PRIVATE)
		prefs.edit().putStringSet(CLEANUP_ACCOUNTS, prefs.getStringSet(CLEANUP_ACCOUNTS, emptySet()).orEmpty() - accountId).commit()
		cleaningAccounts.remove(accountId)
	}

	@JvmStatic
	fun pendingCleanupAccounts(context: Context): Set<String> = synchronized(lock) {
		context.getSharedPreferences(CLEANUP_PREFS, Context.MODE_PRIVATE).getStringSet(CLEANUP_ACCOUNTS, emptySet()).orEmpty().toSet().also(cleaningAccounts::addAll)
	}

	@JvmStatic
	fun deleteAccountData(filesDir: File, cacheDir: File, databasesDir: File, accountId: String): Boolean {
		val hash = NovelImportCoordinator.accountHash(accountId)
		val roots = listOf(
			File(filesDir, "novels/$hash"),
			File(filesDir, "novels/transfers/$hash"),
			File(cacheDir, "novels/import/$hash"),
		)
		roots.forEach { it.deleteRecursively() }
		val database = NovelDatabase.databaseName(accountId)
		val databaseFiles = listOf(database, "$database-wal", "$database-shm", "$database-journal").map { File(databasesDir, it) }
		databaseFiles.forEach { it.delete() }
		return roots.none(File::exists) && databaseFiles.none(File::exists)
	}
}
