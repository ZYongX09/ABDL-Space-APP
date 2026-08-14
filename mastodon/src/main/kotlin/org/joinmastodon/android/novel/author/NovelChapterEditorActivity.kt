package org.joinmastodon.android.novel.author

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import org.joinmastodon.android.api.novels.NovelAuthoringApi
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.ui.compose.MiuixAppTheme
import org.joinmastodon.android.ui.utils.UiUtils

class NovelChapterEditorActivity : ComponentActivity(), NavigationEventDispatcherOwner {
	override val navigationEventDispatcher = NavigationEventDispatcher { finish() }

	override fun onCreate(savedInstanceState: Bundle?) {
		val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)
		val workId = intent.getStringExtra(EXTRA_WORK_ID)
		val volumeId = intent.getStringExtra(EXTRA_VOLUME_ID)
		val chapterId = intent.getStringExtra(EXTRA_CHAPTER_ID)
		val chapterTitle = intent.getStringExtra(EXTRA_CHAPTER_TITLE)
		val session = accountId?.let { AccountSessionManager.getInstance().tryGetAccount(it) }
		UiUtils.setUserPreferredTheme(this, session)
		super.onCreate(savedInstanceState)
		if (session == null || workId == null || volumeId == null || chapterId == null || chapterTitle == null) {
			finish()
			return
		}
		val viewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
			override fun <T : ViewModel> create(modelClass: Class<T>): T {
				@Suppress("UNCHECKED_CAST")
				return AuthoringViewModel(application, accountId) as T
			}
		})[AuthoringViewModel::class.java]
		val chapter = NovelAuthoringApi.ChapterDto().apply {
			id = chapterId
			this.volumeId = volumeId
			title = chapterTitle
		}
		val darkTheme = UiUtils.isDarkTheme()
		viewModel.openChapter(workId, chapter)
		enableEdgeToEdge(
			statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
			navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
		)
		setContent {
			CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides this) {
				MiuixAppTheme {
					val state by viewModel.state.collectAsState()
					NovelChapterEditorScreen(state, viewModel, ::finish)
				}
			}
		}
	}

	companion object {
		private const val EXTRA_ACCOUNT_ID = "account"
		private const val EXTRA_WORK_ID = "work"
		private const val EXTRA_VOLUME_ID = "volume"
		private const val EXTRA_CHAPTER_ID = "chapter"
		private const val EXTRA_CHAPTER_TITLE = "chapter_title"

		fun intent(context: Context, accountId: String, workId: String, chapter: NovelAuthoringApi.ChapterDto) = Intent(context, NovelChapterEditorActivity::class.java)
			.putExtra(EXTRA_ACCOUNT_ID, accountId)
			.putExtra(EXTRA_WORK_ID, workId)
			.putExtra(EXTRA_VOLUME_ID, chapter.volumeId)
			.putExtra(EXTRA_CHAPTER_ID, chapter.id)
			.putExtra(EXTRA_CHAPTER_TITLE, chapter.title)
	}
}
