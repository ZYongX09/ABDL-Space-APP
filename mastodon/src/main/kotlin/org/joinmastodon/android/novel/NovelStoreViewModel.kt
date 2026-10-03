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
import org.joinmastodon.android.api.novels.NovelAuthoringApi
import org.joinmastodon.android.api.novels.PublicNovelStoreApi
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.reader.domain.BookFormat
import org.joinmastodon.reader.domain.ReaderBook
import org.joinmastodon.reader.domain.ReaderChapter

data class NovelStoreState(
	val works: List<PublicNovelStoreApi.WorkDto> = emptyList(),
	val nextCursor: String? = null,
	val selectedWork: PublicNovelStoreApi.WorkDto? = null,
	val loading: Boolean = false,
	val error: String? = null,
	val info: String? = null,
	val reader: NovelReaderState? = null,
	val publicReader: PublicReaderState? = null,
)

data class PublicReaderState(val work: PublicNovelStoreApi.WorkDto, val chapters: List<PublicNovelStoreApi.ChapterDto>, val chapterIndex: Int, val reader: NovelReaderState)

internal fun sortedPublicChapters(work: PublicNovelStoreApi.WorkDto): List<PublicNovelStoreApi.ChapterDto> =
	work.volumes.orEmpty().sortedWith(compareBy<PublicNovelStoreApi.VolumeDto> { it.sortOrder }.thenBy { it.id })
		.flatMap { volume -> volume.chapters.orEmpty().sortedWith(compareBy<PublicNovelStoreApi.ChapterDto> { it.sortOrder }.thenBy { it.id }) }

internal fun publicationChangedState(state: NovelStoreState, refreshed: PublicNovelStoreApi.WorkDto): NovelStoreState =
	state.copy(selectedWork = refreshed, reader = null, publicReader = null, error = "作品内容已更新，请从目录重新打开章节")

/**
 * 公开书城进程级内存缓存：列表是最常被重复打开的页面，重新进入书城时
 * 5 分钟内直接复用上次列表，减少对后端数据库的重复读取。
 * 作品详情缓存 3 分钟；章节内容保持实时请求，不经过此缓存。
 */
internal object NovelStoreMemoryCache {
	private const val TTL_MS = 5 * 60 * 1000L
	private const val DETAIL_TTL_MS = 3 * 60 * 1000L
	private var cachedWorks: List<PublicNovelStoreApi.WorkDto> = emptyList()
	private var cachedNextCursor: String? = null
	private var fetchedAt: Long = 0L
	private val workDetails = mutableMapOf<String, Pair<PublicNovelStoreApi.WorkDto, Long>>()

	fun snapshot(): Pair<List<PublicNovelStoreApi.WorkDto>, String?>? {
		if (fetchedAt == 0L || System.currentTimeMillis() - fetchedAt > TTL_MS) return null
		return cachedWorks to cachedNextCursor
	}

	fun store(works: List<PublicNovelStoreApi.WorkDto>, nextCursor: String?) {
		cachedWorks = works
		cachedNextCursor = nextCursor
		fetchedAt = System.currentTimeMillis()
	}

	fun workDetail(id: String): PublicNovelStoreApi.WorkDto? {
		val entry = workDetails[id] ?: return null
		if (System.currentTimeMillis() - entry.second > DETAIL_TTL_MS) return null
		return entry.first
	}

	fun storeWorkDetail(work: PublicNovelStoreApi.WorkDto) {
		if (workDetails.size > 30) workDetails.clear()
		workDetails[work.id] = work to System.currentTimeMillis()
	}
}

class NovelStoreViewModel(application: Application, accountId: String?) : AndroidViewModel(application) {
	private val api = PublicNovelStoreApi()
	private val authoringApi = accountId?.let { AccountSessionManager.getInstance().tryGetAccount(it)?.let(::NovelAuthoringApi) }
	private val mutableState = MutableStateFlow(NovelStoreState())
	val state: StateFlow<NovelStoreState> = mutableState.asStateFlow()

	init { refresh() }

	fun refresh(force: Boolean = false) = load {
		if (!force) {
			val cached = NovelStoreMemoryCache.snapshot()
			if (cached != null) {
				mutableState.update { it.copy(works = cached.first, nextCursor = cached.second) }
				return@load
			}
		}
		val page = api.executeJson(api.newWorksCall(null), PublicNovelStoreApi.WorkListDto::class.java)
		NovelStoreMemoryCache.store(page.items.orEmpty(), page.nextCursor)
		mutableState.update { it.copy(works = page.items.orEmpty(), nextCursor = page.nextCursor) }
	}

	fun loadNextPage() {
		val cursor = mutableState.value.nextCursor ?: return
		load {
			val page = api.executeJson(api.newWorksCall(cursor), PublicNovelStoreApi.WorkListDto::class.java)
			mutableState.update { state ->
				val merged = state.works + page.items.orEmpty().filter { candidate -> state.works.none { it.id == candidate.id } }
				NovelStoreMemoryCache.store(merged, page.nextCursor)
				state.copy(works = merged, nextCursor = page.nextCursor)
			}
		}
	}

	fun openWork(workId: String) = load {
		// 详情走 3 分钟缓存：重复点开同一本书不再重复请求
		NovelStoreMemoryCache.workDetail(workId)?.let { cached ->
			mutableState.update { it.copy(selectedWork = cached) }
			return@load
		}
		val work = api.executeJson(api.newWorkCall(workId), PublicNovelStoreApi.WorkDto::class.java)
		NovelStoreMemoryCache.storeWorkDetail(work)
		mutableState.update { it.copy(selectedWork = work) }
	}

	/** 举报公开作品：登录态必需，幂等键由客户端生成 */
	fun reportWork(workId: String, reason: String) {
		if (mutableState.value.loading) return
		val client = authoringApi ?: run { mutableState.update { it.copy(error = "登录状态已失效，请重新登录后举报") } ; return }
		load {
			client.executeJson(client.newReportWorkCall(workId, reason, "report:${java.util.UUID.randomUUID()}"), NovelAuthoringApi.ReportResultDto::class.java)
			mutableState.update { it.copy(info = "举报已提交，感谢反馈。管理员会尽快处理。") }
		}
	}

	fun closeWork() { mutableState.update { it.copy(selectedWork = null) } }
	fun dismissInfo() { mutableState.update { it.copy(info = null) } }

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
