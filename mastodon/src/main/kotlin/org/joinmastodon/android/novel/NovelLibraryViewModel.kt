package org.joinmastodon.android.novel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.joinmastodon.android.api.novels.PrivateNovelApi
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.novel.download.NovelDownloadWorker
import org.joinmastodon.android.novel.importer.NovelImportCoordinator
import org.joinmastodon.android.novel.sync.NovelSyncWorker
import org.joinmastodon.reader.data.NovelBookEntity
import org.joinmastodon.reader.data.NovelDatabase

data class NovelLibraryState(
	val books: List<NovelBookEntity> = emptyList(),
	val refreshing: Boolean = false,
	val error: String? = null,
)

class NovelLibraryViewModel(application: Application, val accountId: String) : AndroidViewModel(application) {
	private val database = NovelDatabase.open(application, accountId)
	private val session = AccountSessionManager.getInstance().tryGetAccount(accountId)
	private val api = session?.let(::PrivateNovelApi)
	private val coordinator = NovelImportCoordinator(application)
	private val mutableState = MutableStateFlow(NovelLibraryState())
	val state: StateFlow<NovelLibraryState> = mutableState.asStateFlow()

	init {
		viewModelScope.launch {
			database.novelBookDao().observeActive(accountId).catch { mutableState.update { state -> state.copy(error = it.message) } }
				.collect { books -> mutableState.update { it.copy(books = books) } }
		}
		NovelSyncWorker.enqueue(application, accountId)
		refresh()
	}

	fun refresh() = viewModelScope.launch(Dispatchers.IO) {
		val client = api ?: return@launch
		mutableState.update { it.copy(refreshing = true, error = null) }
		try {
			var cursor: String? = null
			do {
				val page = runInterruptible { client.executeJson(client.newBooksCall(cursor, 100), PrivateNovelApi.BooksPage::class.java) }
				page.items.orEmpty().forEach { remote ->
					val existing = database.novelBookDao().getByRemoteId(accountId, "private", remote.id)
					database.novelBookDao().upsert(NovelBookEntity(
						id = existing?.id ?: UUID.randomUUID().toString(), accountId = accountId, title = remote.title.orEmpty(), author = remote.author,
						remoteId = remote.id, sourceType = "private", contentHash = remote.contentHash, localFilePath = existing?.localFilePath,
						downloadState = existing?.downloadState ?: "remote", remoteUpdatedAt = remote.updatedAt, updatedAt = remote.updatedAt,
					))
				}
				cursor = page.nextCursor
			} while (cursor != null)
			NovelSyncWorker.enqueue(getApplication(), accountId)
		} catch (error: Exception) {
			mutableState.update { it.copy(error = error.message) }
		} finally {
			mutableState.update { it.copy(refreshing = false) }
		}
	}

	fun paste(title: String, author: String?, text: String) = viewModelScope.launch(Dispatchers.IO) {
		val client = api ?: return@launch
		try {
			val remote = runInterruptible { client.executeJson(client.newPasteCall(PrivateNovelApi.PasteRequest(title, author, text)), PrivateNovelApi.BookDto::class.java) }
			database.novelBookDao().upsert(NovelBookEntity(UUID.randomUUID().toString(), accountId, remote.title.orEmpty(), remote.author, remoteId = remote.id, sourceType = "private", contentHash = remote.contentHash, downloadState = "remote", remoteUpdatedAt = remote.updatedAt, updatedAt = remote.updatedAt))
		} catch (error: Exception) { mutableState.update { it.copy(error = error.message) } }
	}

	fun upload(uri: Uri, format: String) = viewModelScope.launch {
		runCatching { coordinator.uploadContentUri(accountId, uri, PrivateNovelApi.UploadMetadata(uri.lastPathSegment ?: "未命名小说", null, format, if (format == "epub") "application/epub+zip" else "text/plain"), 0) {} }
			.onFailure { error -> mutableState.update { it.copy(error = error.message) } }
	}

	fun download(book: NovelBookEntity) { book.remoteId?.let { NovelDownloadWorker.enqueue(getApplication(), accountId, it) } }

	fun delete(book: NovelBookEntity) = viewModelScope.launch(Dispatchers.IO) {
		try {
			book.remoteId?.let { api?.executeEmpty(api.newDeleteBookCall(it)) }
			database.novelBookDao().deleteById(accountId, book.id)
		} catch (error: Exception) { mutableState.update { it.copy(error = error.message) } }
	}

	override fun onCleared() {
		database.close()
		super.onCleared()
	}
}
