package org.joinmastodon.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.joinmastodon.reader.domain.ReaderBook
import org.joinmastodon.reader.domain.ReaderChapter
import org.joinmastodon.reader.domain.ReaderPosition
import org.joinmastodon.reader.domain.ReadingSettings

@Composable
fun ReaderScreen(
	book: ReaderBook,
	chapters: List<ReaderChapter>,
	initialPosition: ReaderPosition = ReaderPosition(),
	onPositionChanged: (ReaderPosition) -> Unit = {},
	onBookmark: (ReaderPosition) -> Unit = {},
	onNote: (ReaderPosition) -> Unit = {},
	onBack: () -> Unit,
) {
	var chapterIndex by remember(book.id) { mutableIntStateOf(initialPosition.chapterIndex.coerceIn(0, chapters.lastIndex.coerceAtLeast(0))) }
	var pageIndex by remember(book.id) { mutableIntStateOf(initialPosition.pageIndex) }
	var settings by remember { mutableStateOf(ReadingSettings()) }
	var controlsVisible by remember { mutableStateOf(false) }
	var settingsVisible by remember { mutableStateOf(false) }
	val chapter = chapters.getOrNull(chapterIndex)
	Box(Modifier.fillMaxSize().background(settings.palette.background)) {
		if (chapter == null) {
			Text("暂无正文", color = settings.palette.secondaryText, modifier = Modifier.align(Alignment.Center))
		} else if (settings.paged) {
			PagedReader(
				chapter = chapter,
				settings = settings,
				initialPage = pageIndex,
				onPageChanged = { pageIndex = it; onPositionChanged(ReaderPosition(chapterIndex, it)) },
				onToggleControls = { controlsVisible = !controlsVisible },
			)
		} else {
			Text(
				text = chapter.content,
				color = settings.palette.text,
				style = TextStyle(fontSize = settings.fontSize.sp, lineHeight = (settings.fontSize * settings.lineHeight).sp),
				modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = settings.horizontalPadding.dp, vertical = 52.dp)
					.pointerInput(chapter.id) { detectTapGestures(onTap = { controlsVisible = !controlsVisible }) },
			)
		}
		if (controlsVisible && chapter != null) {
			ReaderControls(
				bookTitle = book.title,
				chapterTitle = chapter.title,
				chapterIndex = chapterIndex,
				chapterCount = chapters.size,
				palette = settings.palette,
				onBack = onBack,
				onSettings = { settingsVisible = true },
				onBookmark = { onBookmark(ReaderPosition(chapterIndex, pageIndex)) },
				onNote = { onNote(ReaderPosition(chapterIndex, pageIndex)) },
				onChapterSelected = {
					val nextPageIndex = pageIndexAfterChapterChange(chapter.id, chapters[it].id, pageIndex)
					chapterIndex = it
					pageIndex = nextPageIndex
					onPositionChanged(ReaderPosition(it, nextPageIndex))
				},
				modifier = Modifier.align(Alignment.TopCenter),
			)
		}
	}
	if (settingsVisible) ReaderSettingsSheet(settings, { settings = it }, { settingsVisible = false })
}
