package org.joinmastodon.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(settings: ReadingSettings, onChange: (ReadingSettings) -> Unit, onDismiss: () -> Unit) {
	ModalBottomSheet(onDismissRequest = onDismiss) {
		Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
			Text("阅读设置")
			Text("字号 ${settings.fontSize.toInt()}sp")
			Slider(value = settings.fontSize, onValueChange = { onChange(settings.copy(fontSize = it)) }, valueRange = 14f..30f)
			Text("行距 ${"%.1f".format(settings.lineHeight)}")
			Slider(value = settings.lineHeight, onValueChange = { onChange(settings.copy(lineHeight = it)) }, valueRange = 1.2f..2.2f)
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				ReaderPalette.entries.forEach { palette ->
					FilterChip(selected = settings.palette == palette, onClick = { onChange(settings.copy(palette = palette)) }, label = { Text(palette.name) })
				}
			}
		}
	}
}
