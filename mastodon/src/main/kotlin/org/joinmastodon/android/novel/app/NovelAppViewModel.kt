package org.joinmastodon.android.novel.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.joinmastodon.android.novel.app.models.*
import org.joinmastodon.android.novel.app.editor.LocalNovelSummary

sealed interface NovelPage {
    data object Store : NovelPage
    data object Shelf : NovelPage
    data object Creator : NovelPage
    data object Search : NovelPage
    data object AllWorks : NovelPage
    data class Detail(val workId: String) : NovelPage
    data class AuthorProfile(val authorId: Long) : NovelPage
    data object Messages : NovelPage
    data class Comments(val workId: String) : NovelPage
    data class Chapters(val workId: String) : NovelPage
    data object Statistics : NovelPage
    data object OwnerWorks : NovelPage
}

data class NovelLoad<T>(val value: T? = null, val loading: Boolean = false, val error: String? = null)
data class NovelAppState(
    val accountId: String = "", val self: Author? = null, val pages: List<NovelPage> = listOf(NovelPage.Store),
    val storeType: String = "all", val sort: String = "featured", val query: String = "", val searchType: String = "all", val searchSort: String = "featured",
    val shelfFilter: String = "all", val creatorFilter: String = "published", val messageCategory: String = "all", val history: List<String> = emptyList(),
    val works: NovelLoad<Page<Work>> = NovelLoad(), val search: NovelLoad<Page<Work>> = NovelLoad(), val allWorks: NovelLoad<Page<Work>> = NovelLoad(),
    val hot: NovelLoad<List<Work>> = NovelLoad(), val shelf: NovelLoad<org.joinmastodon.android.novel.app.models.Shelf> = NovelLoad(),
    val work: NovelLoad<Work> = NovelLoad(), val author: NovelLoad<AuthorPage> = NovelLoad(), val creator: NovelLoad<org.joinmastodon.android.novel.app.models.Creator> = NovelLoad(),
    val stats: NovelLoad<Stats> = NovelLoad(), val ownerWorks: NovelLoad<List<Work>> = NovelLoad(), val localWorks: NovelLoad<List<LocalNovelSummary>> = NovelLoad(), val comments: NovelLoad<Page<Comment>> = NovelLoad(),
    val messages: NovelLoad<org.joinmastodon.android.novel.app.models.Messages> = NovelLoad(),
    val commentDraft: String = "", val pending: Set<String> = emptySet(), val actionError: String? = null, val notice: String? = null,
) {
    val page: NovelPage get() = pages.last()
    val shelfItems: List<Work> get() = shelf.value?.items.orEmpty().filter { when (shelfFilter) { "updates" -> it.hasUpdate; "completed" -> it.progress?.completed == true; else -> true } }
}

/** Every asynchronous completion is fenced by account epoch AND request generation, including mutations. */
class NovelAppViewModel(
    private val repositoryFactory: (String) -> NovelRepository,
    private val historyStore: NovelSearchHistory,
    private val accountIsValid: (String) -> Boolean = { true },
    private val localWorksLoader: suspend (String) -> List<LocalNovelSummary> = { emptyList() },
) : ViewModel() {
    private val mutableState = MutableStateFlow(NovelAppState())
    val state: StateFlow<NovelAppState> = mutableState.asStateFlow()
    private var repository: NovelRepository? = null
    private var epoch = 0L
    private val jobs = mutableMapOf<String, Job>()
    private val versions = mutableMapOf<String, Long>()
    private var commentOperation: Triple<String, String, String>? = null

    fun bindAccount(accountId: String, self: Author? = null, initialWorkId: String? = null) {
        require(accountId.isNotBlank())
        if (state.value.accountId == accountId && repository != null) return
        epoch++
        jobs.values.forEach { it.cancel() }; jobs.clear(); versions.clear(); commentOperation = null
        repository = repositoryFactory(accountId)
        mutableState.value = NovelAppState(accountId = accountId, self = self, history = historyStore.read(accountId), pages = listOfNotNull(NovelPage.Store, initialWorkId?.let(NovelPage::Detail)))
        refresh()
    }

    /** Called when session disappears or the hosting account changes; old responses can no longer publish. */
    fun unbindAccount() {
        epoch++; jobs.values.forEach { it.cancel() }; jobs.clear(); repository = null; commentOperation = null
        mutableState.value = NovelAppState()
    }

    fun navigate(page: NovelPage) {
        if (state.value.page == page) return
        update { copy(pages = if (page in listOf(NovelPage.Store, NovelPage.Shelf, NovelPage.Creator)) listOf(page) else pages + page, actionError = null, notice = null) }
        refresh()
    }
    fun back(): Boolean {
        val pages = state.value.pages
        if (pages.size <= 1) {
            if (pages.last() != NovelPage.Store) { navigate(NovelPage.Store); return true }
            return false
        }
        update { copy(pages = pages.dropLast(1), actionError = null, notice = null) }; refresh(); return true
    }
    fun refresh() {
        if (repository == null) return
        when (val page = state.value.page) {
            NovelPage.Store -> loadStore()
            NovelPage.AllWorks -> loadAll()
            NovelPage.Search -> { loadHot(); if (state.value.query.isNotBlank()) searchNow() }
            NovelPage.Shelf -> loadShelf()
            NovelPage.Creator -> loadCreator()
            is NovelPage.Detail -> loadWork(page.workId)
            is NovelPage.Chapters -> loadWork(page.workId, withComments = false)
            is NovelPage.Comments -> { if (state.value.work.value?.id != page.workId) loadWork(page.workId, false); loadComments(page.workId) }
            is NovelPage.AuthorProfile -> loadAuthor(page.authorId)
            NovelPage.Messages -> loadMessages()
            NovelPage.Statistics -> loadStats()
            NovelPage.OwnerWorks -> loadOwnerWorks()
        }
    }
    fun setStoreType(type: String) { if (state.value.storeType != type) { update { copy(storeType = type) }; loadStore() } }
    fun setSearchType(type: String) { update { copy(searchType = type) }; searchNow() }
    fun setSearchSort(sort: String) { update { copy(searchSort = sort) }; searchNow() }
    fun setAllSort(sort: String) { update { copy(sort = sort) }; loadAll() }
    fun setShelfFilter(filter: String) { update { copy(shelfFilter = filter) } }
    fun setCreatorFilter(filter: String) { update { copy(creatorFilter = filter) } }
    fun setMessageCategory(category: String) { update { copy(messageCategory = category) }; loadMessages() }
    fun setQuery(query: String) {
        update { copy(query = query.take(100), search = NovelLoad()) }
        jobs.remove("search")?.cancel(); versions["search"] = (versions["search"] ?: 0) + 1
        jobs.remove("searchDebounce")?.cancel()
        if (query.isNotBlank()) jobs["searchDebounce"] = viewModelScope.launch { delay(350); searchNow() }
    }
    fun searchNow() {
        jobs.remove("searchDebounce")?.cancel()
        val snapshot = state.value
        if (snapshot.query.isBlank()) return
        historyStore.remember(snapshot.accountId, snapshot.query)
        update { copy(history = historyStore.read(accountId)) }
        load("search", { copy(search = it) }, snapshot.search) { works(snapshot.searchType, snapshot.searchSort, snapshot.query) }
    }
    fun clearHistory() { historyStore.clear(state.value.accountId); update { copy(history = emptyList()) } }
    fun loadMoreWorks(search: Boolean = false, all: Boolean = false) {
        val s = state.value
        val current = when { search -> s.search; all -> s.allWorks; else -> s.works }
        val cursor = current.value?.nextCursor ?: return
        if (current.loading) return
        val previous = current.value.items
        val setter: NovelAppState.(NovelLoad<Page<Work>>) -> NovelAppState = when { search -> { value -> copy(search = value) }; all -> { value -> copy(allWorks = value) }; else -> { value -> copy(works = value) } }
        load(if (search) "search" else if (all) "all" else "store", setter, current) {
            val next = works(if (search) s.searchType else if (all) "all" else s.storeType, if (search) s.searchSort else if (all) s.sort else "featured", if (search) s.query else "", cursor)
            next.copy(items = (previous + next.items).distinctBy { it.id })
        }
    }
    fun openWork(id: String, fromSearch: Boolean = false) {
        if (fromSearch) mutation("click:$id") { searchClick(id) }
        navigate(NovelPage.Detail(id))
    }
    fun collect(work: Work) = mutation("shelf:${work.id}") { publish ->
        val collected = collect(work.id, !work.inShelf)
        publish {
            val updated = work.copy(inShelf = collected, collections = (work.collections + if (collected) 1 else -1).coerceAtLeast(0))
            copy(work = if (this.work.value?.id == work.id) this.work.copy(value = updated) else this.work,
                shelf = shelf.copy(value = shelf.value?.let { old -> old.copy(items = if (collected) (old.items + updated).distinctBy { it.id } else old.items.filterNot { it.id == work.id }, continueWork = old.continueWork?.takeUnless { !collected && it.id == work.id }) }),
                notice = if (collected) "已加入书架" else "已从书架移除")
        }
    }
    fun follow(author: AuthorPage) = mutation("follow:${author.author.id}") { publish ->
        val following = follow(author.author.id, !author.following)
        publish { copy(author = this.author.copy(value = author.copy(following = following, stats = author.stats.copy(followers = (author.stats.followers + if (following) 1 else -1).coerceAtLeast(0))))) }
    }
    fun applyCreator() {
        val current = state.value.creator.value ?: return
        if (current.active || !current.eligible || current.completedConditions != 3) return
        mutation("apply") { publish ->
            val creator = apply()
            publish { copy(creator = NovelLoad(creator), notice = if (creator.active) "创作者身份已开通" else "资格已更新，请检查开通条件") }
        }
    }
    fun setCommentDraft(text: String) { update { copy(commentDraft = text.take(2000)) } }
    fun postComment(workId: String) {
        val body = state.value.commentDraft.trim()
        if (body.isEmpty()) return
        val operation = commentOperation?.takeIf { it.first == workId && it.second == body } ?: Triple(workId, body, UUID.randomUUID().toString()).also { commentOperation = it }
        mutation("comment:$workId") { publish ->
            val comment = comment(workId, body, operation.third)
            publish {
                commentOperation = null
                copy(commentDraft = if (commentDraft.trim() == body) "" else commentDraft,
                comments = NovelLoad(Page((listOf(comment) + comments.value?.items.orEmpty()).distinctBy { it.id }, comments.value?.nextCursor)),
                work = work.copy(value = work.value?.let { if (it.id == workId) it.copy(comments = it.comments + 1) else it }), notice = "评论已发布") }
        }
    }
    fun deleteComment(comment: Comment) {
        if (!comment.canDelete) return
        mutation("delete:${comment.id}") { publish ->
            deleteComment(comment.id)
            publish { copy(comments = comments.copy(value = comments.value?.let { it.copy(items = it.items.filterNot { item -> item.id == comment.id }) }), work = work.copy(value = work.value?.let { it.copy(comments = (it.comments - 1).coerceAtLeast(0)) }), notice = "评论已删除") }
        }
    }
    fun loadMoreComments(workId: String) {
        val current = state.value.comments
        val cursor = current.value?.nextCursor ?: return
        if (current.loading) return
        load("comments", { copy(comments = it) }, current) { comments(workId, cursor).let { it.copy(items = (current.value.items + it.items).distinctBy { c -> c.id }) } }
    }
    fun report(workId: String?, commentId: String?, reason: String) = mutation("report") { publish ->
        if (commentId != null) reportComment(commentId, reason) else if (workId != null) reportWork(workId, reason)
        publish { copy(notice = "举报已提交，等待人工处理") }
    }
    fun markRead(message: Message? = null) = mutation("readMessages") { publish ->
        markRead(message?.let { listOf(it.id) })
        publish { copy(messages = messages.copy(value = messages.value?.let { old -> old.copy(items = old.items.map { if (message == null || it.id == message.id) it.copy(read = true) else it }, unread = if (message == null) 0 else (old.unread - if (message.read) 0 else 1).coerceAtLeast(0)) })) }
    }
    fun loadMoreMessages() {
        val current = state.value.messages
        val cursor = current.value?.nextCursor ?: return
        if (current.loading) return
        val category = state.value.messageCategory
        load("messages", { copy(messages = it) }, current) { messages(category, cursor).let { it.copy(items = (current.value.items + it.items).distinctBy { m -> m.id }) } }
    }
    fun ownerAction(workId: String, action: String) = mutation("owner:$workId") { publish ->
        ownerAction(workId, action)
        publish { loadCreator(); loadOwnerWorks(); copy(notice = "操作已提交") }
    }
    fun dismissNotice() { update { copy(notice = null, actionError = null) } }

    private fun loadStore() { val s = state.value; load("store", { copy(works = it) }, NovelLoad()) { works(s.storeType) } }
    private fun loadAll() { val s = state.value; load("all", { copy(allWorks = it) }, NovelLoad()) { works(sort = s.sort) } }
    private fun loadHot() = load("hot", { copy(hot = it) }, state.value.hot) { hot() }
    private fun loadShelf() = load("shelf", { copy(shelf = it) }, state.value.shelf) { shelf() }
    private fun loadCreator() {
        load("creator", { copy(creator = it) }, state.value.creator) { creator() }
        val account = state.value.accountId
        load("localWorks", { copy(localWorks = it) }, state.value.localWorks) { localWorksLoader(account) }
    }
    private fun loadStats() = load("stats", { copy(stats = it) }, state.value.stats) { stats() }
    private fun loadOwnerWorks() = load("ownerWorks", { copy(ownerWorks = it) }, state.value.ownerWorks) { ownerWorks() }
    private fun loadMessages() { val category = state.value.messageCategory; load("messages", { copy(messages = it) }, NovelLoad()) { messages(category) } }
    private fun loadAuthor(id: Long) = load("author", { copy(author = it) }, NovelLoad()) { author(id) }
    private fun loadComments(id: String) = load("comments", { copy(comments = it) }, NovelLoad()) { comments(id) }
    private fun loadWork(id: String, withComments: Boolean = true) {
        if (state.value.work.value?.id != id) update { copy(work = NovelLoad(), comments = NovelLoad(), author = NovelLoad(), commentDraft = "") }
        load("work", { copy(work = it) }, state.value.work, after = { value -> if (state.value.page == NovelPage.Detail(id)) loadAuthor(value.author.id) }) { work(id) }
        if (withComments) loadComments(id)
    }
    private fun <T> load(key: String, setter: NovelAppState.(NovelLoad<T>) -> NovelAppState, old: NovelLoad<T>, after: ((T) -> Unit)? = null, block: suspend NovelRepository.() -> T) {
        val repo = repository ?: return
        val e = epoch; val account = state.value.accountId
        jobs.remove(key)?.cancel()
        val version = (versions[key] ?: 0) + 1; versions[key] = version
        update { setter(old.copy(loading = true, error = null)) }
        jobs[key] = viewModelScope.launch {
            try { val result = repo.block(); if (valid(e, account, key, version)) { update { setter(NovelLoad(result)) }; after?.invoke(result) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (valid(e, account, key, version)) update {
                    val unavailable = error is NovelAppApiException && error.status in setOf(401, 403, 404)
                    val failed = if (unavailable) NovelLoad<T>(error = error.userMessage()) else old.copy(loading = false, error = error.userMessage())
                    val next = setter(failed)
                    if (unavailable && key == "work") {
                        jobs.remove("author")?.cancel(); versions["author"] = (versions["author"] ?: 0) + 1
                        jobs.remove("comments")?.cancel(); versions["comments"] = (versions["comments"] ?: 0) + 1
                        next.copy(author = NovelLoad(), comments = NovelLoad())
                    } else next
                }
            }
        }
    }
    private fun mutation(key: String, block: suspend NovelRepository.(publish: (NovelAppState.() -> NovelAppState) -> Unit) -> Unit) {
        val repo = repository ?: return
        if (key in state.value.pending) return
        val e = epoch; val account = state.value.accountId
        update { copy(pending = pending + key, actionError = null, notice = null) }
        jobs["action:$key"] = viewModelScope.launch {
            try {
                // Repository calls suspend, so cancellation is checked before any subsequent state update.
                repo.block { change -> if (valid(e, account)) update(change) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (valid(e, account)) update { copy(actionError = error.userMessage()) } }
            finally { if (valid(e, account)) update { copy(pending = pending - key) } }
        }
    }
    private fun valid(e: Long, account: String, key: String? = null, version: Long? = null): Boolean = e == epoch && state.value.accountId == account && accountIsValid(account) && (key == null || versions[key] == version)
    private fun update(transform: NovelAppState.() -> NovelAppState) { mutableState.value = mutableState.value.transform() }
    override fun onCleared() { epoch++; jobs.values.forEach { it.cancel() }; jobs.clear(); super.onCleared() }
}

private fun Exception.userMessage(): String = when (this) {
    is NovelAppApiException -> when (status) {
        401 -> "登录已失效，请重新登录"; 403 -> "当前账号无权进行此操作"; 404 -> "作品已不可见或已被移除"
        409 -> "作品版本已更新，请刷新后重试"; 429 -> "操作过于频繁，请稍后再试"
        else -> message ?: "请求未完成，请重试"
    }
    else -> "连接失败，请检查网络后重试"
}
