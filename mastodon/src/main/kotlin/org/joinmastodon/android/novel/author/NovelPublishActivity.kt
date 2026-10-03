package org.joinmastodon.android.novel.author

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import org.joinmastodon.android.api.novels.NovelAuthoringApi
import org.joinmastodon.android.api.session.AccountSessionManager
import org.joinmastodon.android.ui.compose.MiuixAppTheme
import org.joinmastodon.android.ui.compose.component.BackNavigationIcon
import org.joinmastodon.android.ui.utils.UiUtils
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.CloudFill
import top.yukonga.miuix.kmp.icon.extended.Ok

class NovelPublishActivity : ComponentActivity(), NavigationEventDispatcherOwner {
	override val navigationEventDispatcher = NavigationEventDispatcher { finish() }

	override fun onCreate(savedInstanceState: Bundle?) {
		val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)
		val workId = intent.getStringExtra(EXTRA_WORK_ID)
		val chapterId = intent.getStringExtra(EXTRA_CHAPTER_ID)
		val chapterTitle = intent.getStringExtra(EXTRA_CHAPTER_TITLE)
		val session = accountId?.let { AccountSessionManager.getInstance().tryGetAccount(it) }
		UiUtils.setUserPreferredTheme(this, session)
		super.onCreate(savedInstanceState)
		if (session == null || workId == null || chapterId == null || chapterTitle == null) {
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
			this.volumeId = intent.getStringExtra(EXTRA_VOLUME_ID).orEmpty()
			title = chapterTitle
		}
		val darkTheme = UiUtils.isDarkTheme()
		viewModel.openPublishChapter(workId, chapter)
		enableEdgeToEdge(
			statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
			navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
		)
		setContent {
			CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides this) {
				MiuixAppTheme {
					val state by viewModel.state.collectAsState()
					Scaffold(containerColor = MiuixTheme.colorScheme.background, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
						Box(Modifier.fillMaxSize().padding(padding)) {
							NovelPublishScreen(state, chapter, viewModel::submitForReview, viewModel::publishRevision, ::finish)
						}
					}
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

		fun intent(context: Context, accountId: String, workId: String, chapter: NovelAuthoringApi.ChapterDto) = Intent(context, NovelPublishActivity::class.java)
			.putExtra(EXTRA_ACCOUNT_ID, accountId)
			.putExtra(EXTRA_WORK_ID, workId)
			.putExtra(EXTRA_VOLUME_ID, chapter.volumeId)
			.putExtra(EXTRA_CHAPTER_ID, chapter.id)
			.putExtra(EXTRA_CHAPTER_TITLE, chapter.title)
	}
}

@androidx.compose.runtime.Composable
private fun NovelPublishScreen(state: AuthoringState, chapter: NovelAuthoringApi.ChapterDto, onSubmit: () -> Unit, onPublish: () -> Unit, onBack: () -> Unit) {
	val status = state.editorRevisionStatus
	val revisionReady = state.editorRevisionId != null
	Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
		Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
			BackNavigationIcon(onClick = onBack)
			Column(Modifier.weight(1f).padding(start = 4.dp)) {
				Text("发布", fontSize = 27.sp, fontWeight = FontWeight.Bold)
				Text(chapter.title, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
			}
		}
		PublishStatusCard(status, revisionReady, state.editorPublishing)
		Spacer(Modifier.height(4.dp))
		state.error?.let { message ->
			Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MiuixTheme.colorScheme.error.copy(alpha = 0.08f)).padding(14.dp)) {
				Text(message, color = MiuixTheme.colorScheme.error, fontSize = 13.sp)
			}
		}
		when (status) {
			"approved" -> PublishActionButton("发布到公开书城", state.editorPublishing, onClick = onPublish)
			"draft", null -> PublishActionButton("提交审核", state.editorPublishing, onClick = onSubmit)
			else -> {}
		}
	}
}

@androidx.compose.runtime.Composable
private fun PublishStatusCard(status: String?, revisionReady: Boolean, operating: Boolean) {
	val shape = RoundedCornerShape(22.dp)
	Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surfaceContainer, shape).border(1.dp, MiuixTheme.colorScheme.primary.copy(alpha = 0.18f), shape).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Icon(if (status == "published" || status == "approved") MiuixIcons.Ok else if (operating) MiuixIcons.CloudFill else MiuixIcons.CloudFill, null, Modifier.size(28.dp), MiuixTheme.colorScheme.primary)
			Text(publishCardTitle(status, revisionReady), Modifier.padding(start = 12.dp), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
		}
		Text(publishCardSummary(status, revisionReady), fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
	}
}

@androidx.compose.runtime.Composable
private fun PublishActionButton(label: String, operating: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
	val active = enabled && !operating
	val start = if (active) MiuixTheme.colorScheme.primary.copy(alpha = 0.72f) else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f)
	val end = if (active) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f)
	Row(
		Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(start, end))).clickable(enabled = active, onClick = onClick).padding(vertical = 16.dp),
		horizontalArrangement = Arrangement.Center,
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (operating) CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp) else Icon(MiuixIcons.Ok, null, Modifier.size(22.dp), MiuixTheme.colorScheme.onPrimary)
		Text(label, Modifier.padding(start = 8.dp), color = MiuixTheme.colorScheme.onPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
	}
}

private fun publishCardTitle(status: String?, revisionReady: Boolean): String = when (status) {
	"published" -> "已发布到公开书城"
	"review_pending" -> "正在等待 MiMo 审核"
	"approved" -> "审核通过，可以发布"
	"rejected" -> "审核未通过"
	"superseded" -> "已有更新版本发布"
	else -> if (revisionReady) "草稿已就绪" else "草稿尚未同步"
}

private fun publishCardSummary(status: String?, revisionReady: Boolean): String = when (status) {
	"published" -> "读者可在公开书城阅读此章节。发布新版本会替换当前公开内容。"
	"review_pending" -> "已提交审核，正在排队等待处理。审核通过后会变为「审核通过」，即可发布。"
	"approved" -> "点击下方按钮即可把此章节发布到公开书城。"
	"rejected" -> "可在后台申诉后重新提交。"
	"superseded" -> "请基于最新版本重新提交。"
	else -> if (revisionReady) "点击下方「提交审核」开始公开发布流程。" else "点击下方按钮将自动同步草稿并提交审核。"
}
