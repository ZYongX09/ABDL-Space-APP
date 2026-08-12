package org.joinmastodon.android.novel

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun NovelLibraryScreen(
	state: NovelLibraryState,
	externalDocument: Uri?,
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
	var pendingDelete by remember { mutableStateOf<NovelBookEntity?>(null) }
	val resolver = NovelDocumentResolver(LocalContext.current.contentResolver)
	val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
		uri?.let { selected -> handleSelectedDocument(selected, resolver::resolve, { upload = it }, onError) }
	}
	LaunchedEffect(externalDocument) {
		externalDocument?.let { selected -> handleSelectedDocument(selected, resolver::resolve, { upload = it }, onError) }
	}

	LazyColumn(
		modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		item {
			Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
				Column(Modifier.weight(1f)) {
					Text("我的书架", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
					Text("${state.books.size} 本 · ${if (state.refreshing) "正在同步" else "云端已连接"}", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
				}
				Text(if (state.refreshing) "同步中" else "同步", Modifier.clickable(onClick = onRefresh), color = MiuixTheme.colorScheme.primary)
			}
		}
		item {
			Card(Modifier.fillMaxWidth()) {
				Text("添加到书架", fontSize = 18.sp, fontWeight = FontWeight.Medium)
				Spacer(Modifier.height(10.dp))
				Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					ImportAction("TXT", R.drawable.ic_fluent_document_24_regular, Modifier.weight(1f)) { picker.launch("text/plain") }
					ImportAction("EPUB", R.drawable.ic_fluent_book_24_regular, Modifier.weight(1f)) { picker.launch("application/epub+zip") }
					ImportAction("粘贴", R.drawable.ic_fluent_add_24_regular, Modifier.weight(1f)) { pasteVisible = true }
				}
				Spacer(Modifier.height(10.dp))
				Text("若系统选择器无法返回文件，可在文件管理器中选择“用其他应用打开”或“分享”，再选择 ABDL Space。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
			}
		}
		if (state.books.isEmpty()) item {
			Column(Modifier.fillMaxWidth().padding(vertical = 72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
				Image(painterResource(R.drawable.ic_fluent_book_48_regular), null, Modifier.size(58.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary))
				Spacer(Modifier.height(16.dp))
				Text("还没有小说", fontSize = 18.sp, fontWeight = FontWeight.Medium)
				Text("导入后会自动保存到本机并同步到云端", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
			}
		} else {
			items(state.books, key = { it.id }) { book ->
				BookCard(book, onOpen, onDownload) { pendingDelete = book }
			}
		}
		item { Spacer(Modifier.height(24.dp)) }
	}

	if (pasteVisible) NovelPasteDialog({ pasteVisible = false }) { title, author, text -> pasteVisible = false; onPaste(title, author, text) }
	upload?.let { (uri, document) -> NovelUploadMetadataDialog(document, { upload = null }) { title, author -> upload = null; onUpload(uri, title, author, document.format, document.mimeType) } }
	pendingDelete?.let { book ->
		NovelDialog("删除《${book.title}》？", { pendingDelete = null }, { pendingDelete = null; onDelete(book) }) {
			Text("将同时从本机和云端书架移除。此操作无法撤销。")
		}
	}
	state.error?.let { message -> NovelDialog("操作失败", onDismissError, onDismissError) { Text(message) } }
}

@Composable
private fun ImportAction(label: String, icon: Int, modifier: Modifier, onClick: () -> Unit) {
	Card(modifier, onClick = onClick) {
		Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
			Image(painterResource(icon), null, Modifier.size(26.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary))
			Spacer(Modifier.height(6.dp))
			Text(label, fontWeight = FontWeight.Medium)
		}
	}
}

@Composable
private fun BookCard(book: NovelBookEntity, onOpen: (NovelBookEntity) -> Unit, onDownload: (NovelBookEntity) -> Unit, onDelete: () -> Unit) {
	val ready = book.downloadState == "ready"
	val downloading = book.downloadState == "downloading"
	val failed = book.downloadState == "failed"
	Card(Modifier.fillMaxWidth(), onClick = { if (ready) onOpen(book) else onDownload(book) }) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
				Image(painterResource(if (ready) R.drawable.ic_fluent_book_48_regular else R.drawable.ic_fluent_cloud_24_regular), null, Modifier.size(34.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary))
			}
			Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
				Text(book.title, fontSize = 18.sp, fontWeight = FontWeight.Medium)
				Text(listOfNotNull(book.author, book.localFilePath?.substringAfterLast('.')?.uppercase()).joinToString(" · "), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
				Text(when { ready -> "已保存到本机"; downloading -> "正在下载并整理章节"; failed -> "下载失败，点按重试"; else -> "仅在云端，点按下载" }, fontSize = 13.sp, color = if (failed) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary)
			}
			Text(if (ready) "阅读" else if (downloading) "等待" else "下载", color = MiuixTheme.colorScheme.primary)
		}
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
			Text("移除", Modifier.clickable(onClick = onDelete).padding(horizontal = 8.dp, vertical = 6.dp), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
		}
	}
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
	NovelDialog("加入书架", onDismiss, { if (title.isNotBlank() && author.isNotBlank()) onConfirm(title, author) }) {
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
