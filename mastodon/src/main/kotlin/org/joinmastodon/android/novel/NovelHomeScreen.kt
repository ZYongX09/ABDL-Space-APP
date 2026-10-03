package org.joinmastodon.android.novel

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import org.joinmastodon.android.R
import org.joinmastodon.android.novel.author.AuthoringScreen
import org.joinmastodon.android.novel.author.AuthoringViewModel
import org.joinmastodon.android.novel.author.NovelWorkStructureActivity
import org.joinmastodon.android.ui.compose.component.BackNavigationIcon
import org.joinmastodon.android.ui.compose.ui.isInDarkTheme
import org.joinmastodon.reader.domain.ReaderPalette
import org.joinmastodon.reader.domain.ReaderPosition
import org.joinmastodon.reader.ui.ReaderScreen
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun NovelHomeScreen(accountId: String, libraryViewModel: NovelLibraryViewModel, authoringViewModel: AuthoringViewModel, storeViewModel: NovelStoreViewModel, externalDocument: Uri? = null, onBack: () -> Unit) {
	val context = LocalContext.current
	val libraryState by libraryViewModel.state.collectAsState()
	val authoringState by authoringViewModel.state.collectAsState()
	val storeState by storeViewModel.state.collectAsState()
	var pendingNote by remember { mutableStateOf<Triple<String, String, ReaderPosition>?>(null) }
	BackHandler(enabled = storeState.reader != null) { storeViewModel.closeReader() }
	storeState.reader?.let { reader ->
		val publicReader = storeState.publicReader
		ReaderScreen(reader.book, reader.chapters, onPositionChanged = {}, onBookmark = {}, onNote = {}, onPreviousChapter = publicReader?.takeIf { it.chapterIndex > 0 }?.let { storeViewModel::previousChapter }, onNextChapter = publicReader?.takeIf { it.chapterIndex < it.chapters.lastIndex }?.let { storeViewModel::nextChapter }, externalChapterIndex = publicReader?.chapterIndex, externalChapterCount = publicReader?.chapters?.size, showAnnotations = false, initialPalette = if (isInDarkTheme()) ReaderPalette.NIGHT else ReaderPalette.PAPER, onBack = storeViewModel::closeReader)
		return
	}
	BackHandler(enabled = storeState.selectedWork != null) { storeViewModel.closeWork() }
	BackHandler(enabled = libraryState.reader != null) { libraryViewModel.closeReader() }
	libraryState.reader?.let { reader ->
		ReaderScreen(
			reader.book,
			reader.chapters,
			onPositionChanged = { libraryViewModel.onReaderPositionChanged(reader.book.id, it) },
			onBookmark = { position -> reader.chapters.getOrNull(position.chapterIndex)?.let { libraryViewModel.addBookmark(reader.book.id, it.id, position) } },
			onNote = { position -> reader.chapters.getOrNull(position.chapterIndex)?.let { pendingNote = Triple(reader.book.id, it.id, position) } },
			initialPalette = if (isInDarkTheme()) ReaderPalette.NIGHT else ReaderPalette.PAPER,
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
				NovelTabBar(tabs.map { stringResource(it.first) }, selectedTab) { index -> if (index != 0) storeViewModel.closeWork(); selectedTab = index }
			}
		},
	) { padding ->
		Box(
			modifier = Modifier.fillMaxSize().padding(padding),
			contentAlignment = Alignment.Center,
		) {
			if (selectedTab == 1) {
				NovelLibraryScreen(libraryState, externalDocument, libraryViewModel::refresh, libraryViewModel::upload, libraryViewModel::paste, libraryViewModel::delete, libraryViewModel::download, libraryViewModel::openReader, { book ->
					authoringViewModel.convertPrivateBookToWork(book)
					android.widget.Toast.makeText(context, "正在转入创作中心，完成后可在创作中心查看", android.widget.Toast.LENGTH_SHORT).show()
				}, libraryViewModel::reportError, libraryViewModel::dismissError)
				return@Box
			}
			if (selectedTab == 2) {
				AuthoringScreen(authoringState, authoringViewModel) { work ->
					context.startActivity(NovelWorkStructureActivity.intent(context, accountId, work))
				}
				return@Box
			}
			NovelStoreScreen(storeState, storeViewModel::refresh, storeViewModel::loadNextPage, storeViewModel::openWork, storeViewModel::openChapter, storeViewModel::closeWork, storeViewModel::dismissError)
		}
	}
}

@Composable
private fun NovelTabBar(tabs: List<String>, selectedIndex: Int, onSelected: (Int) -> Unit) {
	val shape = RoundedCornerShape(18.dp)
	BoxWithConstraints(
		Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
			.clip(shape)
			.border(1.dp, MiuixTheme.colorScheme.onSurface.copy(alpha = 0.10f), shape)
			.background(MiuixTheme.colorScheme.surface.copy(alpha = 0.72f))
			.padding(3.dp),
	) {
		val itemWidth = maxWidth / tabs.size
		val indicatorOffset by animateDpAsState(
			targetValue = itemWidth * selectedIndex,
			animationSpec = tween(durationMillis = 200, easing = LinearEasing),
			label = "NovelTabIndicatorOffset",
		)
		Box(Modifier.offset(x = indicatorOffset).width(itemWidth).height(44.dp).clip(RoundedCornerShape(15.dp)).background(MiuixTheme.colorScheme.primary.copy(alpha = 0.11f)))
		Row(Modifier.fillMaxWidth()) {
			tabs.forEachIndexed { index, label ->
				val selected = index == selectedIndex
				Box(
					Modifier.width(itemWidth).clip(RoundedCornerShape(15.dp))
						.clickable { onSelected(index) }.padding(vertical = 12.dp),
					contentAlignment = Alignment.Center,
				) {
					Text(label, color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
				}
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
