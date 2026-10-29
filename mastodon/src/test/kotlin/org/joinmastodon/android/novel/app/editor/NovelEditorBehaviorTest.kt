@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package org.joinmastodon.android.novel.app.editor

import android.app.Application
import android.net.Uri
import android.widget.EditText
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.joinmastodon.android.novel.NovelAccountDataCleaner
import org.joinmastodon.android.novel.app.NovelAppTransport
import org.joinmastodon.noveleditor.EditorSession
import org.joinmastodon.noveleditor.NovelEditorStorage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class EditorTestTransport : NovelAppTransport {
    data class Request(val method: String, val path: String, val json: JSONObject?, val key: String?)
    val requests = mutableListOf<Request>()
    var reply: suspend (Request) -> JSONObject = { request ->
        when {
            request.path == "/submissions/start" -> JSONObject().put("id", "submission").put("work_id", "server-work").put("status", "uploading")
            request.path.endsWith("/finalize") -> JSONObject().put("id", "submission").put("work_id", "server-work").put("status", "pending")
            else -> JSONObject().put("accepted", request.json?.optJSONArray("chapters")?.length() ?: 0)
        }
    }
    override suspend fun raw(method: String, path: String, body: JSONObject?, idempotencyKey: String?): JSONObject {
        val request = Request(method, path, body?.let { JSONObject(it.toString()) }, idempotencyKey)
        requests += request
        return reply(request)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class NovelEditorBehaviorTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val account = "editor-behavior-${UUID.randomUUID()}"
    private val main = TestCoroutineScheduler()
    private val io = TestCoroutineScheduler()
    private val models = ViewModelStore()
    private val api = EditorTestTransport()
    private lateinit var repository: NovelEditorRepository
    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher(main))
        repository = NovelEditorRepository(app, account, api, StandardTestDispatcher(io))
    }
    @After fun cleanup() { models.clear(); drain(); Dispatchers.resetMain(); app.deleteDatabase(NovelEditorStorage.databaseName(account)) }
    private fun drain() { repeat(20) { main.runCurrent(); io.runCurrent() } }
    private fun model(): NovelEditorController = NovelEditorController(app, repository) { main.currentTime }.also { models.put(UUID.randomUUID().toString(), it) }
    private fun work() = NovelEditorStorage(app, account).use { it.createWork("测试作品", "真实本机行为", "fiction") }
    private fun open(work: org.joinmastodon.noveleditor.EditorModels.CreatedWork): NovelEditorController = model().also { it.open(work.workId(), null, "edit"); drain(); it.openChapter(work.chapterId()); drain(); assertEquals("editor", it.state.value.page) }
    private fun stored(chapter: String) = NovelEditorStorage(app, account).use { it.loadDraft(chapter) }
    private fun edit(model: NovelEditorController, body: String, title: String = "标题") = model.frame(EditorSession.Frame(title, body, body.length, body.length, 42))

    @Test fun continuousImeInputIsSavedAtTenSecondsWithoutAnyNetworkRequest() {
        val work = work(); val model = open(work)
        repeat(19) { n -> main.advanceTimeBy(500); edit(model, "持续输入$n") }
        drain(); assertEquals("", stored(work.chapterId()).content())
        main.advanceTimeBy(500); drain()
        assertEquals("持续输入18", stored(work.chapterId()).content()); assertFalse(model.state.value.dirty)
        repeat(19) { n -> main.advanceTimeBy(500); edit(model, "下个固定周期$n") }
        drain(); assertEquals("持续输入18", stored(work.chapterId()).content())
        main.advanceTimeBy(500); drain()
        assertEquals("下个固定周期18", stored(work.chapterId()).content()); assertTrue(api.requests.isEmpty())
    }

    @Test fun inputWhileManualSaveIsInFlightIsNotDiscardedAndBarrierPersistsNewestFrame() {
        val work = work(); val model = open(work); edit(model, "第一次输入")
        model.saveExplicit(); main.runCurrent() // suspended at actual SQLite IO dispatch
        assertTrue(model.state.value.saving); assertFalse(model.state.value.busy)
        edit(model, "保存期间继续输入", "保存期间改标题")
        io.runCurrent(); main.runCurrent() // acknowledge first captured revision, schedule newest
        assertTrue(model.state.value.dirty)
        drain()
        val saved = stored(work.chapterId()); assertEquals("保存期间继续输入", saved.content()); assertEquals(42, saved.scrollY())
        NovelEditorStorage(app, account).use { assertEquals("保存期间改标题", requireNotNull(it.getChapter(work.chapterId())).title()) }
        assertFalse(model.state.value.dirty); assertFalse(model.state.value.saving); assertTrue(model.state.value.canUndo)
        model.undo(); assertEquals("第一次输入", model.state.value.frame!!.body()); model.redo(); assertEquals("保存期间继续输入", model.state.value.frame!!.body())
        assertTrue(api.requests.isEmpty())
    }

    @Test fun diskFailureBlocksExitAndChapterSwitchAndRestartRecoversTitleTextCursorAndScroll() {
        val work = work()
        val second = NovelEditorStorage(app, account).use { it.addChapter(work.workId(), work.volumeId(), "第二章").id() }
        val model = open(work); val expected = EditorSession.Frame("失败后仍在", "本机保存失败也不可丢失", 2, 7, 57); model.frame(expected)
        NovelEditorStorage(app, account).use { it.writableDatabase.execSQL("CREATE TRIGGER reject_editor_save BEFORE UPDATE ON editor_chapters BEGIN SELECT RAISE(ABORT,'disk full'); END") }
        model.saveExplicit(); drain(); assertTrue(model.state.value.dirty); assertNotNull(model.state.value.error)
        model.openChapter(second); drain(); assertEquals(work.chapterId(), model.state.value.chapterId); assertEquals(expected, model.state.value.frame)
        var left = false; model.leaveEditor { left = true }; drain(); assertFalse(left); assertEquals("editor", model.state.value.page)
        val restarted = open(work); assertEquals(expected, restarted.state.value.frame); assertTrue(restarted.state.value.dirty)
        NovelEditorStorage(app, account).use { it.writableDatabase.execSQL("DROP TRIGGER reject_editor_save") }
        restarted.saveExplicit(); drain(); assertEquals(expected.body(), stored(work.chapterId()).content()); assertEquals(expected.start(), stored(work.chapterId()).selectionStart()); assertFalse(restarted.state.value.dirty)
        restarted.openChapter(second); drain(); assertEquals(second, restarted.state.value.chapterId)
        assertTrue(api.requests.isEmpty())
    }

    @Test fun actualAndroidInputConnectionPreservesComposingSelectionAndRestoresUndoFrameAndScroll() {
        var current = EditorSession.Frame("标题", "第一行\n".repeat(80), 0, 0, 0)
        val view = NovelManuscriptView(app)
        fun bind(frame: EditorSession.Frame) { view.bind("chapter", frame, true, 0xff202020.toInt(), 0xff777777.toInt(), 0xff38669b.toInt(), true, 14f) { current = it } }
        bind(current)
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(393, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY)); view.layout(0, 0, 393, 400)
        val input = view.getChildAt(1) as EditText
        input.requestFocus(); input.setSelection(0)
        val connection = requireNotNull(input.onCreateInputConnection(EditorInfo()))
        connection.setComposingText("正在组合", 1)
        val editable = input.text
        assertTrue(android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editable) >= 0)
        bind(current)
        assertSame(editable, input.text); assertTrue(android.view.inputmethod.BaseInputConnection.getComposingSpanStart(input.text) >= 0)
        connection.finishComposingText(); input.setSelection(1, 3); input.scrollTo(0, 120)
        assertEquals(1, current.start()); assertEquals(3, current.end()); assertEquals(120, current.scroll())
        val undo = EditorSession.Frame("回退标题", "恢复正文\n".repeat(80), 4, 9, 80); bind(undo)
        assertEquals(undo.body(), input.text.toString()); assertEquals(4, input.selectionStart); assertEquals(9, input.selectionEnd); assertEquals(80, input.scrollY)
    }

    @Test fun txtAndDocxParseReviewThenAppendOnlyAfterExplicitConfirmation() {
        val work = work(); val old = "原草稿绝不覆盖"
        NovelEditorStorage(app, account).use { it.checkpoint(work.chapterId(), old, 0, 0, 0, true) }
        val model = model()
        val txt = File(app.cacheDir, "import-${UUID.randomUUID()}.txt").apply { writeText("第一章 起点\n第一段。\n\n第二段。\n第二章 继续\n后一章。") }
        model.parse(Uri.fromFile(txt), true); drain()
        assertEquals("importPreview", model.state.value.page); assertEquals(2, model.state.value.importResult!!.chapters().size)
        NovelEditorStorage(app, account).use { assertEquals(1, it.listWorks(false).size); assertEquals(1, it.structure(work.workId()).chapters().size) }
        model.adjust { it.move(0, 1) }; model.confirmImport(work.workId()); drain()
        NovelEditorStorage(app, account).use { assertEquals(old, it.loadDraft(work.chapterId()).content()); assertEquals(3, it.structure(work.workId()).chapters().size); assertTrue(it.structure(work.workId()).chapters().all { c -> it.remoteChapter(c.id()).status() == "draft" }) }
        val docx = File(app.cacheDir, "document-${UUID.randomUUID()}.docx")
        ZipOutputStream(docx.outputStream()).use { zip -> zip.putNextEntry(ZipEntry("word/document.xml")); zip.write("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>DOCX真实段落。</w:t></w:r></w:p></w:body></w:document>".toByteArray()); zip.closeEntry() }
        model.parse(Uri.fromFile(docx), true); drain(); assertEquals("docx", model.state.value.importResult!!.metadata().format()); assertEquals("DOCX真实段落。", model.state.value.importResult!!.chapters().single().content())
        assertTrue(api.requests.isEmpty()); txt.delete(); docx.delete()
    }

    @Test fun oversizeSourceFailsBeforeCreatingAWorkOrUploading() {
        val file = File(app.cacheDir, "large-${UUID.randomUUID()}.txt")
        java.io.RandomAccessFile(file, "rw").use { it.setLength(10L * 1024 * 1024 + 1) }
        val model = model(); model.parse(Uri.fromFile(file), true); drain(); assertNotNull(model.state.value.error)
        NovelEditorStorage(app, account).use { assertTrue(it.listWorks(false).isEmpty()) }; assertTrue(api.requests.isEmpty()); file.delete()
    }

    @Test fun stagedRetryUsesFrozenRowsSameUuidKeysAndBoundedFortyChapterParts() = runTest {
        val transport = EditorTestTransport(); var failPart = true
        transport.reply = { request -> when {
            request.path.endsWith("/parts/1") && failPart -> { failPart = false; throw java.io.IOException("connection lost") }
            request.path == "/submissions/start" -> JSONObject().put("id", "immutable-stage").put("work_id", "remote-work")
            request.path.endsWith("/finalize") -> JSONObject().put("id", "immutable-stage").put("work_id", "remote-work").put("status", "pending")
            else -> JSONObject().put("accepted", request.json!!.getJSONArray("chapters").length())
        } }
        val repo = NovelEditorRepository(app, account, transport, StandardTestDispatcher(testScheduler))
        val work = work(); val selected = mutableListOf<String>()
        NovelEditorStorage(app, account).use { storage ->
            storage.putAppMetadata(work.workId(), NovelMetadata("标题", "简介", accepted = true).json().toString(), null, null)
            repeat(43) { index -> val id = if (index == 0) work.chapterId() else storage.addChapter(work.workId(), work.volumeId(), "第$index 章").id(); storage.checkpoint(id, "冻结正文$index", 0, 0, 0, true); if (index < 42) selected += id }
        }
        val id = repo.prepare(work.workId(), selected.reversed())
        assertTrue(runCatching { NovelSubmissionSender(repo).send(id) }.isFailure)
        NovelEditorStorage(app, account).use { it.checkpoint(work.chapterId(), "不可被重试上传的新草稿", 0, 0, 0, true) }
        NovelSubmissionSender(NovelEditorRepository(app, account, transport, StandardTestDispatcher(testScheduler))).send(id)
        assertEquals(1, transport.requests.count { it.path == "/submissions/start" })
        val parts = transport.requests.filter { it.path.contains("/parts/") }
        parts.groupBy { it.path }.values.forEach { attempts -> assertEquals(1, attempts.map { it.key }.distinct().size); assertEquals(1, attempts.map { it.json.toString() }.distinct().size) }
        val sent = parts.distinctBy { it.path }.flatMap { r -> val rows = r.json!!.getJSONArray("chapters"); assertTrue(rows.length() <= 40); assertTrue(r.json.toString().toByteArray().size <= 2 * 1024 * 1024); (0 until rows.length()).map { rows.getJSONObject(it) } }
        assertEquals(selected, sent.map { it.getString("id") }); assertEquals("冻结正文0", sent.first().getString("body")); assertEquals(42, sent.size)
        transport.requests.forEach { request -> assertEquals(request.key, UUID.fromString(request.key).toString()) }
        assertEquals("pending", repo.local { it.appSubmission(id).state() })
    }

    @Test fun logoutInvalidatesExistingEditorRepositoryInsteadOfRecreatingAccountData() = runTest {
        val repo = NovelEditorRepository(app, account, api, StandardTestDispatcher(testScheduler)); val work = work()
        NovelAccountDataCleaner.invalidate(account); app.deleteDatabase(NovelEditorStorage.databaseName(account))
        assertTrue(runCatching { repo.directory(work.workId()) }.isFailure); assertFalse(app.getDatabasePath(NovelEditorStorage.databaseName(account)).exists())
    }
}
