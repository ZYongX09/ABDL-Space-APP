package org.joinmastodon.android.novel

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
		modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		item {
			Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
				Column(Modifier.weight(1f)) {
					Text("私人书库", fontSize = 27.sp, fontWeight = FontWeight.Bold)
					Text(
						when {
							state.refreshing -> "正在同步书库"
							state.books.isEmpty() -> "TXT、EPUB 与粘贴文本"
							else -> "${state.books.size} 本小说"
						},
						fontSize = 15.sp,
						color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
					)
				}
				Row(
					Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
					verticalAlignment = Alignment.CenterVertically,
				) {
					HeaderAction(NovelIconType.Sync, if (state.refreshing) "同步中" else "同步", onRefresh)
					Box(Modifier.width(1.dp).height(24.dp).background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f)))
					HeaderAction(NovelIconType.Upload, "导入小说") { importVisible = true }
				}
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
	val cardShape = RoundedCornerShape(18.dp)
	Box(
		Modifier.fillMaxWidth()
			.shadow(8.dp, cardShape, clip = false)
			.background(MiuixTheme.colorScheme.surface, cardShape)
			.border(1.dp, MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f), cardShape)
			.padding(horizontal = 14.dp, vertical = 15.dp),
	) {
		Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
			BookStateTile(ready, book.downloadState == "failed")
			Spacer(Modifier.width(14.dp))
			Column(Modifier.weight(1f)) {
				Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
					Text(book.title, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
					Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onManage).padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
						NovelLineIcon(NovelIconType.Settings, 18.dp, MiuixTheme.colorScheme.onSurfaceVariantSummary)
						Spacer(Modifier.width(4.dp))
						Text("管理", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
					}
				}
				if (metadata.isNotBlank()) Text(metadata, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
				Spacer(Modifier.height(6.dp))
				StatusBadge(status, ready, book.downloadState == "failed", downloading)
				Spacer(Modifier.height(7.dp))
				Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
					Text("更新于 ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(book.updatedAt))}", Modifier.weight(1f), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
					PrimaryBookAction(ready, downloading) { if (ready) onOpen(book) else onDownload(book) }
				}
			}
		}
	}
}

@Composable
private fun HeaderAction(icon: NovelIconType, label: String, onClick: () -> Unit) {
	Row(Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
		NovelLineIcon(icon, 20.dp, MiuixTheme.colorScheme.primary)
		Spacer(Modifier.width(6.dp))
		Text(label, color = MiuixTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
	}
}

@Composable
private fun BookStateTile(ready: Boolean, failed: Boolean) {
	val tint = when { failed -> MiuixTheme.colorScheme.error; else -> MiuixTheme.colorScheme.primary }
	val background = tint.copy(alpha = 0.10f)
	Box(Modifier.size(54.dp).clip(RoundedCornerShape(14.dp)).background(background), contentAlignment = Alignment.Center) {
		NovelLineIcon(if (ready) NovelIconType.Book else NovelIconType.Download, 28.dp, tint)
	}
}

@Composable
private fun StatusBadge(label: String, ready: Boolean, failed: Boolean, downloading: Boolean) {
	val tint = when { failed -> MiuixTheme.colorScheme.error; ready -> Color(0xFF168A5B); else -> MiuixTheme.colorScheme.primary }
	Row(Modifier.clip(RoundedCornerShape(8.dp)).background(tint.copy(alpha = 0.10f)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
		NovelLineIcon(when { failed -> NovelIconType.Error; ready -> NovelIconType.Check; downloading -> NovelIconType.Sync; else -> NovelIconType.Cloud }, 16.dp, tint)
		Spacer(Modifier.width(5.dp))
		Text(label, fontSize = 12.sp, color = tint, fontWeight = FontWeight.Medium)
	}
}

@Composable
private fun PrimaryBookAction(ready: Boolean, downloading: Boolean, onClick: () -> Unit) {
	val enabled = !downloading
	val tint = if (enabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
	val modifier = Modifier.clip(RoundedCornerShape(12.dp))
		.then(if (ready) Modifier.background(tint) else Modifier.border(1.dp, tint, RoundedCornerShape(12.dp)))
		.clickable(enabled = enabled, onClick = onClick)
		.padding(horizontal = 13.dp, vertical = 9.dp)
	Row(modifier, verticalAlignment = Alignment.CenterVertically) {
		NovelLineIcon(if (ready) NovelIconType.Book else NovelIconType.Download, 19.dp, if (ready) Color.White else tint)
		Spacer(Modifier.width(6.dp))
		Text(if (ready) "继续阅读" else if (downloading) "请稍候" else "下载到本机", fontSize = 13.sp, color = if (ready) Color.White else tint, fontWeight = FontWeight.Medium)
	}
}

private enum class NovelIconType { Book, Download, Upload, Sync, Settings, Error, Check, Cloud }

@Composable
private fun NovelLineIcon(type: NovelIconType, size: androidx.compose.ui.unit.Dp, tint: Color) {
	Canvas(Modifier.size(size)) {
		val stroke = Stroke(width = 1.8.dp.toPx())
		val w = this.size.width
		val h = this.size.height
		when (type) {
			NovelIconType.Book -> {
				val left = Path().apply { moveTo(w * .12f, h * .22f); quadraticTo(w * .31f, h * .12f, w * .48f, h * .27f); lineTo(w * .48f, h * .82f); quadraticTo(w * .30f, h * .68f, w * .12f, h * .78f); close() }
				val right = Path().apply { moveTo(w * .88f, h * .22f); quadraticTo(w * .69f, h * .12f, w * .52f, h * .27f); lineTo(w * .52f, h * .82f); quadraticTo(w * .70f, h * .68f, w * .88f, h * .78f); close() }
				drawPath(left, tint, style = stroke); drawPath(right, tint, style = stroke)
			}
			NovelIconType.Download, NovelIconType.Upload -> {
				val upload = type == NovelIconType.Upload
				val fromY = if (upload) h * .68f else h * .20f
				val toY = if (upload) h * .22f else h * .68f
				drawLine(tint, androidx.compose.ui.geometry.Offset(w * .5f, fromY), androidx.compose.ui.geometry.Offset(w * .5f, toY), stroke.width)
				val arrowY = toY
				val wingY = if (upload) arrowY + h * .16f else arrowY - h * .16f
				drawLine(tint, androidx.compose.ui.geometry.Offset(w * .5f, arrowY), androidx.compose.ui.geometry.Offset(w * .34f, wingY), stroke.width)
				drawLine(tint, androidx.compose.ui.geometry.Offset(w * .5f, arrowY), androidx.compose.ui.geometry.Offset(w * .66f, wingY), stroke.width)
				drawLine(tint, androidx.compose.ui.geometry.Offset(w * .18f, h * .82f), androidx.compose.ui.geometry.Offset(w * .82f, h * .82f), stroke.width)
			}
			NovelIconType.Sync -> {
				drawArc(tint, 205f, 220f, false, style = stroke)
				drawArc(tint, 25f, 220f, false, style = stroke)
				drawLine(tint, androidx.compose.ui.geometry.Offset(w * .12f, h * .30f), androidx.compose.ui.geometry.Offset(w * .31f, h * .29f), stroke.width)
				drawLine(tint, androidx.compose.ui.geometry.Offset(w * .88f, h * .70f), androidx.compose.ui.geometry.Offset(w * .69f, h * .71f), stroke.width)
			}
			NovelIconType.Settings -> {
				drawCircle(tint, w * .20f, style = stroke)
				drawCircle(tint, w * .08f, style = stroke)
				for (i in 0 until 8) {
					val angle = Math.toRadians((i * 45).toDouble())
					drawLine(tint, androidx.compose.ui.geometry.Offset(w * .5f + kotlin.math.cos(angle).toFloat() * w * .25f, h * .5f + kotlin.math.sin(angle).toFloat() * h * .25f), androidx.compose.ui.geometry.Offset(w * .5f + kotlin.math.cos(angle).toFloat() * w * .40f, h * .5f + kotlin.math.sin(angle).toFloat() * h * .40f), stroke.width)
				}
			}
			NovelIconType.Error, NovelIconType.Check -> {
				drawCircle(tint, w * .42f, style = stroke)
				if (type == NovelIconType.Error) {
					drawLine(tint, androidx.compose.ui.geometry.Offset(w * .5f, h * .27f), androidx.compose.ui.geometry.Offset(w * .5f, h * .56f), stroke.width)
					drawCircle(tint, w * .045f, androidx.compose.ui.geometry.Offset(w * .5f, h * .72f))
				} else {
					drawLine(tint, androidx.compose.ui.geometry.Offset(w * .27f, h * .52f), androidx.compose.ui.geometry.Offset(w * .44f, h * .68f), stroke.width)
					drawLine(tint, androidx.compose.ui.geometry.Offset(w * .44f, h * .68f), androidx.compose.ui.geometry.Offset(w * .74f, h * .35f), stroke.width)
				}
			}
			NovelIconType.Cloud -> {
				val cloud = Path().apply { moveTo(w * .20f, h * .68f); cubicTo(w * .08f, h * .54f, w * .18f, h * .38f, w * .34f, h * .40f); cubicTo(w * .42f, h * .18f, w * .72f, h * .22f, w * .74f, h * .45f); cubicTo(w * .92f, h * .47f, w * .92f, h * .70f, w * .74f, h * .72f); lineTo(w * .28f, h * .72f) }
				drawPath(cloud, tint, style = stroke)
			}
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
