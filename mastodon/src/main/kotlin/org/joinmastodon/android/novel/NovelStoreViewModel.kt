package org.joinmastodon.android.novel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.joinmastodon.android.api.novels.PublicNovelStoreApi
import org.joinmastodon.reader.domain.BookFormat
import org.joinmastodon.reader.domain.ReaderBook
import org.joinmastodon.reader.domain.ReaderChapter

data class NovelStoreState(
	val works: List<PublicNovelStoreApi.WorkDto> = emptyList(),
	val nextCursor: String? = null,
	val selectedWork: PublicNovelStoreApi.WorkDto? = null,
	val loading: Boolean = false,
	val error: String? = null,
	val reader: NovelReaderState? = null,
	val publicReader: PublicReaderState? = null,
)

data class PublicReaderState(val work: PublicNovelStoreApi.WorkDto, val chapters: List<PublicNovelStoreApi.ChapterDto>, val chapterIndex: Int, val reader: NovelReaderState)

internal fun sortedPublicChapters(work: PublicNovelStoreApi.WorkDto): List<PublicNovelStoreApi.ChapterDto> =
	work.volumes.orEmpty().sortedWith(compareBy<PublicNovelStoreApi.VolumeDto> { it.sortOrder }.thenBy { it.id })
		.flatMap { volume -> volume.chapters.orEmpty().sortedWith(compareBy<PublicNovelStoreApi.ChapterDto> { it.sortOrder }.thenBy { it.id }) }

internal fun publicationChangedState(state: NovelStoreState, refreshed: PublicNovelStoreApi.WorkDto): NovelStoreState =
	state.copy(selectedWork = refreshed, reader = null, publicReader = null, error = "作品内容已更新，请从目录重新打开章节")

class NovelStoreViewModel(application: Application) : AndroidViewModel(application) {
	private val api = PublicNovelStoreApi()
	private val mutableState = MutableStateFlow(NovelStoreState())
	val state: StateFlow<NovelStoreState> = mutableState.asStateFlow()

	init { refresh() }

	fun refresh() = load {
		val page = api.executeJson(api.newWorksCall(null), PublicNovelStoreApi.WorkListDto::class.java)
		mutableState.update { it.copy(works = page.items.orEmpty(), nextCursor = page.nextCursor) }
	}

	fun loadNextPage() {
		val cursor = mutableState.value.nextCursor ?: return
		load {
			val page = api.executeJson(api.newWorksCall(cursor), PublicNovelStoreApi.WorkListDto::class.java)
			mutableState.update { state -> state.copy(works = state.works + page.items.orEmpty().filter { candidate -> state.works.none { it.id == candidate.id } }, nextCursor = page.nextCursor) }
		}
	}

	fun openWork(workId: String) = load {
		val work = api.executeJson(api.newWorkCall(workId), PublicNovelStoreApi.WorkDto::class.java)
		mutableState.update { it.copy(selectedWork = work) }
	}

	fun closeWork() { mutableState.update { it.copy(selectedWork = null) } }

	fun openChapter(work: PublicNovelStoreApi.WorkDto, chapter: PublicNovelStoreApi.ChapterDto) {
		val chapters = sortedPublicChapters(work)
		openChapter(work, chapters, chapters.indexOfFirst { it.id == chapter.id })
	}

	fun previousChapter() { mutableState.value.publicReader?.let { if (it.chapterIndex > 0) openChapter(it.work, it.chapters, it.chapterIndex - 1) } }
	fun nextChapter() { mutableState.value.publicReader?.let { if (it.chapterIndex < it.chapters.lastIndex) openChapter(it.work, it.chapters, it.chapterIndex + 1) } }

	private fun openChapter(work: PublicNovelStoreApi.WorkDto, chapters: List<PublicNovelStoreApi.ChapterDto>, index: Int) = load {
		check(index in chapters.indices) { "公开章节不存在" }
		val chapter = chapters[index]
		val published = try {
			api.executeJson(api.newChapterCall(work.id, chapter.id, chapter.publishedRevisionId), PublicNovelStoreApi.PublishedChapterDto::class.java)
		} catch (error: PublicNovelStoreApi.ApiException) {
			if (error.status == 409) {
				val refreshed = api.executeJson(api.newWorkCall(work.id), PublicNovelStoreApi.WorkDto::class.java)
				mutableState.update { publicationChangedState(it, refreshed) }
				return@load
			}
			throw error
		}
		check(!published.body.isNullOrBlank() && published.chapterId == chapter.id && published.revisionId == chapter.publishedRevisionId) { "公开章节数据无效" }
		mutableState.update {
			val reader = NovelReaderState(
				ReaderBook("public:${work.id}:${published.revisionId}", work.title, work.author?.username, BookFormat.TXT),
				listOf(ReaderChapter(published.chapterId, "public:${work.id}", 0, chapter.title, published.body, published.revisionId)),
			)
			it.copy(reader = reader, publicReader = PublicReaderState(work, chapters, index, reader))
		}
	}

	fun closeReader() { mutableState.update { it.copy(reader = null, publicReader = null) } }
	fun dismissError() { mutableState.update { it.copy(error = null) } }

	private fun load(block: () -> Unit) {
		if (mutableState.value.loading) return
		viewModelScope.launch(Dispatchers.IO) {
			mutableState.update { it.copy(loading = true, error = null) }
			try { runInterruptible { block() } }
			catch (error: Exception) { mutableState.update { it.copy(error = error.message ?: "无法加载公开书城") } }
			finally { mutableState.update { it.copy(loading = false) } }
		}
	}
}
