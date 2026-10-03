package org.joinmastodon.android.novel.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.joinmastodon.reader.domain.ReaderPosition

class NovelReaderSyncController(
	private val scope: CoroutineScope,
	private val writes: NovelSyncWriteFacade,
	private val delayMillis: Long = NovelSyncEngine.PROGRESS_DELAY_MILLIS,
	private val wait: suspend (Long) -> Unit = { delay(it) },
) {
	private var pending: Job? = null

	fun onPositionChanged(bookId: String, position: ReaderPosition) {
		pending?.cancel()
		pending = scope.launch {
			wait(delayMillis)
			writes.saveProgress(bookId, position.chapterIndex, position.pageIndex)
		}
	}
}
