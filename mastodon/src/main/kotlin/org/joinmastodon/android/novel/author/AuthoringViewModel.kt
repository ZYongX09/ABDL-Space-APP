package org.joinmastodon.android.novel.author

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.joinmastodon.android.api.novels.NovelAuthoringApi
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.novel.NovelAccountDataCleaner
import org.joinmastodon.reader.data.NovelAuthorRevisionDraftEntity
import org.joinmastodon.reader.data.NovelAuthorRevisionOutboxEntity
import org.joinmastodon.reader.data.NovelBookEntity
import org.joinmastodon.reader.data.NovelDatabase
import org.joinmastodon.reader.parser.BookParser

data class AuthoringState(
	val loading: Boolean = true,
	val eligibility: NovelAuthoringApi.EligibilityDto? = null,
	val works: List<NovelAuthoringApi.WorkDto> = emptyList(),
	val creating: Boolean = false,
	val createdWorkId: String? = null,
	val selectedWorkId: String? = null,
	val structure: NovelAuthoringApi.StructureDto? = null,
	val structureLoading: Boolean = false,
	val structureOperating: Boolean = false,
	val editingChapter: NovelAuthoringApi.ChapterDto? = null,
	val editorContent: String = "",
	val editorSyncState: String = "clean",
	val editorLoading: Boolean = false,
	val editorConflict: org.joinmastodon.reader.data.NovelAuthorRevisionConflictEntity? = null,
	val editorResolving: Boolean = false,
	val editorRevisionId: String? = null,
	val editorRevisionStatus: String? = null,
	val editorPublishing: Boolean = false,
	val editorSaving: Boolean = false,
	val editorSaveToast: Int = 0,
	val error: String? = null,
)

class AuthoringViewModel(application: Application, val accountId: String) : AndroidViewModel(application) {
	private val session = AccountSessionManager.getInstance().tryGetAccount(accountId)
	private val api = session?.let(::NovelAuthoringApi)
	private val generation = NovelAccountDataCleaner.captureGeneration(accountId)
	private val database = NovelDatabase.open(application, accountId)
	private val draftDao = database.authorDraftDao()
	private val mutableState = MutableStateFlow(AuthoringState())
	val state: StateFlow<AuthoringState> = mutableState.asStateFlow()
	private var refreshGeneration = 0L
	private var structureGeneration = 0L
	private var pendingCreate: PendingCreate? = null
	private var pendingStructureCreate: PendingStructureCreate? = null
	private var editorLoadJob: Job? = null
	private var editorObserveJob: Job? = null
	private val localSaveParent: CompletableJob = SupervisorJob()
	private val localSaveScope = CoroutineScope(localSaveParent + Dispatchers.IO)
	private val conflictResolutionMutex = Mutex()

	init {
		refresh()
	}

	fun refresh() = viewModelScope.launch(Dispatchers.IO) {
		val requestGeneration = synchronized(this@AuthoringViewModel) { ++refreshGeneration }
		mutableState.update { it.copy(loading = true, error = null) }
		val client = api
		if (client == null) {
			mutableState.update { it.copy(loading = false, error = "登录状态已失效") }
			return@launch
		}
		try {
			guard()
			val eligibilityResult = runCatching { loadEligibility(client) }
			val worksResult = runCatching { loadWorks(client) }
			guard()
			if (requestGeneration != synchronized(this@AuthoringViewModel) { refreshGeneration }) return@launch
			mutableState.update { state ->
				state.copy(
					loading = false,
					eligibility = eligibilityResult.getOrNull() ?: state.eligibility,
					works = worksResult.getOrNull()?.let { remote -> mergeWorks(remote, state.works) } ?: state.works,
					error = listOfNotNull(eligibilityResult.exceptionOrNull(), worksResult.exceptionOrNull()).firstOrNull()?.message,
				)
			}
		} catch (error: Exception) {
			if (requestGeneration == synchronized(this@AuthoringViewModel) { refreshGeneration }) mutableState.update { it.copy(loading = false, error = error.message ?: "创作中心加载失败") }
		}
	}

	private suspend fun loadEligibility(client: NovelAuthoringApi) = runInterruptible {
		client.executeJson(client.newEligibilityCall(), NovelAuthoringApi.EligibilityDto::class.java)
	}

	private suspend fun loadWorks(client: NovelAuthoringApi): List<NovelAuthoringApi.WorkDto> = runInterruptible {
		client.executeJson(client.newWorksCall(), NovelAuthoringApi.WorkListDto::class.java).items.orEmpty()
	}

	fun createWork(title: String, description: String, category: String) {
		val normalized = PendingCreate(title.trim(), description.trim(), category, "")
		if (normalized.title.isBlank()) return
		val pending = synchronized(this) {
			if (mutableState.value.creating) return
			val current = pendingCreate
			if (current != null && current.title == normalized.title && current.description == normalized.description && current.category == normalized.category) current
			else normalized.copy(idempotencyKey = UUID.randomUUID().toString()).also { pendingCreate = it }
		}
		mutableState.update { it.copy(creating = true, error = null) }
		viewModelScope.launch(Dispatchers.IO) {
			val client = api
			if (client == null) {
				mutableState.update { it.copy(creating = false, error = "登录状态已失效") }
				return@launch
			}
			try {
				guard()
				val created = runInterruptible {
					client.executeJson(client.newCreateWorkCall(NovelAuthoringApi.CreateWorkRequest(pending.title, pending.description, pending.category), pending.idempotencyKey), NovelAuthoringApi.WorkDto::class.java)
				}
				guard()
				synchronized(this@AuthoringViewModel) { pendingCreate = null }
				mutableState.update { state -> state.copy(creating = false, works = listOf(created) + state.works.filterNot { it.id == created.id }, createdWorkId = created.id) }
			} catch (error: Exception) {
				mutableState.update { it.copy(creating = false, error = error.message ?: "作品创建失败") }
			}
		}
	}

	fun consumeCreatedWork() = mutableState.update { it.copy(createdWorkId = null) }
	fun dismissError() = mutableState.update { it.copy(error = null) }
	fun reportImportError(message: String) = mutableState.update { it.copy(error = message) }

	fun openPublishChapter(workId: String, chapter: NovelAuthoringApi.ChapterDto) {
		if (mutableState.value.editingChapter?.id == chapter.id) return
		openChapter(workId, chapter)
	}

	fun importFileAsWork(uri: Uri, displayName: String, format: String) {
		if (mutableState.value.creating) return
		mutableState.update { it.copy(creating = true, error = null) }
		viewModelScope.launch(Dispatchers.IO) {
			try {
				guard()
				val parsed = withContext(Dispatchers.IO) { parseImportUri(uri, format) }
				guard()
				val title = parsed.book.title.ifBlank { displayName.substringBeforeLast('.').ifBlank { "未命名作品" } }
				val description = parsed.book.author?.takeIf { it.isNotBlank() }?.let { "作者：$it" } ?: "由文件导入"
				buildWorkFromChapters(title, description, parsed.chapters.map { it.title to it.content })
			} catch (error: Exception) {
				mutableState.update { it.copy(creating = false, error = error.message ?: "导入文件失败") }
			}
		}
	}

	fun convertPrivateBookToWork(book: NovelBookEntity) {
		if (mutableState.value.creating) return
		mutableState.update { it.copy(creating = true, error = null) }
		viewModelScope.launch(Dispatchers.IO) {
			try {
				guard()
				val chapters = database.novelChapterDao().getReaderChapters(book.id)
				if (chapters.isEmpty()) { mutableState.update { it.copy(creating = false, error = "这本书还没有可导入的章节，请先下载到本机") }; return@launch }
				val description = book.author?.takeIf { it.isNotBlank() }?.let { "作者：$it" } ?: "由私人书库转入"
				buildWorkFromChapters(book.title, description, chapters.map { it.title to it.content })
			} catch (error: Exception) {
				mutableState.update { it.copy(creating = false, error = error.message ?: "转入作品失败") }
			}
		}
	}

	private suspend fun parseImportUri(uri: Uri, format: String) = withContext(Dispatchers.IO) {
		val context = getApplication<Application>()
		val directory = File(context.cacheDir, "novel-authoring-import").apply { mkdirs() }
		val temp = File(directory, "import.${format.lowercase()}")
		try {
			context.contentResolver.openInputStream(uri)?.use { input ->
				temp.outputStream().use { output -> input.copyTo(output) }
			} ?: error("无法读取所选文件")
			BookParser().parse(temp)
		} finally {
			temp.delete()
		}
	}

	private suspend fun buildWorkFromChapters(title: String, description: String, chapters: List<Pair<String, String>>) {
		val client = api ?: run { mutableState.update { it.copy(creating = false, error = "登录状态已失效") }; return }
		try {
			guard()
			val work = runInterruptible { client.executeJson(client.newCreateWorkCall(NovelAuthoringApi.CreateWorkRequest(title, description, "fiction"), UUID.randomUUID().toString()), NovelAuthoringApi.WorkDto::class.java) }
			guard()
			val volume = runInterruptible { client.executeJson(client.newCreateVolumeCall(work.id, NovelAuthoringApi.TitleRequest("导入正文"), UUID.randomUUID().toString()), NovelAuthoringApi.VolumeDto::class.java) }
			guard()
			val now = System.currentTimeMillis()
			chapters.forEach { (chapterTitle, body) ->
				guard()
				val chapter = runInterruptible { client.executeJson(client.newCreateChapterCall(work.id, volume.id, NovelAuthoringApi.TitleRequest(chapterTitle.ifBlank { "未命名章节" }), UUID.randomUUID().toString()), NovelAuthoringApi.ChapterDto::class.java) }
				guard()
				val revision = runInterruptible { client.executeJson(client.newCreateRevisionCall(chapter.id, NovelAuthoringApi.RevisionBodyRequest(body), "import:${chapter.id}:${UUID.randomUUID()}"), NovelAuthoringApi.RevisionDto::class.java) }
				draftDao.upsertDraft(NovelAuthorRevisionDraftEntity(UUID.randomUUID().toString(), accountId, work.id, volume.id, chapter.id, revision.id, revision.version, 0, body, "draft", "clean", false, now, now, now))
			}
			mutableState.update { it.copy(creating = false, works = listOf(work) + it.works.filterNot { w -> w.id == work.id }, createdWorkId = work.id) }
		} catch (error: Exception) {
			mutableState.update { it.copy(creating = false, error = error.message ?: "导入作品失败") }
		}
	}

	fun openWork(workId: String) {
		synchronized(this) { structureGeneration++ }
		mutableState.update { it.copy(selectedWorkId = workId, structure = null, error = null) }
		loadStructure(workId)
	}

	fun closeWork() {
		synchronized(this) { structureGeneration++ }
		mutableState.update { it.copy(selectedWorkId = null, structure = null, structureLoading = false, structureOperating = false, error = null) }
	}

	fun openChapter(workId: String, chapter: NovelAuthoringApi.ChapterDto) {
		if (mutableState.value.editingChapter?.id == chapter.id && !mutableState.value.editorLoading) return
		editorLoadJob?.cancel()
		editorObserveJob?.cancel()
		mutableState.update { it.copy(selectedWorkId = workId, editingChapter = chapter, editorLoading = true, error = null) }
		editorLoadJob = viewModelScope.launch(Dispatchers.IO) {
			try {
				val inputKey = "$accountId\u0000${chapter.id}"
				val draft = chapterMutex(inputKey).withLock {
					guard()
				var stored = draftDao.getDraft(accountId, chapter.id)
				if (stored == null) {
					val now = System.currentTimeMillis()
					stored = NovelAuthorRevisionDraftEntity(UUID.randomUUID().toString(), accountId, workId, chapter.volumeId, chapter.id, null, 0, 0, "", "draft", "clean", false, now, now, null)
					draftDao.upsertDraft(stored)
				}
					stored
				}
			if (mutableState.value.editingChapter?.id != chapter.id) return@launch
			val conflict = draftDao.conflict(accountId, chapter.id)
			val draftRevisionId = draft.remoteRevisionId
			mutableState.update { it.copy(editorContent = draft.content, editorSyncState = draft.syncState, editorLoading = false, editorConflict = conflict, editorRevisionId = draftRevisionId) }
			if (draftRevisionId != null) fetchRevisionStatus(draftRevisionId)
			editorObserveJob = viewModelScope.launch(Dispatchers.IO) {
				draftDao.observeDraft(accountId, chapter.id).collectLatest { observed ->
					if (observed != null && mutableState.value.editingChapter?.id == chapter.id) {
						val revisionIdChanged = mutableState.value.editorRevisionId != observed.remoteRevisionId
						val observedRevisionId = observed.remoteRevisionId
						mutableState.update {
							it.copy(editorSyncState = observed.syncState, editorRevisionId = observed.remoteRevisionId, editorConflict = if (observed.syncState == "conflict") draftDao.conflict(accountId, chapter.id) else null)
						}
						if (revisionIdChanged && observedRevisionId != null) fetchRevisionStatus(observedRevisionId)
					}
				}
			}
			} catch (error: Exception) {
				if (mutableState.value.editingChapter?.id == chapter.id) mutableState.update { it.copy(editorLoading = false, error = error.message ?: "章节打开失败") }
			}
		}
	}

	fun closeChapter() {
		editorLoadJob?.cancel()
		editorObserveJob?.cancel()
		mutableState.update { it.copy(editingChapter = null, editorContent = "", editorConflict = null, editorLoading = false) }
	}

	fun saveChapterContent(content: String, immediate: Boolean = false) {
		val chapter = mutableState.value.editingChapter ?: return
		if (content.length > 500_000) return
		val inputKey = "$accountId\u0000${chapter.id}"
		val inputVersion = if (immediate) -1L else inputVersions.computeIfAbsent(inputKey) { AtomicLong() }.incrementAndGet()
		mutableState.update { it.copy(editorContent = content, editorSyncState = if (it.editorConflict == null) "pending" else "conflict") }
		localSaveScope.launch {
			val lease = NovelAccountDataCleaner.enterOperation(accountId, generation) ?: return@launch
			try {
				val shouldEnqueue = chapterMutex(inputKey).withLock {
					if (!immediate && inputVersion != inputVersions[inputKey]?.get()) return@withLock false
					guard()
					val current = draftDao.getDraft(accountId, chapter.id) ?: return@withLock false
					val localVersion = current.localVersion + 1
					val now = System.currentTimeMillis()
					val draft = current.copy(localVersion = localVersion, content = content, syncState = if (current.syncState == "conflict") "conflict" else "pending", dirty = true, updatedAt = now)
				val existingOutbox = draftDao.outbox(accountId, current.localId)
				val outboxState = if (current.syncState == "conflict") "blocked_conflict" else "pending"
				if (existingOutbox != null) {
					draftDao.saveLocalDraft(draft, existingOutbox.copy(content = draft.content, localVersion = draft.localVersion, baseVersion = draft.baseVersion, state = outboxState, updatedAt = now))
				} else {
					val op = if (current.remoteRevisionId == null) "create_revision" else "put_draft"
					val identity = if (op == "create_revision") "create:${current.localId}" else "draft:${current.localId}"
					val idempotencyKey = if (op == "create_revision") "chapter:${chapter.id}:initial" else UUID.randomUUID().toString()
					val outbox = NovelAuthorRevisionOutboxEntity(identity, accountId, current.localId, current.workId, current.chapterId, current.remoteRevisionId, op, idempotencyKey, current.baseVersion, localVersion, content, outboxState, 0, now, now)
					draftDao.saveLocalDraft(draft, outbox)
				}
				current.syncState != "conflict"
				}
					if (shouldEnqueue) {
						AuthorDraftSyncWorker.enqueue(getApplication(), accountId)
					}
			} finally { lease.close() }
		}
	}

	fun useServerConflict() = viewModelScope.launch(Dispatchers.IO) {
		conflictResolutionMutex.withLock {
			val chapter = mutableState.value.editingChapter ?: return@withLock
			mutableState.update { it.copy(editorResolving = true) }
			val lease = NovelAccountDataCleaner.enterOperation(accountId, generation) ?: run { mutableState.update { it.copy(editorResolving = false) }; return@withLock }
			try {
				guard()
				val conflict = draftDao.conflict(accountId, chapter.id) ?: return@withLock
				draftDao.resolveUsingServer(accountId, conflict.localDraftId, conflict.conflictId, conflict.localVersion, conflict.serverContent, conflict.serverVersion, System.currentTimeMillis())
				mutableState.update { it.copy(editorContent = conflict.serverContent, editorSyncState = "clean", editorConflict = null) }
			} finally { lease.close(); mutableState.update { it.copy(editorResolving = false) } }
		}
	}

	fun keepLocalConflict() = viewModelScope.launch(Dispatchers.IO) {
		conflictResolutionMutex.withLock {
			val chapter = mutableState.value.editingChapter ?: return@withLock
			mutableState.update { it.copy(editorResolving = true) }
			val lease = NovelAccountDataCleaner.enterOperation(accountId, generation) ?: run { mutableState.update { it.copy(editorResolving = false) }; return@withLock }
			try {
				val conflict = draftDao.conflict(accountId, chapter.id) ?: return@withLock
				guard()
				val client = api ?: error("登录状态已失效")
				val revision = runInterruptible { client.executeDraft(client.newCreateRevisionCall(chapter.id, NovelAuthoringApi.RevisionBodyRequest(conflict.localContent), "conflict:${conflict.conflictId}")) }
				guard()
				val current = draftDao.getDraft(accountId, chapter.id) ?: return@withLock
				draftDao.resolveUsingSibling(accountId, current.localId, conflict.conflictId, conflict.localVersion, revision.id, revision.body, revision.version, System.currentTimeMillis())
				mutableState.update { it.copy(editorContent = revision.body, editorSyncState = "clean", editorConflict = null) }
			} catch (error: Exception) { mutableState.update { it.copy(error = error.message ?: "本地副本保存失败") } }
			finally { lease.close(); mutableState.update { it.copy(editorResolving = false) } }
		}
	}

	fun refreshEditorConflict() = viewModelScope.launch(Dispatchers.IO) {
		val chapter = mutableState.value.editingChapter ?: return@launch
		mutableState.update { it.copy(editorConflict = draftDao.conflict(accountId, chapter.id)) }
	}

	fun saveChapterContentNow(content: String) {
		val chapter = mutableState.value.editingChapter ?: return
		if (mutableState.value.editorSaving || mutableState.value.editorLoading) return
		val inputKey = "$accountId\u0000${chapter.id}"
		mutableState.update { it.copy(editorContent = content, editorSyncState = if (it.editorConflict == null) "pending" else "conflict", editorSaving = true, error = null) }
		viewModelScope.launch(Dispatchers.IO) {
			val lease = NovelAccountDataCleaner.enterOperation(accountId, generation) ?: run { mutableState.update { it.copy(editorSaving = false) }; return@launch }
			val pushedRevisionId: String?
			try {
				pushedRevisionId = chapterMutex(inputKey).withLock {
					guard()
					val current = draftDao.getDraft(accountId, chapter.id)
					val now = System.currentTimeMillis()
					if (current == null) {
						val draft = NovelAuthorRevisionDraftEntity(UUID.randomUUID().toString(), accountId, mutableState.value.selectedWorkId.orEmpty(), chapter.volumeId, chapter.id, null, 0, 1, content, "draft", "pending", true, now, now, null)
						val outbox = NovelAuthorRevisionOutboxEntity("create:${draft.localId}", accountId, draft.localId, draft.workId, draft.chapterId, null, "create_revision", "chapter:${chapter.id}:initial", 0, 1, content, "pending", 0, now, now)
						draftDao.saveLocalDraft(draft, outbox)
						null
					} else {
						val localVersion = current.localVersion + 1
						val draft = current.copy(localVersion = localVersion, content = content, syncState = if (current.syncState == "conflict") "conflict" else "pending", dirty = true, updatedAt = now)
						val existingOutbox = draftDao.outbox(accountId, current.localId)
						val outboxState = if (current.syncState == "conflict") "blocked_conflict" else "pending"
						if (existingOutbox != null) {
							draftDao.saveLocalDraft(draft, existingOutbox.copy(content = content, localVersion = localVersion, baseVersion = draft.baseVersion, state = outboxState, updatedAt = now))
						} else {
							val op = if (current.remoteRevisionId == null) "create_revision" else "put_draft"
							val identity = if (op == "create_revision") "create:${current.localId}" else "draft:${current.localId}"
							val idempotencyKey = if (op == "create_revision") "chapter:${chapter.id}:initial" else UUID.randomUUID().toString()
							val outbox = NovelAuthorRevisionOutboxEntity(identity, accountId, current.localId, current.workId, current.chapterId, current.remoteRevisionId, op, idempotencyKey, current.baseVersion, localVersion, content, outboxState, 0, now, now)
							draftDao.saveLocalDraft(draft, outbox)
						}
						current.remoteRevisionId
					}
				}
			} catch (error: Exception) {
				mutableState.update { it.copy(editorSaving = false, error = error.message ?: "保存失败") }
				return@launch
			} finally { lease.close() }
			val synced = pushDraftSync(accountId, chapter.id)
			if (synced) {
				val stored = draftDao.getDraft(accountId, chapter.id)
				mutableState.update { it.copy(editorSaving = false, editorSyncState = if (stored?.syncState == "conflict") "conflict" else "clean", editorRevisionId = stored?.remoteRevisionId ?: mutableState.value.editorRevisionId, editorSaveToast = mutableState.value.editorSaveToast + 1) }
			} else {
				mutableState.update { it.copy(editorSaving = false, error = "云端同步未完成，将在后台重试") }
			}
		}
	}

	private suspend fun pushDraftSync(accountId: String, chapterId: String): Boolean {
		return try {
			AuthorDraftSyncWorker.enqueueSyncNow(getApplication(), accountId)
			val deadline = System.currentTimeMillis() + 15_000
			var lastSync: String? = "pending"
			while (System.currentTimeMillis() < deadline) {
				val draft = draftDao.getDraft(accountId, chapterId)
				lastSync = draft?.syncState
				if (draft != null && draft.syncState != "pending" && draft.dirty == false) return draft.syncState == "clean"
				if (draft?.syncState == "conflict") return false
				kotlinx.coroutines.delay(300)
			}
			false
		} catch (_: Exception) { false }
	}

	private fun fetchRevisionStatus(revisionId: String) = viewModelScope.launch(Dispatchers.IO) {
		val client = api ?: return@launch
		try {
			guard()
			val result = runInterruptible { client.executeJson(client.newGetRevisionCall(revisionId), NovelAuthoringApi.RevisionDto::class.java) }
			if (mutableState.value.editorRevisionId == revisionId) mutableState.update { it.copy(editorRevisionStatus = result.status) }
		} catch (_: Exception) { }
	}

	fun submitForReview() {
		val chapter = mutableState.value.editingChapter ?: return
		if (mutableState.value.editorPublishing) return
		mutableState.update { it.copy(editorPublishing = true, error = null) }
		viewModelScope.launch(Dispatchers.IO) {
			val client = api ?: run { mutableState.update { it.copy(editorPublishing = false, error = "登录状态已失效") }; return@launch }
			try {
				guard()
				var revisionId = mutableState.value.editorRevisionId
				if (revisionId == null) {
					val content = mutableState.value.editorContent
					val inputKey = "$accountId\u0000${chapter.id}"
					chapterMutex(inputKey).withLock {
						val current = draftDao.getDraft(accountId, chapter.id)
						val now = System.currentTimeMillis()
						if (current != null && current.remoteRevisionId == null) {
							val localVersion = current.localVersion + 1
							val draft = current.copy(localVersion = localVersion, content = content.ifBlank { current.content }, syncState = "pending", dirty = true, updatedAt = now)
							val existingOutbox = draftDao.outbox(accountId, current.localId)
							if (existingOutbox != null) {
								draftDao.saveLocalDraft(draft, existingOutbox.copy(content = draft.content, localVersion = localVersion, baseVersion = draft.baseVersion, state = "pending", updatedAt = now))
							} else {
								val outbox = NovelAuthorRevisionOutboxEntity("create:${current.localId}", accountId, current.localId, current.workId, current.chapterId, null, "create_revision", "chapter:${chapter.id}:initial", current.baseVersion, localVersion, draft.content, "pending", 0, now, now)
								draftDao.saveLocalDraft(draft, outbox)
							}
						}
					}
					AuthorDraftSyncWorker.enqueueSyncNow(getApplication(), accountId)
					val deadline = System.currentTimeMillis() + 20_000
					while (System.currentTimeMillis() < deadline) {
						val draft = draftDao.getDraft(accountId, chapter.id)
						if (draft?.remoteRevisionId != null && draft.syncState != "pending") {
							revisionId = draft.remoteRevisionId
							mutableState.update { it.copy(editorRevisionId = revisionId, editorSyncState = draft.syncState) }
							break
						}
						if (draft?.syncState == "conflict") { mutableState.update { it.copy(editorPublishing = false, error = "存在同步冲突，请先处理冲突") }; return@launch }
						kotlinx.coroutines.delay(500)
					}
				}
				if (revisionId == null) { mutableState.update { it.copy(editorPublishing = false, error = "草稿同步超时，请检查网络后重试") }; return@launch }
				val result = runInterruptible { client.executeJson(client.newSubmitCall(chapter.id, revisionId, "submit:${UUID.randomUUID()}"), NovelAuthoringApi.RevisionDto::class.java) }
				guard()
				mutableState.update { it.copy(editorPublishing = false, editorRevisionStatus = result.status) }
			} catch (error: Exception) { mutableState.update { it.copy(editorPublishing = false, error = error.message ?: "提交审核失败") } }
		}
	}

	fun publishRevision() {
		val revisionId = mutableState.value.editorRevisionId ?: return
		if (mutableState.value.editorPublishing) return
		mutableState.update { it.copy(editorPublishing = true, error = null) }
		viewModelScope.launch(Dispatchers.IO) {
			val client = api ?: run { mutableState.update { it.copy(editorPublishing = false, error = "登录状态已失效") }; return@launch }
			try {
				guard()
				val result = runInterruptible { client.executeJson(client.newPublishCall(revisionId, "publish:${UUID.randomUUID()}"), NovelAuthoringApi.RevisionDto::class.java) }
				guard()
				mutableState.update { it.copy(editorPublishing = false, editorRevisionStatus = result.status) }
			} catch (error: Exception) { mutableState.update { it.copy(editorPublishing = false, error = error.message ?: "发布失败") } }
		}
	}

	fun loadStructure(requestedWorkId: String? = null) = viewModelScope.launch(Dispatchers.IO) {
		val workId = requestedWorkId ?: mutableState.value.selectedWorkId ?: return@launch
		val requestGeneration = synchronized(this@AuthoringViewModel) { ++structureGeneration }
		val client = api ?: return@launch
		mutableState.update { it.copy(structureLoading = true, error = null) }
		try {
			guard()
			val structure = runInterruptible { client.executeJson(client.newStructureCall(workId), NovelAuthoringApi.StructureDto::class.java) }
			requireValidStructure(structure)
			guard()
			if (mutableState.value.selectedWorkId == workId && requestGeneration == synchronized(this@AuthoringViewModel) { structureGeneration }) mutableState.update { it.copy(structure = structure, structureLoading = false) }
		} catch (error: Exception) {
			if (mutableState.value.selectedWorkId == workId && requestGeneration == synchronized(this@AuthoringViewModel) { structureGeneration }) mutableState.update { it.copy(structureLoading = false, error = error.message ?: "作品目录加载失败") }
		}
	}

	fun createVolume(title: String) {
		val workId = mutableState.value.selectedWorkId ?: return
		val pending = stableStructureCreate("volume", workId, null, title.trim())
		structureAction(clearPendingOnSuccess = true) { client, _ -> client.executeJson(client.newCreateVolumeCall(workId, NovelAuthoringApi.TitleRequest(pending.title), pending.idempotencyKey), NovelAuthoringApi.VolumeDto::class.java) }
	}

	fun createChapter(volumeId: String, title: String) {
		val workId = mutableState.value.selectedWorkId ?: return
		val pending = stableStructureCreate("chapter", workId, volumeId, title.trim())
		structureAction(clearPendingOnSuccess = true) { client, _ -> client.executeJson(client.newCreateChapterCall(workId, volumeId, NovelAuthoringApi.TitleRequest(pending.title), pending.idempotencyKey), NovelAuthoringApi.ChapterDto::class.java) }
	}

	fun renameVolume(volumeId: String, title: String) = structureAction { client, workId ->
		client.executeJson(client.newRenameVolumeCall(workId, volumeId, NovelAuthoringApi.TitleRequest(title.trim())), NovelAuthoringApi.VolumeDto::class.java)
	}

	fun renameChapter(volumeId: String, chapterId: String, title: String) = structureAction { client, workId ->
		client.executeJson(client.newRenameChapterCall(workId, volumeId, chapterId, NovelAuthoringApi.TitleRequest(title.trim())), NovelAuthoringApi.ChapterDto::class.java)
	}

	fun deleteVolume(volumeId: String) = structureAction { client, workId ->
		client.executeJson(client.newDeleteVolumeCall(workId, volumeId), NovelAuthoringApi.DeleteDto::class.java)
	}

	fun deleteChapter(volumeId: String, chapterId: String) = structureAction { client, workId ->
		client.executeJson(client.newDeleteChapterCall(workId, volumeId, chapterId), NovelAuthoringApi.DeleteDto::class.java)
	}

	private fun structureAction(clearPendingOnSuccess: Boolean = false, block: (NovelAuthoringApi, String) -> Any) {
		val workId = mutableState.value.selectedWorkId ?: return
		if (mutableState.value.structureOperating) return
		mutableState.update { it.copy(structureOperating = true, error = null) }
		viewModelScope.launch(Dispatchers.IO) {
			val client = api
			if (client == null) { mutableState.update { it.copy(structureOperating = false, error = "登录状态已失效") }; return@launch }
			try {
				guard(); runInterruptible { block(client, workId) }; guard()
				if (clearPendingOnSuccess) synchronized(this@AuthoringViewModel) { pendingStructureCreate = null }
				mutableState.update { it.copy(structureOperating = false) }
				if (mutableState.value.selectedWorkId == workId) loadStructure(workId)
			} catch (error: Exception) { mutableState.update { it.copy(structureOperating = false, error = error.message ?: "作品目录操作失败") } }
		}
	}

	private fun guard() {
		check(session != null && NovelAccountDataCleaner.isSessionValid(accountId, session, generation)) { "账号已退出" }
	}

	private fun mergeWorks(remote: List<NovelAuthoringApi.WorkDto>, current: List<NovelAuthoringApi.WorkDto>): List<NovelAuthoringApi.WorkDto> =
		(remote + current).distinctBy { it.id }.sortedByDescending { it.updatedAt }

	private data class PendingCreate(val title: String, val description: String, val category: String, val idempotencyKey: String)

	private fun stableStructureCreate(type: String, workId: String, volumeId: String?, title: String): PendingStructureCreate = synchronized(this) {
		val current = pendingStructureCreate
		if (current != null && current.type == type && current.workId == workId && current.volumeId == volumeId && current.title == title) current
		else PendingStructureCreate(type, workId, volumeId, title, UUID.randomUUID().toString()).also { pendingStructureCreate = it }
	}

	private fun requireValidStructure(structure: NovelAuthoringApi.StructureDto) {
		require(!structure.work?.id.isNullOrBlank()) { "作品目录响应无效" }
		structure.volumes.orEmpty().forEach { volume ->
			require(!volume.id.isNullOrBlank() && !volume.title.isNullOrBlank()) { "分卷数据无效" }
			volume.chapters.orEmpty().forEach { chapter -> require(!chapter.id.isNullOrBlank() && !chapter.title.isNullOrBlank() && chapter.volumeId == volume.id) { "章节数据无效" } }
		}
	}

	private data class PendingStructureCreate(val type: String, val workId: String, val volumeId: String?, val title: String, val idempotencyKey: String)

	override fun onCleared() {
		editorLoadJob?.cancel()
		editorObserveJob?.cancel()
		localSaveParent.complete()
		localSaveParent.invokeOnCompletion { database.close() }
		super.onCleared()
	}

	companion object {
		private val inputVersions = ConcurrentHashMap<String, AtomicLong>()
		private val chapterMutexes = ConcurrentHashMap<String, Mutex>()
		private fun chapterMutex(key: String) = chapterMutexes.computeIfAbsent(key) { Mutex() }
	}
}
