package org.joinmastodon.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.joinmastodon.reader.domain.ReaderBook
import org.joinmastodon.reader.domain.ReaderChapter
import org.joinmastodon.reader.domain.ReaderPosition
import org.joinmastodon.reader.domain.ReaderPalette
import org.joinmastodon.reader.domain.ReadingSettings

private val readingSettingsSaver = listSaver<ReadingSettings, Any>(
	save = { listOf(it.fontSize, it.lineHeight, it.horizontalPadding, it.paged, it.palette.name) },
	restore = { ReadingSettings(it[0] as Float, it[1] as Float, it[2] as Float, it[3] as Boolean, ReaderPalette.valueOf(it[4] as String)) },
)

@Composable
fun ReaderScreen(
	book: ReaderBook,
	chapters: List<ReaderChapter>,
	initialPosition: ReaderPosition = ReaderPosition(),
	onPositionChanged: (ReaderPosition) -> Unit = {},
	onBookmark: (ReaderPosition) -> Unit = {},
	onNote: (ReaderPosition) -> Unit = {},
	onPreviousChapter: (() -> Unit)? = null,
	onNextChapter: (() -> Unit)? = null,
	externalChapterIndex: Int? = null,
	externalChapterCount: Int? = null,
	showAnnotations: Boolean = true,
	initialPalette: ReaderPalette = ReaderPalette.PAPER,
	toolbarActions: @Composable BoxScope.() -> Unit = {},
	contentBackdropModifier: Modifier = Modifier,
	onBack: () -> Unit,
) {
	var chapterIndex by rememberSaveable(book.id) { mutableIntStateOf(initialPosition.chapterIndex.coerceIn(0, chapters.lastIndex.coerceAtLeast(0))) }
	var pageIndex by rememberSaveable(book.id) { mutableIntStateOf(initialPosition.pageIndex.coerceAtLeast(0)) }
	var settings by rememberSaveable(book.id, initialPalette, stateSaver = readingSettingsSaver) { mutableStateOf(ReadingSettings(palette = initialPalette)) }
	var controlsVisible by rememberSaveable(book.id) { mutableStateOf(false) }
	var settingsVisible by remember { mutableStateOf(false) }
	val chapter = chapters.getOrNull(chapterIndex)
	Box(Modifier.fillMaxSize().background(settings.palette.background)) {
		Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
			// Record only the unchanged reading surface, never the overlaid controls or host actions.
			Box(Modifier.fillMaxSize().then(contentBackdropModifier).background(settings.palette.background)) {
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
						style = readerBodyTextStyle(settings),
						modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = settings.horizontalPadding.dp, vertical = 28.dp)
							.pointerInput(chapter.id) { detectTapGestures(onTap = { controlsVisible = !controlsVisible }) },
					)
				}
			}
			if (controlsVisible && chapter != null) {
				ReaderControls(
					bookTitle = book.title,
					chapterTitle = chapter.title,
					chapterIndex = externalChapterIndex ?: chapterIndex,
					chapterCount = externalChapterCount ?: chapters.size,
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
					onPreviousChapter = onPreviousChapter,
					onNextChapter = onNextChapter,
					showChapterSlider = externalChapterCount == null,
					showAnnotations = showAnnotations,
					modifier = Modifier.align(Alignment.TopCenter),
				)
			}
		}
		// The host keeps its original window-relative placement/insets; visibility never reflows text.
		if (controlsVisible && chapter != null) toolbarActions()
	}
	if (settingsVisible) ReaderSettingsSheet(settings, { settings = it }, { settingsVisible = false })
}
