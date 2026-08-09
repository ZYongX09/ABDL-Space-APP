package org.joinmastodon.android.novel

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.joinmastodon.android.api.novels.PrivateBookUpload
import org.joinmastodon.android.novel.download.NovelDownloadWorker
import org.joinmastodon.android.novel.importer.NovelImportCoordinator
import org.joinmastodon.android.novel.upload.NovelUploadWorker
import org.joinmastodon.reader.data.NovelDatabase

object NovelAccountDataCleaner {
	private val generations = ConcurrentHashMap<String, AtomicLong>()
	private val uploads = ConcurrentHashMap<String, MutableSet<PrivateBookUpload>>()

	@JvmStatic
	fun captureGeneration(accountId: String): Long = generations.computeIfAbsent(accountId) { AtomicLong() }.get()

	@JvmStatic
	fun isGenerationValid(accountId: String, generation: Long): Boolean = captureGeneration(accountId) == generation

	@JvmStatic
	fun registerUpload(accountId: String, upload: PrivateBookUpload) {
		uploads.computeIfAbsent(accountId) { ConcurrentHashMap.newKeySet() }.add(upload)
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

	@JvmStatic
	fun invalidate(accountId: String) {
		generations.computeIfAbsent(accountId) { AtomicLong() }.incrementAndGet()
		uploads.remove(accountId)?.forEach(PrivateBookUpload::cancel)
	}

	@JvmStatic
	fun clean(context: Context, accountId: String) {
		NovelDownloadWorker.cancelAccount(context, accountId)
		NovelUploadWorker.cancelAccount(context, accountId)
		NovelDatabase.closeAccount(accountId)
		deleteAccountData(context.filesDir, context.cacheDir, requireNotNull(context.getDatabasePath("placeholder").parentFile), accountId)
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
