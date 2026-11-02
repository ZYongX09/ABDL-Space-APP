package org.joinmastodon.reader.ui

import androidx.compose.ui.res.stringResource
import org.joinmastodon.reader.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.joinmastodon.reader.domain.ReaderPalette

@Composable
fun ReaderControls(
	bookTitle: String,
	chapterTitle: String,
	chapterIndex: Int,
	chapterCount: Int,
	palette: ReaderPalette,
	onBack: () -> Unit,
	onSettings: () -> Unit,
	onBookmark: () -> Unit,
	onNote: () -> Unit,
	onChapterSelected: (Int) -> Unit,
	onPreviousChapter: (() -> Unit)? = null,
	onNextChapter: (() -> Unit)? = null,
	showChapterSlider: Boolean = true,
	showAnnotations: Boolean = true,
	modifier: Modifier = Modifier,
	toolbarActions: @Composable () -> Unit = {},
) {
	Column(modifier.fillMaxWidth().background(palette.background.copy(alpha = 0.96f)).padding(12.dp)) {
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
			TextButton(onClick = onBack) { Text(stringResource(R.string.novel_reader_back), color = palette.text) }
			Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
				Text(bookTitle, color = palette.text, maxLines = 1)
				Text(chapterTitle, color = palette.secondaryText, maxLines = 1)
			}
			TextButton(onClick = onSettings) { Text(stringResource(R.string.novel_reader_settings), color = palette.text) }
		}
		if (showChapterSlider && chapterCount > 1) {
			Slider(
				value = chapterIndex.toFloat(),
				onValueChange = { onChapterSelected(it.toInt().coerceIn(0, chapterCount - 1)) },
				valueRange = 0f..(chapterCount - 1).toFloat(),
				steps = (chapterCount - 2).coerceAtLeast(0),
			)
		}
		if (onPreviousChapter != null || onNextChapter != null) {
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
				if (onPreviousChapter != null) TextButton(onClick = onPreviousChapter) { Text(stringResource(R.string.novel_reader_previous_chapter), color = palette.text) }
				if (onNextChapter != null) TextButton(onClick = onNextChapter) { Text(stringResource(R.string.novel_reader_next_chapter), color = palette.text) }
			}
		}
		if (showAnnotations) {
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
				TextButton(onClick = onBookmark) { Text(stringResource(R.string.novel_reader_add_bookmark), color = palette.text) }
				TextButton(onClick = onNote) { Text(stringResource(R.string.novel_reader_add_note), color = palette.text) }
			}
		}
		Text(stringResource(R.string.novel_reader_chapter_progress, chapterIndex + 1, chapterCount), color = palette.secondaryText, modifier = Modifier.align(Alignment.CenterHorizontally))
		// Retain the optional legacy slot without crowding the original title/settings row.
		// Window-positioned host actions use ReaderScreen's BoxScope slot instead.
		toolbarActions()
	}
}
