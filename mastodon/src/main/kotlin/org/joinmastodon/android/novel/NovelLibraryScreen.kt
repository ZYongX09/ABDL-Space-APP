package org.joinmastodon.android.novel

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import java.util.Locale
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
	var importVisible by remember { mutableStateOf(false) }
	var pasteVisible by remember { mutableStateOf(false) }
	var upload by remember { mutableStateOf<Pair<Uri, NovelDocument>?>(null) }
	var selectedBook by remember { mutableStateOf<NovelBookEntity?>(null) }
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
		verticalArrangement = Arrangement.spacedBy(10.dp),
	) {
		item {
			Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
				Column(Modifier.weight(1f)) {
					Text("私人书库", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
					Text(
						when {
							state.refreshing -> "正在同步书库"
							state.books.isEmpty() -> "TXT、EPUB 与粘贴文本"
							else -> "${state.books.size} 本小说"
						},
						color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
					)
				}
				Text("同步", Modifier.clickable(onClick = onRefresh).padding(8.dp), color = MiuixTheme.colorScheme.primary)
				Text("导入小说", Modifier.clickable { importVisible = true }.padding(8.dp), color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
			}
		}
		if (state.books.isEmpty()) item {
			Card(Modifier.fillMaxWidth()) {
				Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
					Text("书库还是空的", fontSize = 18.sp, fontWeight = FontWeight.Medium)
					Spacer(Modifier.height(6.dp))
					Text("导入后即可离线阅读，并自动同步到私人云端", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
					Spacer(Modifier.height(14.dp))
					TextButton("导入小说", onClick = { importVisible = true })
				}
			}
		} else {
			items(state.books, key = { it.id }) { book ->
				BookRow(book, state.bookDetails[book.id], onOpen, onDownload) { selectedBook = book }
			}
		}
		item { Spacer(Modifier.height(28.dp)) }
	}

	if (importVisible) ImportDialog(
		onDismiss = { importVisible = false },
		onTxt = { importVisible = false; picker.launch("text/plain") },
		onEpub = { importVisible = false; picker.launch("application/epub+zip") },
		onPaste = { importVisible = false; pasteVisible = true },
	)
	if (pasteVisible) NovelPasteDialog({ pasteVisible = false }) { title, author, text -> pasteVisible = false; onPaste(title, author, text) }
	upload?.let { (uri, document) -> NovelUploadMetadataDialog(document, { upload = null }) { title, author -> upload = null; onUpload(uri, title, author, document.format, document.mimeType) } }
	selectedBook?.let { book -> BookActionsDialog(book, { selectedBook = null }, {
		selectedBook = null
		if (book.downloadState == "ready") onOpen(book) else onDownload(book)
	}, {
		selectedBook = null
		pendingDelete = book
	}) }
	pendingDelete?.let { book ->
		NovelDialog("从书库移除？", { pendingDelete = null }, { pendingDelete = null; onDelete(book) }) {
			Text("《${book.title}》将从本机和私人云端移除，此操作无法撤销。")
		}
	}
	state.error?.let { message -> NovelDialog("操作失败", onDismissError, onDismissError) { Text(message) } }
}

@Composable
private fun BookRow(book: NovelBookEntity, details: NovelBookDetails?, onOpen: (NovelBookEntity) -> Unit, onDownload: (NovelBookEntity) -> Unit, onManage: () -> Unit) {
	val ready = book.downloadState == "ready"
	val downloading = book.downloadState == "downloading"
	val format = book.localFilePath?.substringAfterLast('.', "")?.uppercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: "云端"
	val metadata = buildList {
		book.author?.takeIf(String::isNotBlank)?.let(::add)
		add(format)
		details?.chapterCount?.takeIf { it > 0 }?.let { add("$it 章节") }
		details?.localBytes?.let { add(formatBytes(it)) }
	}.joinToString(" · ")
	val status = when {
		ready -> "可离线阅读"
		book.downloadState == "downloading" -> "正在下载到本机"
		book.downloadState == "failed" -> "下载失败，点按重试"
		else -> "仅在云端"
	}
	Card(Modifier.fillMaxWidth(), onClick = { if (ready) onOpen(book) else if (!downloading) onDownload(book) }) {
		Column(Modifier.fillMaxWidth()) {
			Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
				Column(Modifier.weight(1f)) {
					Text(book.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
					if (metadata.isNotBlank()) Text(metadata, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
				}
				Text("管理", Modifier.clickable(onClick = onManage).padding(start = 12.dp, bottom = 8.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
			}
			Spacer(Modifier.height(8.dp))
			Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
				Text(status, Modifier.weight(1f), fontSize = 13.sp, color = if (book.downloadState == "failed") MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary)
				Text(if (ready) "继续阅读" else if (downloading) "请稍候" else "下载到本机", fontSize = 13.sp, color = if (downloading) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
			}
			Text("更新于 ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(book.updatedAt))}", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
		}
	}
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit, onTxt: () -> Unit, onEpub: () -> Unit, onPaste: () -> Unit) {
	OverlayDialog(show = true, title = "导入小说", onDismissRequest = onDismiss) {
		Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
			ImportRow("选择 TXT 文件", "支持 UTF-8 与 GB18030，导入后可离线阅读", onTxt)
			ImportRow("选择 EPUB 文件", "按书籍目录导入章节", onEpub)
			ImportRow("粘贴文本", "适合短篇、草稿或临时保存的正文", onPaste)
			Text("若系统文件选择器无法返回，可从文件管理器“打开方式”或“分享”到 ABDL Space。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton("关闭", onClick = onDismiss) }
		}
	}
}

@Composable
private fun ImportRow(title: String, summary: String, onClick: () -> Unit) {
	Card(Modifier.fillMaxWidth(), onClick = onClick) {
		Column(Modifier.fillMaxWidth()) {
			Text(title, fontWeight = FontWeight.Medium)
			Text(summary, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
		}
	}
}

@Composable
private fun BookActionsDialog(book: NovelBookEntity, onDismiss: () -> Unit, onPrimary: () -> Unit, onDelete: () -> Unit) {
	OverlayDialog(show = true, title = book.title, onDismissRequest = onDismiss) {
		Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
			TextButton(if (book.downloadState == "ready") "继续阅读" else "下载到本机", onClick = onPrimary)
			TextButton("从书库移除", onClick = onDelete)
			TextButton("取消", onClick = onDismiss)
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
	NovelDialog("加入私人书库", onDismiss, { if (title.isNotBlank() && author.isNotBlank()) onConfirm(title, author) }) {
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

private fun formatBytes(bytes: Long): String = when {
	bytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024f / 1024f)
	bytes >= 1024 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024f)
	else -> "$bytes B"
}

internal fun handleSelectedDocument(uri: Uri, resolve: (Uri) -> NovelDocument, onResolved: (Pair<Uri, NovelDocument>) -> Unit, onError: (String) -> Unit) {
	runCatching { resolve(uri) }.onSuccess { onResolved(uri to it) }.onFailure { onError(it.message ?: "无法读取所选文件") }
}
