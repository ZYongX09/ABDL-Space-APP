package org.joinmastodon.android.novel.upload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.novel.NovelAccountDataCleaner
import org.joinmastodon.android.novel.importer.NovelImportCoordinator
import org.joinmastodon.reader.data.NovelDatabase
import org.joinmastodon.reader.data.NovelTransferEntity

class NovelUploadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
	private class LostClaimException : IOException("小说上传租约已被接管")

	override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
		val accountId = inputData.getString(KEY_ACCOUNT_ID) ?: return@withContext Result.failure()
		val transferId = inputData.getString(KEY_TRANSFER_ID) ?: return@withContext Result.failure()
		val generation = NovelAccountDataCleaner.captureGeneration(accountId)
		val session = AccountSessionManager.getInstance().tryGetAccount(accountId) ?: return@withContext Result.failure()
		val lease = NovelAccountDataCleaner.enterOperation(accountId, generation) ?: return@withContext Result.failure()
		val owner = id.toString()
		val database = NovelDatabase.open(applicationContext, accountId)
		try {
			val now = System.currentTimeMillis()
			if (database.transferDao().claim(transferId, owner, now, now + CLAIM_LEASE_MILLIS) == 0) return@withContext Result.retry()
			var transfer = database.transferDao().get(transferId) ?: return@withContext Result.success()
			if (transfer.accountId != accountId || transfer.direction != NovelTransferEntity.UPLOAD) return@withContext Result.failure()
			if (!NovelAccountDataCleaner.isSessionValid(accountId, session, generation)) return@withContext Result.failure()
			val file = File(transfer.localTempPath)
			if (transfer.phase == NovelTransferEntity.COMPLETE) {
				if (file.parentFile?.deleteRecursively() != false) database.transferDao().delete(transferId)
				return@withContext Result.success()
			}
			if (transfer.phase == NovelTransferEntity.PREPARED && transfer.size <= 0 && file.isFile) {
				transfer = transfer.copy(
					contentHash = org.joinmastodon.android.api.novels.PrivateBookUpload.sha256(file),
					contentMd5 = org.joinmastodon.android.api.novels.PrivateBookUpload.md5Base64(file),
					size = file.length(), updatedAt = System.currentTimeMillis(),
				)
				database.transferDao().upsert(transfer)
			}
			if (!NovelImportCoordinator.isVerifiedTransferFile(file, transfer.size, transfer.contentHash, transfer.contentMd5.orEmpty())) {
				database.transferDao().upsert(transfer.copy(phase = NovelTransferEntity.FAILED, claimOwner = null, claimExpiresAt = null, updatedAt = System.currentTimeMillis()))
				file.parentFile?.deleteRecursively()
				return@withContext Result.failure()
			}
			coroutineScope {
				val renewal = launch {
					while (true) {
						delay(CLAIM_RENEW_MILLIS)
						if (database.transferDao().renewClaim(transferId, owner, System.currentTimeMillis() + CLAIM_LEASE_MILLIS) == 0) throw LostClaimException()
					}
				}
				try {
					NovelImportCoordinator(applicationContext).resumeUpload(accountId, transfer.transferId) {}
				} finally {
					renewal.cancelAndJoin()
				}
			}
			Result.success()
		} catch (error: IOException) {
			if (isStopped) Result.failure() else Result.retry()
		} catch (error: Exception) {
			if (AccountSessionManager.getInstance().tryGetAccount(accountId) == null) Result.failure() else Result.retry()
		} finally {
			database.transferDao().release(transferId, owner)
			database.close()
			lease.close()
		}
	}

	companion object {
		const val KEY_ACCOUNT_ID = "account_id"
		const val KEY_TRANSFER_ID = "transfer_id"
		private const val CLAIM_LEASE_MILLIS = 10 * 60 * 1000L
		private const val CLAIM_RENEW_MILLIS = 5 * 60 * 1000L

		@JvmStatic fun workName(accountId: String, transferId: String) = "novel-upload:${NovelImportCoordinator.accountHash(accountId)}:$transferId"
		@JvmStatic fun uniqueWorkName(accountId: String, transferId: String) = workName(accountId, transferId)
		@JvmStatic fun accountWorkTag(accountId: String) = "novel-transfer-account-${NovelImportCoordinator.accountHash(accountId)}"

		@JvmStatic
		fun enqueue(context: Context, accountId: String, transferId: String) {
			val request = OneTimeWorkRequestBuilder<NovelUploadWorker>()
				.setInputData(Data.Builder().putString(KEY_ACCOUNT_ID, accountId).putString(KEY_TRANSFER_ID, transferId).build())
				.addTag(accountWorkTag(accountId))
				.build()
			WorkManager.getInstance(context).enqueueUniqueWork(workName(accountId, transferId), ExistingWorkPolicy.KEEP, request)
		}

		@JvmStatic
		fun enqueuePending(context: Context, accountId: String) {
			Thread {
				val database = NovelDatabase.open(context, accountId)
				try {
					runBlocking { database.transferDao().list() }
						.filter { it.accountId == accountId && it.direction == NovelTransferEntity.UPLOAD }
						.forEach { enqueue(context, accountId, it.transferId) }
				} finally {
					database.close()
				}
			}.apply { name = "novel-upload-scan" }.start()
		}

		@JvmStatic fun cancelAccount(context: Context, accountId: String) = WorkManager.getInstance(context).cancelAllWorkByTag(accountWorkTag(accountId))
	}
}
