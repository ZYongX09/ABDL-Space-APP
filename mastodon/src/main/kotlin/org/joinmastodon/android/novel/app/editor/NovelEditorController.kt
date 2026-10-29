package org.joinmastodon.android.novel.app.editor

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.joinmastodon.android.novel.importer.NovelImportCoordinator
import org.joinmastodon.noveleditor.EditorLimits
import org.joinmastodon.noveleditor.EditorSession
import org.joinmastodon.noveleditor.EditorText
import org.joinmastodon.noveleditor.ImportModels
import org.joinmastodon.noveleditor.ImportReview
import org.joinmastodon.noveleditor.NovelImportParser
import java.io.File
import java.util.UUID

internal data class NovelEditorState(
    val page: String = "loading", val workId: String? = null, val directory: EditorDirectory? = null,
    val chapterId: String? = null, val frame: EditorSession.Frame? = null, val dirty: Boolean = false, val editToken: Long = 0,
    val saving: Boolean = false, val busy: Boolean = false, val error: String? = null, val notice: String? = null,
    val canUndo: Boolean = false, val canRedo: Boolean = false,
    val metadata: NovelMetadata = NovelMetadata(), val importName: String = "", val importJob: String? = null,
    val importResult: ImportModels.Result? = null, val importRevision: Int = 0, val conflict: String? = null,
)

internal class NovelEditorController(application: Application, val repository: NovelEditorRepository,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(NovelEditorState())
    val state = mutable.asStateFlow()
    private val saveLock = Mutex()
    private var editing: EditorSession? = null
    private var editToken = 0L
    private var importOriginal: ImportModels.Result? = null
    var review: ImportReview? = null; private set
    private val root get() = File(repository.context.filesDir, "novel-app/${NovelImportCoordinator.accountHash(repository.accountId)}")
    init {
        // A hard monotonic period, independent of keyboard events and of save duration.
        viewModelScope.launch { while (true) { delay(200); if (editing?.tick(now()) == true && !saveLock.isLocked) launch { save(latest = false) } } }
    }
    private fun update(block: (NovelEditorState) -> NovelEditorState) { mutable.value = block(mutable.value) }
    private fun operation(action: suspend () -> Unit) {
        if (mutable.value.busy) return
        viewModelScope.launch {
        update { it.copy(busy = true, error = null) }
        try { action() } catch (cancel: CancellationException) { throw cancel } catch (e: Exception) { update { it.copy(error = e.message ?: "操作失败；本地文稿仍保留") } }
        finally { update { it.copy(busy = false) } }
    } }
    fun open(localId: String?, remoteId: String?, mode: String) = operation {
        if (localId != null) {
            if (!showDirectory(localId)) return@operation
            try { refreshOwnerNow(localId) } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { update { it.copy(error = "无法刷新审核状态，可继续本机编辑；提交前需联网对账。文稿未覆盖。") } }
        }
        else if (remoteId != null) { val (id, _) = repository.ownerWork(remoteId); if (!showDirectory(id)) return@operation }
        else update { it.copy(page = if (mode == "import") "import" else "metadata") }
        if (mode == "import") update { it.copy(page = "import") }
    }
    suspend fun showDirectory(id: String): Boolean {
        // A completed save is not a navigation barrier: another old-IME callback may arrive during directory IO.
        val departing = editing
        while (true) {
            if (!save()) return false
            val directory = repository.directory(id)
            if (editing !== departing) return false
            if (departing?.dirty() == true) continue
            editing = null
            update { it.copy(page = "manage", workId = id, directory = directory, metadata = directory.metadata, conflict = directory.conflict, frame = null, chapterId = null, dirty = false, canUndo = false, canRedo = false) }
            return true
        }
    }
    private suspend fun refreshOwnerNow(id: String) {
        val directory = repository.refreshOwner(id)
        update { it.copy(directory = directory, conflict = directory.conflict) }
    }
    fun refreshOwner() = operation {
        if (!save()) return@operation
        refreshOwnerNow(requireNotNull(mutable.value.workId))
        update { it.copy(notice = "已刷新服务端审核状态；本机正文与作品资料均保留，未上传") }
    }
    fun keepLocalAfterConflict() = operation {
        if (!save()) return@operation
        val work = requireNotNull(mutable.value.workId)
        val expected = requireNotNull(mutable.value.directory?.server).toString()
        repository.acknowledgeOwner(work, expected)
        refreshDirectoryNow(work)
        update { it.copy(notice = "已明确保留本机稿；请重新勾选要提交的章节。此次确认没有上传。") }
    }
    private suspend fun refreshDirectoryNow(id: String) {
        val directory = repository.directory(id)
        update { it.copy(directory = directory, conflict = directory.conflict) }
    }
    fun clearError() = update { it.copy(error = null, notice = null) }
    fun metadata(value: NovelMetadata) = update { it.copy(metadata = value) }
    fun showMetadata() = operation { if (save()) update { it.copy(page = "metadata") } }
    fun showImport() = operation { if (save()) update { it.copy(page = "import") } }
    fun chooseCover(uri: Uri) = operation {
        val path = repository.local {
            val resolver = repository.context.contentResolver
            require(resolver.getType(uri) in setOf("image/jpeg", "image/png")) { "封面仅支持 JPG / PNG" }
            val folder = File(root, "covers").apply { mkdirs() }; val file = File(folder, "${UUID.randomUUID()}.image")
            try { resolver.openInputStream(uri).use { input -> requireNotNull(input); file.outputStream().use { output -> val buffer = ByteArray(32768); var total = 0L; while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 5L * 1024 * 1024) { "封面不能超过 5 MB" }; output.write(buffer, 0, n) } } }; val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }; android.graphics.BitmapFactory.decodeFile(file.absolutePath, options); require(options.outWidth > 0 && options.outHeight > 0) { "图片无效" }; file.absolutePath }
            catch (e: Exception) { file.delete(); throw e }
        }
        update { it.copy(metadata = it.metadata.copy(localCover = path, coverUrl = null)) }
    }
    fun uploadCover() = operation {
        val path = requireNotNull(mutable.value.metadata.localCover) { "请先选择封面" }
        val url = kotlinx.coroutines.suspendCancellableCoroutine<String> { continuation ->
            val request = org.joinmastodon.android.api.requests.statuses.UploadAttachment(Uri.fromFile(File(path)), 2_073_600, "小说封面")
            continuation.invokeOnCancellation { request.cancel() }
            request.setCallback(object : me.grishka.appkit.api.Callback<org.joinmastodon.android.model.Attachment> {
                override fun onSuccess(result: org.joinmastodon.android.model.Attachment) { if (continuation.isActive) { if (result.url.isNullOrBlank()) continuation.resumeWith(Result.failure(IllegalStateException("封面仍处理中，请稍后重试"))) else continuation.resumeWith(Result.success(result.url)) } }
                override fun onError(error: me.grishka.appkit.api.ErrorResponse) { if (continuation.isActive) continuation.resumeWith(Result.failure(IllegalStateException("封面上传失败，请重试"))) }
            }); request.exec(repository.accountId)
        }
        update { it.copy(metadata = it.metadata.copy(coverUrl = url), notice = "封面已上传；文稿仍仅在本地") }
    }
    fun storeMetadata() = operation {
        val data = mutable.value.metadata
        val id = mutable.value.workId
        if (id == null) { val created = repository.create(data); showDirectory(created); openChapterNow(repository.directory(created).structure.chapters().first().id()) }
        else { repository.saveMetadata(id, data); showDirectory(id) }
    }
    fun openChapter(id: String) = operation { if (save()) openChapterNow(id) }
    private suspend fun openChapterNow(id: String) {
        val work = requireNotNull(mutable.value.workId)
        val departing = editing
        if (departing?.capture()?.chapterId() == id) return
        repository.hydrate(work, id)
        val loaded = repository.local { s -> requireNotNull(s.getChapter(id)) to requireNotNull(s.loadDraft(id)) }
        var frame = EditorSession.Frame(loaded.first.title(), loaded.second.content(), loaded.second.selectionStart(), loaded.second.selectionEnd(), loaded.second.scrollY())
        val recovered = repository.local { recover(id) }
        // Flush input received while the target's hydrate/load/recovery IO was suspended, before replacing the session.
        if (editing !== departing || !save()) return
        editing = EditorSession(id, loaded.first.currentGeneration(), frame, now())
        editToken++
        if (recovered != null) { frame = recovered; editing?.edit(frame) }
        update { it.copy(page = "editor", chapterId = id, editToken = editToken, frame = frame, dirty = editing!!.dirty(), canUndo = editing!!.canUndo(), canRedo = false, notice = if (recovered != null) "已恢复未完成保存的本机文稿；请检查并保存" else null) }
    }
    fun frame(chapterId: String, token: Long, value: EditorSession.Frame) {
        // UI callbacks carry the chapter AND session they were bound to, never the newly opened chapter.
        if (token != editToken || editing?.capture()?.chapterId() != chapterId) return
        frame(value)
    }
    fun frame(value: EditorSession.Frame) {
        // Busy is not a reason to discard input already delivered by the IME to the active old session.
        try { editing?.edit(value); reflect() } catch (e: IllegalArgumentException) { update { it.copy(error = e.message) } }
    }
    private fun reflect() { val session = editing ?: return; update { it.copy(frame = session.frame(), dirty = session.dirty(), canUndo = session.canUndo(), canRedo = session.canRedo()) } }
    fun undo() { editing?.undo(); reflect() }
    fun redo() { editing?.redo(); reflect() }
    fun insert(text: String) { val f = editing?.frame() ?: return; val a = minOf(f.start(), f.end()).coerceIn(0, f.body().length); val b = maxOf(f.start(), f.end()).coerceIn(a, f.body().length); val next = f.body().replaceRange(a, b, text); frame(EditorSession.Frame(f.title(), next, a + text.length, a + text.length, f.scroll())) }
    fun saveExplicit() { viewModelScope.launch { if (save()) update { it.copy(notice = "已保存到本机，不会自动上传") } } }
    /** Navigation/manual saves acknowledge the newest revision, including input delivered during local IO. */
    suspend fun save(latest: Boolean = true): Boolean = saveLock.withLock {
        val session = editing ?: return@withLock true
        if (!session.dirty()) return@withLock true
        update { it.copy(saving = true) }
        try {
            do {
                val checkpoint = session.capture(); val f = checkpoint.frame()
                try {
                    val result = repository.local { s -> s.checkpointWithTitle(checkpoint.chapterId(), checkpoint.generation(), f.title().ifBlank { "未命名章节" }, f.body(), f.start(), f.end(), f.scroll()) }
                    session.saved(checkpoint, result.generation())
                    repository.local { recoveryFile(checkpoint.chapterId()).delete() }
                    reflect()
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) {
                    session.failed(checkpoint)
                    val backedUp = try { repository.local { backup(checkpoint.chapterId(), session.frame()) }; true }
                        catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { false }
                    update { it.copy(dirty = true, error = if (backedUp) "保存失败，已保留本机恢复副本。请重试；不会切章或离开。" else "保存失败（含恢复副本）。请勿关闭应用，可复制或导出正文后重试。") }
                    return@withLock false
                }
            } while (latest && session.dirty())
            update { it.copy(error = null) }
            true
        } finally { update { it.copy(saving = false) } }
    }
    fun backgroundSave() { viewModelScope.launch { save() } }
    fun leaveEditor(after: () -> Unit) = operation { if (save()) { val id = mutable.value.workId; if (mutable.value.page == "editor" && id != null) showDirectory(id) else after() } }
    fun preview(after: (String, String?) -> Unit) = operation { if (save()) after(requireNotNull(mutable.value.workId), mutable.value.chapterId) }
    fun newChapter() = operation { if (save()) { val id = requireNotNull(mutable.value.workId); val chapter = repository.local { s -> val st = s.structure(id); s.addChapter(id, st.volumes().first().id(), "第${st.chapters().size + 1}章") }; val directory = repository.directory(id); update { it.copy(directory = directory) }; openChapterNow(chapter.id()) } }
    fun reorder(ids: List<String>) = operation { repository.local { it.reorderChapters(requireNotNull(mutable.value.workId), ids) }; showDirectory(requireNotNull(mutable.value.workId)) }
    fun deleteChapter(id: String) = operation { repository.local { it.softDeleteChapter(id) }; showDirectory(requireNotNull(mutable.value.workId)) }
    fun submit(selected: List<String>, retry: Boolean = false) = operation {
        if (!save()) return@operation
        val work = requireNotNull(mutable.value.workId)
        try {
            val id = if (retry) requireNotNull(repository.local { it.pendingAppSubmission(work) }) { "没有待重试的提交" } else repository.prepare(work, selected)
            NovelSubmissionSender(repository).send(id)
        } catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) {
            // prepare already persisted the reconciled owner headers; show them without hiding the active editor.
            refreshDirectoryNow(work)
            if (e is org.joinmastodon.android.novel.app.NovelAppApiException && e.status == 409) {
                try { refreshOwnerNow(work) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { /* Keep the frozen attempt and drafts for explicit retry. */ }
                throw IllegalStateException("服务端投稿状态或公开版本已变化，已尝试刷新状态。请检查后重新选章或重试原冻结提交；本机文稿保留。", e)
            }
            throw e
        }
        if (showDirectory(work)) update { it.copy(notice = "所选章节已提交审核；公开版本不会立即变化") }
    }
    fun cancelSubmission() = operation {
        if (!save()) return@operation
        val work = requireNotNull(mutable.value.workId)
        val id = requireNotNull(repository.local { it.cancellableAppSubmission(work) }) { "没有可放弃的本机提交，请先刷新审核状态" }
        try { NovelSubmissionSender(repository).cancel(id) }
        catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) {
            if (e is org.joinmastodon.android.novel.app.NovelAppApiException && e.status == 409) {
                try { refreshOwnerNow(work) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
                throw IllegalStateException("提交已进入审核或已经结束，不能放弃。已尝试刷新状态；本机稿和重试记录仍保留。", e)
            }
            throw e
        }
        // Owner refresh is best effort only after the server acknowledged the explicit cancellation.
        try { refreshOwnerNow(work) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
        refreshDirectoryNow(work)
        update { it.copy(notice = "已放弃本次提交，全部本机文稿仍保留；如需投稿请明确重新选章。") }
    }
    fun workAction(action: String, afterDelete: () -> Unit) = operation {
        if (!save()) return@operation
        val id = requireNotNull(mutable.value.workId)
        if (action == "complete") { val m = mutable.value.metadata.copy(completed = true); repository.saveMetadata(id, m); showDirectory(id); update { it.copy(notice = "完结标记已保存本机；明确提交章节后进入人工审核") }; return@operation }
        val server = repository.local { it.appMetadata(id)?.serverId() }
        if (server != null) repository.api.raw("POST", "/owner/works/${org.joinmastodon.android.novel.app.NovelAppApi.segment(server)}/action", JSONObject().put("action", action), UUID.randomUUID().toString())
        if (action == "delete") { repository.local { it.softDeleteWork(id) }; afterDelete() }
        else update { it.copy(notice = "作品已下架；本地文稿保留") }
    }
    fun parse(uri: Uri, autoRecognize: Boolean) = operation {
        val name = repository.context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment?.takeIf { uri.scheme == "file" } ?: "文稿.txt"
        require(name.endsWith(".txt", true) || name.endsWith(".docx", true)) { "仅支持 TXT / DOCX" }
        val parsed = repository.local { s ->
            val dir = File(root, "imports").apply { mkdirs() }; val file = File(dir, "${UUID.randomUUID()}.source")
            try {
                repository.context.contentResolver.openInputStream(uri).use { input -> requireNotNull(input); file.outputStream().use { output -> val buffer = ByteArray(32768); var total = 0L; while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 10L * 1024 * 1024) { "文稿不能超过 10 MB" }; output.write(buffer, 0, n) }; require(total > 0) { "文件为空" } } }
                var result = NovelImportParser().parse(file, name, ImportModels.Cancellation.NONE)
                require(result.metadata().format() in setOf("txt", "docx")) { "仅支持 TXT / DOCX" }
                require(result.chapters().sumOf { it.content().toByteArray().size.toLong() } <= 10L * 1024 * 1024) { "解析正文不能超过 10 MB" }
                if (!autoRecognize) { val body = result.chapters().joinToString("\n\n") { it.title() + "\n" + it.content() }; require(body.length <= EditorLimits.CHAPTER_CONTENT) { "正文过长，请开启章节识别后拆分" }; result = ImportModels.Result(result.metadata(), listOf(ImportModels.Chapter(0, "正文", body, null)), result.warnings()) }
                val job = s.createImportJob(name, repository.context.contentResolver.getType(uri), file.absolutePath, file.length(), EditorText.sha256(file.absolutePath + file.length()))
                s.storeImportResult(job.id(), result); job.id() to result
            } finally { file.delete() }
        }
        importOriginal = parsed.second; review = ImportReview(parsed.second.chapters())
        update { it.copy(page = "importPreview", importName = name, importJob = parsed.first, importResult = parsed.second, importRevision = it.importRevision + 1) }
    }
    fun adjust(action: (ImportReview) -> Unit) { try { action(requireNotNull(review)); update { it.copy(importRevision = it.importRevision + 1, importResult = review!!.result(requireNotNull(importOriginal))) } } catch (e: Exception) { update { it.copy(error = e.message) } } }
    fun importPage(page: String) = update { it.copy(page = page) }
    fun confirmImport(targetWork: String?) = operation {
        val original = requireNotNull(importOriginal); val result = requireNotNull(review).result(original); val job = requireNotNull(mutable.value.importJob)
        val created = repository.local { s -> s.storeImportResult(job, result); if (targetWork == null) s.commitImport(job, original.metadata().title().ifBlank { mutable.value.importName.substringBeforeLast('.') }.take(30), "", "fiction") else s.appendReviewedImport(job, targetWork) }
        showDirectory(created.workId()); update { it.copy(notice = "已导入为本地草稿，未上传或发布。请完善作品资料后提交。") }
    }
    suspend fun export(uri: Uri) {
        if (!save()) return
        val id = requireNotNull(mutable.value.workId); val headers = repository.directory(id).structure.chapters()
        repository.context.contentResolver.openOutputStream(uri, "wt").use { output ->
            requireNotNull(output)
            headers.forEach { c -> repository.hydrate(id, c.id()); val text = repository.local { requireNotNull(it.loadDraft(c.id())).content() }; output.write((c.title() + "\n\n").toByteArray()); output.write(text.toByteArray()); output.write("\n\n".toByteArray()) }
        }
    }
    fun exportTo(uri: Uri) = operation { kotlinx.coroutines.withContext(Dispatchers.IO) { export(uri) }; update { it.copy(notice = "文稿已导出") } }
    private fun recoveryFile(chapter: String) = AtomicFile(File(root.apply { mkdirs() }, "recovery-${EditorText.sha256(chapter)}.json"))
    private fun backup(chapter: String, frame: EditorSession.Frame) { val file = recoveryFile(chapter); val stream = file.startWrite(); try { stream.write(JSONObject().put("title", frame.title()).put("body", frame.body()).put("start", frame.start()).put("end", frame.end()).put("scroll", frame.scroll()).toString().toByteArray()); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e } }
    private fun recover(chapter: String): EditorSession.Frame? { val file = recoveryFile(chapter); if (!file.baseFile.exists()) return null; val j = JSONObject(file.openRead().bufferedReader().use { it.readText() }); return EditorSession.Frame(j.getString("title"), j.getString("body"), j.getInt("start"), j.getInt("end"), j.getInt("scroll")) }
}
