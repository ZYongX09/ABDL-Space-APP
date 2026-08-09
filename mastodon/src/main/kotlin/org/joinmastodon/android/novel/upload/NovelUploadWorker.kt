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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.novel.NovelAccountDataCleaner
import org.joinmastodon.android.novel.importer.NovelImportCoordinator
import org.joinmastodon.reader.data.NovelDatabase
import org.joinmastodon.reader.data.NovelTransferEntity

class NovelUploadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
	override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
		val accountId = inputData.getString(KEY_ACCOUNT_ID) ?: return@withContext Result.failure()
		val requestedTransferId = inputData.getString(KEY_TRANSFER_ID)
		val generation = NovelAccountDataCleaner.captureGeneration(accountId)
		if (AccountSessionManager.getInstance().tryGetAccount(accountId) == null) return@withContext Result.failure()
		if (NovelAccountDataCleaner.hasActiveUpload(accountId)) return@withContext Result.retry()
		val database = NovelDatabase.open(applicationContext, accountId)
		try {
			val transfers = database.transferDao().list()
				.filter { it.accountId == accountId && it.direction == NovelTransferEntity.UPLOAD }
				.filter { requestedTransferId == null || it.transferId == requestedTransferId }
			for (transfer in transfers) {
				if (!NovelAccountDataCleaner.isGenerationValid(accountId, generation)) return@withContext Result.failure()
				if (transfer.phase == NovelTransferEntity.PREPARED) {
					val file = File(transfer.localTempPath)
					when (NovelImportCoordinator.recoverPreparedFile(file, transfer.size, transfer.contentHash, transfer.contentMd5.orEmpty()) {
						database.transferDao().delete(transfer.transferId)
					}) {
						NovelImportCoordinator.PreparedRecovery.MISSING -> continue
						NovelImportCoordinator.PreparedRecovery.REBUILD_SUMMARY -> database.transferDao().upsert(transfer.copy(
							contentHash = org.joinmastodon.android.api.novels.PrivateBookUpload.sha256(file),
							contentMd5 = org.joinmastodon.android.api.novels.PrivateBookUpload.md5Base64(file),
							size = file.length(),
							updatedAt = System.currentTimeMillis(),
						))
						NovelImportCoordinator.PreparedRecovery.READY -> Unit
					}
				}
				NovelImportCoordinator(applicationContext).resumeUpload(accountId, transfer.transferId) {}
			}
			Result.success()
		} catch (error: IOException) {
			if (isStopped) Result.failure() else Result.retry()
		} catch (error: Exception) {
			if (AccountSessionManager.getInstance().tryGetAccount(accountId) == null) Result.failure() else Result.retry()
		} finally {
			database.close()
		}
	}

	companion object {
		const val KEY_ACCOUNT_ID = "account_id"
		const val KEY_TRANSFER_ID = "transfer_id"

		@JvmStatic fun uniqueWorkName(accountId: String, transferId: String) = "novel-upload:${NovelImportCoordinator.accountHash(accountId)}:$transferId"
		@JvmStatic fun accountWorkTag(accountId: String) = "novel-transfer-account-${NovelImportCoordinator.accountHash(accountId)}"

		@JvmStatic
		fun enqueue(context: Context, accountId: String, transferId: String) {
			val request = OneTimeWorkRequestBuilder<NovelUploadWorker>()
				.setInputData(Data.Builder().putString(KEY_ACCOUNT_ID, accountId).putString(KEY_TRANSFER_ID, transferId).build())
				.addTag(accountWorkTag(accountId))
				.build()
			WorkManager.getInstance(context).enqueueUniqueWork(uniqueWorkName(accountId, transferId), ExistingWorkPolicy.KEEP, request)
		}

		@JvmStatic
		fun enqueuePending(context: Context, accountId: String) {
			val request = OneTimeWorkRequestBuilder<NovelUploadWorker>()
				.setInputData(Data.Builder().putString(KEY_ACCOUNT_ID, accountId).build())
				.addTag(accountWorkTag(accountId))
				.build()
			WorkManager.getInstance(context).enqueueUniqueWork("novel-upload:${NovelImportCoordinator.accountHash(accountId)}:pending", ExistingWorkPolicy.KEEP, request)
		}

		@JvmStatic fun cancelAccount(context: Context, accountId: String) = WorkManager.getInstance(context).cancelAllWorkByTag(accountWorkTag(accountId))
	}
}
