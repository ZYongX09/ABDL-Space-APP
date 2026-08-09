package org.joinmastodon.android.novel

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.joinmastodon.android.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun NovelLibraryScreen(state: NovelLibraryState, onRefresh: () -> Unit, onUpload: (android.net.Uri, String) -> Unit, onPaste: (String, String?, String) -> Unit, onDelete: (org.joinmastodon.reader.data.NovelBookEntity) -> Unit, onDownload: (org.joinmastodon.reader.data.NovelBookEntity) -> Unit) {
	var pasteVisible by remember { mutableStateOf(false) }
	val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { onUpload(it, if (it.toString().lowercase().endsWith(".epub")) "epub" else "txt") } }
	Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
		Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
			Text(if (state.refreshing) "同步中" else "刷新", modifier = Modifier.clickable(onClick = onRefresh), color = MiuixTheme.colorScheme.primary)
			Text("上传 TXT/EPUB", modifier = Modifier.clickable { picker.launch(arrayOf("text/plain", "application/epub+zip")) }, color = MiuixTheme.colorScheme.primary)
			Text("粘贴文本", modifier = Modifier.clickable { pasteVisible = true }, color = MiuixTheme.colorScheme.primary)
		}
		if (state.books.isEmpty()) Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
			Image(painterResource(R.drawable.ic_fluent_book_48_regular), null, Modifier.size(64.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary))
			Spacer(Modifier.height(18.dp)); Text("书架还是空的，上传或粘贴一本私人小说", fontSize = 17.sp)
		} else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
			items(state.books, key = { it.id }) { book ->
				Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
					Text(book.title, fontSize = 18.sp, fontWeight = FontWeight.Medium)
					Text(listOfNotNull(book.author, if (book.downloadState == "ready") "已下载" else "云端").joinToString(" · "), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
					Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
						if (book.downloadState != "ready") Text("下载", Modifier.clickable { onDownload(book) }, color = MiuixTheme.colorScheme.primary)
						Text("删除", Modifier.clickable { onDelete(book) }, color = MiuixTheme.colorScheme.primary)
					}
				} }
			}
		}
	}
	if (pasteVisible) NovelPasteDialog(onDismiss = { pasteVisible = false }) { title, author, text -> pasteVisible = false; onPaste(title, author, text) }
}

@Composable private fun NovelPasteDialog(onDismiss: () -> Unit, onPaste: (String, String?, String) -> Unit) {
	var title by remember { mutableStateOf("") }; var author by remember { mutableStateOf("") }; var text by remember { mutableStateOf("") }
	androidx.compose.material3.AlertDialog(onDismissRequest = onDismiss, title = { Text("粘贴私人小说") }, text = { Column { androidx.compose.material3.OutlinedTextField(title, { title = it }, label = { Text("标题") }); androidx.compose.material3.OutlinedTextField(author, { author = it }, label = { Text("作者") }); androidx.compose.material3.OutlinedTextField(text, { text = it }, label = { Text("正文") }) } }, confirmButton = { Text("保存", Modifier.clickable(enabled = title.isNotBlank() && text.isNotBlank()) { onPaste(title, author.ifBlank { null }, text) }, color = MiuixTheme.colorScheme.primary) }, dismissButton = { Text("取消", Modifier.clickable(onClick = onDismiss)) })
}
