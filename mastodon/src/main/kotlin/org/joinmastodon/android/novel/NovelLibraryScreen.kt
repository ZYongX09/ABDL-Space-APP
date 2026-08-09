package org.joinmastodon.android.novel

import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.joinmastodon.android.R
import org.joinmastodon.reader.data.NovelBookEntity
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun NovelLibraryScreen(
	state: NovelLibraryState,
	onRefresh: () -> Unit,
	onUpload: (Uri, String, String, String, String) -> Unit,
	onPaste: (String, String, String) -> Unit,
	onDelete: (NovelBookEntity) -> Unit,
	onDownload: (NovelBookEntity) -> Unit,
	onOpen: (NovelBookEntity) -> Unit,
	onError: (String) -> Unit,
	onDismissError: () -> Unit,
) {
	var pasteVisible by remember { mutableStateOf(false) }
	var upload by remember { mutableStateOf<Pair<Uri, NovelDocument>?>(null) }
	val resolver = NovelDocumentResolver(LocalContext.current.contentResolver)
	val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		uri?.let { selected -> handleSelectedDocument(selected, resolver::resolve, { upload = it }, onError) }
	}
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
				Card(Modifier.fillMaxWidth().clickable { onOpen(book) }) { Column(Modifier.padding(16.dp)) {
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
	if (pasteVisible) NovelPasteDialog({ pasteVisible = false }) { title, author, text -> pasteVisible = false; onPaste(title, author, text) }
	upload?.let { (uri, document) -> NovelUploadMetadataDialog(document, { upload = null }) { title, author -> upload = null; onUpload(uri, title, author, document.format, document.mimeType) } }
	state.error?.let { message -> NovelDialog("操作失败", onDismissError, onDismissError) { Text(message) } }
}

@Composable
private fun NovelPasteDialog(onDismiss: () -> Unit, onPaste: (String, String, String) -> Unit) {
	var title by remember { mutableStateOf("") }; var author by remember { mutableStateOf("") }; var text by remember { mutableStateOf("") }
	NovelDialog("粘贴私人小说", onDismiss, { if (title.isNotBlank() && author.isNotBlank() && text.isNotBlank()) onPaste(title, author, text) }) {
		TextField(title, { title = it }, label = "标题"); TextField(author, { author = it }, label = "作者"); TextField(text, { text = it }, label = "正文")
	}
}

@Composable
private fun NovelUploadMetadataDialog(document: NovelDocument, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
	var title by remember(document) { mutableStateOf(document.displayName) }; var author by remember(document) { mutableStateOf("") }
	NovelDialog("小说信息", onDismiss, { if (title.isNotBlank() && author.isNotBlank()) onConfirm(title, author) }) {
		TextField(title, { title = it }, label = "标题"); TextField(author, { author = it }, label = "作者")
	}
}

@Composable
private fun NovelDialog(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit, content: @Composable () -> Unit) {
	OverlayDialog(show = true, title = title, onDismissRequest = onDismiss) {
		Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
			content()
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
				TextButton("取消", onClick = onDismiss)
				TextButton("确定", onClick = onConfirm)
			}
		}
	}
}

internal fun handleSelectedDocument(uri: Uri, resolve: (Uri) -> NovelDocument, onResolved: (Pair<Uri, NovelDocument>) -> Unit, onError: (String) -> Unit) {
	runCatching { resolve(uri) }.onSuccess { onResolved(uri to it) }.onFailure { onError(it.message ?: "无法读取所选文件") }
}
