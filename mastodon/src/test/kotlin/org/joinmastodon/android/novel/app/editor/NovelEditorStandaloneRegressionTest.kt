@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package org.joinmastodon.android.novel.app.editor

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.joinmastodon.android.novel.app.NovelAppApiException
import org.joinmastodon.android.novel.app.NovelAppTransport
import org.joinmastodon.noveleditor.EditorModels
import org.joinmastodon.noveleditor.EditorSession
import org.joinmastodon.noveleditor.EditorText
import org.joinmastodon.noveleditor.NovelEditorStorage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID

/** Independent fixture: does not share the screenshot or original editor-behavior transport. */
private class StandaloneEditorTransport : NovelAppTransport {
    data class Request(val method: String, val path: String, val body: JSONObject?, val key: String?)
    val requests = mutableListOf<Request>()
    var reply: suspend (Request) -> JSONObject = { error("Unexpected ${it.method} ${it.path}") }
    override suspend fun raw(method: String, path: String, body: JSONObject?, idempotencyKey: String?): JSONObject {
        val request = Request(method, path, body?.let { JSONObject(it.toString()) }, idempotencyKey)
        requests += request
        return reply(request)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class NovelEditorStandaloneRegressionTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val account = "standalone-editor-${UUID.randomUUID()}"
    private val main = TestCoroutineScheduler()
    private val io = TestCoroutineScheduler()
    private val models = ViewModelStore()
    private val api = StandaloneEditorTransport()
    private val metadata = NovelMetadata("保留本机作品", "明确选章，未选草稿不可上传", accepted = true)
    private val server = UUID.randomUUID().toString()
    private val serverClient = UUID.randomUUID().toString()
    private fun <T> storage(block: (NovelEditorStorage) -> T): T = NovelEditorStorage(app, account).use(block)
    private fun repo(scheduler: TestCoroutineScheduler = io) = NovelEditorRepository(app, account, api, StandardTestDispatcher(scheduler))
    private fun model(repository: NovelEditorRepository = repo()) = NovelEditorController(app, repository) { main.currentTime }.also { models.put(UUID.randomUUID().toString(), it) }
    private fun drain() { repeat(30) { main.runCurrent(); io.runCurrent() } }
    private fun work(): EditorModels.CreatedWork = storage { s -> s.createWork(metadata.title, metadata.description, "fiction").also { s.putAppMetadata(it.workId(), metadata.json().toString(), null, null); s.checkpoint(it.chapterId(), "首发正文", 1, 3, 41, true) } }
    private fun add(work: EditorModels.CreatedWork, title: String, body: String): String = storage { s -> s.addChapter(work.workId(), work.volumeId(), title).id().also { s.checkpoint(it, body, 1, 2, 53, true) } }
    private fun chapter(id: String, body: String, generation: Long = 4, status: String = "approved", submission: String = "release-one", sort: Int = 0) = JSONObject().put("id", id).put("title", "远端$id").put("body_sha256", EditorText.sha256(body)).put("generation", generation).put("status", status).put("submission_id", submission).put("sort_order", sort)
    private fun owner(release: String?, chapters: List<JSONObject>, reviews: List<JSONObject> = emptyList(), client: String = serverClient) = metadata.json().put("id", server).put("client_work_id", client).put("release_id", release ?: JSONObject.NULL).put("status", "published").put("chapters", JSONArray(chapters)).put("reviews", JSONArray(reviews))
    private fun review(id: String, status: String, chapters: List<JSONObject>, reason: String? = null) = metadata.json().put("id", id).put("work_id", server).put("status", status).put("reason", reason ?: JSONObject.NULL).put("chapters", JSONArray(chapters.map { JSONObject(it.toString()).put("status", status).put("submission_id", id).apply { reason?.let { put("review_reason", it) } } }))
    private fun respond(owner: () -> JSONObject) {
        api.reply = { r -> when {
            r.method == "GET" && r.path == "/owner/works/$server" -> JSONObject(owner().toString())
            r.path == "/submissions/start" -> JSONObject().put("id", "new-attempt").put("work_id", server).put("status", "uploading")
            r.path.endsWith("/finalize") -> JSONObject().put("id", "new-attempt").put("work_id", server).put("status", "pending")
            r.path.contains("/parts/") -> JSONObject().put("accepted", r.body!!.getJSONArray("chapters").length())
            else -> error("Unexpected ${r.method} ${r.path}")
        } }
    }
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher(main)) }
    @After fun cleanup() { models.clear(); drain(); Dispatchers.resetMain(); app.deleteDatabase(NovelEditorStorage.databaseName(account)) }

    @Test fun crossDeviceMirrorPersistsOriginalServerClientIdentityAcrossMetadataSaveAndRestart() = runTest {
        val remoteChapter = UUID.randomUUID().toString()
        val remote = owner("release-one", listOf(chapter(remoteChapter, "已发表旧文")))
        respond { remote }
        val repository = repo(testScheduler)
        val (localId, _) = repository.ownerWork(server)
        assertNotEquals(serverClient, localId)
        storage { s -> s.hydrateRemoteChapter(remoteChapter, "已发表旧文", 4); s.checkpointWithTitle(remoteChapter, requireNotNull(s.getChapter(remoteChapter)).currentGeneration(), "本机改题", "跨设备明确选中修改", 2, 4, 88) }
        repository.saveMetadata(localId, metadata.copy(description = "重启前本机资料"))
        val submission = repo(testScheduler).prepare(localId, listOf(remoteChapter))
        NovelSubmissionSender(repo(testScheduler)).send(submission)
        val start = api.requests.single { it.path == "/submissions/start" }.body!!
        assertEquals(serverClient, start.getString("client_work_id")); assertEquals(server, start.getString("work_id")); assertEquals("release-one", start.getString("base_release_id"))
        storage { s -> assertEquals(serverClient, requireNotNull(s.appMetadata(localId)).clientWorkId()); assertEquals(localId, s.localWorkForServer(server)); assertEquals("跨设备明确选中修改", s.loadDraft(remoteChapter).content()) }
        assertEquals(listOf(remoteChapter), api.requests.filter { it.path.contains("/parts/") }.flatMap { r -> val rows = r.body!!.getJSONArray("chapters"); (0 until rows.length()).map { rows.getJSONObject(it).getString("id") } })
    }

    @Test fun firstApprovalThenSecondExplicitSelectionUsesCurrentReleaseAndNeverUploadsUnselectedDrafts() {
        val work = work(); val selected = add(work, "第二次所选", "第二次仅此章"); val privateChapter = add(work, "私密未选", "此稿绝不能上传")
        var remote: JSONObject? = null; var starts = 0
        api.reply = { r -> when {
            r.method == "GET" -> requireNotNull(remote)
            r.path == "/submissions/start" -> JSONObject().put("id", "local-submission-${++starts}").put("work_id", server)
            r.path.contains("/parts/") -> JSONObject().put("accepted", 1)
            r.path.endsWith("/finalize") -> JSONObject().put("id", "local-submission-$starts").put("work_id", server).put("status", "pending")
            else -> error(r.path)
        } }
        val controller = model(); controller.open(work.workId(), null, "edit"); drain()
        controller.submit(listOf(work.chapterId())); drain(); assertNull(controller.state.value.error)
        val generation = storage { s -> s.appSubmissionChapter(s.localAppSubmissionForServer(work.workId(), "local-submission-1")!!, 0).generation() }
        val approved = chapter(work.chapterId(), "首发正文", generation, submission = "local-submission-1")
        remote = owner("local-submission-1", listOf(approved), listOf(review("local-submission-1", "approved", listOf(approved))), client = work.workId())
        controller.submit(listOf(selected)); drain()
        assertNull(controller.state.value.error); assertNull(controller.state.value.conflict)
        val start = api.requests.filter { it.path == "/submissions/start" }.last().body!!
        assertEquals("local-submission-1", start.getString("base_release_id")); assertEquals(work.workId(), start.getString("client_work_id"))
        val second = api.requests.last { it.path.contains("/parts/") }.body!!.getJSONArray("chapters")
        assertEquals(1, second.length()); assertEquals(selected, second.getJSONObject(0).getString("id")); assertEquals("第二次仅此章", second.getJSONObject(0).getString("body"))
        storage { s -> assertEquals("此稿绝不能上传", s.loadDraft(privateChapter).content()); assertEquals(53, s.loadDraft(privateChapter).scrollY()); assertEquals("published", s.remoteChapter(work.chapterId()).status()) }
    }

    @Test fun localOpenAndExplicitRefreshSynchronizePendingRejectedReasonButNeverReplaceBodies() {
        val work = work(); val privateChapter = add(work, "未选", "未选草稿")
        val remoteHeader = chapter(work.chapterId(), "另一设备审核正文", 9, "pending", "review-one")
        var remote = owner(null, listOf(remoteHeader), listOf(review("review-one", "pending", listOf(remoteHeader))), client = work.workId())
        storage { it.putAppMetadata(work.workId(), metadata.json().toString(), server, null) }
        respond { remote }
        val controller = model(); controller.open(work.workId(), null, "edit"); drain()
        assertEquals("pending", controller.state.value.directory!!.remote[work.chapterId()]!!.status())
        controller.submit(listOf(privateChapter)); drain(); assertNotNull(controller.state.value.error); assertTrue(api.requests.none { it.method != "GET" })
        remote = owner(null, listOf(remoteHeader), listOf(review("review-one", "rejected", listOf(remoteHeader), "请调整章节内容")), client = work.workId()).apply { getJSONArray("chapters").getJSONObject(0).put("status", "rejected") }
        controller.refreshOwner(); drain()
        assertEquals("rejected", controller.state.value.directory!!.remote[work.chapterId()]!!.status()); assertEquals("请调整章节内容", controller.state.value.directory!!.remote[work.chapterId()]!!.reason())
        storage { s -> assertEquals("首发正文", s.loadDraft(work.chapterId()).content()); assertEquals("未选草稿", s.loadDraft(privateChapter).content()); assertEquals(41, s.loadDraft(work.chapterId()).scrollY()) }
        assertTrue(api.requests.none { it.method != "GET" })
    }

    @Test fun offlineOwnerRefreshKeepsEditorUsableAndSubmissionCannotProceedWithStaleBase() {
        val work = work(); storage { it.putAppMetadata(work.workId(), metadata.json().toString(), server, "old-release") }
        api.reply = { throw IOException("offline") }
        val controller = model(); controller.open(work.workId(), null, "edit"); drain()
        assertEquals("manage", controller.state.value.page); assertNotNull(controller.state.value.error)
        controller.openChapter(work.chapterId()); drain(); controller.frame(EditorSession.Frame("离线稿", "离线尾段", 1, 3, 77))
        controller.submit(listOf(work.chapterId())); drain()
        assertEquals("editor", controller.state.value.page); assertNotNull(controller.state.value.error)
        storage { s -> assertEquals("离线尾段", s.loadDraft(work.chapterId()).content()); assertEquals(77, s.loadDraft(work.chapterId()).scrollY()); assertEquals("old-release", requireNotNull(s.appMetadata(work.workId())).releaseId()); assertNull(s.pendingAppSubmission(work.workId())) }
        assertTrue(api.requests.all { it.method == "GET" })
    }

    @Test fun unknownRemoteReleaseBlocksUntilExplicitKeepLocalThenRequiresASeparateSelectedSubmission() {
        val work = work(); val privateChapter = add(work, "私密未选", "不要上传")
        storage { it.putAppMetadata(work.workId(), metadata.json().toString(), server, "old-release") }
        val header = chapter(work.chapterId(), "远端新正文", 20, submission = "remote-new-release")
        var remote = owner("remote-new-release", listOf(header), listOf(review("remote-new-release", "approved", listOf(header))), client = work.workId())
        respond { remote }
        val controller = model(); controller.open(work.workId(), null, "edit"); drain(); assertNotNull(controller.state.value.conflict)
        controller.submit(listOf(work.chapterId())); drain(); assertNotNull(controller.state.value.conflict); assertTrue(api.requests.none { it.method != "GET" })
        // Counters/review claim fields are not a content conflict and must not make confirmation impossible.
        remote = JSONObject(remote.toString()).put("reads", 2)
        controller.keepLocalAfterConflict(); drain(); assertNull(controller.state.value.error); assertNull(controller.state.value.conflict); assertTrue(api.requests.none { it.method != "GET" })
        controller.submit(listOf(work.chapterId())); drain(); assertNull(controller.state.value.error)
        val sent = api.requests.single { it.path.contains("/parts/") }.body!!.getJSONArray("chapters").getJSONObject(0)
        assertEquals("首发正文", sent.getString("body")); assertTrue(sent.getLong("generation") > 20)
        assertEquals("remote-new-release", api.requests.single { it.path == "/submissions/start" }.body!!.getString("base_release_id"))
        storage { s -> assertEquals("首发正文", s.loadDraft(work.chapterId()).content()); assertEquals("不要上传", s.loadDraft(privateChapter).content()); assertEquals(41, s.loadDraft(work.chapterId()).scrollY()) }
    }

    @Test fun conflictConfirmationRefusesAnEvenNewerRemoteReleaseWithoutChangingLocalText() {
        val work = work(); storage { it.putAppMetadata(work.workId(), metadata.json().toString(), server, "old") }
        var remote = owner("remote-a", listOf(chapter(work.chapterId(), "远端甲", submission = "remote-a")), client = work.workId())
        respond { remote }
        val controller = model(); controller.open(work.workId(), null, "edit"); drain()
        remote = owner("remote-b", listOf(chapter(work.chapterId(), "远端乙", 5, submission = "remote-b")), client = work.workId())
        controller.keepLocalAfterConflict(); drain(); assertNotNull(controller.state.value.error)
        storage { s -> assertNotNull(requireNotNull(s.appMetadata(work.workId())).ownerConflict()); assertEquals("首发正文", s.loadDraft(work.chapterId()).content()) }
        assertTrue(api.requests.none { it.method != "GET" })
    }

    @Test fun frozenRetryDoesNotRefreshOrRebaseAndUsesIdenticalBodiesAndKeysAfterDraftChanges() = runTest {
        val work = work(); val repository = repo(testScheduler); var failed = false
        api.reply = { r -> when {
            r.path == "/submissions/start" -> JSONObject().put("id", "frozen").put("work_id", server)
            r.path.contains("/parts/") && !failed -> { failed = true; throw IOException("part offline") }
            r.path.contains("/parts/") -> JSONObject().put("accepted", 1)
            r.path.endsWith("/finalize") -> JSONObject().put("id", "frozen").put("work_id", server).put("status", "pending")
            else -> error("Retry must not refresh/rebase ${r.path}")
        } }
        val id = repository.prepare(work.workId(), listOf(work.chapterId()))
        assertTrue(runCatching { NovelSubmissionSender(repository).send(id) }.isFailure)
        storage { s -> s.checkpoint(work.chapterId(), "提交后私密修改", 0, 0, 0, true) }
        NovelSubmissionSender(repo(testScheduler)).send(id)
        val parts = api.requests.filter { it.path.contains("/parts/") }
        assertEquals(2, parts.size); assertEquals(parts[0].key, parts[1].key); assertEquals(parts[0].body.toString(), parts[1].body.toString()); assertEquals("首发正文", parts[1].body!!.getJSONArray("chapters").getJSONObject(0).getString("body"))
        val count = api.requests.size; NovelSubmissionSender(repo(testScheduler)).send(id); assertEquals(count, api.requests.size)
        storage { assertEquals("提交后私密修改", it.loadDraft(work.chapterId()).content()) }
    }

    @Test fun controlCharacterJsonExpansionIsRejectedBeforeStartAndCanBeAbandonedLocally() = runTest {
        val work = work(); val huge = "\u0001".repeat(500_000)
        storage { it.checkpoint(work.chapterId(), huge, 4, 8, 91, true) }
        val repository = repo(testScheduler); val id = repository.prepare(work.workId(), listOf(work.chapterId()))
        val error = runCatching { NovelSubmissionSender(repository).send(id) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException); assertTrue(error!!.message!!.contains("2 MiB")); assertTrue(api.requests.isEmpty())
        NovelSubmissionSender(repository).cancel(id)
        storage { s -> assertEquals("cancelled", s.appSubmission(id).state()); assertEquals(huge, s.loadDraft(work.chapterId()).content()); assertNull(s.pendingAppSubmission(work.workId())) }
        assertTrue(api.requests.isEmpty())
    }

    @Test fun twentyMiBTotalIsCheckedBeforeAnyServerSlotIsReserved() = runTest {
        val work = work(); val id = storage { s ->
            val body = "x".repeat(500_000)
            val selected = (0 until 42).map { index -> s.addChapter(work.workId(), work.volumeId(), "容量校验章$index").id().also { s.checkpoint(it, body, 0, 0, 0, true) } }
            val submission = s.prepareAppSubmission(work.workId(), metadata.json().toString(), selected.take(41))
            // Simulate an oversized persisted/legacy queue using individually legal rows, not one impossible
            // 21 MiB chapter that fails Android's CursorWindow before the sender can count its bytes.
            s.writableDatabase.execSQL("INSERT INTO editor_app_submission_chapters(submission_id,ordinal,chapter_id,title,body,generation,sha256,sort_order) SELECT ?,41,c.id,c.title,d.content,c.current_generation,d.sha256,42 FROM editor_chapters c JOIN editor_drafts d ON d.chapter_id=c.id WHERE c.id=?", arrayOf(submission, selected.last()))
            assertEquals(42, s.appSubmissionChapterIds(submission).size)
            submission
        }
        val error = runCatching { NovelSubmissionSender(repo(testScheduler)).send(id) }.exceptionOrNull()
        assertTrue("Expected total-byte validation, got $error", error is IllegalArgumentException)
        assertTrue(requireNotNull(error).message!!.contains("20 MiB")); assertTrue(api.requests.isEmpty()); storage { assertEquals("首发正文", it.loadDraft(work.chapterId()).content()) }
    }

    @Test fun explicitCancellationFailureKeepsFrozenKeyAndSuccessfulReplayDoesNotLoseDraft() = runTest {
        val work = work(); val repository = repo(testScheduler); var cancelFails = true
        api.reply = { r -> when {
            r.path == "/submissions/start" -> JSONObject().put("id", "cancel-me").put("work_id", server)
            r.path.contains("/parts/") -> throw IOException("upload offline")
            r.path.endsWith("/cancel") && cancelFails -> { cancelFails = false; throw IOException("cancel response lost") }
            r.path.endsWith("/cancel") -> JSONObject().put("id", "cancel-me").put("work_id", server).put("status", "rejected").put("reason", "作者已取消本次投稿，请重新提交审核。")
            else -> error(r.path)
        } }
        val id = repository.prepare(work.workId(), listOf(work.chapterId()))
        assertTrue(runCatching { NovelSubmissionSender(repository).send(id) }.isFailure)
        assertTrue(api.requests.none { it.path.endsWith("/cancel") })
        assertTrue(runCatching { NovelSubmissionSender(repository).cancel(id) }.isFailure)
        storage { s -> assertEquals("uploading", s.appSubmission(id).state()); assertEquals(id, s.pendingAppSubmission(work.workId())); s.checkpoint(work.chapterId(), "放弃仍留新稿", 2, 4, 64, true) }
        NovelSubmissionSender(repo(testScheduler)).cancel(id)
        val attempts = api.requests.filter { it.path.endsWith("/cancel") }; assertEquals(2, attempts.size); assertEquals(attempts[0].key, attempts[1].key)
        storage { s -> assertEquals("cancelled", s.appSubmission(id).state()); assertEquals("首发正文", s.appSubmissionChapter(id, 0).body()); assertEquals("放弃仍留新稿", s.loadDraft(work.chapterId()).content()); assertNull(s.pendingAppSubmission(work.workId())) }
    }

    @Test fun ambiguousStartCancellationRecoversSameReceiptWithoutSendingAnyChapterParts() = runTest {
        val work = work(); val repository = repo(testScheduler); var startFails = true
        api.reply = { r -> when {
            r.path == "/submissions/start" && startFails -> { startFails = false; throw IOException("start receipt lost") }
            r.path == "/submissions/start" -> JSONObject().put("id", "ambiguous").put("work_id", server)
            r.path.endsWith("/cancel") -> JSONObject().put("id", "ambiguous").put("work_id", server).put("status", "rejected")
            else -> error("Cancellation must not upload ${r.path}")
        } }
        val id = repository.prepare(work.workId(), listOf(work.chapterId()))
        assertTrue(runCatching { NovelSubmissionSender(repository).send(id) }.isFailure); assertEquals("starting", storage { it.appSubmission(id).state() })
        NovelSubmissionSender(repo(testScheduler)).cancel(id)
        val starts = api.requests.filter { it.path == "/submissions/start" }; assertEquals(2, starts.size); assertEquals(starts[0].key, starts[1].key); assertEquals(starts[0].body.toString(), starts[1].body.toString())
        assertTrue(api.requests.none { it.path.contains("/parts/") }); storage { s -> assertEquals("cancelled", s.appSubmission(id).state()); assertEquals("首发正文", s.loadDraft(work.chapterId()).content()) }
    }

    @Test fun definiteStartValidation4xxCanBeExplicitlyAbandonedLocallyWithoutMutatingSnapshotOrKey() = runTest {
        for (status in listOf(404, 409, 422)) {
            val work = work(); val repository = repo(testScheduler)
            api.reply = { throw NovelAppApiException(status, "validation_failed", JSONObject(), "start rejected") }
            val id = repository.prepare(work.workId(), listOf(work.chapterId()))
            val frozen = storage { it.appSubmission(id).metadata() }
            assertTrue(runCatching { NovelSubmissionSender(repository).send(id) }.exceptionOrNull() is NovelAppApiException)
            assertEquals("prepared", storage { it.appSubmission(id).state() })
            val sent = api.requests.last(); assertEquals("/submissions/start", sent.path)
            assertTrue(runCatching { NovelSubmissionSender(repository).send(id) }.exceptionOrNull() is NovelAppApiException)
            assertEquals(sent.key, api.requests.last().key); assertEquals(sent.body.toString(), api.requests.last().body.toString())
            val count = api.requests.size; NovelSubmissionSender(repository).cancel(id); assertEquals(count, api.requests.size)
            storage { s -> assertEquals("cancelled", s.appSubmission(id).state()); assertEquals(frozen, s.appSubmission(id).metadata()); assertEquals("首发正文", s.loadDraft(work.chapterId()).content()) }
        }
    }

    @Test fun timeoutRateLimitAndServerFailureKeepAmbiguousStartingForSameReceiptRecovery() = runTest {
        for (status in listOf(408, 429, 503)) {
            val work = work(); val repository = repo(testScheduler)
            api.reply = { throw NovelAppApiException(status, "transient", JSONObject(), "ambiguous") }
            val id = repository.prepare(work.workId(), listOf(work.chapterId()))
            assertTrue(runCatching { NovelSubmissionSender(repository).send(id) }.isFailure)
            assertEquals("starting", storage { it.appSubmission(id).state() })
            val first = api.requests.last(); assertTrue(runCatching { NovelSubmissionSender(repository).cancel(id) }.isFailure)
            assertEquals(first.key, api.requests.last().key); assertEquals("/submissions/start", api.requests.last().path)
            storage { s -> assertEquals("starting", s.appSubmission(id).state()); assertEquals("首发正文", s.loadDraft(work.chapterId()).content()) }
        }
    }

    @Test fun reviewerClaimedCancellation409NeverMarksTerminalOrDeletesFrozenOrLocalManuscript() = runTest {
        val work = work(); val repository = repo(testScheduler)
        api.reply = { r -> when {
            r.path == "/submissions/start" -> JSONObject().put("id", "claimed").put("work_id", server)
            r.path.contains("/parts/") -> JSONObject()
            r.path.endsWith("/finalize") -> JSONObject().put("id", "claimed").put("work_id", server).put("status", "pending")
            r.path.endsWith("/cancel") -> throw NovelAppApiException(409, "submission_frozen", JSONObject(), "reviewing")
            else -> error(r.path)
        } }
        val id = repository.prepare(work.workId(), listOf(work.chapterId())); NovelSubmissionSender(repository).send(id)
        assertTrue(runCatching { NovelSubmissionSender(repository).cancel(id) }.exceptionOrNull() is NovelAppApiException)
        storage { s -> assertEquals("pending", s.appSubmission(id).state()); assertEquals("首发正文", s.appSubmissionChapter(id, 0).body()); assertEquals("首发正文", s.loadDraft(work.chapterId()).content()) }
    }

    @Test fun oldImeTailDuringNextChapterHydrationIsFlushedAndOldCallbacksCannotWriteNewChapter() {
        val work = work(); val next = UUID.randomUUID().toString(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        storage { s -> s.putAppMetadata(work.workId(), metadata.json().toString(), server, null); s.ensureRemoteChapter(work.workId(), next, "下一章", 1, 4, "published", "prior", null) }
        api.reply = { r -> when {
            r.path == "/owner/works/$server" -> owner(null, listOf(chapter(next, "下一章正文", 4, submission = "prior")), client = work.workId())
            r.path.contains("/chapters/") -> { entered.complete(Unit); release.await(); JSONObject().put("body", "下一章正文").put("generation", 4) }
            else -> error(r.path)
        } }
        val controller = model(); controller.open(work.workId(), null, "edit"); drain(); controller.openChapter(work.chapterId()); drain()
        val token = controller.state.value.editToken; controller.frame(work.chapterId(), token, EditorSession.Frame("旧章", "已保存部分", 0, 0, 4))
        controller.openChapter(next); drain(); assertTrue(entered.isCompleted); assertEquals(work.chapterId(), controller.state.value.chapterId)
        controller.frame(work.chapterId(), token, EditorSession.Frame("尾段标题", "最终保存后 hydrate 期间尾段", 2, 5, 99))
        release.complete(Unit); drain()
        assertEquals(next, controller.state.value.chapterId); assertEquals("下一章正文", controller.state.value.frame!!.body())
        storage { s -> assertEquals("最终保存后 hydrate 期间尾段", s.loadDraft(work.chapterId()).content()); assertEquals("尾段标题", requireNotNull(s.getChapter(work.chapterId())).title()); assertEquals(99, s.loadDraft(work.chapterId()).scrollY()); assertEquals("下一章正文", s.loadDraft(next).content()) }
        controller.frame(work.chapterId(), token, EditorSession.Frame("旧回调", "不能落进下一章", 0, 0, 0)); assertEquals("下一章正文", controller.state.value.frame!!.body())
    }

    @Test fun oldImeTailDuringDirectoryIoIsFlushedBeforeSessionIsDiscarded() {
        val work = work(); val controller = model(); controller.open(work.workId(), null, "edit"); drain(); controller.openChapter(work.chapterId()); drain()
        val token = controller.state.value.editToken
        // The old session is clean: leave's initial save returns, and directory IO is now queued separately.
        controller.leaveEditor { fail("Leaving editor should show directory") }; main.runCurrent()
        assertTrue(controller.state.value.busy); assertEquals("editor", controller.state.value.page)
        controller.frame(work.chapterId(), token, EditorSession.Frame("目录前尾题", "directory IO 期间最后输入", 1, 6, 63))
        drain(); assertEquals("manage", controller.state.value.page)
        storage { s -> assertEquals("directory IO 期间最后输入", s.loadDraft(work.chapterId()).content()); assertEquals("目录前尾题", requireNotNull(s.getChapter(work.chapterId())).title()); assertEquals(63, s.loadDraft(work.chapterId()).scrollY()) }
        controller.openChapter(work.chapterId()); drain()
        val current = controller.state.value.frame
        controller.frame(work.chapterId(), token, EditorSession.Frame("旧session", "同章重开也不能污染", 0, 0, 0)); assertEquals(current, controller.state.value.frame)
        assertTrue(api.requests.isEmpty())
    }
}
