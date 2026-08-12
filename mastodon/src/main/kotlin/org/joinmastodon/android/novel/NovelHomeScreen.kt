package org.joinmastodon.android.novel

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import org.joinmastodon.android.R
import org.joinmastodon.android.ui.compose.component.BackNavigationIcon
import org.joinmastodon.reader.domain.ReaderPosition
import org.joinmastodon.reader.ui.ReaderScreen
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun NovelHomeScreen(accountId: String, libraryViewModel: NovelLibraryViewModel, externalDocument: Uri? = null, onBack: () -> Unit) {
	val libraryState by libraryViewModel.state.collectAsState()
	var pendingNote by remember { mutableStateOf<Triple<String, String, ReaderPosition>?>(null) }
	libraryState.reader?.let { reader ->
		ReaderScreen(
			reader.book,
			reader.chapters,
			onPositionChanged = { libraryViewModel.onReaderPositionChanged(reader.book.id, it) },
			onBookmark = { position -> reader.chapters.getOrNull(position.chapterIndex)?.let { libraryViewModel.addBookmark(reader.book.id, it.id, position) } },
			onNote = { position -> reader.chapters.getOrNull(position.chapterIndex)?.let { pendingNote = Triple(reader.book.id, it.id, position) } },
			onBack = libraryViewModel::closeReader,
		)
		pendingNote?.let { (bookId, chapterId, position) ->
			NovelNoteDialog({ pendingNote = null }) { note -> pendingNote = null; libraryViewModel.addNote(bookId, chapterId, position, note) }
		}
		return
	}
	val tabs = listOf(
		R.string.novel_recommend to R.string.novel_recommend_empty,
		R.string.novel_bookshelf to R.string.novel_bookshelf_empty,
		R.string.novel_creation to R.string.novel_creation_empty,
	)
	var selectedTab by remember { mutableIntStateOf(if (externalDocument == null) 0 else 1) }

	Scaffold(
		containerColor = MiuixTheme.colorScheme.background,
		topBar = {
			Column {
				SmallTopAppBar(
					title = stringResource(R.string.novel),
					navigationIcon = { BackNavigationIcon(onClick = onBack) },
				)
				TabRow(
					tabs = tabs.map { stringResource(it.first) },
					selectedTabIndex = selectedTab,
					onTabSelected = { selectedTab = it },
				)
			}
		},
	) { padding ->
		Box(
			modifier = Modifier.fillMaxSize().padding(padding),
			contentAlignment = Alignment.Center,
		) {
			if (selectedTab == 1) {
				NovelLibraryScreen(libraryState, externalDocument, libraryViewModel::refresh, libraryViewModel::upload, libraryViewModel::paste, libraryViewModel::delete, libraryViewModel::download, libraryViewModel::openReader, libraryViewModel::reportError, libraryViewModel::dismissError)
				return@Box
			}
			Column(
				modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.Center,
			) {
				Text(
					text = if (selectedTab == 0) "公开书城将在创作功能完成后开放" else "作者功能正在建设",
					color = MiuixTheme.colorScheme.onSurface,
					fontSize = 20.sp,
					fontWeight = FontWeight.SemiBold,
				)
				Spacer(Modifier.height(10.dp))
				Text(
					text = if (selectedTab == 0) "当前先确保私人书库、离线阅读和云同步稳定。" else "注册满 72 小时且至少发布过 1 条未删除帖子即可成为作者。后续将提供作品、分卷、章节、云草稿、MiMo 审核、评级、发布与申诉。",
					color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
					fontSize = 15.sp,
				)
			}
		}
	}
}

@Composable
private fun NovelNoteDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
	var note by remember { mutableStateOf("") }
	OverlayDialog(show = true, title = "添加笔记", onDismissRequest = onDismiss) {
		Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
			TextField(note, { note = it }, label = "笔记正文")
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
				TextButton("取消", onClick = onDismiss)
				TextButton("保存", onClick = { if (note.isNotBlank()) onSave(note) })
			}
		}
	}
}
