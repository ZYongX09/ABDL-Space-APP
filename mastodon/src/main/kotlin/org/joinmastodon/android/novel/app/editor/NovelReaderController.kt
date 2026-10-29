package org.joinmastodon.android.novel.app.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.joinmastodon.android.novel.NovelAccountDataCleaner
import org.joinmastodon.android.novel.app.NovelAppApi
import org.joinmastodon.android.novel.app.NovelAppApiException
import org.joinmastodon.android.novel.sync.NovelSyncWorker
import org.joinmastodon.android.novel.sync.NovelSyncWriteFacade
import org.joinmastodon.reader.data.*
import org.joinmastodon.reader.domain.*
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ReaderHeader(val id: String, val title: String, val length: Int = 0)
internal data class ReaderAnnotation(val id: String, val chapterId: String, val page: Int, val note: String?)
internal data class ReaderHostState(
    val book: ReaderBook? = null, val headers: List<ReaderHeader> = emptyList(), val chapter: ReaderChapter? = null,
    val index: Int = 0, val page: Int = 0, val release: String? = null, val loading: Boolean = true,
    val error: String? = null, val changed: Boolean = false, val notice: String? = null,
    val annotations: List<ReaderAnnotation> = emptyList(),
)

/** Thin data adapter only: content rendering, typography and reader controls stay in reader-core. */
internal class NovelReaderController(application: Application, val repository: NovelEditorRepository,
    private val publicId: String?, private val localId: String?, private val privateId: String?,
) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(ReaderHostState()); val state = mutable.asStateFlow()
    private val writes = Mutex()
    private var load: Job? = null
    private var openToken = 0
    private val readingId get() = when { localId != null -> "local:$localId"; privateId != null -> "private:$privateId"; else -> "public:${requireNotNull(publicId)}" }
    init { require(listOfNotNull(publicId, localId, privateId).size == 1) { "Reader requires exactly one source" } }
    private fun update(block: (ReaderHostState) -> ReaderHostState) { mutable.value = block(mutable.value) }
    fun start(requestedChapter: String? = null) {
        load?.cancel(); load = viewModelScope.launch {
            update { it.copy(loading = true, error = null, changed = false, chapter = null) }
            try {
                val mark = repository.local { it.readingMark(readingId) }
                var remoteMark: JSONObject? = null
                val directory: Pair<ReaderBook, List<ReaderHeader>> = when {
                    localId != null -> repository.local { s -> val structure = s.structure(localId); ReaderBook(readingId, structure.work().title(), format = BookFormat.TXT) to structure.chapters().map { ReaderHeader(it.id(), it.title(), it.contentLength()) } }
                    privateId != null -> privateDatabase { db -> val book = requireNotNull(db.novelBookDao().getById(repository.accountId, privateId)); require(book.deletedAt == null) { "此私人作品已移除" }; if (mark == null) { val saved = db.syncDao().progress(repository.accountId, "progress:$privateId")?.payload?.let(::JSONObject); if (saved != null) remoteMark = saved }; ReaderBook(readingId, book.title, book.author, BookFormat.TXT) to db.novelChapterDao().getHeadersByBookId(privateId).map { ReaderHeader(it.id, it.title, it.contentLength) } }
                    else -> {
                        val work = repository.api.raw("GET", "/works/${NovelAppApi.segment(requireNotNull(publicId))}")
                        val release = requireNotNull(work.stringOrNull("release_id")) { "作品暂无可阅读的公开版本" }
                        update { it.copy(release = release) }
                        remoteMark = work.optJSONObject("progress")
                        if (remoteMark == null) runCatching { repository.api.raw("GET", "/progress/${NovelAppApi.segment(publicId)}").optJSONObject("progress") }.onSuccess { remoteMark = it }.onFailure { if (it is CancellationException) throw it }
                        val rows = work.optJSONArray("chapters")
                        ReaderBook(readingId, work.getString("title"), work.optJSONObject("author")?.optString("display_name"), BookFormat.TXT) to (0 until (rows?.length() ?: 0)).map { i -> val row = rows!!.getJSONObject(i); ReaderHeader(row.getString("id"), row.getString("title"), row.optInt("word_count")) }
                    }
                }
                require(directory.second.isNotEmpty()) { "这本作品暂无章节" }
                val markChapter = mark?.chapterId() ?: if (privateId != null) directory.second.getOrNull(remoteMark?.optInt("chapterIndex", -1) ?: -1)?.id else remoteMark?.optString("chapter_id")
                val chapter = requestedChapter ?: markChapter
                val index = directory.second.indexOfFirst { it.id == chapter }.coerceAtLeast(0)
                val resume = if (directory.second[index].id == markChapter && requestedChapter == null) (mark?.page() ?: remoteMark?.optInt(if (privateId != null) "pageIndex" else "offset", 0) ?: 0) else 0
                val oldRelease = mark?.releaseId() ?: remoteMark?.stringOrNull("release_id")
                val changed = publicId != null && oldRelease != null && oldRelease != mutable.value.release
                update { it.copy(book = directory.first, headers = directory.second, index = index, page = resume, notice = if (changed) "作品已更新，已按稳定章节 ID 恢复位置；正文分页可能变化。" else null) }
                openNow(index, resume)
                refreshAnnotations()
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { fail(error) }
        }
    }
    fun open(index: Int, page: Int = 0) {
        if (index !in mutable.value.headers.indices) return
        val previous = mutable.value
        load?.cancel(); val token = ++openToken
        load = viewModelScope.launch { try { if (persistPosition(previous, previous.page) && token == openToken) openNow(index, page) } catch (cancel: CancellationException) { throw cancel } catch (e: Exception) { fail(e) } }
    }
    private suspend fun openNow(index: Int, page: Int) {
        val snapshot = mutable.value; val header = snapshot.headers[index]
        update { it.copy(loading = true, chapter = null, error = null, index = index, page = page) }
        val body = when {
            localId != null -> repository.local { s -> requireNotNull(s.getChapter(header.id)).also { require(it.workId() == localId) }; requireNotNull(s.loadDraft(header.id)).content() }
            privateId != null -> privateDatabase { db ->
                val row = db.novelChapterDao().getHeadersByBookId(privateId).firstOrNull { it.id == header.id } ?: error("本地章节已移除")
                require(row.contentLength <= 5_000_000) { "此本地章节过长，请先拆分后阅读" }
                buildString(row.contentLength) { var offset = 1; while (offset <= row.contentLength) { val chunk = db.novelChapterDao().getContentChunk(header.id, offset, NovelChapterDao.CHAPTER_CURSOR_CHUNK_SIZE).orEmpty(); if (chunk.isEmpty()) break; append(chunk); offset += chunk.codePointCount(0, chunk.length) } }
            }
            else -> {
                val loaded = repository.api.raw("GET", "/works/${NovelAppApi.segment(requireNotNull(publicId))}/chapters/${NovelAppApi.segment(header.id)}${NovelAppApi.query("release_id" to snapshot.release)}")
                require(loaded.getString("release_id") == snapshot.release) { "公开版本已变化，请重新加载" }
                val text = loaded.getString("body")
                loaded.stringOrNull("body_sha256")?.let { require(org.joinmastodon.noveleditor.EditorText.sha256(text) == it) { "章节校验失败，请重试" } }
                text
            }
        }
        update { it.copy(chapter = ReaderChapter(header.id, readingId, 0, header.title, body, header.id), loading = false, page = page) }
        if (publicId != null) viewModelScope.launch { try { repository.api.raw("POST", "/works/${NovelAppApi.segment(publicId)}/read", JSONObject().put("chapter_id", header.id).put("release_id", snapshot.release), UUID.randomUUID().toString()) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { /* A counter failure must not hide content. */ } }
    }
    private fun fail(e: Exception) {
        val unavailable = e is NovelAppApiException && e.status in listOf(401, 403, 404)
        val changed = e is NovelAppApiException && e.status == 409
        update { it.copy(loading = false, chapter = null, changed = changed, error = when { changed -> "作品版本已变化，请重新加载目录。阅读进度与笔记仍保留。"; unavailable -> "作品已不可用或当前账号无权阅读。不会展示旧正文。"; else -> e.message ?: "无法加载章节，请重试" }) }
    }
    fun position(position: ReaderPosition) { update { it.copy(page = position.pageIndex.coerceAtLeast(0)) }; savePosition(position.pageIndex) }
    fun savePosition(page: Int = mutable.value.page) { val snapshot = mutable.value; viewModelScope.launch { persistPosition(snapshot, page) } }
    fun leave(after: () -> Unit) { viewModelScope.launch { if (persistPosition(mutable.value, mutable.value.page)) after() } }
    private suspend fun persistPosition(snapshot: ReaderHostState, page: Int): Boolean = writes.withLock {
        val header = snapshot.headers.getOrNull(snapshot.index) ?: return@withLock true
        if (snapshot.chapter == null) return@withLock true
        var localSaved = false
            try {
                repository.local { it.putReadingMark(readingId, header.id, page, snapshot.release) }
                localSaved = true
                when {
                    privateId != null -> privateDatabase { db ->
                        val book = requireNotNull(db.novelBookDao().getById(repository.accountId, privateId))
                        if (book.remoteId != null) NovelSyncWriteFacade(repository.accountId, db, { NovelSyncWorker.enqueue(getApplication(), repository.accountId) }).saveProgress(privateId, snapshot.index, page)
                        else db.syncDao().upsertProgress(NovelProgressEntity("progress:$privateId", repository.accountId, privateId, JSONObject().put("chapterIndex", snapshot.index).put("pageIndex", page).toString(), System.currentTimeMillis(), null))
                    }
                    publicId != null -> {
                        // ReaderScreen exposes page index, not measured text offsets. Persist that same unit on both sides.
                        val progress = JSONObject().put("chapter_id", header.id).put("offset", page.coerceAtLeast(0)).put("percent", 100.0 * snapshot.index / snapshot.headers.size).put("completed", false).put("release_id", snapshot.release)
                        repository.api.raw("PUT", "/progress/${NovelAppApi.segment(publicId)}", progress, UUID.randomUUID().toString())
                    }
                }
                true
            } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { update { it.copy(notice = if (localSaved) "阅读位置已保留本机；云端同步失败可稍后重试" else "本机阅读位置保存失败，请重试后再离开") }; localSaved }
    }
    fun annotation(note: String? = null) {
        val snapshot = mutable.value; val header = snapshot.headers.getOrNull(snapshot.index) ?: return
        if (snapshot.chapter == null || snapshot.loading || snapshot.error != null) return
        viewModelScope.launch { try {
            if (privateId == null) repository.local { it.addReadingAnnotation(readingId, header.id, snapshot.page, note) }
            else privateDatabase { db ->
                val book = requireNotNull(db.novelBookDao().getById(repository.accountId, privateId)); val id = UUID.randomUUID().toString()
                if (book.remoteId != null) {
                    val facade = NovelSyncWriteFacade(repository.accountId, db, { NovelSyncWorker.enqueue(getApplication(), repository.accountId) })
                    if (note == null) facade.saveBookmark(id, privateId, header.id, snapshot.page) else facade.saveAnnotation(id, privateId, header.id, snapshot.page, snapshot.page, "", note)
                } else if (note == null) db.bookmarkDao().applyRemote(BookmarkEntity(id, repository.accountId, privateId, header.id, snapshot.page))
                else db.annotationDao().applyRemote(AnnotationEntity(id, repository.accountId, privateId, header.id, snapshot.page, snapshot.page, "", note))
            }
            refreshAnnotations(); update { it.copy(notice = if (note == null) "书签已保存" else "笔记已保存") }
        } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) { update { it.copy(notice = "保存书签/笔记失败，请重试") } } }
    }
    private suspend fun refreshAnnotations() {
        val annotations = if (privateId == null) repository.local { s -> s.readingAnnotations(readingId).map { ReaderAnnotation(it.id(), it.chapterId(), it.page(), it.note()) } }
        else privateDatabase { db -> db.bookmarkDao().getByBookId(repository.accountId, privateId).map { ReaderAnnotation(it.id, it.chapterId, it.position, null) } + db.annotationDao().getByBookId(repository.accountId, privateId).map { ReaderAnnotation(it.id, it.chapterId, it.startOffset, it.note) } }
        update { it.copy(annotations = annotations) }
    }
    fun clearNotice() = update { it.copy(notice = null) }
    private suspend fun <T> privateDatabase(block: suspend (NovelDatabase) -> T): T = kotlinx.coroutines.withContext(repository.io) {
        val lease = checkNotNull(NovelAccountDataCleaner.enterOperation(repository.accountId, repository.generation)) { "账号已注销" }
        lease.use { val db = NovelDatabase.open(getApplication(), repository.accountId); try { block(db) } finally { db.close() } }
    }
}
