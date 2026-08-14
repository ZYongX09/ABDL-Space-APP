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
	val error: String? = null,
)

class AuthoringViewModel(application: Application, val accountId: String) : AndroidViewModel(application) {
	private val session = AccountSessionManager.getInstance().tryGetAccount(accountId)
	private val api = session?.let(::NovelAuthoringApi)
	private val generation = NovelAccountDataCleaner.captureGeneration(accountId)
	private val mutableState = MutableStateFlow(AuthoringState())
	val state: StateFlow<AuthoringState> = mutableState.asStateFlow()
	private var refreshGeneration = 0L
	private var pendingCreate: PendingCreate? = null

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

	private fun guard() {
		check(session != null && NovelAccountDataCleaner.isSessionValid(accountId, session, generation)) { "账号已退出" }
	}

	private fun mergeWorks(remote: List<NovelAuthoringApi.WorkDto>, current: List<NovelAuthoringApi.WorkDto>): List<NovelAuthoringApi.WorkDto> =
		(remote + current).distinctBy { it.id }.sortedByDescending { it.updatedAt }

	private data class PendingCreate(val title: String, val description: String, val category: String, val idempotencyKey: String)
}
