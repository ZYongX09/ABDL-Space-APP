package org.joinmastodon.android.novel.author

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import java.util.UUID
import org.joinmastodon.android.api.novels.NovelAuthoringApi
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.novel.NovelAccountDataCleaner
import org.joinmastodon.android.novel.importer.NovelImportCoordinator
import org.joinmastodon.reader.data.NovelAuthorRevisionConflictEntity
import org.joinmastodon.reader.data.NovelDatabase

class AuthorDraftSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
	override suspend fun doWork(): Result {
		val accountId = inputData.getString(KEY_ACCOUNT_ID) ?: return Result.failure()
		val generation = NovelAccountDataCleaner.captureGeneration(accountId)
		val session = AccountSessionManager.getInstance().tryGetAccount(accountId) ?: return Result.failure()
		val lease = NovelAccountDataCleaner.enterOperation(accountId, generation) ?: return Result.failure()
		val database = NovelDatabase.open(applicationContext, accountId)
		return try {
			val api = NovelAuthoringApi(session)
			val dao = database.authorDraftDao()
			for (item in dao.pending(accountId)) {
				check(NovelAccountDataCleaner.isSessionValid(accountId, session, generation)) { "账号已退出" }
				try {
					if (item.operation == "create_revision") {
						val response = api.executeDraft(api.newCreateRevisionCall(item.chapterId, NovelAuthoringApi.RevisionBodyRequest(item.content), item.idempotencyKey))
						check(response.chapterId == item.chapterId)
						check(NovelAccountDataCleaner.isSessionValid(accountId, session, generation)) { "账号已退出" }
						dao.acknowledgeCreate(accountId, item.localDraftId, item.localVersion, response.id, response.version, UUID.randomUUID().toString(), System.currentTimeMillis())
					} else {
						val revisionId = item.remoteRevisionId ?: error("草稿缺少云端版本")
						val response = api.executeDraft(api.newDraftCall(revisionId, NovelAuthoringApi.DraftRequest(item.content, item.baseVersion), item.idempotencyKey))
						check(response.id == revisionId && response.chapterId == item.chapterId)
						check(NovelAccountDataCleaner.isSessionValid(accountId, session, generation)) { "账号已退出" }
						dao.acknowledgePush(accountId, item.localDraftId, item.localVersion, response.version, System.currentTimeMillis())
					}
				} catch (conflict: NovelAuthoringApi.DraftConflictException) {
					if (item.operation != "put_draft") throw conflict
					val server = conflict.serverRevision
					check(server.id == item.remoteRevisionId && server.chapterId == item.chapterId && server.status == "draft")
					dao.recordConflict(NovelAuthorRevisionConflictEntity(UUID.randomUUID().toString(), accountId, item.localDraftId, item.chapterId, item.baseVersion, item.localVersion, item.content, server.version, server.body, System.currentTimeMillis(), null, null), item.identity, item.localVersion)
				} catch (error: NovelAuthoringApi.ApiException) {
					if (error.status == 408 || error.status == 429 || error.status >= 500) throw error
					dao.failOutbox(accountId, item.identity, item.localVersion, System.currentTimeMillis())
				}
			}
			Result.success()
		} catch (_: java.io.IOException) {
			Result.retry()
		} catch (_: IllegalStateException) {
			Result.failure()
		} finally {
			database.close()
			lease.close()
		}
	}

	companion object {
		private const val KEY_ACCOUNT_ID = "account_id"
		private fun tag(accountId: String) = "novel-author-sync-account:${NovelImportCoordinator.accountHash(accountId)}"
		private fun name(accountId: String) = "novel-author-sync:${NovelImportCoordinator.accountHash(accountId)}"
		fun enqueue(context: Context, accountId: String) {
			val request = OneTimeWorkRequestBuilder<AuthorDraftSyncWorker>()
				.setInputData(Data.Builder().putString(KEY_ACCOUNT_ID, accountId).build())
				.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
				.setInitialDelay(800, TimeUnit.MILLISECONDS)
				.addTag(tag(accountId)).build()
			WorkManager.getInstance(context).enqueueUniqueWork(name(accountId), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
		}
		fun cancelAccount(context: Context, accountId: String) = WorkManager.getInstance(context).cancelAllWorkByTag(tag(accountId))
	}
}
