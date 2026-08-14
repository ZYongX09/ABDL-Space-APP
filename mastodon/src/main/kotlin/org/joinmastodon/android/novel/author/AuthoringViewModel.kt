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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.joinmastodon.android.api.novels.NovelAuthoringApi
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.novel.NovelAccountDataCleaner

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
	val error: String? = null,
)

class AuthoringViewModel(application: Application, val accountId: String) : AndroidViewModel(application) {
	private val session = AccountSessionManager.getInstance().tryGetAccount(accountId)
	private val api = session?.let(::NovelAuthoringApi)
	private val generation = NovelAccountDataCleaner.captureGeneration(accountId)
	private val mutableState = MutableStateFlow(AuthoringState())
	val state: StateFlow<AuthoringState> = mutableState.asStateFlow()
	private var refreshGeneration = 0L
	private var structureGeneration = 0L
	private var pendingCreate: PendingCreate? = null
	private var pendingStructureCreate: PendingStructureCreate? = null

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
}
