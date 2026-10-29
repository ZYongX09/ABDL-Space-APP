package org.joinmastodon.android.novel.app.editor

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.joinmastodon.android.novel.NovelAccountDataCleaner
import org.joinmastodon.noveleditor.NovelEditorStorage

data class LocalNovelSummary(val localId: String, val serverId: String?, val title: String, val updatedAt: Long, val chapterCount: Int, val wordCount: Long)

/** Directory-only lookup; never loads chapter bodies or invents server draft counts. */
suspend fun listLocalWorks(context: Context, accountId: String): List<LocalNovelSummary> = withContext(Dispatchers.IO) {
    val generation = NovelAccountDataCleaner.captureGeneration(accountId)
    val lease = NovelAccountDataCleaner.enterOperation(accountId, generation) ?: return@withContext emptyList()
    lease.use {
        NovelEditorStorage(context, accountId).use { storage ->
            storage.listWorks(false).map { work -> LocalNovelSummary(work.id(), storage.appMetadata(work.id())?.serverId(), work.title(), work.updatedAt(), work.chapterCount(), work.characterCount()) }
        }
    }
}
