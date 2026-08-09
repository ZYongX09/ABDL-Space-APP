package org.joinmastodon.android.novel.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.joinmastodon.android.api.novels.PrivateNovelApi
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.novel.NovelAccountDataCleaner
import org.joinmastodon.android.novel.importer.NovelImportCoordinator
import org.joinmastodon.reader.data.NovelDatabase

class NovelSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
	override suspend fun doWork(): Result {
		val accountId = inputData.getString(KEY_ACCOUNT_ID) ?: return Result.failure()
		val generation = NovelAccountDataCleaner.captureGeneration(accountId)
		val session = AccountSessionManager.getInstance().tryGetAccount(accountId) ?: return Result.failure()
		val lease = NovelAccountDataCleaner.enterOperation(accountId, generation) ?: return Result.failure()
		val database = NovelDatabase.open(applicationContext, accountId)
		return try {
			if (!NovelAccountDataCleaner.isSessionValid(accountId, session, generation)) return Result.failure()
			NovelSyncEngine(accountId, PrivateNovelSyncRemote(PrivateNovelApi(session)), RoomNovelSyncStore(accountId, database)).use { it.sync() }
			if (NovelAccountDataCleaner.isSessionValid(accountId, session, generation)) Result.success() else Result.failure()
		} catch (_: java.io.IOException) {
			Result.retry()
		} finally {
			database.close()
			lease.close()
		}
	}

	companion object {
		private const val KEY_ACCOUNT_ID = "account_id"
		@JvmStatic fun accountTag(accountId: String) = "novel-sync-account:${NovelImportCoordinator.accountHash(accountId)}"
		@JvmStatic fun workName(accountId: String) = "novel-sync:${NovelImportCoordinator.accountHash(accountId)}"
		@JvmStatic fun enqueue(context: Context, accountId: String) {
			val request = OneTimeWorkRequestBuilder<NovelSyncWorker>()
				.setInputData(Data.Builder().putString(KEY_ACCOUNT_ID, accountId).build())
				.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
				.addTag(accountTag(accountId)).build()
			WorkManager.getInstance(context).enqueueUniqueWork(workName(accountId), ExistingWorkPolicy.KEEP, request)
		}
		@JvmStatic fun cancelAccount(context: Context, accountId: String) = WorkManager.getInstance(context).cancelAllWorkByTag(accountTag(accountId))
	}
}
