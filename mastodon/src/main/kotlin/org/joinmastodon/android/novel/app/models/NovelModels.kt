package org.joinmastodon.android.novel.app.models

import org.json.JSONArray
import org.json.JSONObject

/** The app domain contract. IDs are opaque strings, user/author IDs are integers, times Unix seconds. */
data class Author(val id: Long, val username: String, val displayName: String, val avatar: String, val url: String) {
    val name: String get() = displayName.ifBlank { username }
}

data class Stats(
    val works: Long = 0, val followers: Long = 0, val words: Long = 0,
    val reads: Long = 0, val collections: Long = 0, val comments: Long = 0,
    val readDelta: Double? = null, val collectionDelta: Double? = null, val commentDelta: Double? = null,
)

data class Progress(
    val chapterId: String, val offset: Int, val percent: Double, val completed: Boolean,
    val releaseId: String, val updatedAt: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("chapter_id", chapterId).put("offset", offset)
        .put("percent", percent).put("completed", completed).put("release_id", releaseId)
}

data class Chapter(
    val id: String, val title: String, val sortOrder: Long, val wordCount: Long,
    val generation: Long, val bodySha256: String, val status: String,
    val submittedAt: Long? = null, val publishedAt: Long? = null,
    val reviewReason: String? = null, val submissionId: String? = null,
)

data class ChapterBody(
    val id: String, val title: String, val body: String, val bodySha256: String,
    val generation: Long, val releaseId: String?,
)

data class ChapterInput(
    val id: String, val title: String, val sortOrder: Long, val generation: Long,
    val body: String, val bodySha256: String,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("sort_order", sortOrder)
        .put("generation", generation).put("body", body).put("body_sha256", bodySha256)
}

data class Work(
    val id: String, val clientWorkId: String?, val releaseId: String?, val title: String,
    val description: String, val workType: String, val coverUrl: String?, val tags: List<String>,
    val statements: List<String>, val source: String, val authorization: String, val completed: Boolean,
    val status: String, val author: Author, val wordCount: Long, val chapterCount: Int,
    val reads: Long, val collections: Long, val comments: Long, val publishedAt: Long?, val updatedAt: Long,
    val inShelf: Boolean = false, val progress: Progress? = null, val chapters: List<Chapter> = emptyList(),
    val originalAuthor: String = "",
) {
    val displayAuthorName: String get() = if (workType == "repost") originalAuthor.ifBlank { "原作者未填写" } else author.name
    val typeLabel: String get() = when (workType) { "original" -> "原创"; "repost" -> "授权转载"; "ai" -> "AI辅助"; else -> "作品" }
    val statusLabel: String get() = when (status) {
        "draft" -> "本地草稿"; "pending" -> "审核中"; "published" -> if (completed) "已完结" else "连载中"
        "rejected" -> "未通过"; "withdrawn" -> "已下架"; else -> "待确认"
    }
    val hasUpdate: Boolean get() = progress?.let { !it.completed && releaseId != null && it.releaseId != releaseId } ?: false
}

data class Comment(val id: String, val body: String, val author: Author, val createdAt: Long, val canDelete: Boolean)
data class Message(
    val id: String, val category: String, val title: String, val body: String, val workId: String?,
    val chapterId: String?, val createdAt: Long, val read: Boolean,
)
data class Page<T>(val items: List<T>, val nextCursor: String?)
data class Shelf(val items: List<Work>, val continueWork: Work?)
data class AuthorPage(val author: Author, val stats: Stats, val items: List<Work>, val following: Boolean)
data class Creator(
    val active: Boolean, val eligible: Boolean, val babyVerified: Boolean, val accountAgeEligible: Boolean,
    val qualifyingPosts: Int, val requiredPosts: Int, val requiredLikes: Int, val registeredAt: String,
    val works: List<Work>, val stats: Stats,
) {
    val completedConditions: Int get() = listOf(babyVerified, accountAgeEligible, qualifyingPosts >= requiredPosts).count { it }
}
data class Messages(val items: List<Message>, val nextCursor: String?, val unread: Int)

/** JSONObject decoding is intentionally shared with editor/card consumers, without a second JSON adapter stack. */
object NovelJson {
    fun author(j: JSONObject) = Author(j.getLong("id"), j.optString("username"), j.optString("display_name"), j.optString("avatar"), j.optString("url"))
    fun stats(j: JSONObject) = Stats(j.optLong("works"), j.optLong("followers"), j.optLong("words"), j.optLong("reads"), j.optLong("collections"), j.optLong("comments"), j.doubleOrNull("read_delta"), j.doubleOrNull("collection_delta"), j.doubleOrNull("comment_delta"))
    fun progress(j: JSONObject) = Progress(j.getString("chapter_id"), j.optInt("offset"), j.optDouble("percent", 0.0).coerceIn(0.0, 100.0), j.optBoolean("completed"), j.getString("release_id"), j.optLong("updated_at"))
    fun chapter(j: JSONObject) = Chapter(j.getString("id"), j.optString("title"), j.optLong("sort_order"), j.optLong("word_count"), j.optLong("generation"), j.optString("body_sha256"), j.optString("status"), j.longOrNull("submitted_at"), j.longOrNull("published_at"), j.stringOrNull("review_reason"), j.stringOrNull("submission_id"))
    fun chapterBody(j: JSONObject) = ChapterBody(j.getString("id"), j.optString("title"), j.getString("body"), j.getString("body_sha256"), j.getLong("generation"), j.stringOrNull("release_id"))
    fun work(j: JSONObject) = Work(
        j.getString("id"), j.stringOrNull("client_work_id"), j.stringOrNull("release_id"), j.getString("title"),
        j.optString("description"), j.optString("work_type"), j.stringOrNull("cover_url"), strings(j.optJSONArray("tags")),
        strings(j.optJSONArray("statements")), j.optString("source"), j.optString("authorization"), j.optBoolean("completed"),
        j.optString("status"), author(j.getJSONObject("author")), j.optLong("word_count"), j.optInt("chapter_count"),
        j.optLong("reads"), j.optLong("collections"), j.optLong("comments"), j.longOrNull("published_at"), j.optLong("updated_at"),
        j.optBoolean("in_shelf"), j.optJSONObject("progress")?.let(::progress), objects(j.optJSONArray("chapters"), ::chapter).sortedBy { it.sortOrder },
        (j.opt("original_author") as? String).orEmpty(),
    )
    fun comment(j: JSONObject) = Comment(j.getString("id"), j.getString("body"), author(j.getJSONObject("author")), j.getLong("created_at"), j.optBoolean("can_delete"))
    fun message(j: JSONObject) = Message(j.getString("id"), j.getString("category"), j.getString("title"), j.optString("body"), j.stringOrNull("work_id"), j.stringOrNull("chapter_id"), j.getLong("created_at"), j.optBoolean("read"))
    fun works(j: JSONObject) = Page(objects(j.optJSONArray("items"), ::work), j.stringOrNull("next_cursor"))
    fun comments(j: JSONObject) = Page(objects(j.optJSONArray("items"), ::comment), j.stringOrNull("next_cursor"))
    fun shelf(j: JSONObject) = Shelf(objects(j.optJSONArray("items"), ::work), j.optJSONObject("continue_work")?.let(::work))
    fun authorPage(j: JSONObject) = AuthorPage(author(j.getJSONObject("author")), stats(j.getJSONObject("stats")), objects(j.optJSONArray("items"), ::work), j.optBoolean("following"))
    fun creator(j: JSONObject) = Creator(j.optBoolean("active"), j.optBoolean("eligible"), j.optBoolean("baby_verified"), j.optBoolean("account_age_eligible"), j.optInt("qualifying_posts"), j.optInt("required_posts", 2), j.optInt("required_likes", 3), j.optString("registered_at"), objects(j.optJSONArray("works"), ::work), stats(j.optJSONObject("stats") ?: JSONObject()))
    fun messages(j: JSONObject) = Messages(objects(j.optJSONArray("items"), ::message), j.stringOrNull("next_cursor"), j.optInt("unread"))
    fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.opt(it) as? String }
    fun <T> objects(a: JSONArray?, decode: (JSONObject) -> T): List<T> = if (a == null) emptyList() else (0 until a.length()).map { decode(a.getJSONObject(it)) }
}

fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeUnless { it == "null" }
private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else optLong(key)
private fun JSONObject.doubleOrNull(key: String): Double? = if (isNull(key)) null else optDouble(key).takeIf { it.isFinite() }
