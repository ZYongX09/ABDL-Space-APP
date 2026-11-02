package org.joinmastodon.reader.ui

import androidx.compose.ui.res.stringResource
import org.joinmastodon.reader.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.joinmastodon.reader.domain.ReaderPalette
import org.joinmastodon.reader.domain.ReadingSettings

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderSettingsSheet(settings: ReadingSettings, onChange: (ReadingSettings) -> Unit, onDismiss: () -> Unit) {
	ModalBottomSheet(onDismissRequest = onDismiss) {
		Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
			Text(stringResource(R.string.novel_reader_reading_settings))
			Text(stringResource(R.string.novel_reader_font_size, settings.fontSize.toInt()))
			Slider(value = settings.fontSize, onValueChange = { onChange(settings.copy(fontSize = it)) }, valueRange = 14f..30f)
			Text(stringResource(R.string.novel_reader_line_height, java.text.NumberFormat.getNumberInstance(androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).apply { maximumFractionDigits = 1 }.format(settings.lineHeight)))
			Slider(value = settings.lineHeight, onValueChange = { onChange(settings.copy(lineHeight = it)) }, valueRange = 1.2f..2.2f)
			FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				ReaderPalette.entries.forEach { palette ->
					FilterChip(selected = settings.palette == palette, onClick = { onChange(settings.copy(palette = palette)) }, label = { Text(stringResource(when (palette) { ReaderPalette.PAPER -> R.string.novel_reader_palette_paper; ReaderPalette.WHITE -> R.string.novel_reader_palette_white; ReaderPalette.NIGHT -> R.string.novel_reader_palette_night })) })
				}
			}
		}
	}
}
