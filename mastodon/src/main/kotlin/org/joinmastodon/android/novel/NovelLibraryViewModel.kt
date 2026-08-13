package org.joinmastodon.android.novel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import java.nio.charset.StandardCharsets
import java.io.File
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
import org.joinmastodon.android.novel.sync.NovelReaderSyncController
import org.joinmastodon.android.novel.sync.NovelSyncWriteFacade
import org.joinmastodon.reader.data.NovelBookEntity
import org.joinmastodon.reader.data.NovelDatabase
import org.joinmastodon.reader.domain.BookFormat
import org.joinmastodon.reader.domain.ReaderBook
import org.joinmastodon.reader.domain.ReaderChapter
import org.joinmastodon.reader.domain.ReaderPosition

data class NovelLibraryState(
	val books: List<NovelBookEntity> = emptyList(),
	val bookDetails: Map<String, NovelBookDetails> = emptyMap(),
	val refreshing: Boolean = false,
	val error: String? = null,
	val reader: NovelReaderState? = null,
	val openingBookId: String? = null,
)

data class NovelBookDetails(val chapterCount: Int, val localBytes: Long?)

data class NovelReaderState(val book: ReaderBook, val chapters: List<ReaderChapter>)

class NovelLibraryViewModel(application: Application, val accountId: String) : AndroidViewModel(application) {
	private val database = NovelDatabase.open(application, accountId)
	private val session = AccountSessionManager.getInstance().tryGetAccount(accountId)
	private val api = session?.let(::PrivateNovelApi)
	private val coordinator = NovelImportCoordinator(application)
	private val generation = NovelAccountDataCleaner.captureGeneration(accountId)
	val syncWrites = NovelSyncWriteFacade(accountId, database, requestSync = { NovelSyncWorker.enqueue(application, accountId) })
	private val readerSync = NovelReaderSyncController(viewModelScope, syncWrites)
	private val mutableState = MutableStateFlow(NovelLibraryState())
	val state: StateFlow<NovelLibraryState> = mutableState.asStateFlow()

	init {
		viewModelScope.launch {
			database.novelBookDao().observeActive(accountId).catch { mutableState.update { state -> state.copy(error = it.message) } }
				.collect { books ->
					val details = books.associate { book ->
						val localFile = book.localFilePath?.let(::File)?.takeIf(File::isFile)
						book.id to NovelBookDetails(database.novelChapterDao().countByBookId(book.id), localFile?.length())
					}
					mutableState.update { it.copy(books = books, bookDetails = details) }
				}
		}
		NovelSyncWorker.enqueue(application, accountId)
	}

	fun refresh() = viewModelScope.launch(Dispatchers.IO) {
		mutableState.update { it.copy(refreshing = true, error = null) }
		try {
			guard()
			NovelSyncWorker.enqueue(getApplication(), accountId)
			guard()
		} catch (error: Exception) {
			mutableState.update { it.copy(error = error.message) }
		} finally {
			mutableState.update { it.copy(refreshing = false) }
		}
	}

	fun paste(title: String, author: String, text: String) = viewModelScope.launch(Dispatchers.IO) {
		val client = api ?: return@launch
		try {
			guard()
			val remote = runInterruptible { client.executeJson(client.newPasteCall(PrivateNovelApi.PasteRequest(title, author, text)), PrivateNovelApi.BookDto::class.java) }
			guard()
			coordinator.importPastedText(accountId, text, remote)
			guard()
		} catch (error: Exception) { mutableState.update { it.copy(error = error.message) } }
	}

	fun upload(uri: Uri, title: String, author: String, format: String, mimeType: String) = viewModelScope.launch {
		runCatching { guard(); coordinator.uploadContentUri(accountId, uri, PrivateNovelApi.UploadMetadata(title, author, format, mimeType), 0) {}; guard() }
			.onFailure { error -> mutableState.update { it.copy(error = error.message) } }
	}

	fun download(book: NovelBookEntity) {
		book.remoteId?.let { remoteId ->
			viewModelScope.launch(Dispatchers.IO) {
				database.novelBookDao().updatePrivateDownloadState(accountId, remoteId, "downloading", System.currentTimeMillis())
				NovelDownloadWorker.enqueue(getApplication(), accountId, remoteId)
			}
		}
	}

	fun openReader(book: NovelBookEntity) {
		if (mutableState.value.openingBookId != null) return
		mutableState.update { it.copy(openingBookId = book.id, error = null) }
		viewModelScope.launch(Dispatchers.IO) {
			try {
				val chapters = database.novelChapterDao().getReaderChapters(book.id)
				if (chapters.isEmpty()) {
					mutableState.update { it.copy(error = "这本小说尚未保存到本机") }
					return@launch
				}
				val format = if (book.localFilePath?.endsWith(".epub", true) == true) BookFormat.EPUB else BookFormat.TXT
				mutableState.update { state -> state.copy(reader = NovelReaderState(
					ReaderBook(book.id, book.title, book.author, format),
					chapters.map { ReaderChapter(it.id, it.bookId, it.chapterIndex, it.title, it.content, it.id) },
				)) }
			} catch (error: Exception) {
				mutableState.update { it.copy(error = error.message ?: "无法打开这本小说") }
			} finally {
				mutableState.update { it.copy(openingBookId = null) }
			}
		}
	}

	fun closeReader() { mutableState.update { it.copy(reader = null) } }
	fun onReaderPositionChanged(bookId: String, position: ReaderPosition) = readerSync.onPositionChanged(bookId, position)
	fun addBookmark(bookId: String, chapterId: String, position: ReaderPosition) = viewModelScope.launch(Dispatchers.IO) {
		runCatching { syncWrites.saveBookmark(stableReaderItemId("bookmark", bookId, chapterId, position), bookId, chapterId, position.pageIndex) }
			.onFailure { reportError(it.message ?: "添加书签失败") }
	}
	fun addNote(bookId: String, chapterId: String, position: ReaderPosition, note: String) = viewModelScope.launch(Dispatchers.IO) {
		if (note.isBlank()) return@launch
		runCatching { syncWrites.saveAnnotation(stableReaderItemId("note", bookId, chapterId, position), bookId, chapterId, position.pageIndex, position.pageIndex, "", note) }
			.onFailure { reportError(it.message ?: "添加笔记失败") }
	}
	fun reportError(message: String) { mutableState.update { it.copy(error = message) } }
	fun dismissError() { mutableState.update { it.copy(error = null) } }

	fun delete(book: NovelBookEntity) = viewModelScope.launch(Dispatchers.IO) {
		try {
			guard()
			book.remoteId?.let { api?.executeEmpty(api.newDeleteBookCall(it)) }
			guard()
			database.novelBookDao().deleteById(accountId, book.id)
			guard()
		} catch (error: Exception) { mutableState.update { it.copy(error = error.message) } }
	}

	override fun onCleared() {
		database.close()
		super.onCleared()
	}

	private fun guard() {
		check(session != null && NovelAccountDataCleaner.isSessionValid(accountId, session, generation)) { "账号已退出" }
	}

	private fun stableReaderItemId(type: String, bookId: String, chapterId: String, position: ReaderPosition): String =
		UUID.nameUUIDFromBytes("$accountId:$type:$bookId:$chapterId:${position.chapterIndex}:${position.pageIndex}".toByteArray(StandardCharsets.UTF_8)).toString()
}
