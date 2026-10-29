package org.joinmastodon.android.novel.app

import android.content.Context
import java.util.UUID
import org.joinmastodon.android.novel.app.models.*
import org.json.JSONArray
import org.json.JSONObject

/** No public-list endpoint or legacy draft-sync API is used by the rebuilt app. */
class NovelRepository(private val transport: NovelAppTransport) {
    suspend fun works(type: String = "all", sort: String = "featured", query: String = "", cursor: String? = null): Page<Work> = NovelJson.works(
        transport.raw("GET", "/works" + NovelAppApi.query("type" to type, "sort" to sort, "query" to query, "cursor" to cursor, "limit" to "20")))
    suspend fun work(id: String): Work = NovelJson.work(transport.raw("GET", "/works/${id.path()}"))
    suspend fun hot(): List<Work> = NovelJson.works(transport.raw("GET", "/hot")).items
    suspend fun searchClick(id: String) { transport.raw("POST", "/works/${id.path()}/search-click", JSONObject(), operation()) }
    suspend fun shelf(): Shelf = NovelJson.shelf(transport.raw("GET", "/shelf"))
    suspend fun collect(id: String, collected: Boolean): Boolean = transport.raw("POST", "/shelf/${id.path()}", JSONObject().put("collected", collected), operation()).getBoolean("collected")
    suspend fun author(id: Long): AuthorPage = NovelJson.authorPage(transport.raw("GET", "/authors/$id"))
    suspend fun follow(id: Long, following: Boolean): Boolean = transport.raw("POST", "/authors/$id/follow", JSONObject().put("following", following), operation()).getBoolean("following")
    suspend fun creator(): Creator = NovelJson.creator(transport.raw("GET", "/creator"))
    suspend fun apply(): Creator = NovelJson.creator(transport.raw("POST", "/creator/apply", JSONObject(), operation()))
    suspend fun ownerWorks(): List<Work> = NovelJson.works(transport.raw("GET", "/owner/works")).items
    suspend fun stats(): Stats = NovelJson.stats(transport.raw("GET", "/stats"))
    suspend fun comments(id: String, cursor: String? = null): Page<Comment> = NovelJson.comments(transport.raw("GET", "/works/${id.path()}/comments" + NovelAppApi.query("cursor" to cursor, "limit" to "20")))
    suspend fun comment(id: String, body: String, key: String): Comment = NovelJson.comment(transport.raw("POST", "/works/${id.path()}/comments", JSONObject().put("body", body), key))
    suspend fun deleteComment(id: String) { transport.raw("DELETE", "/comments/${id.path()}", JSONObject(), operation()) }
    suspend fun reportWork(id: String, reason: String, chapterId: String? = null) {
        transport.raw("POST", "/works/${id.path()}/report", JSONObject().put("reason", reason).apply { chapterId?.let { put("chapter_id", it) } }, operation())
    }
    suspend fun reportComment(id: String, reason: String) { transport.raw("POST", "/comments/${id.path()}/report", JSONObject().put("reason", reason), operation()) }
    suspend fun messages(category: String = "all", cursor: String? = null): Messages = NovelJson.messages(transport.raw("GET", "/messages" + NovelAppApi.query("category" to category, "cursor" to cursor)))
    suspend fun markRead(ids: List<String>? = null) { transport.raw("POST", "/messages/read", if (ids == null) JSONObject().put("all", true) else JSONObject().put("ids", JSONArray(ids)), operation()) }
    suspend fun ownerAction(id: String, action: String) {
        require(action in listOf("complete", "withdraw", "delete"))
        transport.raw("POST", "/owner/works/${id.path()}/action", JSONObject().put("action", action), operation())
    }
    private fun String.path() = NovelAppApi.segment(this)
    private fun operation() = UUID.randomUUID().toString()
}

interface NovelSearchHistory {
    fun read(accountId: String): List<String>
    fun remember(accountId: String, query: String)
    fun clear(accountId: String)
}

/** Search history stays on-device and is separately keyed for each account. */
class PreferenceNovelSearchHistory(context: Context) : NovelSearchHistory {
    private val preferences = context.getSharedPreferences("novel_app_search", Context.MODE_PRIVATE)
    override fun read(accountId: String): List<String> = runCatching {
        NovelJson.strings(JSONArray(preferences.getString(accountId, "[]"))).take(12)
    }.getOrDefault(emptyList())
    override fun remember(accountId: String, query: String) {
        if (query.isBlank()) return
        preferences.edit().putString(accountId, JSONArray((listOf(query.trim().take(100)) + read(accountId)).distinct().take(12)).toString()).apply()
    }
    override fun clear(accountId: String) { preferences.edit().remove(accountId).apply() }
}
