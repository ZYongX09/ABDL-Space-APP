package org.joinmastodon.android.novel

import android.graphics.Color
import android.content.Intent
import android.os.Build
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.joinmastodon.android.api.novels.NovelV2Api
import org.joinmastodon.reader.domain.BookFormat
import org.joinmastodon.reader.domain.ReaderBook
import org.joinmastodon.reader.domain.ReaderChapter
import org.joinmastodon.reader.domain.ReaderPalette
import org.joinmastodon.reader.ui.ReaderScreen
import org.joinmastodon.reader.data.NovelDatabase
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.novel.author.AuthoringViewModel
import org.joinmastodon.android.ui.compose.MiuixAppTheme
import org.joinmastodon.android.ui.utils.UiUtils

class NovelActivity : ComponentActivity(), NavigationEventDispatcherOwner {
	override val navigationEventDispatcher = NavigationEventDispatcher { finish() }

	override fun onCreate(savedInstanceState: Bundle?) {
		val externalDocument = if (savedInstanceState == null) externalDocument(intent) else null
		val accountID = if (externalDocument != null) AccountSessionManager.getInstance().lastActiveAccountID else intent.getStringExtra(EXTRA_ACCOUNT_ID)
		val session = accountID?.let {
			runCatching { AccountSessionManager.getInstance().getAccount(it) }.getOrNull()
		}
		UiUtils.setUserPreferredTheme(this, session)
		super.onCreate(savedInstanceState)
		if (session == null) {
			finish()
			return
		}
		val libraryViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
			override fun <T : ViewModel> create(modelClass: Class<T>): T {
				@Suppress("UNCHECKED_CAST")
				return NovelLibraryViewModel(application, accountID) as T
			}
		})[NovelLibraryViewModel::class.java]
		val authoringViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
			override fun <T : ViewModel> create(modelClass: Class<T>): T {
				@Suppress("UNCHECKED_CAST")
				return AuthoringViewModel(application, accountID) as T
			}
		})[AuthoringViewModel::class.java]
		val storeViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
			override fun <T : ViewModel> create(modelClass: Class<T>): T {
				@Suppress("UNCHECKED_CAST")
				return NovelStoreViewModel(application, accountID) as T
			}
		})[NovelStoreViewModel::class.java]

		val darkTheme = UiUtils.isDarkTheme()
		enableEdgeToEdge(
			statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
			navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
		)
			val squareWork = intent.getStringExtra(EXTRA_SQUARE_WORK)
			val squareRelease = intent.getStringExtra(EXTRA_SQUARE_RELEASE)
			val squareChapter = intent.getStringExtra(EXTRA_SQUARE_CHAPTER)
			val privateBook = intent.getStringExtra(EXTRA_PRIVATE_BOOK)
			setContent {
				CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides this) {
					MiuixAppTheme {
						if (squareWork != null && squareRelease != null && squareChapter != null) {
							SquareReaderHost(session, squareWork, squareRelease, squareChapter, ::finish)
						} else if (privateBook != null) {
							PrivateReaderHost(application, accountID, privateBook, ::finish)
						} else {
							NovelHomeScreen(accountId = accountID, libraryViewModel = libraryViewModel, authoringViewModel = authoringViewModel, storeViewModel = storeViewModel, externalDocument = externalDocument, onBack = ::finish)
						}
					}
				}
			}
	}

	private fun externalDocument(intent: Intent): Uri? = when (intent.action) {
		Intent.ACTION_VIEW -> intent.data
		Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
			else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
		else -> null
	}

	companion object {
		const val EXTRA_ACCOUNT_ID = "account"
		const val EXTRA_SQUARE_WORK = "square_work"
		const val EXTRA_SQUARE_RELEASE = "square_release"
		const val EXTRA_SQUARE_CHAPTER = "square_chapter"
		const val EXTRA_PRIVATE_BOOK = "private_book"
	}
}

@androidx.compose.runtime.Composable
private fun SquareReaderHost(session: org.joinmastodon.android.api.session.AccountSession, workId: String, releaseId: String, initialChapterId: String, onBack: () -> Unit) {
	var state by remember { mutableStateOf<SquareReaderState?>(null) }
	var failed by remember { mutableStateOf(false) }
	LaunchedEffect(workId, releaseId, initialChapterId) {
		try {
			state = withContext(Dispatchers.IO) {
				val api = NovelV2Api(session)
				val work = api.squareWork(workId)
				require(work.release_id == releaseId)
				val chapter = work.volumes.orEmpty().flatMap { it.chapters.orEmpty() }.firstOrNull { it.id == initialChapterId }
					?: error("Chapter not found")
				val loaded = api.squareChapter(workId, chapter.id, releaseId)
				SquareReaderState(ReaderBook("square:$workId:$releaseId", work.title, work.author?.username, BookFormat.TXT), listOf(ReaderChapter(loaded.id, "square:$workId", 0, chapter.title, loaded.body, releaseId)), 0)
			}
		} catch (_: Exception) { failed = true }
	}
	val current = state
	if (current != null) {
		ReaderScreen(current.book, current.chapters, onPositionChanged = {}, onBookmark = {}, onNote = {}, externalChapterIndex = current.index, externalChapterCount = current.chapters.size, showAnnotations = false, initialPalette = if (UiUtils.isDarkTheme()) ReaderPalette.NIGHT else ReaderPalette.PAPER, onBack = onBack)
	} else {
		Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (!failed) CircularProgressIndicator() else androidx.compose.material3.Text("无法加载此公开版本") }
	}
}

private data class SquareReaderState(val book: ReaderBook, val chapters: List<ReaderChapter>, val index: Int)

@androidx.compose.runtime.Composable
private fun PrivateReaderHost(application: android.app.Application, accountId: String, bookId: String, onBack: () -> Unit) {
	var state by remember { mutableStateOf<SquareReaderState?>(null) }
	var failed by remember { mutableStateOf(false) }
	LaunchedEffect(accountId, bookId) {
		try {
			state = withContext(Dispatchers.IO) {
				val database = NovelDatabase.open(application, accountId)
				try {
					val book = checkNotNull(database.novelBookDao().getById(accountId, bookId))
					val chapters = database.novelChapterDao().getReaderChapters(bookId).map { ReaderChapter(it.id, it.bookId, it.chapterIndex, it.title, it.content, it.id) }
					SquareReaderState(ReaderBook(book.id, book.title, book.author, if (book.localFilePath?.endsWith(".epub", true) == true) BookFormat.EPUB else BookFormat.TXT), chapters, 0)
				} finally { database.close() }
			}
		} catch (_: Exception) { failed = true }
	}
	val current = state
	if (current != null) ReaderScreen(current.book, current.chapters, onPositionChanged = {}, onBookmark = {}, onNote = {}, initialPalette = if (UiUtils.isDarkTheme()) ReaderPalette.NIGHT else ReaderPalette.PAPER, onBack = onBack)
	else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (!failed) CircularProgressIndicator() else androidx.compose.material3.Text("这本小说尚未保存到本机") }
}
