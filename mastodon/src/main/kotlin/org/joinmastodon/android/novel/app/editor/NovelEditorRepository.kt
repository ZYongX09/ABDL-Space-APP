package org.joinmastodon.android.novel.app.editor

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.joinmastodon.android.novel.NovelAccountDataCleaner
import org.joinmastodon.android.novel.app.NovelAppApi
import org.joinmastodon.android.novel.app.NovelAppTransport
import org.joinmastodon.noveleditor.EditorModels
import org.joinmastodon.noveleditor.NovelEditorStorage
import java.nio.charset.StandardCharsets
import java.util.UUID

internal data class NovelMetadata(
    val title: String = "", val description: String = "", val type: String = "original",
    val coverUrl: String? = null, val localCover: String? = null, val tags: List<String> = emptyList(),
    val statements: List<String> = emptyList(), val source: String = "", val authorization: String = "",
    val completed: Boolean = false, val accepted: Boolean = false,
) {
    fun validate() {
        require(title.isNotBlank() && title.length <= 30) { "作品名称需为 1–30 字" }
        require(description.isNotBlank() && description.length <= 500) { "作品简介需为 1–500 字" }
        require(type in setOf("original", "repost", "ai")) { "请选择作品类型" }
        require(tags.size <= 3 && tags.all { it.isNotBlank() && it.length <= 30 }) { "最多 3 个标签，每个不超过 30 字" }
        require(type != "repost" || (source.isNotBlank() && authorization.isNotBlank())) { "转载需填写来源及授权说明" }
        require(source.length <= 2000 && authorization.length <= 2000) { "来源及授权说明过长" }
        require(accepted) { "请确认创作与发布规范" }
    }
    fun json() = JSONObject().put("title", title).put("description", description).put("work_type", type)
        .put("cover_url", coverUrl ?: JSONObject.NULL).put("local_cover", localCover ?: JSONObject.NULL)
        .put("tags", JSONArray(tags)).put("statements", JSONArray(statements)).put("source", source)
        .put("authorization", authorization).put("completed", completed).put("accepted", accepted)
    companion object {
        fun from(j: JSONObject) = NovelMetadata(j.optString("title"), j.optString("description"), j.optString("work_type", "original"),
            j.stringOrNull("cover_url"), j.stringOrNull("local_cover"), j.strings("tags"), j.strings("statements"),
            j.optString("source"), j.optString("authorization"), j.optBoolean("completed"), j.optBoolean("accepted", false))
    }
}
internal fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
internal fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
internal data class EditorDirectory(val structure: EditorModels.WorkStructure, val metadata: NovelMetadata, val remote: Map<String, NovelEditorStorage.RemoteChapter>, val server: JSONObject? = null, val conflict: String? = null)
internal class NovelOwnerConflict(message: String) : IllegalStateException(message)
internal class NovelReviewInProgress : IllegalStateException("此作品已有上传或审核中的提交。可刷新审核状态，或重试原冻结提交；本机草稿仍保留。")

/** Account-leased local IO. No mutation here schedules an uploader or a sync worker. */
internal class NovelEditorRepository(val context: Context, val accountId: String, val api: NovelAppTransport,
    internal val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    internal val generation = NovelAccountDataCleaner.captureGeneration(accountId)
    suspend fun <T> local(block: (NovelEditorStorage) -> T): T = withContext(io) {
        val lease = checkNotNull(NovelAccountDataCleaner.enterOperation(accountId, generation)) { "账号已注销" }
        lease.use { NovelEditorStorage(context, accountId).use(block) }
    }
    internal fun requireActive() {
        check(NovelAccountDataCleaner.isGenerationValid(accountId, generation)) { "账号已注销" }
        (api as? NovelAppApi)?.requireSessionActive()
    }
    suspend fun directory(id: String): EditorDirectory = local { s ->
        val structure = s.structure(id); val stored = s.appMetadata(id)
        EditorDirectory(structure, stored?.let { NovelMetadata.from(JSONObject(it.payload())) }
            ?: NovelMetadata(title = structure.work().title(), description = structure.work().description()),
            structure.chapters().associate { it.id() to s.remoteChapter(it.id()) },
            stored?.ownerPayload()?.let(::JSONObject), stored?.ownerConflict())
    }
    suspend fun create(metadata: NovelMetadata): String {
        metadata.validate()
        return local { s -> s.createWork(metadata.title, metadata.description, "fiction").workId().also { s.putAppMetadata(it, metadata.json().toString(), null, null) } }
    }
    suspend fun saveMetadata(id: String, metadata: NovelMetadata) { metadata.validate(); local { s ->
        val old = s.appMetadata(id)
        s.writableDatabase.beginTransaction()
        try { s.updateWorkInfo(id, metadata.title, metadata.description); s.putAppMetadata(id, metadata.json().toString(), old?.serverId(), old?.releaseId()); s.writableDatabase.setTransactionSuccessful() }
        finally { s.writableDatabase.endTransaction() }
    } }
    /** Mirror owner headers/status only. Never replace a local body, title, cursor or metadata draft. */
    suspend fun ownerWork(serverId: String): Pair<String, JSONObject> {
        requireActive()
        val remote = api.raw("GET", "/owner/works/${NovelAppApi.segment(serverId)}")
        requireActive()
        require(remote.getString("id") == serverId) { "服务端作品绑定不匹配；本机文稿未改动" }
        val clientId = requireNotNull(remote.stringOrNull("client_work_id")) { "服务端未返回作品绑定，请刷新后重试" }
        val id = local { s ->
            s.writableDatabase.beginTransaction()
            try {
                val existing = s.localWorkForServer(serverId)
                val work = existing ?: s.createWork(remote.getString("title"), remote.optString("description"), "fiction").workId()
                val old = s.appMetadata(work)
                val release = remote.stringOrNull("release_id")
                val reviews = remote.optJSONArray("reviews") ?: JSONArray()
                val knownRelease = release != null && s.localAppSubmissionForServer(work, release) != null
                var conflict = old?.ownerConflict()
                if (existing != null && old?.releaseId() != release && !knownRelease) conflict = "远端公开版本已变化。本机正文与作品资料均已保留；请检查后明确保留本机稿并重新选择提交章节。"
                val previous = old?.ownerPayload()?.let(::JSONObject)
                if (existing != null && previous != null && ownerContent(previous) != ownerContent(remote)) {
                    val latest = remote.optJSONArray("chapters")?.optJSONObject(0)?.stringOrNull("submission_id")
                        ?: reviews.optJSONObject(0)?.stringOrNull("id")
                    if (latest == null || s.localAppSubmissionForServer(work, latest) == null) conflict = conflict ?: "远端投稿内容已变化。本机草稿未覆盖，请检查后明确保留本机稿并重新选章。"
                }
                val metadata = old?.let { NovelMetadata.from(JSONObject(it.payload())) } ?: NovelMetadata.from(remote).copy(accepted = true)
                s.putAppMetadata(work, metadata.json().toString(), serverId, release)
                s.putOwnerState(work, clientId, remote.toString(), conflict)
                val chapters = remote.optJSONArray("chapters") ?: JSONArray()
                val headers = linkedMapOf<String, JSONObject>()
                for (i in 0 until chapters.length()) { val c = chapters.getJSONObject(i); headers[c.getString("id")] = c }
                for (i in 0 until reviews.length()) {
                    val review = reviews.getJSONObject(i); val reviewId = review.getString("id")
                    val local = s.localAppSubmissionForServer(work, reviewId)
                    if (local != null && review.optString("status") != "uploading") s.updateAppSubmission(local, reviewId, review.getString("status"), review.toString())
                    val rows = review.optJSONArray("chapters") ?: JSONArray()
                    for (n in 0 until rows.length()) {
                        val c = rows.getJSONObject(n); val chapterId = c.getString("id")
                        if (chapterId !in headers) headers[chapterId] = c
                        else if (headers[chapterId]?.stringOrNull("submission_id") == reviewId && c.stringOrNull("review_reason") != null) headers[chapterId] = c
                    }
                }
                headers.values.forEachIndexed { i, c ->
                    val status = if (c.optString("status") == "approved") "published" else c.optString("status", "published")
                    s.ensureRemoteChapter(work, c.getString("id"), c.getString("title"), c.optInt("sort_order", i), c.optLong("generation", 1), status, c.stringOrNull("submission_id"), c.stringOrNull("review_reason"))
                }
                // Only a newly mirrored work's empty placeholder is removed, never an existing private chapter.
                if (existing == null && headers.isNotEmpty()) s.structure(work).chapters().filter { it.id() !in headers }.forEach { s.softDeleteChapter(it.id()) }
                s.writableDatabase.setTransactionSuccessful()
                work
            } finally { s.writableDatabase.endTransaction() }
        }
        return id to remote
    }
    private fun ownerContent(owner: JSONObject): String {
        val metadata = NovelMetadata.from(owner).copy(accepted = false, localCover = null).json()
        val chapters = owner.optJSONArray("chapters") ?: JSONArray()
        return metadata.toString() + (0 until chapters.length()).map { i -> val c = chapters.getJSONObject(i); listOf(c.optString("id"), c.optString("title"), c.optInt("sort_order"), c.optLong("generation"), c.optString("body_sha256")) }.toString()
    }
    suspend fun refreshOwner(workId: String): EditorDirectory {
        val serverId = local { it.appMetadata(workId)?.serverId() }
        if (serverId != null) { val (bound, _) = ownerWork(serverId); check(bound == workId) { "作品绑定变化；本机文稿仍保留" } }
        return directory(workId)
    }
    suspend fun acknowledgeOwner(workId: String, expected: String) {
        val previous = JSONObject(expected)
        val refreshed = requireNotNull(refreshOwner(workId).server)
        check(previous.stringOrNull("release_id") == refreshed.stringOrNull("release_id") && ownerContent(previous) == ownerContent(refreshed)) { "远端内容再次变化，请检查刷新后的状态再确认；本机稿仍保留。" }
        local { it.acknowledgeOwnerConflict(workId, refreshed.toString()) }
    }
    suspend fun hydrate(workId: String, chapterId: String) {
        val info = local { s ->
            val c = requireNotNull(s.getChapter(chapterId)); require(c.workId() == workId)
            if (c.state() != "REMOTE_STUB") null else Triple(requireNotNull(s.appMetadata(workId)?.serverId()), s.remoteChapter(chapterId), c)
        } ?: return
        val query = NovelAppApi.query("submission_id" to info.second.submissionId())
        val result = api.raw("GET", "/owner/works/${NovelAppApi.segment(info.first)}/chapters/${NovelAppApi.segment(chapterId)}$query")
        local { s -> s.hydrateRemoteChapter(chapterId, result.getString("body"), result.optLong("generation", info.second.generation())) }
    }
    suspend fun prepare(workId: String, selected: List<String>): String {
        require(selected.isNotEmpty()) { "请选择要提交的章节" }
        val directory = refreshOwner(workId)
        directory.conflict?.let { throw NovelOwnerConflict(it) }
        val reviews = directory.server?.optJSONArray("reviews") ?: JSONArray()
        if ((0 until reviews.length()).any { reviews.getJSONObject(it).optString("status") in setOf("uploading", "pending", "reviewing") }) throw NovelReviewInProgress()
        check(local { it.pendingAppSubmission(workId) } == null) { "已有未完成的冻结提交，请重试或明确放弃；本机新修改不会混入重试。" }
        selected.forEach { hydrate(workId, it) }
        return local { s ->
            val meta = requireNotNull(s.appMetadata(workId)) { "请先完善作品资料" }
            meta.ownerConflict()?.let { throw NovelOwnerConflict(it) }
            val metadata = NovelMetadata.from(JSONObject(meta.payload())); metadata.validate()
            require(metadata.localCover == null || metadata.coverUrl != null) { "请先明确上传所选封面，或移除本地封面" }
            val clientId = if (meta.serverId() == null) workId else requireNotNull(meta.clientWorkId()) { "请先刷新服务端作品绑定" }
            val payload = metadata.json().apply { remove("accepted"); remove("local_cover"); put("client_work_id", clientId); put("base_release_id", meta.releaseId() ?: JSONObject.NULL); meta.serverId()?.let { put("work_id", it) } }
            s.prepareAppSubmission(workId, payload.toString(), selected)
        }
    }
}

/** Explicit, retriable staged review submission. Frozen rows and deterministic UUIDs survive process death. */
internal class NovelSubmissionSender(private val repository: NovelEditorRepository) {
    private fun key(id: String, stage: String) = UUID.nameUUIDFromBytes("$id:$stage".toByteArray(StandardCharsets.UTF_8)).toString()
    private fun json(c: NovelEditorStorage.SubmissionChapter) = JSONObject().put("id", c.id()).put("title", c.title()).put("sort_order", c.sortOrder()).put("generation", c.generation()).put("body", c.body()).put("body_sha256", c.sha256())
    private fun bytes(rows: JSONArray) = JSONObject().put("chapters", rows).toString().toByteArray(StandardCharsets.UTF_8).size
    /** Run before reserving the server's uploading slot, including JSON escaping expansion. */
    private suspend fun preflight(snapshot: NovelEditorStorage.AppSubmission): List<String> {
        val ids = repository.local { it.appSubmissionChapterIds(snapshot.id()) }
        require(ids.isNotEmpty()) { "请选择要提交的章节" }
        require(snapshot.metadata().toByteArray(StandardCharsets.UTF_8).size <= NovelAppApi.MAX_REQUEST_BYTES) { "作品资料过大" }
        var total = 0L; var rows = JSONArray()
        for (index in ids.indices) {
            val c = repository.local { it.appSubmissionChapter(snapshot.id(), index) }
            total += c.body().toByteArray(StandardCharsets.UTF_8).size
            require(total <= 20L * 1024 * 1024) { "所选正文超过 20 MiB，请减少所选章节" }
            val value = json(c)
            require(bytes(JSONArray().put(value)) <= NovelAppApi.MAX_REQUEST_BYTES) { "此章节 JSON 超过 2 MiB，请拆分后明确重新提交" }
            if (rows.length() == 40 || bytes(JSONArray(rows.toString()).put(value)) > NovelAppApi.MAX_REQUEST_BYTES - 1024) rows = JSONArray()
            rows.put(value)
            require(bytes(rows) <= NovelAppApi.MAX_REQUEST_BYTES) { "所选章节编码过大" }
        }
        return ids
    }
    private suspend fun start(snapshot: NovelEditorStorage.AppSubmission): String {
        snapshot.serverSubmissionId()?.let { return it }
        repository.requireActive()
        // 'starting' records an ambiguous network outcome, so cancellation can recover this same receipt.
        repository.local { it.updateAppSubmission(snapshot.id(), null, "starting", null) }
        val result = try { repository.api.raw("POST", "/submissions/start", JSONObject(snapshot.metadata()), key(snapshot.id(), "start")) }
        catch (error: org.joinmastodon.android.novel.app.NovelAppApiException) {
            // Contract validation 4xx commits neither slot nor receipt. Network/timeout/5xx remains ambiguous.
            repository.requireActive()
            if (error.status in 400..499 && error.status !in setOf(408, 429)) repository.local { it.updateAppSubmission(snapshot.id(), null, "prepared", null) }
            throw error
        }
        repository.requireActive()
        val remote = result.getString("id")
        repository.local { s ->
            s.writableDatabase.beginTransaction()
            try {
                s.updateAppSubmission(snapshot.id(), remote, "uploading", null)
                val old = requireNotNull(s.appMetadata(snapshot.workId()))
                s.putAppMetadata(snapshot.workId(), old.payload(), result.getString("work_id"), old.releaseId())
                s.putOwnerState(snapshot.workId(), JSONObject(snapshot.metadata()).getString("client_work_id"), old.ownerPayload(), old.ownerConflict())
                s.writableDatabase.setTransactionSuccessful()
            } finally { s.writableDatabase.endTransaction() }
        }
        return remote
    }
    suspend fun send(localSubmissionId: String): JSONObject {
        val snapshot = repository.local { it.appSubmission(localSubmissionId) }
        if (snapshot.state() in setOf("pending", "reviewing", "approved")) return JSONObject(requireNotNull(snapshot.result()))
        check(snapshot.state() in setOf("prepared", "starting", "uploading")) { "本次提交已结束，请明确重新选择章节提交" }
        val ids = preflight(snapshot)
        val remoteId = start(snapshot)
        var part = 0; var array = JSONArray()
        suspend fun flush() {
            if (array.length() == 0) return
            repository.requireActive()
            repository.api.raw("PUT", "/submissions/${NovelAppApi.segment(remoteId)}/parts/$part", JSONObject().put("chapters", array), key(localSubmissionId, "part:$part"))
            repository.requireActive()
            part++; array = JSONArray()
        }
        for (index in ids.indices) {
            val value = json(repository.local { it.appSubmissionChapter(localSubmissionId, index) })
            if (array.length() == 40 || bytes(JSONArray(array.toString()).put(value)) > NovelAppApi.MAX_REQUEST_BYTES - 1024) flush()
            array.put(value)
        }
        flush()
        repository.requireActive()
        val result = repository.api.raw("POST", "/submissions/${NovelAppApi.segment(remoteId)}/finalize", JSONObject(), key(localSubmissionId, "finalize"))
        repository.requireActive()
        require(result.optString("status") == "pending") { "服务器尚未进入审核状态" }
        repository.local { s ->
            s.writableDatabase.beginTransaction()
            try { s.updateAppSubmission(localSubmissionId, remoteId, "pending", result.toString()); ids.forEachIndexed { i, id -> s.putRemoteChapter(id, "pending", remoteId, null, s.appSubmissionChapter(localSubmissionId, i).generation()) }; s.writableDatabase.setTransactionSuccessful() }
            finally { s.writableDatabase.endTransaction() }
        }
        return result
    }
    /** Only an explicit user confirmation may abandon a frozen attempt; no local manuscript is deleted. */
    suspend fun cancel(localSubmissionId: String) {
        val snapshot = repository.local { it.appSubmission(localSubmissionId) }
        check(snapshot.state() in setOf("prepared", "starting", "uploading", "pending")) { "本次提交已进入审核或已结束，请先刷新状态" }
        val remoteId = snapshot.serverSubmissionId() ?: if (snapshot.state() == "starting") start(snapshot) else null
        if (remoteId == null) { repository.local { it.updateAppSubmission(localSubmissionId, null, "cancelled", null) }; return }
        repository.requireActive()
        val result = repository.api.raw("POST", "/submissions/${NovelAppApi.segment(remoteId)}/cancel", JSONObject(), key(localSubmissionId, "cancel"))
        repository.requireActive()
        require(result.getString("id") == remoteId && result.optString("status") == "rejected") { "服务器未确认取消，请重试；本机文稿仍保留" }
        repository.local { s -> s.updateAppSubmission(localSubmissionId, remoteId, "cancelled", result.toString()) }
    }
}
