package org.joinmastodon.android.novel

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.joinmastodon.android.novel.importer.NovelImportCoordinator

class NovelAccountCleanupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
	override suspend fun doWork(): Result {
		val accountId = inputData.getString(KEY_ACCOUNT_ID) ?: return Result.failure()
		if (!NovelAccountDataCleaner.cleanIfIdle(applicationContext, accountId)) return Result.retry()
		NovelAccountDataCleaner.clearCleanupPending(applicationContext, accountId)
		return Result.success()
	}

	companion object {
		private const val KEY_ACCOUNT_ID = "account_id"
		private const val CLEANUP_TAG_PREFIX = "novel-cleanup-account-"

		@JvmStatic fun workName(accountId: String) = "novel-cleanup:${NovelImportCoordinator.accountHash(accountId)}"
		@JvmStatic fun accountWorkTag(accountId: String) = CLEANUP_TAG_PREFIX + NovelImportCoordinator.accountHash(accountId)

		@JvmStatic
		fun enqueue(context: Context, accountId: String) {
			val request = OneTimeWorkRequestBuilder<NovelAccountCleanupWorker>()
				.setInputData(Data.Builder().putString(KEY_ACCOUNT_ID, accountId).build())
				.addTag(accountWorkTag(accountId))
				.build()
			WorkManager.getInstance(context).enqueueUniqueWork(workName(accountId), ExistingWorkPolicy.REPLACE, request)
		}

		@JvmStatic
		fun enqueuePending(context: Context) {
			NovelAccountDataCleaner.pendingCleanupAccounts(context).forEach { enqueue(context, it) }
		}
	}
}
