package org.joinmastodon.android.novel.app

import org.joinmastodon.android.novel.app.editor.LocalNovelSummary
import org.joinmastodon.android.novel.app.models.*
import org.json.JSONArray
import org.json.JSONObject

/** Test-only, explicitly fictional metadata. No production sample data or substitute chapter bodies. */
internal object NovelAppTestFixtures {
    val author = Author(42, "fixture_mianyun", "棉云（测试）", "", "https://example.test/@fixture_mianyun")
    const val time = 1793189400L
    val titles = listOf("柔软日常", "安心角落", "和自己和解", "周末小屋", "被理解的那天", "慢慢靠近", "云端小屋", "晚安来信", "温柔星球")
    fun work(index: Int = 0, type: String = "original", status: String = "published"): Work = Work(
        id = "fixture-work-$index", clientWorkId = "fixture-local-$index", releaseId = "fixture-release-$index", title = titles[index % titles.size],
        description = "测试用虚构作品简介：成年朋友在平凡日常中学习沟通、尊重彼此的边界。", workType = type, coverUrl = null,
        tags = listOf("18+", "成年角色", "社群上传"), statements = listOf("内容不适宜未成年人阅读", "虚构内容，请勿当真"), source = if (type == "repost") "测试授权来源" else "", authorization = if (type == "repost") "测试授权说明" else "", completed = index == 1,
        status = status, author = author, wordCount = 32000 + index * 1500L, chapterCount = 13,
        reads = 1028 + index * 100L, collections = 68, comments = 24, publishedAt = time - 86400, updatedAt = time - index * 3600,
        chapters = listOf(Chapter("fixture-chapter-1", "第 1 章 · 属于自己的角落", 0, 2200, 1, "test-sha256", "published"), Chapter("fixture-chapter-13", "第 13 章 · 留给自己的空间", 12, 2400, 1, "test-sha256-last", "published")),
    )
    val stats = Stats(2, 68, 80000, 1286, 86, 24, 12.0, 8.0, 6.0)
    val local = LocalNovelSummary("fixture-local-only", null, "仅在本机的故事", time * 1000, 2, 3400)
    fun creator(active: Boolean = true, eligible: Boolean = true) = Creator(active, eligible, eligible, true, if (eligible) 2 else 1, 2, 3, "2026-10-23T09:41:00Z", listOf(work(), work(1), work(2, status = "pending")), stats)
    val comment = Comment("fixture-comment", "测试评论：喜欢这个故事平静的叙述。", author.copy(id = 43, displayName = "小岛（测试）"), time - 86400, false)
    val messages = Messages(listOf(
        Message("fixture-message-update", "updates", "收藏的作品更新了", "测试消息：已关注的作者发布了新章节，接着上次的故事读下去。", "fixture-work-0", "fixture-chapter-13", time, false),
        Message("fixture-message-review", "system", "章节审核已通过", "测试消息：章节已公开展示，读者现在可以阅读。", "fixture-work-0", "fixture-chapter-13", time - 3600, false),
        Message("fixture-message-interaction", "interactions", "读者回复了你", "测试消息：来聊聊这个故事。", "fixture-work-0", null, time - 86400, true),
    ), null, 2)
    fun state(page: NovelPage = NovelPage.Store): NovelAppState {
        val shelfWorks = (0..5).map { work(it).copy(inShelf = true, progress = when (it) {
            0 -> Progress("fixture-chapter-1", 80, 36.0, false, "older-release", time - 7200)
            1 -> Progress("fixture-chapter-1", 25, 20.0, false, "older-release", time - 8000)
            4 -> Progress("fixture-chapter-13", 999, 100.0, true, "fixture-release-4", time - 86400)
            else -> null
        }) }
        return NovelAppState(accountId = "novel-fixture", self = author, pages = if (page in listOf(NovelPage.Store, NovelPage.Shelf, NovelPage.Creator)) listOf(page) else listOf(NovelPage.Store, page),
            works = NovelLoad(Page(listOf(work(), work(1), work(5, "repost"), work(6, "ai")), null)),
            allWorks = NovelLoad(Page((0..5).map { work(it, if (it == 5) "repost" else "original") }, null)),
            search = NovelLoad(Page(listOf(work(), work(3, "ai"), work(7, "repost")), null)), hot = NovelLoad((0..4).map { work(it) }),
            history = listOf("柔软日常", "棉云", "安心角落"), work = NovelLoad(work()), shelf = NovelLoad(Shelf(shelfWorks, shelfWorks.first())),
            author = NovelLoad(AuthorPage(author, stats, listOf(work(), work(1)), false)), creator = NovelLoad(creator()), stats = NovelLoad(stats), ownerWorks = NovelLoad(creator().works), localWorks = NovelLoad(listOf(local)),
            comments = NovelLoad(Page(listOf(comment), null)), messages = NovelLoad(messages))
    }
    fun workJson(value: Work = work()): JSONObject = JSONObject()
        .put("id", value.id).put("client_work_id", value.clientWorkId).put("release_id", value.releaseId).put("title", value.title)
        .put("description", value.description).put("work_type", value.workType).put("cover_url", value.coverUrl ?: JSONObject.NULL)
        .put("tags", JSONArray(value.tags)).put("statements", JSONArray(value.statements)).put("source", value.source).put("authorization", value.authorization)
        .put("completed", value.completed).put("status", value.status).put("author", authorJson(value.author)).put("word_count", value.wordCount)
        .put("chapter_count", value.chapterCount).put("reads", value.reads).put("collections", value.collections).put("comments", value.comments)
        .put("published_at", value.publishedAt).put("updated_at", value.updatedAt).put("in_shelf", value.inShelf)
        .put("chapters", JSONArray(value.chapters.map { chapter -> JSONObject().put("id", chapter.id).put("title", chapter.title).put("sort_order", chapter.sortOrder).put("word_count", chapter.wordCount).put("generation", chapter.generation).put("body_sha256", chapter.bodySha256).put("status", chapter.status) }))
    fun authorJson(value: Author = author) = JSONObject().put("id", value.id).put("username", value.username).put("display_name", value.displayName).put("avatar", value.avatar).put("url", value.url)
    fun creatorJson(active: Boolean = false, eligible: Boolean = true): JSONObject = JSONObject().put("active", active).put("eligible", eligible).put("baby_verified", eligible)
        .put("account_age_eligible", true).put("qualifying_posts", if (eligible) 2 else 1).put("required_posts", 2).put("required_likes", 3).put("registered_at", "2026-10-23T09:41:00Z").put("works", JSONArray()).put("stats", JSONObject())
    fun page(vararg works: Work, cursor: String? = null) = JSONObject().put("items", JSONArray(works.map(::workJson))).put("next_cursor", cursor ?: JSONObject.NULL)
}

internal class MemoryNovelSearchHistory : NovelSearchHistory {
    private val entries = mutableMapOf<String, List<String>>()
    override fun read(accountId: String) = entries[accountId].orEmpty()
    override fun remember(accountId: String, query: String) { entries[accountId] = (listOf(query) + read(accountId)).distinct() }
    override fun clear(accountId: String) { entries.remove(accountId) }
}

internal class TestNovelTransport(var response: suspend (String, String, JSONObject?, String?) -> JSONObject = { _, path, _, _ ->
    when {
        path == "/creator" -> NovelAppTestFixtures.creatorJson()
        path == "/shelf" -> NovelAppTestFixtures.page().put("continue_work", JSONObject.NULL)
        path == "/stats" -> JSONObject()
        else -> NovelAppTestFixtures.page()
    }
}) : NovelAppTransport {
    data class Request(val method: String, val path: String, val body: JSONObject?, val key: String?)
    val requests = mutableListOf<Request>()
    override suspend fun raw(method: String, path: String, body: JSONObject?, idempotencyKey: String?): JSONObject {
        requests += Request(method, path, body?.let { JSONObject(it.toString()) }, idempotencyKey)
        return response(method, path, body, idempotencyKey)
    }
}
