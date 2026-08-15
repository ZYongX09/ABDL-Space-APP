package org.joinmastodon.android.novel.author

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
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
import org.joinmastodon.reader.data.NovelDatabase

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
						stored = NovelAuthorRevisionDraftEntity(UUID.randomUUID().toString(), accountId, workId, chapter.volumeId, chapter.id, null, 0, 0, "", "draft", "pending", true, now, now, null)
						val outbox = NovelAuthorRevisionOutboxEntity("create:${stored.localId}", accountId, stored.localId, stored.workId, stored.chapterId, null, "create_revision", "chapter:${chapter.id}:initial", 0, 0, "", "pending", 0, now, now)
						draftDao.saveLocalDraft(stored, outbox)
						AuthorDraftSyncWorker.enqueue(getApplication(), accountId)
					}
					stored
				}
				if (mutableState.value.editingChapter?.id != chapter.id) return@launch
				val conflict = draftDao.conflict(accountId, chapter.id)
				mutableState.update { it.copy(editorContent = draft.content, editorSyncState = draft.syncState, editorLoading = false, editorConflict = conflict) }
				editorObserveJob = viewModelScope.launch(Dispatchers.IO) {
					draftDao.observeDraft(accountId, chapter.id).collectLatest { observed ->
						if (observed != null && mutableState.value.editingChapter?.id == chapter.id) mutableState.update {
							it.copy(editorSyncState = observed.syncState, editorConflict = if (observed.syncState == "conflict") draftDao.conflict(accountId, chapter.id) else null)
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

	fun saveChapterContent(content: String) {
		val chapter = mutableState.value.editingChapter ?: return
		if (content.length > 500_000) return
		val inputKey = "$accountId\u0000${chapter.id}"
		val inputVersion = inputVersions.computeIfAbsent(inputKey) { AtomicLong() }.incrementAndGet()
		mutableState.update { it.copy(editorContent = content, editorSyncState = if (it.editorConflict == null) "pending" else "conflict") }
		localSaveScope.launch {
			val lease = NovelAccountDataCleaner.enterOperation(accountId, generation) ?: return@launch
			try {
				val shouldEnqueue = chapterMutex(inputKey).withLock {
					if (inputVersion != inputVersions[inputKey]?.get()) return@withLock false
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
