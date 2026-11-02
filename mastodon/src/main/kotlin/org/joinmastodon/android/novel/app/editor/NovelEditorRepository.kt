package org.joinmastodon.android.novel.app.editor

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.joinmastodon.android.novel.NovelAccountDataCleaner
import org.joinmastodon.android.novel.app.NovelAppApi
import org.joinmastodon.android.novel.app.NovelAppTransport
import org.joinmastodon.android.novel.app.NovelSessionRequests
import org.joinmastodon.android.novel.app.models.Work
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.noveleditor.EditorModels
import org.joinmastodon.noveleditor.NovelEditorStorage
import java.nio.charset.StandardCharsets
import java.util.UUID

internal data class NovelMetadata(
    val title: String = "", val description: String = "", val type: String = "original",
    val coverUrl: String? = null, val localCover: String? = null, val tags: List<String> = emptyList(),
    val statements: List<String> = emptyList(), val source: String = "", val authorization: String = "",
    val completed: Boolean = false, val accepted: Boolean = false,
    val originalAuthor: String = "", val coverPending: Boolean = localCover != null && coverUrl == null,
) {
    fun validate() {
        require(title.isNotBlank() && title.length <= 30) { "novel_i18n_title_length" }
        require(description.isNotBlank() && description.length <= 500) { "novel_i18n_description_length" }
        require(type in setOf("original", "repost", "ai")) { "novel_i18n_type_required" }
        require(tags.size <= 3 && tags.all { it.isNotBlank() && it.length <= 30 }) { "novel_i18n_tags_limit" }
        require(originalAuthor.length <= 120) { "novel_i18n_original_author_limit" }
        require(type != "repost" || originalAuthor.isNotBlank()) { "novel_i18n_original_author_required" }
        require(type != "repost" || (source.isNotBlank() && authorization.isNotBlank())) { "novel_i18n_permission_required" }
        require(source.length <= 2000 && authorization.length <= 2000) { "novel_i18n_permission_length" }
        require(accepted) { "novel_i18n_rules_required" }
    }
    fun json() = JSONObject().put("title", title).put("description", description).put("work_type", type)
        .put("cover_url", coverUrl ?: JSONObject.NULL).put("local_cover", localCover ?: JSONObject.NULL)
        .put("tags", JSONArray(tags)).put("statements", JSONArray(statements)).put("source", source)
        .put("authorization", authorization).put("completed", completed).put("accepted", accepted)
        .put("original_author", originalAuthor).put("local_cover_pending", coverPending)
    /** Local state is deliberately absent from the frozen wire metadata. */
    fun submissionJson() = json().apply {
        remove("accepted")
        keys().asSequence().filter { it.startsWith("local_") }.toList().forEach { remove(it) }
        // Older production allowlists do not accept even an empty original_author on original/AI work.
        if (type != "repost" && originalAuthor.isEmpty()) remove("original_author")
    }
    companion object {
        fun fromWork(work: Work) = NovelMetadata(
            title = work.title, description = work.description, type = work.workType, coverUrl = work.coverUrl,
            tags = work.tags, statements = work.statements, source = work.source, authorization = work.authorization,
            completed = work.completed, originalAuthor = work.originalAuthor,
        )
        fun from(j: JSONObject) = NovelMetadata(j.optString("title"), j.optString("description"), j.optString("work_type", "original"),
            j.stringOrNull("cover_url"), j.stringOrNull("local_cover"), j.strings("tags"), j.strings("statements"),
            j.optString("source"), j.optString("authorization"), j.optBoolean("completed"), j.optBoolean("accepted", false),
            j.stringOrNull("original_author").orEmpty(), j.optBoolean("local_cover_pending", j.stringOrNull("local_cover") != null && j.stringOrNull("cover_url") == null))
    }
}
internal fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
internal fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
internal data class EditorDirectory(val structure: EditorModels.WorkStructure, val metadata: NovelMetadata, val remote: Map<String, NovelEditorStorage.RemoteChapter>, val server: JSONObject? = null, val conflict: String? = null)
internal class NovelOwnerConflict(message: String) : IllegalStateException(message)
internal class NovelReviewInProgress : IllegalStateException("novel_i18n_review_in_progress")

/** Account-leased local IO. No mutation here schedules an uploader or a sync worker. */
internal class NovelEditorRepository(val context: Context, val accountId: String, val api: NovelAppTransport,
    internal val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    internal val generation = NovelAccountDataCleaner.captureGeneration(accountId)
    /** Capture exactly once, under the logout gate and only while the repository's API is current. */
    internal val coverSession: NovelCoverSession? = NovelAccountDataCleaner.withAccountLock {
        if (api !is NovelAppApi || !NovelAccountDataCleaner.isGenerationValid(accountId, generation) || !api.isSessionActive()) null
        else AccountSessionManager.getInstance().tryGetAccount(accountId)?.let { session ->
            val token = session.token.accessToken
            NovelCoverSession(session.domain, token, NovelSessionRequests.capture(session, token))
        }
    }
    suspend fun <T> local(block: (NovelEditorStorage) -> T): T = withContext(io) {
        val lease = checkNotNull(NovelAccountDataCleaner.enterOperation(accountId, generation)) { "novel_i18n_account_logged_out" }
        lease.use { NovelEditorStorage(context, accountId).use(block) }
    }
    internal fun requireActive() {
        check(NovelAccountDataCleaner.isGenerationValid(accountId, generation)) { "novel_i18n_account_logged_out" }
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
    /** Persist only cover changes immediately; unfinished form fields and old repost metadata stay editable. */
    suspend fun saveCover(id: String, metadata: NovelMetadata) = local { storage ->
        val old = storage.appMetadata(id)
        val payload = old?.payload()?.let(::JSONObject) ?: directoryMetadata(storage, id).json()
        payload.put("local_cover", metadata.localCover ?: JSONObject.NULL).put("cover_url", metadata.coverUrl ?: JSONObject.NULL)
            .put("local_cover_pending", metadata.coverPending)
        storage.putAppMetadata(id, payload.toString(), old?.serverId(), old?.releaseId())
    }
    private fun directoryMetadata(storage: NovelEditorStorage, id: String): NovelMetadata {
        val work = storage.structure(id).work()
        return NovelMetadata(title = work.title(), description = work.description())
    }
    /** Mirror owner headers/status only. Never replace a local body, title, cursor or metadata draft. */
    suspend fun ownerWork(serverId: String): Pair<String, JSONObject> {
        requireActive()
        val remote = api.raw("GET", "/owner/works/${NovelAppApi.segment(serverId)}")
        requireActive()
        require(remote.getString("id") == serverId) { "novel_i18n_server_binding_mismatch" }
        val clientId = requireNotNull(remote.stringOrNull("client_work_id")) { "novel_i18n_server_binding_missing" }
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
                if (existing != null && old?.releaseId() != release && !knownRelease) conflict = "novel_i18n_owner_release_conflict"
                val previous = old?.ownerPayload()?.let(::JSONObject)
                if (existing != null && previous != null && ownerContent(previous) != ownerContent(remote)) {
                    val latest = remote.optJSONArray("chapters")?.optJSONObject(0)?.stringOrNull("submission_id")
                        ?: reviews.optJSONObject(0)?.stringOrNull("id")
                    if (latest == null || s.localAppSubmissionForServer(work, latest) == null) conflict = conflict ?: "novel_i18n_owner_content_conflict"
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
        val metadata = NovelMetadata.from(owner).copy(accepted = false, localCover = null, coverPending = false).json()
        val chapters = owner.optJSONArray("chapters") ?: JSONArray()
        return metadata.toString() + (0 until chapters.length()).map { i -> val c = chapters.getJSONObject(i); listOf(c.optString("id"), c.optString("title"), c.optInt("sort_order"), c.optLong("generation"), c.optString("body_sha256")) }.toString()
    }
    suspend fun refreshOwner(workId: String): EditorDirectory {
        val serverId = local { it.appMetadata(workId)?.serverId() }
        if (serverId != null) { val (bound, _) = ownerWork(serverId); check(bound == workId) { "novel_i18n_work_binding_changed" } }
        return directory(workId)
    }
    suspend fun acknowledgeOwner(workId: String, expected: String) {
        val previous = JSONObject(expected)
        val refreshed = requireNotNull(refreshOwner(workId).server)
        check(previous.stringOrNull("release_id") == refreshed.stringOrNull("release_id") && ownerContent(previous) == ownerContent(refreshed)) { "novel_i18n_owner_changed_again" }
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
        require(selected.isNotEmpty()) { "novel_i18n_select_chapters" }
        val directory = refreshOwner(workId)
        directory.conflict?.let { throw NovelOwnerConflict(it) }
        val reviews = directory.server?.optJSONArray("reviews") ?: JSONArray()
        if ((0 until reviews.length()).any { reviews.getJSONObject(it).optString("status") in setOf("uploading", "pending", "reviewing") }) throw NovelReviewInProgress()
        check(local { it.pendingAppSubmission(workId) } == null) { "novel_i18n_frozen_submission_exists" }
        selected.forEach { hydrate(workId, it) }
        return local { s ->
            val meta = requireNotNull(s.appMetadata(workId)) { "novel_i18n_metadata_required" }
            meta.ownerConflict()?.let { throw NovelOwnerConflict(it) }
            val metadata = NovelMetadata.from(JSONObject(meta.payload())); metadata.validate()
            require(!metadata.coverPending && (metadata.localCover == null || metadata.coverUrl != null)) { "novel_i18n_pending_cover" }
            val clientId = if (meta.serverId() == null) workId else requireNotNull(meta.clientWorkId()) { "novel_i18n_refresh_binding" }
            val payload = metadata.submissionJson().apply { put("client_work_id", clientId); put("base_release_id", meta.releaseId() ?: JSONObject.NULL); meta.serverId()?.let { put("work_id", it) } }
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
        require(ids.isNotEmpty()) { "novel_i18n_select_chapters" }
        require(snapshot.metadata().toByteArray(StandardCharsets.UTF_8).size <= NovelAppApi.MAX_REQUEST_BYTES) { "novel_i18n_metadata_too_large" }
        var total = 0L; var rows = JSONArray()
        for (index in ids.indices) {
            val c = repository.local { it.appSubmissionChapter(snapshot.id(), index) }
            total += c.body().toByteArray(StandardCharsets.UTF_8).size
            require(total <= 20L * 1024 * 1024) { "novel_i18n_selected_text_limit" }
            val value = json(c)
            require(bytes(JSONArray().put(value)) <= NovelAppApi.MAX_REQUEST_BYTES) { "novel_i18n_chapter_json_limit" }
            if (rows.length() == 40 || bytes(JSONArray(rows.toString()).put(value)) > NovelAppApi.MAX_REQUEST_BYTES - 1024) rows = JSONArray()
            rows.put(value)
            require(bytes(rows) <= NovelAppApi.MAX_REQUEST_BYTES) { "novel_i18n_encoded_chapters_limit" }
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
        check(snapshot.state() in setOf("prepared", "starting", "uploading")) { "novel_i18n_submission_finished" }
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
        require(result.optString("status") == "pending") { "novel_i18n_review_not_pending" }
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
        check(snapshot.state() in setOf("prepared", "starting", "uploading", "pending")) { "novel_i18n_submission_cannot_cancel" }
        val remoteId = snapshot.serverSubmissionId() ?: if (snapshot.state() == "starting") start(snapshot) else null
        if (remoteId == null) { repository.local { it.updateAppSubmission(localSubmissionId, null, "cancelled", null) }; return }
        repository.requireActive()
        val result = repository.api.raw("POST", "/submissions/${NovelAppApi.segment(remoteId)}/cancel", JSONObject(), key(localSubmissionId, "cancel"))
        repository.requireActive()
        require(result.getString("id") == remoteId && result.optString("status") == "rejected") { "novel_i18n_cancellation_unconfirmed" }
        repository.local { s -> s.updateAppSubmission(localSubmissionId, remoteId, "cancelled", result.toString()) }
    }
}
